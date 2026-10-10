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
import com.tom.rv2ide.ai.tool.GatedAnalyzer
import com.tom.rv2ide.lsp.api.ILanguageServer
import com.tom.rv2ide.lsp.models.DiagnosticResult
import com.tom.rv2ide.lsp.models.DiagnosticSeverity
import java.io.File
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

/**
 * 把 IDE 语言服务器的诊断能力接到 AI 工具层的适配器。
 *
 * **为什么需要适配**：`core/ai-tool` 是纯 JVM、零 Android 依赖的模块，不能引用 LSP 模型
 * （`DiagnosticItem` 等）。本类在 app 层完成 `DiagnosticResult → DiagnosticsPort.Report`
 * 的类型转换，并驱动 `suspend fun analyze`。
 *
 * **超时必须是真的**：见 [GatedAnalyzer] —— 语言服务器的 analyze 是
 * `thread.join()` 阻塞式实现、没有挂起点，协程超时对它无效。这里用独立可中断线程
 * 加硬超时，避免编辑等高频路径被拖住。
 *
 * **NO_UPDATE 的语义按语言服务器区分（关键）**：
 * - **Java** 的 `analyze` 在「文件不属于任何模块」「分析异常」「设置关闭了分析」时都
 *   返回 `NO_UPDATE`，它与「分析完成且无诊断」结构上不可区分。当成干净结果回传会让模型
 *   得出「我改的代码没问题」的错误结论，因此这里报 **failed**。
 * - **Kotlin** 的 `analyze` **只查缺失导入**，没有导入问题时返回的就是 `NO_UPDATE`——
 *   即「干净」对 Kotlin 走的是与「失败」同一个哨兵。若也报 failed，则绝大多数正常的
 *   `.kt` 文件都会报「分析未完成」（假失败），该工具对 Kotlin 就废了。因此对 Kotlin
 *   把 `NO_UPDATE` 解释为「无导入问题」，报空的成功结果，并在结果备注里说明只查了导入。
 */
class ReportedDiagnosticsPort(private val serverProvider: (String) -> ILanguageServer?) :
    DiagnosticsPort {

  override fun isAvailable(): Boolean = unavailableReason().isEmpty()

  override fun unavailableReason(): String {
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

  override fun isSupported(filePath: String): Boolean = File(filePath).isAnalyzable()

  override fun analyze(absolutePath: String, timeoutMs: Long): DiagnosticsPort.Report {
    if (absolutePath.isBlank()) {
      return DiagnosticsPort.Report.failed("文件路径为空")
    }
    val file = File(absolutePath)
    val serverId =
        file.serverIdFor()
            ?: return DiagnosticsPort.Report.unavailable(
                "该文件类型没有可用的语言服务器（仅 .java / .kt / .kts 支持按需分析）")

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

    // 硬超时：把 suspend analyze 放进独立线程跑，runBlocking 只负责在**该线程内**驱动协程，
    // 由 GatedAnalyzer 在调用方线程上有界等待。
    val outcome =
        GatedAnalyzer.run(
            GatedAnalyzer.BlockingTask { runBlocking { server.analyze(file.toPath()) } }, timeoutMs)
    if (outcome === GatedAnalyzer.TIMED_OUT) {
      return DiagnosticsPort.Report.timedOut("分析超时（超过 ${timeoutMs / 1000} 秒）")
    }
    if (outcome !is DiagnosticResult) {
      return DiagnosticsPort.Report.failed("分析执行异常或未返回结果")
    }
    val result = outcome

    // 引用比较：NO_UPDATE 是哨兵单例，含义是「没有可用的分析结果」——但对 Kotlin 它同时
    // 也代表「干净」，故按语言服务器区分处理（见类注释）。
    if (result === DiagnosticResult.NO_UPDATE) {
      return if (SERVER_ID_KOTLIN == serverId) {
        DiagnosticsPort.Report.of(emptyList())
      } else {
        DiagnosticsPort.Report.failed(
            "语言服务器未返回分析结果：文件可能不属于当前项目的任何模块，或设置中关闭了代码分析，或分析过程出错")
      }
    }

    return DiagnosticsPort.Report.of(result.diagnostics.map { it.toPortItem() })
  }

  private fun com.tom.rv2ide.lsp.models.DiagnosticItem.toPortItem(): DiagnosticsPort.Item =
      DiagnosticsPort.Item(
          range.start.line, range.start.column, severityValue(severity), message, code)

  private fun severityValue(severity: DiagnosticSeverity): Int =
      when (severity) {
        DiagnosticSeverity.ERROR -> 1
        DiagnosticSeverity.WARNING -> 2
        DiagnosticSeverity.INFO -> 3
        DiagnosticSeverity.HINT -> 4
      }

  /** 扩展名 → 语言服务器 id；不支持时返回 null。 */
  private fun File.serverIdFor(): String? =
      when (extensionLowercase()) {
        "java" -> SERVER_ID_JAVA
        "kt", "kts" -> SERVER_ID_KOTLIN
        else -> null
      }

  private fun File.isAnalyzable(): Boolean = serverIdFor() != null

  private fun File.extensionLowercase(): String {
    val name = this.name
    val dot = name.lastIndexOf('.')
    return if (dot < 0) "" else name.substring(dot + 1).lowercase(Locale.US)
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
