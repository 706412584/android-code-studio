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

import android.os.Handler
import android.os.Looper
import com.blankj.utilcode.util.ActivityUtils
import com.tom.rv2ide.activities.editor.ProjectHandlerActivity
import com.tom.rv2ide.ai.tool.BuildVariantPort
import com.tom.rv2ide.ai.tool.ToolContext
import com.tom.rv2ide.lookup.Lookup
import com.tom.rv2ide.projects.IProjectManager
import com.tom.rv2ide.projects.IWorkspace
import com.tom.rv2ide.projects.android.AndroidModule
import com.tom.rv2ide.projects.builder.BuildService
import java.util.concurrent.atomic.AtomicBoolean
import org.slf4j.LoggerFactory

/**
 * [BuildVariantPort] 的 app 层实现。
 *
 * **切换为什么走 Activity**：变体选择的真实生效点是 tooling 端同步时，唯一生效路径是
 * `ProjectHandlerActivity.initializeProject(selections)`——它与用户在「Build Variants」
 * 面板点 Apply 是**同一条路径**，因此进度条、状态栏、ViewModel 刷新、LSP 重连等副作用
 * 全部自动一致；`setVariantSelections` 是 `core/projects` 的 internal 且只是本地缓存，
 * app 层既调不到、调了也无效。
 *
 * **为什么必须传全量选择**：tooling 端对 selections 中缺失的模块回退到其变体列表第一项
 * （AndroidProjectModelBuilder），只传目标模块会重置其他模块的选择。
 *
 * **线程规则**：所有对 Activity / ViewModel 的访问一律经主线程（`mainHandler.post` /
 * `onMain`）。`editorViewModel` 是 `by viewModels()` 委托属性，首次访问必须在主线程。
 */
class ReportedBuildVariantPort : BuildVariantPort {

  override fun isAvailable(): Boolean = workspaceOrNull() != null

  override fun unavailableReason(): String =
      if (isAvailable()) {
        ""
      } else {
        "当前没有已打开/已同步的项目。请先在编辑器中打开项目，等 Gradle 同步完成后再试。"
      }

  override fun listModules(): List<BuildVariantPort.Module> {
    val workspace = workspaceOrNull() ?: return emptyList()
    val selections =
        try {
          workspace.getAndroidVariantSelections()
        } catch (e: RuntimeException) {
          log.debug("读取变体选择失败: {}", e.message)
          emptyMap()
        }
    return try {
      workspace.getSubProjects()
          .filterIsInstance<AndroidModule>()
          .map { module ->
            val variants =
                module.variants.map { v ->
                  val artifact = v.mainArtifact
                  BuildVariantPort.Variant(
                      v.name,
                      artifact.assembleTaskName ?: "",
                      artifact.applicationId ?: "")
                }
            BuildVariantPort.Module(
                module.path, // GradleProject.path 为非空 String
                module.name,
                module.isApplication,
                module.namespace ?: "", // namespace 可空（AndroidModule.kt 构造参数）
                variants,
                selections[module.path]?.selectedVariant ?: "")
          }
    } catch (e: RuntimeException) {
      // 项目模型未就绪（还在同步）——返回空列表，由工具文案解释。
      log.debug("列出 Android 模块失败: {}", e.message)
      emptyList()
    }
  }

  override fun switchTo(
      modulePath: String,
      variantName: String,
      timeoutMs: Long,
      cancel: ToolContext.CancellationSignal?,
  ): BuildVariantPort.Result {
    // 1) 工作区
    val workspace = workspaceOrNull() ?: return BuildVariantPort.Result.failed(unavailableReason())

    // 2) 模块存在且为 Android 模块
    val module =
        try {
          workspace.findProject(modulePath)
        } catch (e: RuntimeException) {
          null
        }
    if (module !is AndroidModule) {
      return BuildVariantPort.Result.failed(
          "模块 \"$modulePath\" 不存在或不是 Android 模块。可用模块：" + androidModulePaths(workspace))
    }

    // 3) 变体名有效
    val variant =
        try {
          module.getVariant(variantName)
        } catch (e: RuntimeException) {
          null
        }
    if (variant == null) {
      return BuildVariantPort.Result.failed(
          "模块 \"$modulePath\" 没有名为 \"$variantName\" 的变体。可用变体：" +
              module.variants.joinToString(", ") { it.name })
    }

    // 4) 已是当前选择 → 快速路径（不触发同步）
    val currentSelections =
        try {
          workspace.getAndroidVariantSelections()
        } catch (e: RuntimeException) {
          emptyMap()
        }
    if (currentSelections[modulePath]?.selectedVariant == variantName) {
      return BuildVariantPort.Result.alreadySelected(modulePath, variantName)
    }

    // 5) 全量选择：缺失的模块会被 tooling 端回退到第一个变体，必须带全（见类注释）
    if (currentSelections.isEmpty()) {
      return BuildVariantPort.Result.failed(
          "项目同步尚未完成（没有已生效的变体选择）。请等待 Gradle 同步完成后重试。")
    }
    val selections = LinkedHashMap<String, String>()
    currentSelections.forEach { (path, info) -> selections[path] = info.selectedVariant }
    selections[modulePath] = variantName

    // 6) 编辑器 Activity
    val activity =
        findEditorActivity()
            ?: return BuildVariantPort.Result.failed(
                "未找到编辑器界面（项目编辑器未打开或已被系统回收）。请在编辑器中打开项目后重试。")

    // 7) 前置状态检查（主线程）：正在同步/构建时 initializeProject 会静默忽略
    val busy =
        onMain { activity.editorViewModel.isInitializing || activity.editorViewModel.isBuildInProgress }
    if (busy) {
      return BuildVariantPort.Result.failed("项目正在同步或构建中，请等待结束后再切换变体。")
    }
    val service = lookupBuildService()
    if (service == null || !service.isToolingServerStarted()) {
      return BuildVariantPort.Result.failed(
          "Gradle 构建服务尚未就绪。请等待项目同步完成后再试（若刚打开项目，请等状态栏显示同步完成）。")
    }

    // 8) 触发同步（主线程；与用户手动 Apply 同路径）
    onMain { activity.initializeProject(selections) }

    // 9) 等待新变体在 workspace 中生效
    return awaitSwitch(activity, modulePath, variantName, timeoutMs, cancel)
  }

  // ---- 等待状态机 ----

  private fun awaitSwitch(
      activity: ProjectHandlerActivity,
      modulePath: String,
      variantName: String,
      timeoutMs: Long,
      cancel: ToolContext.CancellationSignal?,
  ): BuildVariantPort.Result {
    val deadline = System.currentTimeMillis() + timeoutMs.coerceAtLeast(1_000L)
    val startWindow = System.currentTimeMillis() + START_WINDOW_MS
    // 非阻塞快照：每轮 post 一次读值，避免在等待循环里做同步主线程往返（主线程若卡死会拖住判定）。
    // 时序说明：post 是异步的，本轮 get() 读到的是**上一轮**投递的快照（延迟 ≤ 500ms）。
    // 这对 10 分钟级操作与 10s 启动窗口都无影响；「回落检测」最多晚 500ms 发现。
    val initializingRef = AtomicBoolean(false)
    var sawInitializing = false

    while (true) {
      if (cancel != null && cancel.isCancelled()) {
        return BuildVariantPort.Result.cancelled(
            modulePath,
            variantName,
            "用户停止。已触发的项目同步不会因此中止，可能仍在后台进行。")
      }

      // 每轮重新取 workspace —— 同步成功会替换 WorkspaceImpl 实例，旧引用看不到新选择
      val selected =
          try {
            IProjectManager.getInstance()
                .getWorkspace()
                ?.getAndroidVariantSelections()
                ?.get(modulePath)
                ?.selectedVariant
          } catch (e: RuntimeException) {
            null
          }
      if (selected == variantName) {
        return BuildVariantPort.Result.switched(
            modulePath, variantName, assembleTaskOf(modulePath, variantName))
      }

      mainHandler.post { initializingRef.set(activity.editorViewModel.isInitializing) }
      val initializing = initializingRef.get()
      if (initializing) {
        sawInitializing = true
      } else if (sawInitializing) {
        // 同步流程已结束（postProjectInit 跑过）但变体未生效 → 初始化失败
        return BuildVariantPort.Result.failed(
            "项目同步已结束，但变体未能切换为 \"$variantName\"（初始化可能失败）。" +
                "请查看编辑器状态栏与 IDE 日志（ide_log_read）后重试。")
      } else if (System.currentTimeMillis() > startWindow) {
        // 从未观察到「正在初始化」→ initializeProject 被静默忽略（并发保护）或未执行
        return BuildVariantPort.Result.failed(
            "未能启动项目同步（可能项目正在同步/构建中，或编辑器界面不可用）。请稍后重试。")
      }

      if (System.currentTimeMillis() > deadline) {
        return BuildVariantPort.Result.timedOut(
            modulePath,
            variantName,
            "等待期间变体仍未生效。项目同步可能仍在后台进行——可稍后在侧栏「Build Variants」面板"
                + "确认结果；重试前请先确认同步已结束。")
      }

      try {
        Thread.sleep(POLL_INTERVAL_MS)
      } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        return BuildVariantPort.Result.cancelled(
            modulePath, variantName, "等待被中断。已触发的项目同步不会因此中止。")
      }
    }
  }

  // ---- 工具方法 ----

  private fun workspaceOrNull(): IWorkspace? =
      try {
        IProjectManager.getInstance().getWorkspace()
      } catch (e: RuntimeException) {
        null
      }

  private fun androidModulePaths(workspace: IWorkspace): String =
      try {
        workspace.getSubProjects()
            .filterIsInstance<AndroidModule>()
            .joinToString(", ") { it.path }
            .ifEmpty { "（无）" }
      } catch (e: RuntimeException) {
        "（读取失败）"
      }

  /** 成功后的 assemble 任务名：从新 workspace 的模块模型读，取不到返回空串。 */
  private fun assembleTaskOf(modulePath: String, variantName: String): String =
      try {
        val module =
            IProjectManager.getInstance().getWorkspace()?.findProject(modulePath) as? AndroidModule
        module?.getVariant(variantName)?.mainArtifact?.assembleTaskName ?: ""
      } catch (e: RuntimeException) {
        ""
      }

  /**
   * 找当前存活的编辑器 Activity。
   *
   * 用 `ActivityUtils.getActivityList()`（blankj utilcodex）遍历活动栈找第一个
   * `ProjectHandlerActivity` 实例，而不是只看栈顶——栈顶可能是设置页、
   * 全屏助手（`AssistantFullscreenActivity`，不是编辑器子类）等。
   */
  private fun findEditorActivity(): ProjectHandlerActivity? =
      try {
        ActivityUtils.getActivityList()
            .filterIsInstance<ProjectHandlerActivity>()
            .firstOrNull { !it.isFinishing && !it.isDestroyed }
      } catch (e: RuntimeException) {
        null
      }

  private fun lookupBuildService(): BuildService? =
      try {
        Lookup.getDefault().lookup(BuildService.KEY_BUILD_SERVICE)
      } catch (e: RuntimeException) {
        null
      }

  /** 在主线程同步执行并返回结果；已在主线程时直接执行。 */
  private fun <T> onMain(block: () -> T): T {
    if (Looper.myLooper() == Looper.getMainLooper()) {
      return block()
    }
    val result = java.util.concurrent.atomic.AtomicReference<T>()
    val error = java.util.concurrent.atomic.AtomicReference<RuntimeException>()
    val latch = java.util.concurrent.CountDownLatch(1)
    mainHandler.post {
      try {
        result.set(block())
      } catch (e: RuntimeException) {
        error.set(e)
      } finally {
        latch.countDown()
      }
    }
    try {
      latch.await()
    } catch (e: InterruptedException) {
      // 恢复中断标志并向上抛：调用方（switchTo）会以 RuntimeException 形式冒泡，
      // 由工具层的 try/catch 转成错误结果（ToolExecutor 亦会 restoreInterrupt）。
      Thread.currentThread().interrupt()
      throw IllegalStateException("等待主线程执行被中断", e)
    }
    error.get()?.let { throw it }
    return result.get()
  }

  private companion object {
    private val log = LoggerFactory.getLogger(ReportedBuildVariantPort::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 轮询间隔。 */
    private const val POLL_INTERVAL_MS = 500L

    /** 启动检测窗口：超过它仍未见「正在初始化」，判定同步未启动。 */
    private const val START_WINDOW_MS = 10_000L
  }
}
