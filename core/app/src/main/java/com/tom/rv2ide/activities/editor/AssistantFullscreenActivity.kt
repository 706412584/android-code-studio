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
import android.os.Build
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
import com.tom.rv2ide.preferences.internal.GeneralPreferences
import com.tom.rv2ide.projects.IProjectManager
import com.tom.rv2ide.utils.resolveAttr
import java.io.File

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
    hideSystemBars()
  }

  /**
   * 再次隐藏系统栏。
   *
   * <p><b>为什么不止 `onApplySystemBarInsets` 一处</b>：那个钩子只在 decor view 附加到窗口时
   * 触发一次，而系统会在窗口获得焦点时**重置**系统栏可见性——结果是隐藏请求被吞掉，
   * 实测全屏页内容根节点只有 2210px（屏幕 2340px），底部 130px 导航栏始终占位。
   * 这既让用户看到「不是真全屏」，又让可用高度少了 130px：底部工具条被挤到只剩 3px，
   * 里面的按钮全部量不出尺寸。
   *
   * <p>在每次窗口焦点回到本 Activity 时重发一次，是沉浸式的常规做法：
   * 用户上滑临时唤出系统栏后离开再回来，也能重新进入全屏。
   */
  override fun onWindowFocusChanged(hasFocus: Boolean) {
    super.onWindowFocusChanged(hasFocus)
    if (hasFocus) {
      hideSystemBars()
    }
  }

  private fun hideSystemBars() {
    WindowInsetsControllerCompat(window, window.decorView).apply {
      hide(WindowInsetsCompat.Type.systemBars())
      systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
    // Android 10 及以下补一份系统 UI 标志位。
    //
    // <p>`WindowInsetsControllerCompat` 在 API 30 以下走的是 `systemUiVisibility` 兼容层，
    // 而 MIUI 等 ROM 对这一层的 `hide()` 响应并不可靠——实测状态栏隐藏了、导航栏没有，
    // 内容区只剩 2210px（屏幕 2340px），底部 130px 一直被导航栏占着。
    // 直接写标志位是 API 30 以下的常规做法，两者并存不冲突。
    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
      @Suppress("DEPRECATION")
      window.decorView.systemUiVisibility =
          (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
              or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
              or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
              or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
              or View.SYSTEM_UI_FLAG_FULLSCREEN
              or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
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
    // 面板已无边距（FULLSCREEN 形态的留白与圆角都已归零，见 applyMode），
    // 这层底色只是兜底：万一某处透出窗口底色，能保持与面板同色而不是闪一下异色。
    container.setBackgroundColor(resolveAttr(com.tom.rv2ide.common.R.attr.colorSurface))

    // 用 Activity 的生命周期作用域：助手视图里所有协程都挂在它上面，Activity 销毁即取消。
    // 注入进程级共享 orchestrator（AssistantOrchestratorProvider）：真全屏与主页悬浮/内联页/
    // 应用外悬浮共用同一实例，否则这里会另起一段会话——用户从悬浮窗长按进真全屏后，
    // 看到的是另一条对话、上下文用量也对不上。
    val view =
        FloatingAssistantView(
            // 传 onClosed：本窗口里**面板就是全部内容**，收起它之后只剩一个空容器，
            // 用户会看到一整片白且无处可去。因此「关闭 / 最小化」在这里的含义是
            // 「退出全屏」，与另外两个入口（主页悬浮、内联页）的「收起面板」不同。
            ActivityHost(this, lifecycleScope, container, onClosed = { finish() }),
            FloatingAssistantView.Mode.FULLSCREEN,
            AssistantOrchestratorProvider.get(),
        )
    view.attach()
    // 绑定当前项目。解析走 resolveWorkspace()，见其 KDoc——不能只问 IProjectManager。
    view.setWorkspace(resolveWorkspace())
    // 展开面板。**不可省略**：`assistantOverlay` 在 XML 里是 `visibility="gone"`，
    // 只有 open() 会把它设为可见。本 Activity 没有可点开的 FAB（它自己就是全屏宿主），
    // 漏掉这一步的结果是一个空容器铺满屏幕——实测就是整片纯白。
    // open() 同时负责回放会话、刷新模型/权限标签与上下文圆环。
    view.open()
    assistant = view

    return container
  }

  /**
   * 解析本页要绑定到哪个项目。
   *
   * <p><b>为什么不能只问 [IProjectManager]</b>：`getWorkspace()` 只在项目于 IDE 里
   * **真正打开之后**才有值，而用户完全可能停在主屏的项目管理界面——那里的助手是可用的，
   * 它的工作区取自 `GeneralPreferences.lastOpenedProject`（见 `MainFragment.currentWorkspace`）。
   * 两个来源不一致，实测后果就是「主屏助手能发消息，点全屏后发同一条却报
   * 『尚未打开项目，请先打开或新建一个项目』」。
   *
   * <p>顺序：已打开的项目优先（编辑器入口本就该用它），否则回退到主屏认定的项目。
   * 与 `MainFragment.currentWorkspace()` 的判据保持一致（存在且是目录），
   * 否则会出现「主屏认为有项目、全屏认为没有」的第二处漂移。
   *
   * <p>必须走 `getWorkspace()`（可空）而不是 `projectDir`：后者的 getter 是
   * `checkNotNull(_projectDir){...}`，未打开项目时直接抛 `IllegalStateException`
   * （真机 am start 拉本 Activity 时实测崩溃）。
   */
  private fun resolveWorkspace(): File? {
    IProjectManager.getInstance().getWorkspace()?.getProjectDir()?.let {
      return it
    }
    val path = GeneralPreferences.lastOpenedProject
    if (path.isEmpty() || path == GeneralPreferences.NO_OPENED_PROJECT) {
      return null
    }
    val dir = File(path)
    return if (dir.exists() && dir.isDirectory) dir else null
  }

  override fun onDestroy() {
    // 取消进行中的 agent 运行，避免视图销毁后回调仍写控件。
    assistant?.dispose()
    assistant = null
    super.onDestroy()
  }
}
