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

package com.tom.rv2ide.artificial.agent.compose

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import com.tom.rv2ide.artificial.agent.compose.compat.FileAccessProvider
import com.tom.rv2ide.artificial.agent.compose.compat.ProvideAcsImageViewer
import com.tom.rv2ide.artificial.agent.compose.compat.ProvideAcsToolOpeners
import com.tom.rv2ide.artificial.agent.compose.compat.PendingUserQuestion
import com.tom.rv2ide.artificial.agent.compose.compat.UserQuestionAnswer
import com.tom.rv2ide.artificial.agent.compose.components.bubbles.AgentMessageItem
import com.tom.rv2ide.artificial.agent.compose.components.tools.AskUserQuestionPanel
import com.tom.rv2ide.artificial.agent.compose.components.tools.ToolCallGroupHeader
import com.tom.rv2ide.artificial.agent.compose.model.AgentUIMessage
import com.tom.rv2ide.artificial.agent.compose.model.MessageRole
import com.tom.rv2ide.artificial.agent.compose.model.ToolRunStatus
import com.tom.rv2ide.artificial.agent.compose.theme.AIEditorTheme
import com.tom.rv2ide.artificial.agent.compose.theme.Radius
import com.tom.rv2ide.artificial.agent.compose.theme.Spacing
import com.tom.rv2ide.resources.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Compose 渲染路径的开关。
 *
 * <p><b>为什么默认关（走 XML）</b>：本次移植的组件尚在陆续落地，Compose 路径还没有经过真机
 * 验证（见任务「真机验证 Compose 渲染层」）。默认打开等于让所有用户替我们做验证；默认关闭则
 * 两条路径并存，随时可以切回来，出问题也不影响任何人。
 *
 * <p><b>开关的读写只经过本对象</b>：偏好键与存储位置都收敛在这里，设置界面（见
 * `preferences/aiAgentPrefExts.kt` 的 Compose 渲染开关）与渲染路径两侧都调 [isEnabled] /
 * [setEnabled]，不各自持有字符串键。散落两处必然会在改名时漏掉一处，而那个漏掉的一侧
 * 不会报错——表现只是「开关点了没反应」。
 */
internal object AssistantComposeRender {

  private const val PREFS_NAME = "ai_agent_tools"

  /**
   * 偏好键。
   *
   * <p>[FloatingAssistantView] 监听偏好变更以即时装/卸渲染路径，需要按键名过滤——
   * 同一份偏好文件里还有权限模式、shell 后端等十来个键，不筛会误触发。
   */
  const val KEY_ENABLED = "assistant_compose_render"

  /**
   * 默认 **true**（2026-10-09 翻转）：Compose 渲染已是唯一在维护的路径，
   * XML 列表仅作回退保留。新装用户直接走 Compose，不再需要手动开开关。
   *
   * <p>历史：接线期默认 false 是因为 Compose 侧未真机验证，需要能随时切回；
   * 现在渲染层已完整（消息/工具卡/思考块/diff/询问面板/滚动），
   * 默认值继续为 false 只会让用户看到过时的界面。
   */
  fun isEnabled(context: Context): Boolean =
      context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

  fun setEnabled(context: Context, enabled: Boolean) {
    context
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(KEY_ENABLED, enabled)
        .apply()
  }
}

/**
 * 面板向宿主发起的动作。
 *
 * <p>做成接口而不是一串 lambda：宿主需要同时处理展开、回滚、菜单三件事，回调参数一多，
 * 调用点就会变成一长串位置参数，加一个动作就得改所有调用点。
 */
internal interface AssistantPanelCallbacks {

  /**
   * 切换某条的展开/折叠。
   *
   * <p>**只是通知宿主，不由面板自己翻转状态**：展开态存在适配器的条目数据里
   * （[com.tom.rv2ide.adapters.AssistantMessageAdapter.Item]），面板读的是映射结果
   * （[com.tom.rv2ide.artificial.agent.compose.model.AgentUIMessage.expanded]）。
   * 面板若自行记住展开态，条目滚出视口被回收后状态就丢了——与 RecyclerView 时代
   * 「展开的是另一条」是同一类故障。回写数据后，下一帧快照自然带上新状态。
   */
  fun onToggleExpanded(messageId: Long)

  /** 回滚一次文件改动。锚点是宿主注入的 ComposeView（弹出菜单需要给 PopupMenu 一个锚）。 */
  fun onRevert(messageId: Long, diffId: String)

  /** 长按/更多菜单（复制原文、复制纯文、引用提问等），复用 XML 路径已有的菜单。 */
  fun onMoreClick(messageId: Long)

  /**
   * 切换一个**工具分组**的展开/折叠。
   *
   * <p>组与单条消息是两套不同的状态：单条走 [onToggleExpanded]，组的展开意图存在
   * `ToolGroup.pinnedExpanded` 上。混用会导致「点组头时把某一条工具卡展开了」——
   * 组 id 与消息 id 数值上不会撞（都由同一个计数器分配），但语义完全不同。
   *
   * @param groupId 组标识，即 `ToolGroup` 条目的 id
   * @param currentlyCollapsed 该组**当前**是否折叠。宿主据此翻转意图——适配器只提供翻转、
   *   不提供置位，传目标状态会被当成当前状态，结果正好相反。
   */
  fun onToggleGroup(groupId: Long, currentlyCollapsed: Boolean)
}

/**
 * 助手消息流的 Compose 实现。
 *
 * <p><b>分发交给移植组件</b>：[AgentMessageItem] 内部已经按 `message.role` 与若干标记位完成了
 * 全部分发（用户药丸、助手 Markdown 正文、推理块、工具行/差异卡、压缩状态、变体切换…），
 * 所以这里只做三件本层才有的事：列表化、稳定 key、把展开态与菜单接回宿主。
 * 本层**不重复实现任何渲染**——一旦在这里按角色再分一次支，同一个分发规则就会有两份，
 * 两边迟早不一致。
 *
 * <p><b>流式期间不整表重建</b>：消息快照由 [AssistantMessageState] 按条目记忆化后给出，
 * 未变化的条目是**同一个 [com.tom.rv2ide.artificial.agent.compose.model.AgentUIMessage] 实例**；
 * 配合下面的 `key = { it.id }`，`LazyColumn` 只重组内容真的变了的那几条。本层不做裁剪或
 * 分页：条目数由对话长度决定，而滚动窗口内实际参与组合的条目由 `LazyColumn` 自己控制。
 *
 * @param darkTheme 是否用深色主题。**由宿主按 ACS 主题解析后传入，不在这里读系统配置**——
 *   ACS 可以应用内手动切主题而不改系统 uiMode，若按 `isSystemInDarkTheme()` 判断，
 *   会出现「系统深色 + 应用浅色」时 Compose 用深色文字画在浅色面板上，正文读不出来。
 */
@Composable
internal fun AssistantComposePanel(
    state: AssistantMessageState,
    listState: LazyListState,
    callbacks: AssistantPanelCallbacks,
    darkTheme: Boolean,
    modifier: Modifier = Modifier,
    /** 容器路径解析用；透传给图像查看器（容器路径→本地文件需要它）。 */
    fileAccess: FileAccessProvider? = null,
    /** 待答的询问；非 null 时弹出对话框。由宿主驱动（见 [AssistantComposePanelHost.askQuestion]）。 */
    question: PendingUserQuestion? = null,
    /**
     * 询问的结局。
     *
     * <p>三种取值对应 ACS 工具侧的三条不同分支，不能合并：
     * - 有答案 → 工具报告用户的选择
     * - 空列表（用户点「补充」）→ 工具对每题报「（未作答）」
     * - **null（用户取消）→ 工具报「用户没有回答」，并明确要求模型不要擅自假定答案**。
     *   把取消也塞成空列表会让模型把「用户没答」当成「用户选了空」继续往下做。
     */
    onQuestionDone: (UserQuestionAnswer?) -> Unit = {},
) {
  // 把两个 compat 提供的「宿主能力」包在面板外层。**必须在组件树内、消息渲染之外**：
  //   - ProvideAcsImageViewer：消息里的图片点击走它 → ACS 已有的 ToolResultImageSupport
  //     灯箱。不包的话 LocalImageViewer 保持空实现，点图片**静默无反应**（不报错、
  //     也不崩），是最难发现的一类故障。
  //   - ProvideAcsToolOpeners：浏览器链接交给系统浏览器。不包则入口隐藏。
  // 两者都由 compat 写好，本层只决定「包不包」——这正是它们设计成可选接线的原因。
  ProvideAcsImageViewer(fileAccess = fileAccess) {
    ProvideAcsToolOpeners { PanelContent(state, listState, callbacks, darkTheme, modifier, question, onQuestionDone) }
  }
}

@Composable
private fun PanelContent(
    state: AssistantMessageState,
    listState: LazyListState,
    callbacks: AssistantPanelCallbacks,
    darkTheme: Boolean,
    modifier: Modifier,
    question: PendingUserQuestion?,
    onQuestionDone: (UserQuestionAnswer?) -> Unit,
) {
  AIEditorTheme(darkTheme = darkTheme) {
    // 读一次快照：列表变化时本函数重组，而 LazyColumn 按 key 只重组变化的条目
    val messages by state.messages
    // 「挂操作行」只给最新一条助手消息，因此这里算一次，避免在 item 作用域里逐条求末项
    val lastId = messages.lastOrNull()?.id
    // 按组标识把连续的工具组条目折成一项。映射层已把适配器算好的分组带了过来
    // （见 AcsMessageMapper 约定 1），这里只做聚合，不重新推断成组规则。
    val renderItems = remember(messages) { toRenderItems(messages) }

    // 刻意不铺背景色：面板底色由宿主的 MaterialCardView（?attr/colorSurface）提供，
    // XML 路径的 RecyclerView 同样没有背景。这里若铺 [MaterialTheme.colorScheme.background]，
    // 会与宿主主题（可能是自定义的 VSCODE 浅色等）产生一块可见色差。
    Box(modifier = modifier.fillMaxSize()) {
      LazyColumn(
          state = listState,
          modifier = Modifier.fillMaxSize(),
          // 横向 16dp = XML 路径的留白（`item_assistant_message` 的 paddingStart/End、
          // `item_tool_call` / `item_tool_group` 的 layout_marginStart/End 都是 16dp）。
          //
          // **早先这里写着「横向留白由宿主布局负责」——那是错的**：宿主布局里
          // `assistantMessages` 所在的 RecyclerView 只有 paddingTop/Bottom=8dp，外层
          // FrameLayout 也没有横向 padding；XML 路径的留白来自**每个 item 自己**。
          // 于是 Compose 侧落到默认的 0dp，实测内容左边缘只有 22px（≈8dp 的可见余量），
          // 而 16dp 在 440dpi 上应是 44px——比 XML 路径少了一半，长文本几乎贴着边框。
          //
          // 用 contentPadding 而不是逐条加 margin：内边距属于滚动容器，滚动条与内容一起内缩、
          // 滚动时不会突然出现空白；逐条 margin 则会让「末条贴底」等行为在条目间不一致。
          contentPadding =
              PaddingValues(horizontal = Spacing.lg, vertical = Spacing.sm),
      ) {
        items(items = renderItems, key = { it.key }) { item ->
          when (item) {
            is RenderItem.Single ->
                MessageRow(
                    message = item.message,
                    showActions = item.message.id == lastId,
                    callbacks = callbacks,
                )
            is RenderItem.Group -> ToolGroupRow(item, callbacks)
          }
        }
      }

      // 询问对话框。用 Compose 的 Dialog 而不是把它当成列表里的一项：提问是**模态**的
      // ——工具调用在等服务端答案（`askUserQuestion` 是阻塞式端口），答案必须先于后续内容
      // 出现。作为列表项会被滚走，用户就再也看不到问题了。
      if (question != null) {
        Dialog(onDismissRequest = { onQuestionDone(null) }) {
          Surface(
              shape = RoundedCornerShape(Radius.lg),
              color = MaterialTheme.colorScheme.surface,
          ) {
            AskUserQuestionPanel(
                question = question,
                // 答案的转换（多选逗号连接、自定义文本优先）由 compat 的 toAnswerList 负责，
                // 这里只做「谁触发」的分发，不重新发明格式。
                onConfirm = { onQuestionDone(it) },
                // 「补充」= 用户不在预设里选，要对每题报「未作答」（空列表）。
                // **不能用 null**：那是「取消」，工具会据此要求模型不要假定答案，
                // 而用户点「补充」表达的恰恰是「我要自己说」。
                onSkip = { onQuestionDone(UserQuestionAnswer(emptyList())) },
            )
          }
        }
      }
    }
  }
}

/**
 * 一个渲染项：要么是单条消息，要么是一个折叠工具组。
 *
 * <p>之所以先聚合成这项再交给 `LazyColumn`，是因为组必须作为一个整体参与列表（一个 key、
 * 一次可见性判断）；把组头和子项都当独立 item 塞进列表，折叠时就得靠"子项自己知道该隐藏"
 * 来表达，那会把折叠状态复制到每个子项上，两边必然会不同步。
 */
private sealed interface RenderItem {
  /** 列表 key。组用 `g` 前缀，与消息 id 区分开——两者数值来自同一个计数器，不加前缀会撞 key。 */
  val key: String

  data class Single(val message: AgentUIMessage) : RenderItem {
    override val key: String get() = message.id
  }

  data class Group(
      val id: String,
      val expanded: Boolean,
      val children: List<AgentUIMessage>,
  ) : RenderItem {
    override val key: String get() = "g$id"
  }
}

/**
 * 把消息快照聚合成渲染项：`groupId` 相同的连续条目折成一个 [RenderItem.Group]。
 *
 * <p>只按标识聚合、不判断"该不该成组"——那是适配器 `ToolGrouping` 的职责，它的规则
 * （子 agent 工具必须独立、推理块依赖前一项是否已成组）重复实现一遍必然与 XML 路径分叉。
 */
private fun toRenderItems(messages: List<AgentUIMessage>): List<RenderItem> {
  val out = ArrayList<RenderItem>(messages.size)
  var i = 0
  while (i < messages.size) {
    val gid = messages[i].groupId
    if (gid == null) {
      out += RenderItem.Single(messages[i])
      i++
      continue
    }
    var j = i
    while (j < messages.size && messages[j].groupId == gid) j++
    out += RenderItem.Group(gid, messages[i].groupExpanded, messages.subList(i, j).toList())
    i = j
  }
  return out
}

/**
 * 工具组：折叠态一行「N 次工具调用」，展开态逐个渲染组内条目。
 *
 * <p>`count` 只数组内的**工具调用**（推理块不计）：组头说的是"调了几次工具"，
 * 把推理块算进去会让计数比用户实际感知的调用次数多。与适配器的组摘要是同一口径。
 */
@Composable
private fun ToolGroupRow(item: RenderItem.Group, callbacks: AssistantPanelCallbacks) {
  val id = item.id.toLongOrNull()
  androidx.compose.foundation.layout.Column(modifier = Modifier.fillMaxWidth()) {
    ToolCallGroupHeader(
        count = item.children.count { it.role == MessageRole.TOOL },
        // 任一子项还在跑，组头就走高亮：表达「这批调用尚未结束」
        running = item.children.any { it.toolStatus == ToolRunStatus.RUNNING },
        expanded = item.expanded,
        onToggle = {
          // 传**当前**折叠状态：适配器只提供翻转，传目标状态会翻反。
          if (id != null) callbacks.onToggleGroup(id, currentlyCollapsed = !item.expanded)
        },
    )
    if (item.expanded) {
      item.children.forEach { child ->
        // 组内条目一律不挂操作行：与 XML 一致（组内的行是纯工具行/推理行，
        // 「复制/更多」只属于整条消息）。
        MessageRow(message = child, showActions = false, callbacks = callbacks)
      }
    }
  }
}

/**
 * 渲染一条消息，并把三处展开态与回滚入口接回宿主。
 *
 * <p>抽成一个函数而不是内联在 `when` 分支里：组内条目与独立条目用的是**同一套**接线，
 * 抄两份必然在后续改动里只改一处。
 */
@Composable
private fun MessageRow(
    message: AgentUIMessage,
    showActions: Boolean,
    callbacks: AssistantPanelCallbacks,
) {
  val id = message.id.toLongOrNull()
  AgentMessageItem(
      message = message,
      showActions = showActions,
      // 展开态来自数据（见 AssistantPanelCallbacks.onToggleExpanded）；组件按
      // 「override == true 才展开」解释，false 与 null 在视觉上同为收起
      toolExpandedOverride = message.expanded,
      onToolExpandedChange = { wanted ->
        // 组件回传的是「目标状态」，适配器提供的是「翻转」，因此仅在真的不同时才翻转：
        // 无条件翻转会在重复回调（重组导致的重复调用）时把状态翻回去。
        if (id != null && wanted != message.expanded) {
          callbacks.onToggleExpanded(id)
        }
      },
      onMoreClick = { if (id != null) callbacks.onMoreClick(id) },
      // 推理（思考）块的展开态走同一条通道。映射层为 Thinking 条目填的就是
      // AgentUIMessage.expanded，所以这里与工具行共用同一个字段与同一个回调——
      // 宿主的 onToggleExpanded 会依次尝试三种翻转，只有类型匹配的那个生效。
      reasoningExpandedOverride = message.expanded,
      onReasoningExpandedChange = { wanted ->
        if (id != null && wanted != message.expanded) {
          callbacks.onToggleExpanded(id)
        }
      },
  )
  // 回滚入口：移植组件没有 diff 撤销的回调，而这是本次必须保住的
  // 三项交互之一，因此由本层按数据里的 diffId 补一行。数据齐备（diffId / reverted
  // 都由映射层带到），行为与 XML 路径一致：已撤销则按钮禁用，不再可点。
  val diffId = message.diffId
  if (diffId != null && id != null) {
    RevertRow(reverted = message.reverted) { callbacks.onRevert(id, diffId) }
  }
}

/** 单条改动的回滚入口；[reverted] 为真时禁用，避免用户重复点击并收到「已经回滚过了」。 */
@Composable
private fun RevertRow(reverted: Boolean, onRevert: () -> Unit) {
  Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md),
      horizontalArrangement = Arrangement.End,
  ) {
    TextButton(onClick = onRevert, enabled = !reverted) {
      Text(
          text =
              stringResource(
                  if (reverted) R.string.ai_assistant_revert_done else R.string.ai_assistant_revert),
          style = MaterialTheme.typography.labelLarge,
      )
    }
  }
}

/**
 * 把面板装进一个 [ComposeView]，并持有跨重组存活的状态。
 *
 * <p>放在这里而不是让宿主自己 `setContent`：宿主是 View 层，不该知道 [LazyListState] 这类
 * Compose 概念；它只需要「加到容器里」「滚到底」「销毁时释放」三个动作。
 *
 * <p>刻意**不**设置 `ViewCompositionStrategy`：默认策略在视图从窗口分离时释放组合，正是
 * 宿主切换可见性（GONE/VISIBLE，不 detach）时期望的行为；若改成随 ViewTree 生命周期释放，
 * 在无 `ViewTreeLifecycleOwner` 的外悬浮场景会直接抛异常。
 */
internal class AssistantComposePanelHost(
    context: Context,
    val state: AssistantMessageState,
    callbacks: AssistantPanelCallbacks,
    /** 容器路径解析用；透传给面板里的图像查看器。null 时容器路径的图看不了（其余仍可看）。 */
    fileAccess: FileAccessProvider? = null,
) {

  /**
   * 主题明暗在构造期解析一次。
   *
   * <p>**不能在 `setContent` 的 lambda 里现算**：那会在每次重组时重新解析主题属性；
   * 而且面板存活期间 ACS 主题不会变（切主题会重建面板），算一次就够。
   * 声明在 [view] 之前——Kotlin 的属性初始化按声明顺序执行，反过来会读到未初始化的值。
   */
  private val darkTheme = isHostDarkTheme(context)

  val listState = LazyListState()

  /**
   * 当前待答的询问；null 表示没有。作为 [mutableStateOf] 而非普通字段：它是 Compose 的输入，
   * 赋值要触发重组才会弹出对话框。
   */
  private val pendingQuestion = mutableStateOf<PendingUserQuestion?>(null)

  /** 作答回传口。同一时刻只可能有一个提问在等（`askUserQuestion` 是阻塞式端口）。 */
  private var questionSink: ((UserQuestionAnswer?) -> Unit)? = null

  /**
   * 弹出询问并等待作答。
   *
   * <p><b>为什么由面板承接</b>：`askUserQuestion` 是阻塞式端口（调用方用 latch 等答案），
   * 而 Compose 的组合是异步的——只能先记下「有提问在等」，等用户在对话框上点完再回调。
   * 调用方（[com.tom.rv2ide.artificial.agent.FloatingAssistantView]）负责这一等的超时。
   *
   * <p><b>必须回主线程调用</b>：内部改的是 Compose 状态。
   *
   * @param onDone 答案；null 表示用户取消
   */
  fun askQuestion(question: PendingUserQuestion, onDone: (UserQuestionAnswer?) -> Unit) {
    questionSink = onDone
    pendingQuestion.value = question
  }

  /** 面板销毁时把未决提问收尾，避免调用方一直等到超时。 */
  fun cancelPendingQuestion() {
    finishQuestion(null)
  }

  private fun finishQuestion(answer: UserQuestionAnswer?) {
    val sink = questionSink ?: return
    // 先清状态再回调：回调方可能立刻发起下一个提问（模型连续问两轮），
    // 顺序反了会被本函数末尾的清理抹掉。
    questionSink = null
    pendingQuestion.value = null
    sink(answer)
  }

  val view: ComposeView =
      ComposeView(context).apply {
        setContent {
          AssistantComposePanel(
              state = state,
              listState = listState,
              callbacks = callbacks,
              darkTheme = darkTheme,
              fileAccess = fileAccess,
              question = pendingQuestion.value,
              onQuestionDone = { finishQuestion(it) },
          )
        }
      }

  /**
   * 滚到最后一条。
   *
   * <p>用后发先至的 `scrollToItem` 而不是动画：流式输出期间每次追加都会调用，动画会被下一次
   * 调用打断并排队，表现为「一直追不上底部」。瞬时定位与 XML 路径的 `scrollToPosition` 同义。
   */
  fun scrollToBottom(scope: CoroutineScope) {
    val count = state.messages.value.size
    if (count == 0) {
      return
    }
    scope.launch { listState.scrollToItem(count - 1) }
  }

  /**
   * 按 ACS 主题判断明暗，而不是按系统 uiMode。
   *
   * <p>判据与 `AssistantCodeHighlighter.Colors.from` 一致：取主题的 `colorBackground` 亮度。
   * 原因是**应用内可以手动切主题而不改系统配置**——若按 `isSystemInDarkTheme()` 判断，
   * 「系统深色 + 应用浅色」时 Compose 侧会用深色文字画在浅色面板上，正文直接读不出来。
   *
   * <p>在这里（构造期）算一次而不是在组合里读：主题明暗在面板存活期间不会变，放组合里
   * 每次重组都要解析一次主题属性。
   */
  private fun isHostDarkTheme(context: Context): Boolean {
    val value = android.util.TypedValue()
    val resolved = context.theme.resolveAttribute(android.R.attr.colorBackground, value, true)
    // 解不出来时按深色处理：ACS 默认深色（见 AcsComposeTheme.DarkColorScheme 的取值）
    val background = if (resolved) value.data else 0xFF07111F.toInt()
    return androidx.core.graphics.ColorUtils.calculateLuminance(background) <= 0.5
  }
}
