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

package com.tom.rv2ide.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.tom.rv2ide.R
import com.tom.rv2ide.artificial.agent.FloatingAssistantView
import com.tom.rv2ide.artificial.agent.host.ActivityHost
import com.tom.rv2ide.preferences.internal.GeneralPreferences
import com.tom.rv2ide.viewmodel.MainViewModel
import java.io.File

/**
 * 应用内 AI 助手页（内联形态）。
 *
 * <p>与主页的悬浮助手（[MainFragment] 里的 SIDEBAR）**并存**，不替换：悬浮助手是
 * 「边看主页边问」的浮层，本页是「专门坐下来对话」的正式页面。两者共用同一个
 * [com.tom.rv2ide.artificial.agent.AgentOrchestrator]（见下），因此会话连续、不会并发。
 *
 * <p><b>形态</b>：复用 [FloatingAssistantView] 的 [FloatingAssistantView.Mode.INLINE]，
 * 而不是另写一套消息列表。面板的消息流、工具卡片、会话抽屉、diff 回滚是同一套逻辑，
 * 复制一份必然在后续改动里分叉。
 *
 * <p><b>宿主</b>：[ActivityHost] 把「弹窗弹在哪、设置怎么跳」交给 Activity——本页与
 * 悬浮助手用同一套弹窗实现，行为一致。
 *
 * <p><b>生命周期</b>：作用域必须用 `viewLifecycleOwner.lifecycleScope`，不能用
 * `BaseFragment.viewLifecycleScope`（那是 Dispatchers.Default 的普通作用域，不随视图
 * 销毁取消，会在视图 detach 后继续写控件）。
 *
 * <p><b>orchestrator 归属</b>：内联页与其它宿主（主页悬浮、应用外悬浮、真全屏）若各建一个
 * orchestrator，会各持一个活动会话 id，可能同时跑两个 agent 循环、并发写两份会话文件。
 * 因此四者共用 [com.tom.rv2ide.artificial.agent.AssistantOrchestratorProvider] 的进程级单例
 * （经 [MainViewModel.assistantOrchestrator] 暴露），本页通过 [FloatingAssistantView] 的
 * `sharedOrchestrator` 参数注入。orchestrator 的单例回调槽位（危险工具授权、上下文用量）
 * 由当前可见的视图安装，见 `FloatingAssistantView`。
 */
class AssistantPageFragment : Fragment() {

  /**
   * Activity 级共享的 ViewModel。
   *
   * <p>用 `requireActivity()` 作为 owner，与 [MainFragment]/[TemplateListFragment] 一致——
   * 屏幕切换状态（currentScreen/previousScreen）本就属于 Activity。
   */
  private val viewModel by viewModels<MainViewModel>(ownerProducer = { requireActivity() })

  /** 内联助手视图；随视图创建/销毁。 */
  private var assistant: FloatingAssistantView? = null

  /**
   * 返回键：先让面板收起抽屉/弹窗，再退回主页。
   *
   * <p>**必须由本页自持**：主页 [MainFragment] 也注册了一个返回 callback，而它在视图
   * 隐藏（`isVisible=false`）后仍处于 RESUMED、其 callback 仍启用——本页显示时那个
   * callback 会先消费返回键且不再传递，导致 `MainActivity` 的 `SCREEN_AI -> SCREEN_MAIN`
   * 分支永远不触发。本 callback 注册在它之后、优先级更高，且**只在 SCREEN_AI 可见时启用**，
   * 保证返回键先落到本页。
   *
   * <p>直接 `setScreen` 而不是 `onBackPressed()` 透传：透传会被上述 MainFragment 的
   * callback 吞掉，屏幕不切换。本 callback 只在 SCREEN_AI 时启用，「AI 页返回 = 回主页」
   * 由本页直接负责（与 [TemplateDetailsFragment] 的 previous 按钮同一机制）。
   */
  private val backCallback =
      object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
          if (assistant?.collapseIfOpen() == true) {
            return
          }
          viewModel.setScreen(MainViewModel.SCREEN_MAIN, back = true)
        }
      }

  override fun onCreateView(
      inflater: LayoutInflater,
      container: ViewGroup?,
      savedInstanceState: Bundle?,
  ): View = inflater.inflate(R.layout.fragment_assistant_page, container, false)

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)

    val host = ActivityHost(requireContext(), viewLifecycleOwner.lifecycleScope, view as ViewGroup)
    // 注入 MainViewModel 持有的共享 orchestrator：与主页悬浮助手是同一个实例，
    // 会话连续、不会并发（详见 MainViewModel.assistantOrchestrator）。
    val assistantView =
        FloatingAssistantView(
            host,
            FloatingAssistantView.Mode.INLINE,
            viewModel.assistantOrchestrator,
        )
    assistantView.attach()
    assistantView.setWorkspace(currentWorkspace())
    // INLINE 是页面形态，没有可收起的宿主：直接展开面板。
    // open() 还会回放本工作区上次的会话、刷新模型/权限标签与上下文圆环。
    assistantView.open()
    assistant = assistantView

    // 返回键回调注册在 viewLifecycleOwner 上（随视图自动注销）。
    // 仅在本页是当前屏幕时启用——否则它会一直拦在主链上，抢走主页悬浮助手的返回处理。
    // 见 [backCallback] 关于「为何必须由本页自持」的说明。
    requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)
    viewModel.currentScreen.observe(viewLifecycleOwner) { screen ->
      backCallback.isEnabled = screen == MainViewModel.SCREEN_AI
    }
  }

  override fun onDestroyView() {
    // 先取消进行中的运行，避免视图销毁后回调仍写控件。
    assistant?.dispose()
    assistant = null
    super.onDestroyView()
  }

  /**
   * 当前工作区；无可用项目时返回 null。
   *
   * <p>与 [MainFragment.currentWorkspace] 同源：主屏/助手页都没有「当前项目」概念，
   * 工作区从「最近打开的项目」推得。prefs 里没有可用项目时传 null，助手会提示
   * 「请先打开项目」而不是静默失败。
   */
  private fun currentWorkspace(): File? {
    val path = GeneralPreferences.lastOpenedProject
    if (path.isEmpty() || path == GeneralPreferences.NO_OPENED_PROJECT) {
      return null
    }
    val dir = File(path)
    return if (dir.exists() && dir.isDirectory) dir else null
  }
}
