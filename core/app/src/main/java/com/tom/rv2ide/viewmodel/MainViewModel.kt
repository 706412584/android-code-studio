/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.viewmodel

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModel
import com.tom.rv2ide.app.BaseApplication
import com.tom.rv2ide.artificial.agent.AgentOrchestrator
import com.tom.rv2ide.templates.Template
import java.util.concurrent.atomic.AtomicInteger

/**
 * [ViewModel] for main activity.
 *
 * @author Akash Yadav
 */
class MainViewModel : ViewModel() {

  companion object {

    // The values assigned to these variables reflect the order in which the screens are presented
    // to the user. A screen with a lower value is displayed before a screen with a higher value.
    // For example, SCREEN_MAIN is the first screen visible to the user, followed by
    // SCREEN_TEMPLATE_LIST,
    // and then SCREEN_TEMPLATE_DETAILS.
    //
    // These values are used as unique identifiers for the screens as well as for determining
    // whether
    // the screen change transition should be forward or backward.
    const val SCREEN_MAIN = 0
    const val SCREEN_TEMPLATE_LIST = 1
    const val SCREEN_TEMPLATE_DETAILS = 2

    /**
     * 应用内 AI 助手页（内联形态）。
     *
     * <p>取值 3 而不是插在 1/2 之间：它是一条与「模板向导」并行的独立分支，从主页直接进入、
     * 返回键直接回主页，与模板列表/详情没有先后关系。放在末尾使既有屏幕的编号保持不变。
     *
     * <p>编号不连续（跳过中间值）不影响前进/后退判定——`MainActivity.onScreenChanged`
     * 用的是「目标编号是否大于来源编号」，而不是「是否恰好 +1」。见那里的说明。
     */
    const val SCREEN_AI = 3
  }

  private val _currentScreen = MutableLiveData(-1)
  private val _previousScreen = AtomicInteger(-1)
  private val _isTransitionInProgress = MutableLiveData(false)

  /**
   * 本次屏幕切换是否由返回键触发。
   *
   * <p>转场方向不能只看编号差：`SCREEN_AI` 是 3，与 `SCREEN_MAIN` 的 0 之间跳了 3，
   * 旧判据 `(screen - previous) == 1` 会把「主页 → AI 页」误判成后退（反向动画）。
   * 返回键是唯一能明确知道「这是后退」的来源，因此由它在这里打标记，其余切换一律
   * 视为前进（配合编号是否增大，见 `MainActivity.onScreenChanged`）。
   */
  private var _navigatingBack = false

  internal val template = MutableLiveData<Template<*>>(null)
  internal val creatingProject = MutableLiveData(false)

  val currentScreen: LiveData<Int> = _currentScreen

  val previousScreen: Int
    get() = _previousScreen.get()

  /** 本次切换是否由返回键触发；见 [_navigatingBack]。 */
  val navigatingBack: Boolean
    get() = _navigatingBack

  var isTransitionInProgress: Boolean
    get() = _isTransitionInProgress.value ?: false
    set(value) {
      _isTransitionInProgress.value = value
    }

  /**
   * 切换到 [screen]。
   *
   * @param back 是否由返回键触发。默认 false（用户主动导航，视为前进）。
   */
  @JvmOverloads
  fun setScreen(screen: Int, back: Boolean = false) {
    _navigatingBack = back
    _previousScreen.set(_currentScreen.value ?: SCREEN_MAIN)
    _currentScreen.value = screen
  }

  fun postTransition(owner: LifecycleOwner, action: Runnable) {
    if (isTransitionInProgress) {
      _isTransitionInProgress.observe(
          owner,
          object : Observer<Boolean> {
            override fun onChanged(t: Boolean) {
              _isTransitionInProgress.removeObserver(this)
              action.run()
            }
          },
      )
    } else {
      action.run()
    }
  }

  /**
   * Activity 级共享的 AI 助手 orchestrator。
   *
   * <p><b>为什么放在这里</b>：主页悬浮助手（SIDEBAR）与 AI 助手页（INLINE）是两个
   * [com.tom.rv2ide.artificial.agent.FloatingAssistantView] 实例。若各自持有 orchestrator，
   * 就会各有一个活动会话 id，可能同时跑两个 agent 循环、并发写两份会话文件。让
   * [MainViewModel]（生命周期与 MainActivity 相同、两个视图都能取到）持有唯一实例，
   * 两个视图注入同一个引用，会话天然连续、不会并发。
   *
   * <p><b>回调归属</b>：orchestrator 的「危险工具授权」「上下文用量」是单例槽位，
   * 由可见的那个视图安装（`FloatingAssistantView` 内按「谁可见谁拥有」处理）。
   *
   * <p>用 application context 构造：orchestrator 被 Activity 级 ViewModel 持有，若绑
   * Activity context 会在旋转/重建时泄漏；而它需要 Context 只是为了读偏好、写会话目录，
   * 不需要 Activity 的资源。
   */
  val assistantOrchestrator: AgentOrchestrator by lazy {
    AgentOrchestrator(
        BaseApplication.getBaseInstance(),
        AgentOrchestrator.defaultDiffStore(BaseApplication.getBaseInstance()),
    )
  }
}
