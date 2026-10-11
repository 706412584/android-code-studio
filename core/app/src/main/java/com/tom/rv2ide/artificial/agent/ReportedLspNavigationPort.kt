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

import com.tom.rv2ide.ai.tool.GatedAnalyzer
import com.tom.rv2ide.ai.tool.LspNavigationPort
import com.tom.rv2ide.lsp.api.ILanguageServer
import com.tom.rv2ide.lsp.models.DefinitionParams
import com.tom.rv2ide.lsp.models.DefinitionResult
import com.tom.rv2ide.lsp.models.ReferenceParams
import com.tom.rv2ide.lsp.models.ReferenceResult
import com.tom.rv2ide.models.Location as LspLocation
import com.tom.rv2ide.models.Position
import com.tom.rv2ide.progress.ICancelChecker
import java.io.File
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

/**
 * 把语言服务器的导航能力（跳定义 / 找引用）接到 AI 工具层。
 *
 * **与诊断同构**：ai-tool 模块零 Android/LSP 依赖，因此转换与驱动都放在 app 层；
 * 超时同样用 [GatedAnalyzer]（语言服务器的 findDefinition/findReferences 是挂起函数，
 * 但内部可能走阻塞式编译，协程超时不可靠）。
 *
 * **只对实现了导航的语言服务器生效**：jdt（`ide.lsp.java`）实现了定义/引用查找；
 * XML/Clang 未实现（返回空结果），Kotlin 的引用查找也有限。因此这里用 isSupported
 * 限定到 Java/Kotlin，其余类型由工具层明确报「不支持」而不是给「没找到」的假结果。
 */
class ReportedLspNavigationPort(private val serverProvider: (String) -> ILanguageServer?) :
    LspNavigationPort {

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

  override fun navigate(
      filePath: String,
      line: Int,
      column: Int,
      kind: LspNavigationPort.Kind,
      timeoutMs: Long,
  ): LspNavigationPort.Result {
    val file = File(filePath)
    val serverId =
        serverIdFor(file)
            ?: return LspNavigationPort.Result.unavailable(
                "该文件类型没有支持导航的语言服务器（仅 .java / .kt）")

    val server =
        try {
          serverProvider(serverId)
        } catch (e: RuntimeException) {
          null
        }
    if (server == null) {
      return LspNavigationPort.Result.unavailable("$NO_PROJECT_REASON，或 $serverId 语言服务器未启动")
    }

    val position = Position(line, column)
    val path = file.toPath()
    val outcome =
        GatedAnalyzer.run(
            GatedAnalyzer.BlockingTask {
              runBlocking {
                when (kind) {
                  LspNavigationPort.Kind.DEFINITION ->
                      server.findDefinition(
                          DefinitionParams(path, position, ICancelChecker.NOOP))
                  LspNavigationPort.Kind.REFERENCES ->
                      server.findReferences(
                          ReferenceParams(path, position, true, ICancelChecker.NOOP))
                }
              }
            },
            timeoutMs)

    if (outcome === GatedAnalyzer.TIMED_OUT) {
      return LspNavigationPort.Result.timedOut("导航超时（超过 ${timeoutMs / 1000} 秒）")
    }

    val locations: List<LspLocation> =
        when (outcome) {
          is DefinitionResult -> outcome.locations
          is ReferenceResult -> outcome.locations
          else -> return LspNavigationPort.Result.failed("语言服务器未返回导航结果或执行异常")
        }

    return LspNavigationPort.Result.of(
        locations.map {
          LspNavigationPort.Location(
              it.file.toString(), it.range.start.line, it.range.start.column)
        })
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
    private val log = LoggerFactory.getLogger(ReportedLspNavigationPort::class.java)

    private const val SERVER_ID_JAVA = "ide.lsp.java"
    private const val SERVER_ID_KOTLIN = "kotlin"

    private val SERVER_IDS = arrayOf(SERVER_ID_JAVA, SERVER_ID_KOTLIN)

    private const val NO_PROJECT_REASON = "当前没有打开的项目"
  }
}
