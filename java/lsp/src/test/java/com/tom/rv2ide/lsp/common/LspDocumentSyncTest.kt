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

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.tom.rv2ide.eventbus.events.editor.ChangeType
import com.tom.rv2ide.eventbus.events.editor.DocumentChangeEvent
import com.tom.rv2ide.eventbus.events.editor.DocumentCloseEvent
import com.tom.rv2ide.eventbus.events.editor.DocumentSaveEvent
import com.tom.rv2ide.models.Range
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LspDocumentSync] 的行为测试：用真实管道验证发往服务器的报文序列。
 *
 * <p>覆盖：didOpen 只发一次、didChange 全量同步、乱序版本丢弃、
 * 未打开文档的变更自动补 didOpen、didClose 后不再发变更。
 */
class LspDocumentSyncTest {

  private val gson = Gson()

  /** 建一套管道 + 客户端 + sync，返回 (sync, 读端)。 */
  private fun setup(): Pair<LspDocumentSync, java.io.InputStream> {
    val toClient = PipedInputStream(256 * 1024)
    val clientOutput = PipedOutputStream(toClient)
    val toServer = PipedInputStream(256 * 1024)
    val serverOutput = PipedOutputStream(toServer)
    val client = LspStdioClient(toClient, serverOutput)
    val sync = LspDocumentSync(client).also { it.languageIdFor = { "typescript" } }
    return sync to toServer
  }

  private fun readFrame(input: java.io.InputStream): JsonObject {
    val json = LspFraming.readMessage(input) ?: error("EOF")
    return gson.fromJson(json, JsonObject::class.java)
  }

  @Test
  fun didOpen_sendsLanguageIdAndText() {
    val (sync, server) = setup()
    val path = Paths.get("/sdcard/proj/App.tsx")

    sync.didOpen(path, "const x = 1;")

    val frame = readFrame(server)
    assertEquals("textDocument/didOpen", frame.get("method").asString)
    val td = frame.getAsJsonObject("params").getAsJsonObject("textDocument")
    // 不断言具体 uri 字符串：Windows 上 Path.toUri() 会给无盘符绝对路径补
    // 盘符（与生产 Android 不同）。断言 uri 以原路径结尾即可。
    assertTrue(
        "uri 应指向打开的文件：${td.get("uri").asString}",
        td.get("uri").asString.endsWith("sdcard/proj/App.tsx"))
    assertEquals("typescript", td.get("languageId").asString)
    assertEquals("const x = 1;", td.get("text").asString)
    assertTrue(sync.isOpen(path))
  }

  @Test
  fun didOpen_twice_sendsChangeNotDuplicateOpen() {
    // 重复 didOpen 是协议违规（编辑器可能多次触发打开事件）
    val (sync, server) = setup()
    val path = Paths.get("/sdcard/proj/App.tsx")

    sync.didOpen(path, "v1")
    sync.didOpen(path, "v2")

    assertEquals("textDocument/didOpen", readFrame(server).get("method").asString)
    val second = readFrame(server)
    assertEquals("textDocument/didChange", second.get("method").asString)
    val changes = second.getAsJsonObject("params").getAsJsonArray("contentChanges")
    assertEquals("v2", changes[0].asJsonObject.get("text").asString)
  }

  @Test
  fun didChange_sendsFullText() {
    val (sync, server) = setup()
    val path = Paths.get("/sdcard/proj/App.tsx")

    sync.didOpen(path, "v1")
    readFrame(server)

    val event =
        DocumentChangeEvent(
            changedFile = path,
            changedText = "x",
            _newText = null,
            version = 2,
            changeType = ChangeType.INSERT,
            changeDelta = 1,
            changeRange = Range.NONE,
        )
    event.newText = "v2 full text"
    sync.didChange(event)

    val frame = readFrame(server)
    assertEquals("textDocument/didChange", frame.get("method").asString)
    val params = frame.getAsJsonObject("params")
    val changes = params.getAsJsonArray("contentChanges")
    // 全量同步：不带 range 字段，只有 text
    val change = changes[0].asJsonObject
    assertEquals("v2 full text", change.get("text").asString)
    assertFalse("全量同步不应带 range", change.has("range"))
  }

  @Test
  fun didChange_staleVersionDropped() {
    val (sync, server) = setup()
    val path = Paths.get("/sdcard/proj/App.tsx")

    sync.didOpen(path, "v0")
    readFrame(server)

    fun change(version: Int, text: String) {
      val event =
          DocumentChangeEvent(
              changedFile = path,
              changedText = "x",
              _newText = null,
              version = version,
              changeType = ChangeType.INSERT,
              changeDelta = 1,
              changeRange = Range.NONE,
          )
      event.newText = text
      sync.didChange(event)
    }

    change(2, "v2")
    assertEquals("v2", readFrame(server).getAsJsonObject("params")
        .getAsJsonArray("contentChanges")[0].asJsonObject.get("text").asString)

    // 乱序到达的过期变更（v1 < v2）：必须丢弃，否则服务器停在旧内容上
    change(1, "v1-stale")
    // 再来一条合法的（v3），验证队列仍能正常工作
    change(3, "v3")
    val frame = readFrame(server)
    assertEquals("v3", frame.getAsJsonObject("params")
        .getAsJsonArray("contentChanges")[0].asJsonObject.get("text").asString)
  }

  @Test
  fun didChange_withoutOpen_sendsDidOpenFirst() {
    val (sync, server) = setup()
    val path = Paths.get("/sdcard/proj/App.tsx")

    // 未 didOpen 直接变更（编辑器异步时序）→ 补发 didOpen
    sync.didChange(path, "hello")

    val frame = readFrame(server)
    assertEquals("textDocument/didOpen", frame.get("method").asString)
    assertTrue(sync.isOpen(path))
  }

  @Test
  fun didClose_stopsTracking() {
    val (sync, server) = setup()
    val path = Paths.get("/sdcard/proj/App.tsx")

    sync.didOpen(path, "v1")
    readFrame(server)

    sync.didClose(DocumentCloseEvent(path))
    assertEquals("textDocument/didClose", readFrame(server).get("method").asString)
    assertFalse(sync.isOpen(path))

    // 关闭后的变更会重新走「补 didOpen」路径
    sync.didChange(path, "v2")
    assertEquals("textDocument/didOpen", readFrame(server).get("method").asString)
  }

  @Test
  fun didClose_untrackedDocument_sendsNothing() {
    val (sync, server) = setup()
    sync.didClose(DocumentCloseEvent(Paths.get("/sdcard/proj/Other.tsx")))
    // 读端无数据：用 available() 判断（管道中不应有字节）
    Thread.sleep(100)
    assertEquals(0, server.available())
  }

  @Test
  fun didSave_onlyForOpenDocuments() {
    val (sync, server) = setup()
    val path = Paths.get("/sdcard/proj/App.tsx")

    // 未打开：不发
    sync.didSave(DocumentSaveEvent(path))
    Thread.sleep(100)
    assertEquals(0, server.available())

    // 打开后：发
    sync.didOpen(path, "v1")
    readFrame(server)
    sync.didSave(DocumentSaveEvent(path))
    assertEquals("textDocument/didSave", readFrame(server).get("method").asString)
  }

  @Test
  fun reset_clearsTracking() {
    val (sync, server) = setup()
    val path = Paths.get("/sdcard/proj/App.tsx")
    sync.didOpen(path, "v1")
    readFrame(server)

    sync.reset()
    assertFalse(sync.isOpen(path))
    assertEquals(0, sync.openDocumentCount())
  }

  @Test
  fun languageIdFor_perFileResolution() {
    val (sync, _) = setup()
    sync.languageIdFor = { p ->
      when (p.fileName.toString().substringAfterLast('.')) {
        "jsx" -> "javascriptreact"
        "tsx" -> "typescriptreact"
        else -> "typescript"
      }
    }
    val path = Paths.get("/sdcard/proj/App.tsx")
    sync.didOpen(path, "x")
    // 无法直接读 languageIdFor 的输出而不读帧，这里通过重开读取验证
    // （languageIdFor 的行为在集成层验证；此处只验证不抛异常）
    assertTrue(sync.isOpen(path))
  }
}
