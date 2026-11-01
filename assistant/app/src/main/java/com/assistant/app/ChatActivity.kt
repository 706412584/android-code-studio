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
import com.assistant.ui.AssistantSessionsDrawer
import com.assistant.ui.SessionRow
import com.assistant.ui.WorkspaceBrowser
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

  /** 侧边栏「文件」Tab 的状态持有者（目录树 / 展开 / 剪切板）。 */
  private lateinit var browser: WorkspaceBrowser

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    modelSettings = ModelSettings(this)
    workspace = File(filesDir, "workspace").apply { mkdirs() }
    browser = WorkspaceBrowser(workspace)
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
            // 每次运行前读一次：用户在设置里改了模式，下一次发送即生效。
            { com.assistant.ui.AssistantPrefs.chatMode(this) },
        )

    // ---- 渲染 ----
    timeline = MessageTimeline()
    // 思考折叠偏好：结束后是否自动收起。用户可在设置里改，这里每次创建时读一次。
    timeline.autoCollapseThinking =
        com.assistant.ui.AssistantPrefs.thinkingAutoCollapse(this)
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
            // 会话抽屉开关。汉堡键打开的是它（不是设置）——与参考项目一致。
            var showDrawer by remember { mutableStateOf(false) }
            // 会话列表。每次打开抽屉时重读磁盘，保证新建/删除后立即反映。
            var sessions by remember { mutableStateOf(listOf<SessionRow>()) }

            androidx.activity.compose.BackHandler(enabled = showDrawer) { showDrawer = false }

            AssistantChatScreen(
                chatView = chatView.view,
                modelName = modelLabel(),
                conversationTitle = currentTitle,
                providerIcon = {
                  com.assistant.ui.components.independent.ProviderLogoIcon(
                      provider = providerTarget(),
                      size = 14.dp,
                  )
                },
                isBusy = busy,
                // 文件加入输入框后自动填进去；清空由输入栏在发送时完成。
                initialInput = pendingInput,
                onInitialInputConsumed = { pendingInput = "" },
                onSend = { text -> send(text) },
                onStop = { runner.cancel() },
                onOpenSessions = {
                  sessions = loadSessions()
                  // 文件树可能被模型改动过（工具写了文件），每次打开重读一遍。
                  browser.refresh()
                  showDrawer = true
                },
                onNewChat = {
                  conversationId = ensureConversation()
                  currentTitle = "新的对话"
                  timeline.clear()
                  reducer = EventReducer(timeline)
                  sessions = loadSessions()
                  showDrawer = false
                },
                drawer = {
                  AssistantSessionsDrawer(
                      sessions = sessions,
                      currentSessionId = conversationId,
                      workspace = workspace,
                      browser = browser,
                      onSelect = { row ->
                        openSession(row)
                        showDrawer = false
                      },
                      onNewSession = {
                        conversationId = ensureConversation()
                        currentTitle = "新的对话"
                        timeline.clear()
                        reducer = EventReducer(timeline)
                        sessions = loadSessions()
                        showDrawer = false
                      },
                      onDelete = { row ->
                        try {
                          conversationStore.delete(row.id)
                        } catch (e: Exception) {
                          android.util.Log.w("ChatActivity", "删除会话失败: ${row.id}", e)
                        }
                        if (row.id == conversationId) {
                          conversationId = ensureConversation()
                          currentTitle = "新的对话"
                          timeline.clear()
                          reducer = EventReducer(timeline)
                          showDrawer = false
                        }
                        sessions = loadSessions()
                        if (sessions.isEmpty()) {
                          conversationId = ensureConversation()
                          currentTitle = "新的对话"
                          timeline.clear()
                          reducer = EventReducer(timeline)
                          sessions = loadSessions()
                        }
                      },
                      onRename = { row, title ->
                        try {
                          conversationStore.rename(row.id, title)
                          if (row.id == conversationId) currentTitle = title
                          sessions = loadSessions()
                        } catch (e: Exception) {
                          android.util.Log.w("ChatActivity", "重命名失败: ${row.id}", e)
                        }
                      },
                      // 置顶：ConversationStore 没有该能力（需要新的条目类型），
                      // 传空实现——点了没反应，但不会崩。
                      onTogglePin = {},
                      onOpenSettings = {
                        showDrawer = false
                        startActivity(
                            android.content.Intent(this@ChatActivity, SettingsActivity::class.java))
                      },
                      onDismiss = { showDrawer = false },
                      // 文件树里点文件：把内容读出来追加到输入框。
                      // 本 App 没有编辑器，这是「查看文件」在本环境下的合理落点。
                      onOpenFile = { path ->
                        appendFileToInput(path)
                        showDrawer = false
                      },
                      onAddToInput = { path ->
                        appendFileToInput(path)
                        showDrawer = false
                      },
                  )
                },
                drawerVisible = showDrawer,
                onDismissDrawer = { showDrawer = false },
                // 外观设置：卡片缩放与正文颜色。每次重组读一次偏好——
                // 用户在设置页改完返回时这里会重新求值（Activity 重建）。
                style =
                    com.assistant.ui.theme.AssistantStyle(
                        cardScale = com.assistant.ui.AssistantPrefs.cardScale(this@ChatActivity),
                        textColor =
                            com.assistant.ui.AssistantPrefs.textColor(this@ChatActivity)
                                .takeIf { it != 0 }
                                ?.let { androidx.compose.ui.graphics.Color(it) },
                    ),
            )
          }
        }
    setContentView(rootView)

    if (!modelSettings.isReady()) {
      showSetupHint()
    }
    chatView.view.post { chatView.forceScrollToBottom(uiScope) }
  }

  /** 顶栏标题：当前会话名。 */
  private var currentTitle: String = "新的对话"

  /** 从磁盘读会话列表（按最近修改倒序，由 store 保证）。 */
  private fun loadSessions(): List<com.assistant.ui.SessionRow> =
      try {
        conversationStore.list().map { s ->
          com.assistant.ui.SessionRow(
              id = s.id,
              title = s.title,
              updatedAt = s.modifiedAt,
              messageCount = s.messageCount,
          )
        }
      } catch (e: Exception) {
        android.util.Log.w("ChatActivity", "会话列表读取失败", e)
        emptyList()
      }

  /**
   * 切到另一个会话：清空列表并从磁盘回放。
   *
   * <p>回放走 `ConversationHistory.fold` 的反向——这里不复用那条路径：
   * fold 产出的是**模型消息**（给 API 用），而界面要的是**条目**（带工具卡、思考块）。
   * 两者信息量不同，直接映射会丢掉工具调用的渲染态。
   * 因此回放只还原用户/助手文本与工具调用结果，其余渲染态（展开、耗时）按默认值给。
   */
  private fun openSession(row: com.assistant.ui.SessionRow) {
    conversationId = row.id
    currentTitle = row.title
    timeline.clear()
    reducer = EventReducer(timeline)
    try {
      val entries = conversationStore.read(row.id)
      for (location in entries) {
        when (val entry = location.entry) {
          is com.tom.rv2ide.ai.agent.conversation.UserMessageEntry -> {
            val text = entry.content
            if (!text.isNullOrBlank()) timeline.append(MessageTimeline.Role.USER, text)
          }
          is com.tom.rv2ide.ai.agent.conversation.AssistantMessageEntry -> {
            val text = entry.content
            if (!text.isNullOrBlank()) {
              timeline.append(MessageTimeline.Role.ASSISTANT, text)
            }
            // 工具调用作为独立卡片回放：它们承载了「这一轮做了什么」。
            entry.toolCalls.forEach { call ->
              val id =
                  timeline.appendToolCall(call.name, summarizeArgs(call.arguments), call.arguments)
              timeline.completeToolCall(id, "（历史记录，输出未单独保存）", isError = false)
            }
          }
          is com.tom.rv2ide.ai.agent.conversation.ToolResultEntry -> {
            // 结果条目只补输出文本；卡片已在上一条助手消息里建好。
            timeline.lastToolCallId()?.let { id ->
              timeline.completeToolCall(
                  id,
                  entry.content.orEmpty(),
                  isError = entry.isError,
              )
            }
          }
          else -> Unit
        }
      }
    } catch (e: Exception) {
      android.util.Log.w("ChatActivity", "会话回放失败: ${row.id}", e)
      timeline.append(MessageTimeline.Role.TRACE, "会话回放失败：${e.message ?: "未知错误"}")
    }
    chatView.forceScrollToBottom(uiScope)
  }

  /** 工具参数摘要（与 EventReducer 的同名逻辑一致，这里只需短摘要）。 */
  private fun summarizeArgs(args: String?): String {
    if (args.isNullOrBlank()) return ""
    return try {
      val obj = org.json.JSONObject(args)
      val sb = StringBuilder()
      val keys = obj.keys()
      while (keys.hasNext()) {
        val key = keys.next()
        if (sb.isNotEmpty()) sb.append(' ')
        sb.append(key).append('=').append(obj.optString(key).take(40))
      }
      sb.toString()
    } catch (e: Exception) {
      args.take(60)
    }
  }

  /**
   * 把文件内容追加到输入框。
   *
   * <p><b>为什么是「追加到输入框」而不是打开编辑器</b>：本 App 没有代码编辑器
   * （编辑器属于 ACS 的资产，不在剥离范围内）。用户点文件多半是想「让模型看这个」
   * 或「我自己看一眼」——前者追加到输入框最直接，后者靠系统的分享/查看器。
   * 这里读文本并加一行说明，超过 256KB 会截断（见 [WorkspaceBrowser.readText]）。
   */
  private fun appendFileToInput(path: String) {
    val file = File(path)
    if (file.isDirectory) {
      return
    }
    val text = browser.readText(path)
    if (text == null) {
      android.widget.Toast.makeText(this, "读取失败：${file.name}", android.widget.Toast.LENGTH_SHORT)
          .show()
      return
    }
    pendingInput =
        buildString {
          if (pendingInput.isNotEmpty()) {
            append(pendingInput).append("\n\n")
          }
          append("以下是我选中的文件 `").append(file.name).append("`：\n```\n")
          append(text)
          append("\n```")
        }
    android.widget.Toast.makeText(this, "已加入输入框", android.widget.Toast.LENGTH_SHORT).show()
  }

  /** 待发送的输入内容（文件加入后由输入栏读取）。 */
  private var pendingInput: String = ""

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
