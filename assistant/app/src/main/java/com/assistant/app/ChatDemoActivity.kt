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

package com.assistant.app

import androidx.activity.ComponentActivity
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.assistant.ui.AssistantChatCallbacks
import com.assistant.ui.AssistantChatView
import com.assistant.ui.model.AssistantDiffBuilder
import com.assistant.ui.model.MessageTimeline

/**
 * 剥离验证（第 2 步）：**不依赖任何 ACS 模块**，把 Compose 渲染层跑起来。
 *
 * <p><b>为什么必须继承 ComponentActivity 而不是 android.app.Activity</b>：
 * `ComposeView` 在 attach 时要找 `ViewTreeLifecycleOwner`，找不到就抛
 * `IllegalStateException`（`createLifecycleAwareWindowRecomposer` 的空检查）。
 * 纯框架 Activity 不提供它；`androidx.activity.ComponentActivity` 提供。
 * 这是 Compose 宿主的硬要求，与 ACS 无关。
 *
 * <p>本 Activity 用假数据构造一条完整的会话（用户消息 → 推理 → 工具卡 → 回答 → 改动汇总），
 * 目的是让「渲染层能不能脱离 ACS 独立工作」这件事在真机上一眼可见——
 * 编译通过只能说明类型对得上，`NoSuchMethodError` / 资源缺失 / 主题取不到
 * 都只在设备上暴露。
 *
 * <p>刻意不用真实模型请求：那需要服务商配置，而这一步要验证的是**渲染**，
 * 不是协议层（协议层在第 1 步已经验证过能构造）。
 */
class ChatDemoActivity : ComponentActivity() {

  private lateinit var chatView: AssistantChatView
  private lateinit var timeline: MessageTimeline

  /** 滚动用的协程作用域。Activity 销毁时取消，避免往已 detach 的列表投递滚动。 */
  private val uiScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main)

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    timeline = MessageTimeline()

    chatView =
        AssistantChatView(
            this,
            timeline,
            object : AssistantChatCallbacks {
              override fun onToggleExpanded(messageId: Long) {
                // 三种可展开条目各有自己的翻转方法，按 id 逐个调用；
                // 找不到对应类型的会各自静默跳过——与 ACS 侧的接线同一套路。
                timeline.toggleExpanded(messageId)
                timeline.toggleThinkingExpanded(messageId)
                timeline.toggleDiffExpanded(messageId)
              }

              override fun onToggleGroup(groupId: Long, currentlyCollapsed: Boolean) {
                timeline.toggleGroupExpanded(groupId, currentlyCollapsed)
              }

              override fun onRevert(messageId: Long, diffId: String) {
                // 独立 App 里没有工作区可回滚，只标记状态——这一步验证的是渲染，
                // 回滚实现属于后续的 Port 接线。
                timeline.markReverted(messageId)
                timeline.markDiffReverted(messageId)
              }

              override fun onMoreClick(messageId: Long, anchor: View) {
                android.widget.Toast.makeText(
                        this@ChatDemoActivity, "更多操作（演示）", android.widget.Toast.LENGTH_SHORT)
                    .show()
              }
            },
        )

    seedDemoConversation(timeline)

    setContentView(
        FrameLayout(this).apply {
          addView(
              chatView.view,
              ViewGroup.LayoutParams.MATCH_PARENT,
              ViewGroup.LayoutParams.MATCH_PARENT,
          )
        })

    // 首帧布局完成后再滚到底：直接调用会拿到 viewportEndOffset = 0。
    chatView.view.post { chatView.forceScrollToBottom(uiScope) }
  }

  override fun onDestroy() {
    chatView.release()
    super.onDestroy()
  }

  /**
   * 构造一条覆盖渲染层全部条目类型的会话。
   *
   * <p>刻意把每种条目都放一条：这次验证的价值在于「全部都渲染得出来」，
   * 只放一条纯文本的话，Markdown / 代码高亮 / 工具卡 / diff 卡 / 思考块全都没被走到。
   */
  private fun seedDemoConversation(timeline: MessageTimeline) {
    timeline.append(
        MessageTimeline.Role.USER,
        "帮我看看 `MessageTimeline.kt` 里分组逻辑有没有问题，顺便给个使用示例。",
    )

    val thinkingId =
        timeline.appendThinking("先定位文件，然后通读分组决策……", null, streaming = false)
    timeline.finishThinking(thinkingId)

    val readId = timeline.appendToolCall("file_read", "MessageTimeline.kt", """{"path":"MessageTimeline.kt"}""")
    timeline.completeToolCall(readId, "（文件内容略，共 900 行）", isError = false)

    val grepId = timeline.appendToolCall("file_search", "ToolGrouping.decide", """{"query":"ToolGrouping.decide"}""")
    timeline.completeToolCall(grepId, "3 处命中", isError = false)

    timeline.append(
        MessageTimeline.Role.ASSISTANT,
        """
        分组逻辑本身没问题，但有三处容易踩的点：

        | 位置 | 风险 |
        |---|---|
        | `appendToolCall` | 子 agent 工具必须独立，否则步骤没展示位 |
        | `appendThinking` | 推理块要能穿透分组查找，否则每轮都新建块 |
        | `trimToolImages` | 图片要限张数，否则长任务会把堆撑爆 |

        ```kotlin
        val id = timeline.appendToolCall("file_read", "a.kt", "{\\"path\\":\\"a.kt\\"}")
        timeline.completeToolCall(id, output, isError = false)
        ```
        """.trimIndent(),
    )

    // 改动汇总卡：两条 diff 子项，验证行号列 / 增删底色 / 汇总组头。
    val diffIds =
        timeline.appendDiffGroup(listOf("MessageTimeline.kt" to "demo-1", "ToolGrouping.kt" to "demo-2"))
    val before = "fun decide(a: Boolean): Decision = if (a) MERGE else APPEND"
    val after =
        """
        fun decide(lastIsGroup: Boolean, incoming: String): Decision {
          if (isStandaloneTool(incoming)) return Decision.APPEND
          return if (lastIsGroup) Decision.MERGE_INTO_LAST_GROUP else Decision.APPEND
        }
        """.trimIndent()
    if (diffIds.size == 2) {
      timeline.updateDiff(diffIds[0], AssistantDiffBuilder.build(before, after))
      timeline.updateDiff(diffIds[1], AssistantDiffBuilder.build("", "enum class Decision { APPEND }"))
    }
  }
}
