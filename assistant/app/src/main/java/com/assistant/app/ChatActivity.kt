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

import android.os.Bundle
import android.os.SystemClock
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.assistant.engine.AppHttpPort
import com.assistant.engine.ConversationRunner
import com.assistant.engine.ModelSettings
import com.assistant.engine.ProviderPresets
import com.assistant.engine.ToolSetFactory
import com.assistant.ui.AssistantChatCallbacks
import com.assistant.ui.AssistantChatView
import com.assistant.ui.AssistantChatScreen
import com.assistant.ui.EventReducer
import com.assistant.ui.model.MessageTimeline
import com.tom.rv2ide.ai.agent.AgentEvent
import com.tom.rv2ide.ai.agent.AgentRunResult
import com.tom.rv2ide.ai.agent.conversation.FileConversationStore
import com.tom.rv2ide.ai.tool.ToolExecutor
import com.tom.rv2ide.ai.tool.ToolRegistry
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * 真正能对话的界面：消息列表 + 输入栏 + 引擎。
 *
 * <p>与 [ChatDemoActivity] 的区别：那个用假数据证明「渲染层能独立工作」，
 * 这个把引擎接上去，发一条消息能真的收到模型回复并执行工具。
 *
 * <p><b>事件线程</b>：内核的事件来自 HTTP 线程，[EventReducer] 与 [MessageTimeline]
 * 都要求主线程，因此每个事件都要 `runOnUiThread`。不做节流——流式增量的频率由
 * 服务商决定（通常几十毫秒一次），`MessageTimeline` 的通知本身已由
 * `AssistantMessageState` 按帧合并。
 */
class ChatActivity : ComponentActivity() {

  private lateinit var timeline: MessageTimeline
  private lateinit var reducer: EventReducer
  private lateinit var chatView: AssistantChatView
  private lateinit var modelSettings: ModelSettings
  private lateinit var runner: ConversationRunner
  private lateinit var conversationStore: FileConversationStore

  private val uiScope = CoroutineScope(Dispatchers.Main)

  /** 当前会话 id。本步骤固定一个会话（多会话属于后续步骤）。 */
  private lateinit var conversationId: String

  /** 工作区：模型能访问的文件范围。 */
  private lateinit var workspace: File

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    modelSettings = ModelSettings(this)
    workspace = File(filesDir, "workspace").apply { mkdirs() }
    conversationStore = FileConversationStore(File(filesDir, "conversations"))

    conversationId = ensureConversation()

    // ---- 引擎 ----
    val http = AppHttpPort()
    val registry: ToolRegistry =
        ToolSetFactory.create(
            workspace,
            File(filesDir, "memory/memory.jsonl"),
            File(filesDir, "skills"),
            http,
        )
    val executor = ToolExecutor(registry, null, null)
    runner =
        ConversationRunner(
            modelSettings,
            registry,
            executor,
            conversationStore,
            workspace,
            null,
        )

    // ---- 渲染 ----
    timeline = MessageTimeline()
    reducer = EventReducer(timeline)
    chatView =
        AssistantChatView(
            this,
            timeline,
            object : AssistantChatCallbacks {
              override fun onToggleExpanded(messageId: Long) {
                timeline.toggleExpanded(messageId)
                timeline.toggleThinkingExpanded(messageId)
                timeline.toggleDiffExpanded(messageId)
              }

              override fun onToggleGroup(groupId: Long, currentlyCollapsed: Boolean) {
                timeline.toggleGroupExpanded(groupId, currentlyCollapsed)
              }

              override fun onRevert(messageId: Long, diffId: String) {
                // 回滚需要工作区上下文与 IO 线程，属于后续步骤。
                // 这里只标记状态，让用户看到「已撤销」的即时反馈。
                timeline.markReverted(messageId)
                timeline.markDiffReverted(messageId)
              }

              override fun onMoreClick(messageId: Long, anchor: View) {
                android.widget.Toast.makeText(
                        this@ChatActivity, "更多操作尚未接入", android.widget.Toast.LENGTH_SHORT)
                    .show()
              }
            },
        )

    // 整页一个 Compose 树：顶栏 + 消息列表 + 输入栏。
    //
    // 拆成两个 ComposeView（一个放消息、一个放输入栏）会各包一次主题——
    // 实测结果是消息区蓝色、输入栏 M3 默认紫，割裂感明显。合成一棵树后
    // AIEditorTheme 只在最外层包一次。
    val rootView =
        androidx.compose.ui.platform.ComposeView(this).apply {
          setContent {
            // 输入栏的忙碌态要跟着运行状态走，因此用一个 Compose 状态把它推上来。
            var busy by remember { mutableStateOf(false) }
            // 每帧读一次 runner 状态：运行结束由 onFinished 置 false。
            LaunchedEffect(Unit) {
              while (true) {
                busy = runner.isRunning()
                kotlinx.coroutines.delay(120)
              }
            }
            AssistantChatScreen(
                chatView = chatView.view,
                modelName = modelLabel(),
                conversationTitle = "新的对话",
                providerIcon = {
                  com.assistant.ui.components.independent.ProviderLogoIcon(
                      provider = providerTarget(),
                      size = 14.dp,
                  )
                },
                isBusy = busy,
                onSend = { text -> send(text) },
                onStop = { runner.cancel() },
                onOpenSettings = {
                  startActivity(
                      android.content.Intent(this@ChatActivity, SettingsActivity::class.java))
                },
                onNewChat = {
                  // 新会话：换 id + 清空列表。历史仍留在磁盘上（会话列表属于后续步骤）。
                  conversationId = ensureConversation()
                  timeline.clear()
                  reducer = EventReducer(timeline)
                },
            )
          }
        }
    setContentView(rootView)

    if (!modelSettings.isReady()) {
      showSetupHint()
    }
    chatView.view.post { chatView.forceScrollToBottom(uiScope) }
  }

  /** 输入栏芯片上显示的模型名。 */
  private fun modelLabel(): String {
    val preset = ProviderPresets.find(modelSettings.providerId())
    return "${preset?.label ?: "未配置"} · ${modelSettings.modelId()}"
  }

  /** 顶栏副标题前的服务商 logo。 */
  private fun providerTarget(): com.assistant.ui.components.independent.ProviderSelectionTarget? {
    val preset = ProviderPresets.find(modelSettings.providerId()) ?: return null
    return com.assistant.ui.components.independent.ProviderSelectionTarget(
        id = preset.id,
        name = preset.label,
        protocol =
            if (preset.protocolType ==
                com.tom.rv2ide.ai.protocol.ModelProtocolType.ANTHROPIC_MESSAGES) {
              com.assistant.ui.components.independent.ProviderProtocol.ANTHROPIC_MESSAGES
            } else {
              com.assistant.ui.components.independent.ProviderProtocol.OPENAI_COMPATIBLE
            },
    )
  }

  override fun onDestroy() {
    runner.cancel()
    chatView.release()
    super.onDestroy()
  }

  // ---- 发送 ----

  private fun send(text: String) {
    if (text.isBlank()) {
      return
    }
    if (!modelSettings.isReady()) {
      showSetupHint()
      return
    }
    // 本地先画用户气泡：内核也会广播 USER_MESSAGE，EventReducer 会判重
    // （末条已是同一内容的用户消息则跳过），所以这里画是安全的。
    timeline.append(MessageTimeline.Role.USER, text)
    chatView.forceScrollToBottom(uiScope)

    val handle =
        runner.start(
            conversationId,
            text,
            null,
            object : ConversationRunner.Listener {
              override fun onEvent(event: AgentEvent) {
                runOnUiThread {
                  reducer.reduce(event, SystemClock.elapsedRealtime())
                  chatView.forceScrollToBottom(uiScope)
                }
              }

              override fun onFinished(result: AgentRunResult?, error: Throwable?) {
                runOnUiThread {
                  if (error != null) {
                    timeline.append(
                        MessageTimeline.Role.TRACE,
                        "运行失败：${error.javaClass.simpleName} — ${error.message ?: "无详情"}",
                    )
                  }
                  chatView.forceScrollToBottom(uiScope)
                }
              }
            },
        )
    if (handle == null) {
      timeline.append(MessageTimeline.Role.TRACE, "无法启动：上一轮仍在运行。")
      chatView.forceScrollToBottom(uiScope)
    }
  }

  // ---- 会话 ----

  /**
   * 取一个可用的会话 id。
   *
   * <p>本步骤固定「每次启动新开一个会话」——会话列表（历史切换、重命名、删除）
   * 属于后续步骤，先把「发一条消息能收到回复」跑通。
   */
  private fun ensureConversation(): String {
    return try {
      val created =
          conversationStore.create(
              "新的对话",
              workspace.absolutePath,
              modelSettings.modelId(),
              com.tom.rv2ide.ai.tool.ToolSettingsPort.PERMISSION_CONFIRM,
          )
      created.id
    } catch (e: Exception) {
      // 存储不可用时退化成纯内存会话：id 仍需合法（FileConversationStore 校验），
      // 用固定的合法串，落盘会失败但不影响本轮对话。
      android.util.Log.w("ChatActivity", "会话创建失败，退化为内存会话", e)
      "00000000-0000-0000-0000-000000000000"
    }
  }

  /** 配置不完整时给一句可操作的提示（而不是静默什么都不发生）。 */
  private fun showSetupHint() {
    val problem = modelSettings.readinessProblem()
    timeline.append(
        MessageTimeline.Role.TRACE,
        "还不能对话：$problem\n请在设置里配置服务商与 API Key。",
    )
  }
}
