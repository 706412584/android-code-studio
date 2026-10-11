/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidCodeStudio is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.lsp.common

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.tom.rv2ide.eventbus.events.editor.DocumentChangeEvent
import com.tom.rv2ide.eventbus.events.editor.DocumentCloseEvent
import com.tom.rv2ide.eventbus.events.editor.DocumentOpenEvent
import com.tom.rv2ide.eventbus.events.editor.DocumentSaveEvent
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 文档生命周期同步（textDocument/didOpen|didChange|didClose）。
 *
 * <p><b>为什么用全量同步而不是增量同步</b>：LSP 的增量同步要求客户端把编辑操作翻译成
 * range 变更，而 IDE 的事件模型（[DocumentChangeEvent]）提供的是变更后全文（newText）+
 * 变更区间——把区间翻译成 LSP 增量变更需要对编辑器的每一类操作都有精确的行列语义，
 * 出错时服务器状态会静默漂移，而漂移后所有补全位置都会错乱，极难排查。全量同步
 * （每次变更发全文）在移动端多传的字节可接受（项目文件多为几十 KB），换来「永不漂移」。
 * TSLS 与 Dart 分析服务器都支持 TextDocumentSyncKind.Full。
 *
 * <p><b>两个版本号的含义</b>：
 * - LSP 版本（发给服务器的 version）由本类单调自增，服务器用它判断消息新旧。
 * - 编辑器版本（[DocumentChangeEvent.version]）由编辑器维护，本类用它丢弃乱序到达的
 *   过期变更：EventBus 的投递与编辑器协程调度都不是严格有序的，快速输入时可能先到
 *   v5 后到 v4，若照单全收，服务器最终停在 v4 的内容上——后续所有诊断都错位。
 *   打开（didOpen）时重置该跟踪值，因为编辑器实例重建后版本会从头开始。
 */
class LspDocumentSync(private val client: LspStdioClient) {

  /**
   * 按文件给出 LSP languageId（如 "typescript" / "typescriptreact" / "dart"）。
   *
   * <p>不是常量：TSLS 用 languageId 决定把文件纳入哪套分析（tsx 与 ts 的 JSX 语义不同）。
   */
  var languageIdFor: (Path) -> String = { "plaintext" }

  /** 已同步的文档 URI → 最后发送的 LSP 版本。 */
  private val openDocuments = ConcurrentHashMap<String, Int>()

  /** 已同步的文档 URI → 最后接受的编辑器版本。 */
  private val lastEditorVersions = ConcurrentHashMap<String, Int>()

  private val nextVersion = AtomicInteger(1)

  /** 该文档是否已向服务器打开。 */
  fun isOpen(path: Path): Boolean = openDocuments.containsKey(LspConverters.pathToUri(path))

  /**
   * 打开文档（didOpen）。已打开的文档改为发送一次全量 didChange——重复 didOpen 是
   * 协议违规，而编辑器可能对同一文件多次触发打开事件（切页签、重载）。
   */
  fun didOpen(path: Path, text: String) {
    val uri = LspConverters.pathToUri(path)
    val version = nextVersion.getAndIncrement()
    // 编辑器实例（重）建后 version 从 0 重新开始，重置防抖基线
    lastEditorVersions[uri] = 0
    val previous = openDocuments.put(uri, version)
    if (previous != null) {
      sendDidChange(uri, version, text)
      return
    }
    sendDidOpen(path, uri, version, text)
  }

  /** 处理编辑器变更事件（didChange）。 */
  fun didChange(event: DocumentChangeEvent) {
    val path = event.changedFile
    val text = event.newText ?: readTextOrNull(path.toFile()) ?: return
    didChange(path, text, event.version)
  }

  /** 直接以文本刷新文档（补全请求前同步编辑器最新内容用）。 */
  fun didChange(path: Path, text: String, editorVersion: Int = -1) {
    val uri = LspConverters.pathToUri(path)

    if (editorVersion >= 0) {
      val last = lastEditorVersions[uri]
      if (last != null && editorVersion <= last) {
        // 乱序到达的过期变更：丢弃比发送错误内容安全（见类注释）
        return
      }
      lastEditorVersions[uri] = editorVersion
    }

    val version = nextVersion.getAndIncrement()
    if (!openDocuments.containsKey(uri)) {
      // 未打开过：补一次 didOpen。事件可能来自「先编辑后打开」的时序
      //（编辑器构造与文件读取异步），补开比丢弃变更安全。
      openDocuments[uri] = version
      sendDidOpen(path, uri, version, text)
      return
    }
    openDocuments[uri] = version
    sendDidChange(uri, version, text)
  }

  /** 保存（didSave）。未打开的文档忽略。 */
  fun didSave(event: DocumentSaveEvent) {
    val uri = LspConverters.pathToUri(event.savedFile)
    if (!openDocuments.containsKey(uri)) {
      return
    }
    val params =
        JsonObject().apply {
          add("textDocument", JsonObject().apply { addProperty("uri", uri) })
        }
    client.notify("textDocument/didSave", params)
  }

  /** 关闭（didClose）。未打开的文档忽略。 */
  fun didClose(event: DocumentCloseEvent) {
    val uri = LspConverters.pathToUri(event.closedFile)
    if (openDocuments.remove(uri) == null) {
      return
    }
    lastEditorVersions.remove(uri)
    val params =
        JsonObject().apply {
          add("textDocument", JsonObject().apply { addProperty("uri", uri) })
        }
    client.notify("textDocument/didClose", params)
  }

  /** 服务器重启后清空本地跟踪（新进程不知道旧文档）。 */
  fun reset() {
    openDocuments.clear()
    lastEditorVersions.clear()
  }

  /** 已同步文档数（诊断用）。 */
  fun openDocumentCount(): Int = openDocuments.size

  private fun sendDidOpen(path: Path, uri: String, version: Int, text: String) {
    val params =
        JsonObject().apply {
          add(
              "textDocument",
              JsonObject().apply {
                addProperty("uri", uri)
                addProperty("languageId", languageIdFor(path))
                addProperty("version", version)
                addProperty("text", text)
              },
          )
        }
    client.notify("textDocument/didOpen", params)
  }

  private fun sendDidChange(uri: String, version: Int, text: String) {
    val changes = JsonArray().apply { add(JsonObject().apply { addProperty("text", text) }) }
    val params =
        JsonObject().apply {
          add(
              "textDocument",
              JsonObject().apply {
                addProperty("uri", uri)
                addProperty("version", version)
              },
          )
          add("contentChanges", changes)
        }
    client.notify("textDocument/didChange", params)
  }

  private fun readTextOrNull(file: java.io.File): String? =
      try {
        file.readText(Charsets.UTF_8)
      } catch (e: Exception) {
        null
      }
}
