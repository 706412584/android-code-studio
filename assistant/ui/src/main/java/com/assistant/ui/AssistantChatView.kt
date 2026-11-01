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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.assistant.ui

import android.content.Context
import android.view.View
import com.assistant.ui.compat.FileAccessProvider
import com.assistant.ui.compat.PendingUserQuestion
import com.assistant.ui.compat.UserQuestionAnswer
import com.assistant.ui.model.MessageTimeline
import kotlinx.coroutines.CoroutineScope

/**
 * 宿主侧的全部动作回调。
 *
 * <p>做成接口而不是一串 lambda：宿主需要同时处理展开、回滚、菜单三件事，回调参数一多，
 * 调用点就会变成一长串位置参数，加一个动作就得改所有调用点。
 *
 * <p><b>面板不自己翻转展开态</b>：展开态存在 [MessageTimeline] 的条目数据里，
 * 面板读的是映射结果。面板若自行记住展开态，条目滚出视口被回收后状态就丢了。
 * 回调返回后，宿主回写数据，下一帧快照自然带上新状态。
 */
interface AssistantChatCallbacks {

  /** 切换某条消息的展开/折叠（工具卡 / 推理块 / 差异卡三种都走这里）。 */
  fun onToggleExpanded(messageId: Long)

  /**
   * 切换一个**工具分组**的展开/折叠。
   *
   * <p>组与单条消息是两套不同的状态：单条走 [onToggleExpanded]，组的展开意图存在
   * `ToolGroup.pinnedExpanded` 上。混用会导致「点组头时把某一条工具卡展开了」。
   *
   * @param currentlyCollapsed 该组**当前**是否折叠。宿主据此翻转意图——
   *   时间线只提供翻转、不提供置位，传目标状态会被当成当前状态，结果正好相反。
   */
  fun onToggleGroup(groupId: Long, currentlyCollapsed: Boolean)

  /** 回滚一次文件改动。 */
  fun onRevert(messageId: Long, diffId: String)

  /**
   * 消息长按/「更多」菜单（复制原文、复制纯文、引用提问等）。
   *
   * @param anchor 可用来锚定弹出菜单的 View（面板自身的 ComposeView）
   */
  fun onMoreClick(messageId: Long, anchor: View)
}

/**
 * 助手对话面板的公开入口：一个可直接加进容器 View 的 [View]。
 *
 * <p><b>为什么需要这个门面</b>：渲染层内部（[AssistantComposePanel] / [AssistantMessageState]）
 * 都是 `internal`——它们是实现细节，宿主不该直接依赖。宿主只需要四个动作：
 * 拿到 View、往时间线里塞事件、把待答的询问交给面板、销毁时释放。
 *
 * <p><b>线程约定</b>：全部方法都必须在主线程调用（内部改的是 Compose 状态）。
 */
class AssistantChatView(
    context: Context,
    private val timeline: MessageTimeline,
    callbacks: AssistantChatCallbacks,
    /** 容器路径解析用；null 时容器路径的图看不了（其余仍可看）。 */
    fileAccess: FileAccessProvider? = null,
) {

  private val state = AssistantMessageState(timeline)

  // 先声明 host 再声明回调：回调里要读 host.view 做菜单锚点，而 host 的构造又需要回调。
  // Kotlin 的属性初始化按声明顺序执行，因此 host 用 `by lazy`——构造回调对象时不会立刻
  // 求值 host，等到真正点击「更多」时才解析，届时 host 早已初始化完毕。
  private val host: AssistantComposePanelHost by lazy {
    AssistantComposePanelHost(context, state, panelCallbacks, fileAccess)
  }

  private val panelCallbacks: AssistantPanelCallbacks =
      object : AssistantPanelCallbacks {
        override fun onToggleExpanded(messageId: Long) = callbacks.onToggleExpanded(messageId)

        override fun onToggleGroup(groupId: Long, currentlyCollapsed: Boolean) =
            callbacks.onToggleGroup(groupId, currentlyCollapsed)

        override fun onRevert(messageId: Long, diffId: String) =
            callbacks.onRevert(messageId, diffId)

        override fun onMoreClick(messageId: Long) {
          callbacks.onMoreClick(messageId, host.view)
        }
      }

  /** 可直接加进布局容器的视图。 */
  val view: View
    get() = host.view

  /**
   * 强制滚到最后一条（**用户主动操作时用**：发消息、切会话、点跳底按钮）。
   *
   * <p>与面板内智能跟随的分工：跟随只在用户本来就在底部时生效；
   * 而「我刚发了一条」这种用户主动行为必须无条件滚到底。
   */
  fun forceScrollToBottom(scope: CoroutineScope) = host.forceScrollToBottom(scope)

  /** 打开/切会话后请求一次性滚到底。 */
  fun requestScrollToBottom(reason: String) = host.requestScrollToBottom(reason)

  /**
   * 弹出询问并等待作答。
   *
   * <p>`ask_user_question` 是阻塞式端口（调用方用 latch 等答案），而 Compose 的组合是异步的
   * ——只能先记下「有提问在等」，等用户在对话框上点完再回调。
   *
   * @param onDone 答案；null 表示用户取消
   */
  fun askQuestion(question: PendingUserQuestion, onDone: (UserQuestionAnswer?) -> Unit) =
      host.askQuestion(question, onDone)

  /** 面板销毁时把未决提问收尾，避免调用方一直等到超时。 */
  fun cancelPendingQuestion() = host.cancelPendingQuestion()

  /**
   * 弹出危险工具确认框并等待用户答复。
   *
   * <p>与 [askQuestion] 同为「阻塞式端口的界面侧」：内核的确认闸门在工具线程上等答案，
   * 这里用「先记状态、等用户点完再回调」承接。
   *
   * <p>**必须回主线程调用。**
   */
  fun confirmDangerousTool(
      pending: com.assistant.ui.components.independent.PendingDangerousTool,
      onChoice: (com.assistant.ui.components.independent.DangerousToolChoice) -> Unit,
  ) = host.confirmDangerousTool(pending, onChoice)

  /** 面板销毁时把未决确认按「拒绝」收尾，避免工具线程一直等。 */
  fun cancelPendingDangerousTool() = host.cancelPendingDangerousTool()

  /**
   * 释放：注销时间线观察者。
   *
   * <p>不调用的话，时间线会一直持有状态对象（进而持有消息列表），面板关掉后内存不释放。
   * 重复调用安全。
   */
  fun release() {
    state.dispose()
    host.cancelPendingQuestion()
    host.cancelPendingDangerousTool()
  }
}
