/*
 * This file is part of AndroidCodeStudio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.artificial.agent

import com.tom.rv2ide.ai.tool.GatedAnalyzer
import com.tom.rv2ide.ai.tool.WorkspaceSymbolPort
// Result 是本类的返回类型；不加别名会解析到 kotlin.Result（同名的标准库类型）。
import com.tom.rv2ide.ai.tool.WorkspaceSymbolPort.Result as SymbolResult
import com.tom.rv2ide.lsp.java.providers.WorkspaceSymbolProvider
import com.tom.rv2ide.projects.IProjectManager
import com.tom.rv2ide.projects.IWorkspace
import com.tom.rv2ide.projects.ModuleProject
import org.slf4j.LoggerFactory

/**
 * 把 IDE 的工作区符号搜索接到 AI 工具层。
 *
 * **与 [ReportedDiagnosticsPort] 同构**：
 * - 工作区**按调用时现查**（用户可能中途才打开项目）；
 * - 硬超时走 [GatedAnalyzer]——搜索循环里的 parse 是阻塞的，协程超时对它无效；
 * - 四态分离：unavailable / failed / timedOut / of（外加 truncated 维度），绝不用空列表表达失败。
 *
 * **为什么不用语言服务器实例**：搜索路径完全不经过 `ILanguageServer`（`Parser` 自己经
 * `IProjectManager` 找模块，`compileJavaSourceClasses` 是 `ModuleProject` 的公开字段），
 * 因此不受「服务器是否已 start」的时序影响——只要求「项目已打开」（索引已建）。
 */
class ReportedWorkspaceSymbolPort : WorkspaceSymbolPort {

  override fun isAvailable(): Boolean = unavailableReason().isEmpty()

  override fun unavailableReason(): String {
    val workspace = workspaceOrNull() ?: return NO_PROJECT_REASON
    if (modulesOf(workspace).none { hasJavaSources(it) }) {
      return NO_JAVA_SOURCES_REASON
    }
    return ""
  }

  override fun search(
      query: String,
      kinds: Set<WorkspaceSymbolPort.Kind>?,
      limit: Int,
      timeoutMs: Long,
  ): SymbolResult {
    val workspace = workspaceOrNull() ?: return SymbolResult.unavailable(unavailableReason())

    val modules = modulesOf(workspace)
    if (modules.isEmpty() || modules.none { hasJavaSources(it) }) {
      return SymbolResult.unavailable(unavailableReason())
    }

    // 索引为空 ≠ 没找到：模块可能尚未同步完成。给 failed + 可执行说明，
    // 与「搜索完成、确实无此符号」的文案严格区分。
    if (modules.all { it.compileJavaSourceClasses.allSources().isEmpty() }) {
      return SymbolResult.failed(
          "项目源码索引为空：模块可能尚未同步完成（请先跑一次 Gradle 同步），或项目没有 Java 源码")
    }

    val localKinds = kinds?.mapNotNull { toLocalKind(it) }?.toSet()
    // 内层再留 SLACK_MS 余量：搜索循环在 deadline 处自行收尾、返回部分结果 + truncated，
    // 比被 GatedAnalyzer 一刀切掉（0 结果）对模型更有用。
    val budgetMs = (timeoutMs - SLACK_MS).coerceAtLeast(1L)

    val outcome =
        GatedAnalyzer.run(
            GatedAnalyzer.BlockingTask {
              val deadline = System.nanoTime() + budgetMs * 1_000_000L
              WorkspaceSymbolProvider(workspace)
                  .search(query, localKinds, limit, deadline)
            },
            timeoutMs)
    if (outcome === GatedAnalyzer.TIMED_OUT) {
      return SymbolResult.timedOut("搜索超时（超过 ${timeoutMs / 1000} 秒）")
    }
    if (outcome !is Pair<*, *>) {
      // 任务抛异常时 GatedAnalyzer 返回 null；不透出异常文本，避免泄漏内部栈。
      return SymbolResult.failed("符号搜索执行异常")
    }

    @Suppress("UNCHECKED_CAST")
    val outcomePair = outcome as Pair<List<WorkspaceSymbolProvider.Match>, String>
    val matches = outcomePair.first
    val truncatedReason = outcomePair.second
    val symbols = matches.map { it.toPortSymbol() }
    return if (truncatedReason.isEmpty()) {
      SymbolResult.of(symbols)
    } else {
      SymbolResult.ofTruncated(symbols, truncatedReason)
    }
  }

  private fun WorkspaceSymbolProvider.Match.toPortSymbol(): WorkspaceSymbolPort.Symbol =
      WorkspaceSymbolPort.Symbol(
          name, owner, toPortKind(kind), file.toAbsolutePath().toString(), line, column)

  /** 工作区里的模块项目（跳过根项目，与 `JavaLanguageServer.setupWorkspace` 口径一致）。 */
  private fun modulesOf(workspace: IWorkspace): List<ModuleProject> {
    val rootPath =
        try {
          workspace.getRootProject().path
        } catch (t: Throwable) {
          null
        }
    return workspace.getSubProjects().filterIsInstance<ModuleProject>().filter { it.path != rootPath }
  }

  private fun hasJavaSources(module: ModuleProject): Boolean =
      try {
        module.compileJavaSourceClasses.allSources().isNotEmpty()
      } catch (t: Throwable) {
        false
      }

  private fun workspaceOrNull(): IWorkspace? =
      try {
        IProjectManager.getInstance().getWorkspace()
      } catch (t: Throwable) {
        log.debug("取工作区失败: {}", t.message)
        null
      }

  companion object {
    private val log = LoggerFactory.getLogger(ReportedWorkspaceSymbolPort::class.java)

    /** GatedAnalyzer 与内层 deadline 之间的余量：留给结果转换与线程收尾。 */
    private const val SLACK_MS = 1_500L

    private const val NO_PROJECT_REASON =
        "当前没有打开的项目（符号索引随项目会话建立，请在编辑器中打开该项目）"

    private const val NO_JAVA_SOURCES_REASON = "当前项目没有可搜索的 Java 源码（.kt 暂不支持）"

    private fun toLocalKind(kind: WorkspaceSymbolPort.Kind): WorkspaceSymbolProvider.Kind? =
        when (kind) {
          WorkspaceSymbolPort.Kind.CLASS -> WorkspaceSymbolProvider.Kind.CLASS
          WorkspaceSymbolPort.Kind.INTERFACE -> WorkspaceSymbolProvider.Kind.INTERFACE
          WorkspaceSymbolPort.Kind.ENUM -> WorkspaceSymbolProvider.Kind.ENUM
          WorkspaceSymbolPort.Kind.ANNOTATION -> WorkspaceSymbolProvider.Kind.ANNOTATION
          WorkspaceSymbolPort.Kind.METHOD -> WorkspaceSymbolProvider.Kind.METHOD
          WorkspaceSymbolPort.Kind.FIELD -> WorkspaceSymbolProvider.Kind.FIELD
          else -> null
        }

    private fun toPortKind(kind: WorkspaceSymbolProvider.Kind): WorkspaceSymbolPort.Kind =
        when (kind) {
          WorkspaceSymbolProvider.Kind.CLASS -> WorkspaceSymbolPort.Kind.CLASS
          WorkspaceSymbolProvider.Kind.INTERFACE -> WorkspaceSymbolPort.Kind.INTERFACE
          WorkspaceSymbolProvider.Kind.ENUM -> WorkspaceSymbolPort.Kind.ENUM
          WorkspaceSymbolProvider.Kind.ANNOTATION -> WorkspaceSymbolPort.Kind.ANNOTATION
          WorkspaceSymbolProvider.Kind.METHOD -> WorkspaceSymbolPort.Kind.METHOD
          WorkspaceSymbolProvider.Kind.FIELD -> WorkspaceSymbolPort.Kind.FIELD
        }
  }
}
