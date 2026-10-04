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

package com.tom.rv2ide.activities.editor

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import androidx.activity.enableEdgeToEdge
import androidx.core.graphics.Insets
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.tom.rv2ide.app.EdgeToEdgeIDEActivity
import com.tom.rv2ide.artificial.agent.AssistantOrchestratorProvider
import com.tom.rv2ide.artificial.agent.FloatingAssistantView
import com.tom.rv2ide.artificial.agent.host.ActivityHost
import com.tom.rv2ide.projects.IProjectManager
import com.tom.rv2ide.utils.resolveAttr

/**
 * AI 助手的**真全屏**宿主：一个独立 Activity，沉浸式隐藏系统栏。
 *
 * <p><b>与 [FloatingAssistantView.Mode.FULLSCREEN] 的区别（两个「全屏」不是一回事）</b>：
 *
 * <ul>
 *   <li>[FloatingAssistantView.Mode.FULLSCREEN] 只是把助手面板在**宿主 parent 内**撑满——
 *       它仍然活在一个带系统栏、带编辑器工具栏、带底部 bottom sheet 的窗口里，
 *       面板只是「窗口内最大化」。「全屏」是相对同一个窗口里的其他控件而言的。
 *   <li>本 Activity 是**独立窗口 + 隐藏系统栏**：状态栏与导航栏整体消失，
 *       助手占据整块屏幕，背后没有编辑器、没有工具栏。这是真正的沉浸式。
 * </ul>
 *
 * <p>两者并不冲突：本 Activity 内部仍然用 [FloatingAssistantView.Mode.FULLSCREEN]
 * 渲染面板（见 [bindLayout]），因为「把面板撑满它所在的窗口」这件事对两种宿主都成立——
 * 区别只在于这个窗口本身是不是全屏的。
 *
 * <p><b>为什么继承 [EdgeToEdgeIDEActivity]</b>：它已经把 `decorFitsSystemWindows`
 * 关掉并接好了 window insets 分发（`applyEdgeToEdge()`），这正是沉浸式的起点。
 * 自己写一遍 edge-to-edge 只会与它的 insets 逻辑打架。
 *
 * @see EdgeToEdgeIDEActivity
 */
class AssistantFullscreenActivity : EdgeToEdgeIDEActivity() {

  private var assistant: FloatingAssistantView? = null

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // 内容延伸到系统栏区域；否则系统栏即使隐藏，布局里也会保留一块黑边。
    // 与 BaseEditorActivity 里同一处调用（`WindowCompat.setDecorFitsSystemWindows`）。
    enableEdgeToEdge()
    WindowCompat.setDecorFitsSystemWindows(window, false)
    // 透明化系统栏背景：隐藏过程中或用户上滑临时唤出时，不会出现一条突兀的色带。
    window.statusBarColor = Color.TRANSPARENT
    window.navigationBarColor = Color.TRANSPARENT
  }

  /**
   * 沉浸式 API 调用点：在 decor view 附加到窗口、拿到系统栏 insets 时隐藏系统栏。
   *
   * <p><b>为什么在这里而不是 onCreate</b>：`WindowInsetsControllerCompat` 需要一个已经
   * 附加到窗口的 decor view；基类通过 `decorView.doOnAttach { onApplySystemBarInsets(...) }`
   * 保证了这一点，且此刻 insets 已知。这是基类为「按 insets 定制系统栏」预留的钩子。
   *
   * <p>两处调用缺一不可：
   * <ol>
   *   <li>`hide(WindowInsetsCompat.Type.systemBars())` —— 隐藏状态栏 + 导航栏。
   *   <li>`systemBarsBehavior = BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` —— 用户从屏幕边缘
   *       上滑时可临时唤出系统栏（半透明覆盖在内容上，几秒后自动再隐藏）。不设它的话，
   *       系统栏一旦隐藏就只能靠程序再调 `show()` 才能出来，用户无法返回。
   * </ol>
   *
   * <p><b>刻意不设置任何 padding</b>：全屏助手的意图就是占满整块屏幕、不避让系统栏。
   * 基类默认会按 insets 给 decorView 加边距，那是给「系统栏可见」的界面用的。
   */
  override fun onApplySystemBarInsets(insets: Insets) {
    WindowInsetsControllerCompat(window, window.decorView).apply {
      hide(WindowInsetsCompat.Type.systemBars())
      systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
  }

  /**
   * 构建内容视图：一个撑满窗口的 [FrameLayout]，作为助手的挂载容器。
   *
   * <p>必须是 `FrameLayout`：助手视图用 `FrameLayout.LayoutParams` 定位可拖动的 FAB，
   * 换成别的父容器类型会抛 `ClassCastException`（与 `content_editor.xml` 里
   * `assistantContainer` 的注释是同一个约束）。
   *
   * <p>挂载的是 [FloatingAssistantView.Mode.FULLSCREEN]：面板在这个窗口内撑满。
   */
  override fun bindLayout(): View {
    val container = FrameLayout(this)
    // FULLSCREEN 形态四周仍留 8/12dp 边距。显式铺一层主题 surface 色兜底，
    // 免得透出的窗口底色在深浅主题切换时与面板卡片不同色。
    container.setBackgroundColor(resolveAttr(com.tom.rv2ide.common.R.attr.colorSurface))

    // 用 Activity 的生命周期作用域：助手视图里所有协程都挂在它上面，Activity 销毁即取消。
    // 注入进程级共享 orchestrator（AssistantOrchestratorProvider）：真全屏与主页悬浮/内联页/
    // 应用外悬浮共用同一实例，否则这里会另起一段会话——用户从悬浮窗长按进真全屏后，
    // 看到的是另一条对话、上下文用量也对不上。
    val view =
        FloatingAssistantView(
            ActivityHost(this, lifecycleScope, container),
            FloatingAssistantView.Mode.FULLSCREEN,
            AssistantOrchestratorProvider.get(),
        )
    view.attach()
    // 绑定当前打开的项目。必须走 getWorkspace()（可空）而不是 projectDir：
    // ProjectManagerImpl.projectDir 的 getter 是 checkNotNull(_projectDir){...}，
    // **未打开项目时直接抛 IllegalStateException**（真机 am start 拉本 Activity 时实测崩溃），
    // 它不返回 null 也不返回不存在的目录。getWorkspace() 可空、getProjectDir() 不抛；
    // 无项目时传 null，助手会自行提示「请先打开项目」。
    view.setWorkspace(IProjectManager.getInstance().getWorkspace()?.getProjectDir())
    assistant = view

    return container
  }

  override fun onDestroy() {
    // 取消进行中的 agent 运行，避免视图销毁后回调仍写控件。
    assistant?.dispose()
    assistant = null
    super.onDestroy()
  }
}
