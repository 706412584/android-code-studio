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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import com.tom.rv2ide.artificial.agent.compose.components.bubbles.AgentMessageItem
import com.tom.rv2ide.artificial.agent.compose.theme.AIEditorTheme
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
 * <p>开关放在与 [com.tom.rv2ide.artificial.agent.AgentToolSettings] 同一份偏好
 * （`ai_agent_tools`）里，便于将来在 AI 设置屏加一个可见的开关，而不用再挪一次位置。
 * 目前只能通过 adb 切换：
 *
 * ```
 * adb shell run-as com.tom.rv2ide sh -c \
 *   'echo "assistant_compose_render=true" >> \
 *    /data/data/com.tom.rv2ide/shared_prefs/ai_agent_tools.xml'
 * ```
 *
 * <p>换用正式的设置项时，**只改这里**，渲染路径的其它代码不需要感知开关的存在。
 */
internal object AssistantComposeRender {

  private const val PREFS_NAME = "ai_agent_tools"
  private const val KEY_ENABLED = "assistant_compose_render"

  fun isEnabled(context: Context): Boolean =
      context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)
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
) {
  AIEditorTheme(darkTheme = darkTheme) {
    // 读一次快照：列表变化时本函数重组，而 LazyColumn 按 key 只重组变化的条目
    val messages by state.messages
    // 「挂操作行」只给最新一条助手消息，因此这里算一次，避免在 item 作用域里逐条求末项
    val lastId = messages.lastOrNull()?.id

    // 刻意不铺背景色：面板底色由宿主的 MaterialCardView（?attr/colorSurface）提供，
    // XML 路径的 RecyclerView 同样没有背景。这里若铺 [MaterialTheme.colorScheme.background]，
    // 会与宿主主题（可能是自定义的 VSCODE 浅色等）产生一块可见色差。
    Box(modifier = modifier.fillMaxSize()) {
      LazyColumn(
          state = listState,
          modifier = Modifier.fillMaxSize(),
          // 横向留白由宿主布局负责（与 XML 路径一致），这里只补上下边距
          contentPadding = PaddingValues(vertical = Spacing.sm),
      ) {
        items(items = messages, key = { it.id }) { message ->
          val id = message.id.toLongOrNull()
          AgentMessageItem(
              message = message,
              showActions = message.id == lastId,
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
          // 回滚入口：移植组件目前没有 diff 撤销的回调（见交付报告），而这是本次必须保住的
          // 三项交互之一，因此由本层按数据里的 diffId 补一行。数据齐备（diffId / reverted
          // 都由映射层带到），行为与 XML 路径一致：已撤销则按钮禁用，不再可点。
          val diffId = message.diffId
          if (diffId != null && id != null) {
            RevertRow(reverted = message.reverted) { callbacks.onRevert(id, diffId) }
          }
        }
      }
    }
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

  val view: ComposeView =
      ComposeView(context).apply {
        setContent {
          AssistantComposePanel(
              state = state,
              listState = listState,
              callbacks = callbacks,
              darkTheme = darkTheme,
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
