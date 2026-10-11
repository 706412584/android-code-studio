/*
 * This file is part of AndroidCodeStudio.
 *
 * AndroidCodeStudio is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AndroidCodeStudio is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.artificial.agent

import com.tom.rv2ide.ai.tool.CodeFormatPort
import com.tom.rv2ide.ai.tool.GatedAnalyzer
import com.tom.rv2ide.lsp.api.ILanguageServer
import com.tom.rv2ide.lsp.models.CodeFormatResult
import com.tom.rv2ide.lsp.models.FormatCodeParams
import com.tom.rv2ide.models.Range
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Locale
import org.slf4j.LoggerFactory

/**
 * 把语言服务器的格式化能力接到 AI 工具层。
 *
 * **整体流程在本类内完成**：读文件 → `formatCode`（对整份内容）→ 把结果里的 edits
 * 应用到文本 → 写回文件。工具层只需知道「改了没有、改了多少字符」。
 *
 * **应用 edits 必须与编辑器一致**：编辑器的 `LSPFormatter` 见 **`result.isIndexed`** 才用
 * `indexedTextEdits`（char index），否则用 `edits`（line/column Range）反向应用。
 * 这里沿用同一判据，否则 Java 的格式化结果会错位（Java 返回 indexed=true）。
 */
class ReportedCodeFormatPort(private val serverProvider: (String) -> ILanguageServer?) :
    CodeFormatPort {

  override fun isAvailable(): Boolean = unavailableReason().isEmpty()

  override fun unavailableReason(): String {
    for (id in SERVER_IDS) {
      try {
        if (serverProvider(id) != null) {
          return ""
        }
      } catch (e: RuntimeException) {
        log.debug("语言服务器 {} 查询失败: {}", id, e.message)
      }
    }
    return NO_PROJECT_REASON + "（语言服务器随项目会话启动，请在编辑器中打开该项目）"
  }

  override fun isSupported(filePath: String): Boolean = serverIdFor(File(filePath)) != null

  override fun format(absolutePath: String, timeoutMs: Long): CodeFormatPort.Result {
    val file = File(absolutePath)
    val serverId =
        serverIdFor(file)
            ?: return CodeFormatPort.Result.unavailable("该文件类型没有格式化器（仅 .java / .kt）")
    val server =
        try {
          serverProvider(serverId)
        } catch (e: RuntimeException) {
          null
        }
    if (server == null) {
      return CodeFormatPort.Result.unavailable("$NO_PROJECT_REASON，或 $serverId 语言服务器未启动")
    }

    val original =
        try {
          String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)
        } catch (e: Exception) {
          return CodeFormatPort.Result.failed("读取文件失败：${e.message}")
        }

    val outcome =
        GatedAnalyzer.run(
            GatedAnalyzer.BlockingTask {
              // 格式化是同步实现（Java 直接跑 formatter，Kotlin 走进程）——用硬超时保护。
              server.formatCode(FormatCodeParams(original, Range.NONE))
            },
            timeoutMs)
    if (outcome === GatedAnalyzer.TIMED_OUT) {
      return CodeFormatPort.Result.timedOut("格式化超时（超过 ${timeoutMs / 1000} 秒）")
    }
    if (outcome !is CodeFormatResult) {
      return CodeFormatPort.Result.failed("格式化未返回结果或执行异常")
    }

    val formatted = applyEdits(original, outcome)
    if (formatted == original) {
      return CodeFormatPort.Result.done(false, 0)
    }
    return try {
      Files.write(file.toPath(), formatted.toByteArray(StandardCharsets.UTF_8))
      CodeFormatPort.Result.done(true, kotlin.math.abs(formatted.length - original.length))
    } catch (e: Exception) {
      CodeFormatPort.Result.failed("写回文件失败：${e.message}")
    }
  }

  /** 与编辑器 LSPFormatter 相同的应用方式：indexed 用 char index，否则用 line/column。 */
  private fun applyEdits(original: String, result: CodeFormatResult): String {
    val hasIndexed = result.indexedTextEdits.isNotEmpty()
    val hasRange = result.edits.isNotEmpty()
    if (!hasIndexed && !hasRange) {
      return original
    }
    var text = original
    if (result.isIndexed && hasIndexed) {
      // 按 index 倒序应用，避免前面的插入/删除移动了后面 edit 的偏移。
      result.indexedTextEdits
          .sortedByDescending { it.start }
          .forEach { edit ->
            val start = edit.start.coerceIn(0, text.length)
            val end = edit.end.coerceIn(start, text.length)
            text = text.substring(0, start) + edit.newText + text.substring(end)
          }
    } else {
      result.edits
          .sortedWith(compareByDescending<com.tom.rv2ide.lsp.models.TextEdit> { it.range.start.line }
              .thenByDescending { it.range.start.column })
          .forEach { edit ->
            val start = indexOf(text, edit.range.start.line, edit.range.start.column)
            val end = indexOf(text, edit.range.end.line, edit.range.end.column)
            if (start in 0..text.length && end in start..text.length) {
              text = text.substring(0, start) + edit.newText + text.substring(end)
            }
          }
    }
    return text
  }

  /** line/column（0 基）→ char index。 */
  private fun indexOf(text: String, line: Int, column: Int): Int {
    var currentLine = 0
    var index = 0
    while (currentLine < line && index < text.length) {
      if (text[index] == '\n') {
        currentLine++
      }
      index++
    }
    return (index + column).coerceIn(0, text.length)
  }

  private fun serverIdFor(file: File): String? {
    val name = file.name
    val dot = name.lastIndexOf('.')
    return when (if (dot < 0) "" else name.substring(dot + 1).lowercase(Locale.US)) {
      "java" -> SERVER_ID_JAVA
      "kt", "kts" -> SERVER_ID_KOTLIN
      else -> null
    }
  }

  companion object {
    private val log = LoggerFactory.getLogger(ReportedCodeFormatPort::class.java)

    private const val SERVER_ID_JAVA = "ide.lsp.java"
    private const val SERVER_ID_KOTLIN = "kotlin"

    private val SERVER_IDS = arrayOf(SERVER_ID_JAVA, SERVER_ID_KOTLIN)

    private const val NO_PROJECT_REASON = "当前没有打开的项目"
  }
}
