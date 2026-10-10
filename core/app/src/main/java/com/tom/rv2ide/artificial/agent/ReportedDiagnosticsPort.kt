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

import com.tom.rv2ide.ai.tool.DiagnosticsPort
import com.tom.rv2ide.lsp.api.ILanguageServer
import com.tom.rv2ide.lsp.models.DiagnosticItem
import com.tom.rv2ide.lsp.models.DiagnosticResult
import com.tom.rv2ide.lsp.models.DiagnosticSeverity
import java.io.File
import java.util.Locale
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory

/**
 * 把 IDE 语言服务器的诊断能力接到 AI 工具层的适配器。
 *
 * **为什么需要适配**：`core/ai-tool` 是纯 JVM、零 Android 依赖的模块，不能引用 LSP 模型
 * （`DiagnosticItem` 等）。本类在 app 层完成 `DiagnosticResult → DiagnosticsPort.Report`
 * 的类型转换，并驱动 `suspend fun analyze`。
 *
 * **线程**：agent 循环跑在后台线程（见 `AgentOrchestrator` 的 `new Thread`），
 * 因此这里用 [runBlocking] 驱动挂起函数不会阻塞主线程。
 *
 * **NO_UPDATE 必须当作失败而不是「无诊断」**：语言服务器的 `analyze` 在
 * 「文件不属于任何模块」「分析异常」「设置里关闭了分析」等多种情况下都返回 `NO_UPDATE`，
 * 它与「分析完成且没有诊断」在数据结构上不可区分。若把它当干净结果回传，
 * 模型会得出「我改的代码没问题」的错误结论——这是静默失败最有害的形态。
 * 因此这里用**引用比较**识别 NO_UPDATE 并明确报失败。
 */
class ReportedDiagnosticsPort(private val serverProvider: (String) -> ILanguageServer?) :
    DiagnosticsPort {

  /** 单文件分析超时（ms）：首次编译要建符号索引，给足余量。 */
  private val analysisTimeoutMs = 20_000L

  override fun isAvailable(): Boolean = unavailableReason().isEmpty()

  override fun unavailableReason(): String {
    // 语言服务器随项目会话启动：任一 server 可达即可用；都取不到说明没有项目在编辑器里打开。
    for (id in SERVER_IDS) {
      try {
        if (serverProvider(id) != null) {
          return ""
        }
      } catch (e: RuntimeException) {
        // 注册表未初始化等异常按不可用处理，交由下一次调用重试。
        log.debug("语言服务器 {} 查询失败: {}", id, e.message)
      }
    }
    return NO_PROJECT_REASON + "（语言服务器随项目会话启动，请在编辑器中打开该项目）"
  }

  override fun analyze(absolutePath: String, timeoutMs: Long): DiagnosticsPort.Report {
    if (absolutePath.isBlank()) {
      return DiagnosticsPort.Report.failed("文件路径为空")
    }
    val file = File(absolutePath)
    val serverId = serverIdFor(file)
        ?: return DiagnosticsPort.Report.unavailable("该文件类型没有可用的语言服务器（仅 .java / .kt 支持按需分析）")

    val server =
        try {
          serverProvider(serverId)
        } catch (e: RuntimeException) {
          log.debug("取语言服务器 {} 失败: {}", serverId, e.message)
          null
        }
    if (server == null) {
      return DiagnosticsPort.Report.unavailable("$NO_PROJECT_REASON，或 $serverId 语言服务器未启动")
    }

    val budgetMs = if (timeoutMs > 0) timeoutMs else analysisTimeoutMs
    val result =
        try {
          // Java 的 analyze 内部是 join() 阻塞实现，取消不可抢占，超时只在协作式实现上生效。
          runBlocking { withTimeoutOrNull(budgetMs) { server.analyze(file.toPath()) } }
        } catch (e: Throwable) {
          log.warn("分析 {} 失败: {}", file.name, e.message)
          null
        }
    if (result == null) {
      return DiagnosticsPort.Report.failed("分析超时（超过 ${budgetMs / 1000} 秒）或执行异常")
    }
    // 引用比较：NO_UPDATE 是哨兵单例，含义是「没能分析」而非「没有诊断」。
    if (result === DiagnosticResult.NO_UPDATE) {
      return DiagnosticsPort.Report.failed(
          "语言服务器未返回分析结果：文件可能不属于当前项目的任何模块，或设置中关闭了代码分析，或分析过程出错")
    }

    return DiagnosticsPort.Report.of(result.diagnostics.map { it.toPortItem() })
  }

  private fun DiagnosticItem.toPortItem(): DiagnosticsPort.Item =
      DiagnosticsPort.Item(
          range.start.line,
          range.start.column,
          severityValue(severity),
          message,
          code)

  private fun severityValue(severity: DiagnosticSeverity): Int =
      when (severity) {
        DiagnosticSeverity.ERROR -> 1
        DiagnosticSeverity.WARNING -> 2
        DiagnosticSeverity.INFO -> 3
        DiagnosticSeverity.HINT -> 4
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
    private val log = LoggerFactory.getLogger(ReportedDiagnosticsPort::class.java)

    /** 语言服务器 id，与 LspHandler.registerLanguageServers 注册的常量一致。 */
    private const val SERVER_ID_JAVA = "ide.lsp.java"
    private const val SERVER_ID_KOTLIN = "kotlin"

    private val SERVER_IDS = arrayOf(SERVER_ID_JAVA, SERVER_ID_KOTLIN)

    private const val NO_PROJECT_REASON = "当前没有打开的项目"
  }
}
