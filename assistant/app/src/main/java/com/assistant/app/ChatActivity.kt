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
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.assistant.engine.AppHttpPort
import com.assistant.engine.AssistantToolSettings
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
import com.assistant.ui.compat.HostFileAccessProvider
import com.assistant.ui.compat.PendingUploadAttachment
import com.assistant.ui.compat.isImage
import com.assistant.ui.compat.toPendingAttachment
import com.assistant.ui.compat.toAnswerList
import com.assistant.ui.compat.toPendingUserQuestion
import com.assistant.ui.components.independent.MAX_PENDING_ATTACHMENTS
import com.assistant.ui.components.independent.appendAttachmentsToRequest
import com.assistant.ui.components.independent.copyUriToWorkspace
import com.assistant.ui.components.independent.maxAttachmentMessage
import com.assistant.ui.components.independent.partialUploadMessage
import com.assistant.ui.components.independent.uploadFallbackError
import com.assistant.ui.components.independent.uploadSuccessMessage
import com.assistant.ui.model.MessageTimeline
import com.tom.rv2ide.ai.agent.AgentEvent
import com.tom.rv2ide.ai.agent.AgentRunResult
import com.tom.rv2ide.ai.agent.conversation.FileConversationStore
import com.tom.rv2ide.ai.tool.ToolExecutor
import com.tom.rv2ide.ai.tool.ToolRegistry
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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

  /**
   * 权限闸门。字段而非局部变量：确认/提问的回调（[awaitUserDecision] 等）要用它。
   */
  private lateinit var toolSettings: AssistantToolSettings

  /** 宿主的文件访问实现：附件落盘与缩略图读取都经它（容器路径 ↔ 本地文件）。 */
  private lateinit var fileAccess: com.assistant.ui.compat.FileAccessProvider

  /**
   * 待发附件。用 SnapshotStateList 而不是普通 MutableList：
   * 它是可观察的，选取落盘后 add 会直接触发 Compose 重组（列表里出现附件芯片）。
   */
  private val pendingAttachments =
      mutableStateListOf<
          PendingUploadAttachment>()

  private var filePickerLauncher:
      ActivityResultLauncher<Array<String>>? = null
  private var imagePickerLauncher:
      ActivityResultLauncher<
          PickVisualMediaRequest>? = null
  private var takePictureLauncher:
      ActivityResultLauncher<android.net.Uri>? = null

  /** 拍照输出的临时 uri（FileProvider 授权）；拍照回调里取回。 */
  private var cameraPhotoUri: android.net.Uri? = null

  /** 上下文已用 token（内核回调）。Compose 状态：改了要重绘顶栏与占用环。 */
  private var contextUsedTokens by mutableStateOf(0)

  /** 上下文窗口大小；0 = 模型未声明（此时占用环不显示）。 */
  private var contextWindowTokens by mutableStateOf(0)

  private val uiScope = CoroutineScope(Dispatchers.Main)

  /** 当前会话 id。本步骤固定一个会话（多会话属于后续步骤）。 */
  private lateinit var conversationId: String

  /** 工作区：模型能访问的文件范围。 */
  private lateinit var workspace: File

  /** 侧边栏「文件」Tab 的状态持有者（目录树 / 展开 / 剪切板）。 */
  private lateinit var browser: WorkspaceBrowser

  /** 本次运行的 MCP 连接；Activity 销毁时逐个关闭。 */
  private val mcpClients = ArrayList<com.tom.rv2ide.ai.tool.mcp.McpClient>()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    modelSettings = ModelSettings(this)
    workspace = File(filesDir, "workspace").apply { mkdirs() }
    browser = WorkspaceBrowser(workspace)
    conversationStore = FileConversationStore(File(filesDir, "conversations"))

    conversationId = ensureConversation()

    // ---- 引擎 ----
    val http = AppHttpPort()
    val media = com.assistant.engine.MediaSettings(this)
    val mcpSettings = com.assistant.engine.McpSettings(this)
    val registry: ToolRegistry =
        ToolSetFactory.create(
            workspace,
            File(filesDir, "memory/memory.jsonl"),
            File(filesDir, "skills"),
            http,
            // 生成端点未配置时传 null，工具就不注册（模型看不到它）。
            media.imageEndpoint(),
            media.videoEndpoint(),
        )
    // MCP：把已启用的 server 的工具接进来。失败只记日志、不阻断——
    // 某个 server 连不上不该让整个对话不可用。连接存进字段，onDestroy 时关闭。
    runCatching {
          val result = mcpSettings.attach(http, registry, mcpClients)
          if (result.failures.isNotEmpty()) {
            android.util.Log.w("ChatActivity", "MCP 接入失败: ${result.failures.joinToString("; ")}")
          }
          android.util.Log.i("ChatActivity", "MCP 接入 ${result.toolCount} 个工具")
        }
        .onFailure { android.util.Log.w("ChatActivity", "MCP 接入异常", it) }
    // 权限闸门：**必须显式构造并同时交给 executor 与 runner**。
    //
    // 两处都不能省（此前只传 null，导致默认 PERMISSION_AUTO 静默放行危险工具）：
    //  - ToolExecutor 第 2 参为 null 时，它会自建 ToolPermissionService(defaults(), …)，
    //    而 defaults() 的 getPermissionMode() 返回 PERMISSION_AUTO；
    //  - ConversationRunner 的 toolSettings 决定 ToolContext.settings，
    //    那是 Question 弹窗与「确认后执行」路径读取的那一份。
    // 任一为 null，界面上写着「逐次确认」，实际都不问。
    toolSettings = AssistantToolSettings(this)
    val permissionService =
        com.tom.rv2ide.ai.tool.ToolPermissionService(toolSettings, registry)
    val executor = ToolExecutor(registry, permissionService, null)
    runner =
        ConversationRunner(
            modelSettings,
            registry,
            executor,
            conversationStore,
            workspace,
            toolSettings,
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

    // ---- 权限闸门接线 ----
    //
    // 内核的确认闸门是**阻塞式端口**（工具线程在等答案），而 Compose 的弹窗必须
    // 在主线程异步进行。因此这里用 latch 把两边接起来：
    //   工具线程 → confirm()（阻塞等 latch）→ post 到主线程弹窗 → 用户点击 → 唤醒
    // 超时兜底：Activity 若已销毁或弹窗迟迟不来，不能把工具线程一直挂着。
    toolSettings.setDangerousToolConfirmer(
        AssistantToolSettings.DangerousToolConfirmer { toolName, args ->
          awaitUserDecision(toolName, args)
        })
    toolSettings.setQuestionAsker(
        AssistantToolSettings.QuestionAsker { questions ->
          awaitUserAnswers(questions)
        })

    // 上下文用量：内核的 TokenUsageTracker 一直在统计（压缩触发条件就靠它），
    // 但此前没接出来，顶栏的 token 数与占用环恒为 0。
    // 回调可能来自工具线程，必须切主线程——它写的是 Compose 状态。
    runner.setUsageListener { used, total ->
      runOnUiThread {
        contextUsedTokens = used
        contextWindowTokens = total
      }
    }

    // ---- 附件 ----
    //
    // 三个选取入口各自注册：文件走 OpenMultipleDocuments，图片走 PickMultipleVisualMedia
    // （它会返回可读的图片 uri），拍照走 TakePicture。**必须在这里注册**——
    // ActivityResultContracts 的回调只能投到注册者，Compsoe 层拿不到。
    fileAccess = HostFileAccessProvider(workspace)
    filePickerLauncher =
        registerForActivityResult(
            ActivityResultContracts.OpenMultipleDocuments()) {
                uris ->
              // 普通文件不读 base64，只把内容落盘 + 路径告诉模型。
              handlePickedUris(uris, includeImageData = false)
            }
    imagePickerLauncher =
        registerForActivityResult(
            ActivityResultContracts.PickMultipleVisualMedia(
                MAX_PENDING_ATTACHMENTS)) { uris ->
              handlePickedUris(uris, includeImageData = true)
            }
    takePictureLauncher =
        registerForActivityResult(
            ActivityResultContracts.TakePicture()) { success ->
          val uri = cameraPhotoUri
          cameraPhotoUri = null
          // 用户取消拍照时 success=false，那时 uri 已无意义，直接丢弃。
          if (success && uri != null) {
            handlePickedUris(listOf(uri), includeImageData = true)
          }
        }

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

            // 读一次刷新触发计数：设置页改了模型后返回，这里重新求值。
            // 必须在 modelLabel() 之前读——Compose 只让读过该状态的作用域重组。
            @Suppress("UNUSED_EXPRESSION") headerRefresh

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
                // 终端页：Termux 的 TerminalView + proot 进 Ubuntu。
                // `this@ChatActivity` 不能省——lambda 里的 `this` 是 Compose 作用域。
                onOpenTerminal = {
                  startActivity(
                      android.content.Intent(this@ChatActivity, TerminalActivity::class.java))
                },
                // ---- 附件 ----
                // pendingAttachments 是 SnapshotStateList，本身可观察：选取落盘后 add
                // 即触发重组，不需要额外通知。
                attachments = pendingAttachments,
                onRemoveAttachment = { index ->
                  if (index in pendingAttachments.indices) pendingAttachments.removeAt(index)
                },
                onReadAttachment = { path -> readAttachmentText(path) },
                // 上限由 MAX_PENDING_ATTACHMENTS 统一决定，界面与落盘用同一个数。
                canUploadFiles =
                    pendingAttachments.size <
                        MAX_PENDING_ATTACHMENTS,
                canUploadImages =
                    pendingAttachments.size <
                        MAX_PENDING_ATTACHMENTS,
                onUploadFile = { launchFilePicker() },
                onUploadImage = { launchImagePicker() },
                onTakePhoto = { takePhoto() },
                // ---- 斜杠命令 / @ 技能 ----
                // 数据源来自内核，不在这里自造：命令集与解析规则必须与
                // ConversationRunner 里 SlashCommandCatalog.parse 的判定一致，
                // 抄一份出来就会在两边分别演化（改了一边另一边就静默失效）。
                slashCommands = slashCommands(),
                skills = skillCommands(),
                // ---- 上下文用量 ----
                // 内核只给「已用多少」，输入/输出分开的细分它没有（只有估算总量），
                // 因此输入侧填已用量、输出侧留 0，而不是编一个数——顶栏显示的是
                // 「上下文占了多少」，这才是用户要看的。
                inputTokens = contextUsedTokens,
                outputTokens = 0,
                tokenProgress =
                    if (contextWindowTokens > 0) {
                      (contextUsedTokens.toFloat() / contextWindowTokens).coerceIn(0f, 1f)
                    } else {
                      0f
                    },
                contextWindowSize = contextWindowTokens,
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
      // 只给一句提示 + 一个按钮，**不自动跳转**。
      // 曾试过自动跳设置页，实测是：冷启动 1 秒后界面突然跳走，
      // 用户来不及看清就离开了对话页（「点击还是会跳转设置 你看看 几秒后」）。
      // 去哪由用户自己决定——提示条上就有入口。
      showSetupHint()
    }
    chatView.view.post { chatView.forceScrollToBottom(uiScope) }
  }

  /**
   * 从设置页返回时刷新顶栏（模型名/服务商 logo）。
   *
   * <p>不刷新的话，用户在设置里换了模型、返回对话页，顶栏还显示旧模型——
   * 只有杀掉重进才更新。Compose 树不会因为 Activity 恢复而自动重组，
   * 得踢一下状态。
   */
  override fun onResume() {
    super.onResume()
    headerRefresh++
  }

  /** 顶栏重算触发器（见 [onResume]）。 */
  private var headerRefresh by mutableStateOf(0)

  /** 顶栏标题：当前会话名。 */
  private var currentTitle: String = "新的对话"

  /**
   * 把工具线程的阻塞确认接到主线程弹窗。
   *
   * <p>工具线程在这里等 [CountDownLatch]，直到用户在确认框上点了按钮。
   * **超时按拒绝处理**：Activity 销毁或界面卡死时不能把工具线程永久挂住，
   * 而超时放行是绝对不能接受的（那等于绕过确认）。
   */
  private fun awaitUserDecision(toolName: String, args: String): Boolean {
    val latch = java.util.concurrent.CountDownLatch(1)
    // AtomicBoolean 而不是 var：主线程写、工具线程读，需要可见性保证
    // （latch 只负责唤醒，不负责把值刷出去；Kotlin 的局部变量也无法加 @Volatile）。
    val allowed = java.util.concurrent.atomic.AtomicBoolean(false)
    runOnUiThread {
      chatView.confirmDangerousTool(
          com.assistant.ui.components.independent.PendingDangerousTool(
              toolName = toolName,
              caption = describeTool(toolName),
              detail = args.orEmpty(),
          ),
      ) { choice ->
        allowed.set(choice != com.assistant.ui.components.independent.DangerousToolChoice.DENY)
        // 「始终允许」在这里落一条持久化规则。必须由本层写：只有这里知道
        // 用户点的是哪个按钮（内核的 applyDecision 就是为这个分工留的）。
        if (choice == com.assistant.ui.components.independent.DangerousToolChoice.ALLOW_ALWAYS) {
          toolSettings.applyDecision(
              toolName,
              args,
              com.tom.rv2ide.ai.tool.DangerousToolDecision.ALLOW_ALWAYS,
          )
        }
        latch.countDown()
      }
    }
    val answered = latch.await(CONFIRM_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
    if (!answered) {
      android.util.Log.w("ChatActivity", "危险工具确认超时，按拒绝处理: $toolName")
      return false
    }
    return allowed.get()
  }

  /**
   * 把工具线程的阻塞提问接到主线程提问面板。
   *
   * <p>返回 null 表示「没有答」——内核据此告诉模型不要擅自假定答案（见
   * {@code ToolSettingsPort.askUserQuestion} 的约定）。
   */
  private fun awaitUserAnswers(
      questions: List<com.tom.rv2ide.ai.tool.ToolSettingsPort.Question>
  ): List<String>? {
    val latch = java.util.concurrent.CountDownLatch(1)
    val answers = java.util.concurrent.atomic.AtomicReference<List<String>?>(null)
    runOnUiThread {
      val pending = questions.toPendingUserQuestion(java.util.UUID.randomUUID().toString())
      chatView.askQuestion(pending) { answer ->
        // 与 askUserQuestion 的分支约定对齐（见 AssistantComposePanel 的 onQuestionDone 注释）：
        // null = 取消 → 原样回 null；否则转成内核期望的字符串列表（多选逗号连接）。
        answers.set(
            if (answer == null) null
            else answer.toAnswerList())
        latch.countDown()
      }
    }
    val answered = latch.await(CONFIRM_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
    if (!answered) {
      android.util.Log.w("ChatActivity", "提问等待超时，按未作答处理")
    }
    return answers.get()
  }

  /**
   * 把工具规范名翻成一句人话，让用户看清要放行什么。
   *
   * <p>覆盖范围对齐内核里声明了 {@code needsConfirmation() == true} 的工具
   * （file_delete / file_move / shell_execute / archive / http_request / task_stop）。
   * MCP 工具名由 server 决定（动态），一律走 else——那句「执行工具 xxx」仍是可判断的。
   */
  private fun describeTool(toolName: String): String =
      when (toolName) {
        "file_delete" -> "删除文件"
        "file_move" -> "移动 / 重命名文件"
        "shell_execute" -> "执行 shell 命令"
        "archive" -> "打包 / 解压"
        "http_request" -> "发起 HTTP 请求"
        "task_stop" -> "中断正在运行的命令"
        "format_code" -> "格式化代码"
        "code_actions" -> "应用代码动作"
        "build_variant" -> "切换构建变体"
        else -> "执行工具 $toolName"
      }

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
    // 关闭 MCP 长连接：不关的话 SSE 传输会一直挂着，进程退出前都不释放。
    mcpClients.forEach { runCatching { it.close() } }
    mcpClients.clear()
    chatView.release()
    super.onDestroy()
  }

  // ---- 斜杠命令 / @ 技能 ----

  /**
   * 内核的命令定义 → 输入栏要的投影。
   *
   * <p>`acceptsArgs` 一律 true：内核的命令表里每一条都可能带参数
   * （`/mode plan`、`/model xxx`），点选后补一个空格正好接着打参数。
   */
  private fun slashCommands(): List<com.assistant.ui.components.independent.InputSlashCommand> =
      com.tom.rv2ide.ai.agent.command.SlashCommandCatalog.definitions().map { def ->
        com.assistant.ui.components.independent.InputSlashCommand(
            name = def.name,
            description = def.description,
            acceptsArgs = true,
        )
      }

  /**
   * 已安装技能 → `@` 菜单条目。
   *
   * <p>每次重组都重读磁盘：技能可以通过文件树或外部工具新增，缓存住会让新技能
   * 一直不出现（用户会以为装失败了）。
   */
  private fun skillCommands(): List<com.assistant.ui.components.independent.InputSlashCommand> =
      runCatching {
            com.tom.rv2ide.ai.tool.skill.SkillRegistry.load(File(filesDir, "skills"))
                .all()
                .map { skill ->
                  com.assistant.ui.components.independent.InputSlashCommand(
                      name = skill.name,
                      description = skill.description,
                      acceptsArgs = true,
                  )
                }
          }
          .getOrDefault(emptyList())

  /**
   * 若输入是斜杠命令则执行它，返回 true（调用方据此跳过发送）。
   *
   * <p><b>为什么命令不能直接当消息发出去</b>：`/mode plan` 发给模型只是一句普通文本，
   * 模型会回一句「好的，已切换到计划模式」——而实际模式没变。用户看到的是「命令生效了」
   * 的假象。命令必须在本地解释。
   *
   * <p>命令集与解析规则全部取自内核 `SlashCommandCatalog`，不在界面层另写一份
   * ——两份实现必然在改动后分离。
   */
  private fun tryHandleSlashCommand(text: String): Boolean {
    val parsed = com.tom.rv2ide.ai.agent.command.SlashCommandCatalog.parse(text)
    if (!parsed.isCommand) {
      return false
    }
    if (!parsed.isExecutable) {
      // 参数illegal：把原因直接说出来，别让用户以为是命令没生效。
      timeline.append(
          MessageTimeline.Role.TRACE,
          "命令参数有误：${parsed.error}\n输入 /help 查看用法。")
      chatView.forceScrollToBottom(uiScope)
      return true
    }
    when (parsed.kind) {
      com.tom.rv2ide.ai.agent.command.SlashCommandCatalog.Kind.MODE -> {
        val mode = parsed.modeOrNull()
        if (mode == null) {
          timeline.append(MessageTimeline.Role.TRACE, "未知模式：${parsed.argument}")
        } else {
          // 写偏好即可：runner 每次运行前重读（见 ConversationRunner 的 chatModeId 回调）。
          com.assistant.ui.AssistantPrefs.setChatMode(this, mode.id)
          timeline.append(MessageTimeline.Role.TRACE, "已切换到 ${mode.id} 模式")
        }
      }
      com.tom.rv2ide.ai.agent.command.SlashCommandCatalog.Kind.MODEL -> {
        val model = parsed.argument
        if (model.isBlank()) {
          timeline.append(MessageTimeline.Role.TRACE, "用法：/model <模型名>")
        } else {
          modelSettings.setModelId(model)
          timeline.append(MessageTimeline.Role.TRACE, "已切换到模型 $model")
        }
      }
      com.tom.rv2ide.ai.agent.command.SlashCommandCatalog.Kind.NEW_CONVERSATION -> {
        conversationId = ensureConversation()
        currentTitle = "新的对话"
        timeline.clear()
        reducer = EventReducer(timeline)
        timeline.append(MessageTimeline.Role.TRACE, "已新建会话")
      }
      com.tom.rv2ide.ai.agent.command.SlashCommandCatalog.Kind.CLEAR -> {
        // 只清屏、不删盘：会话文件仍在，重开能看到历史。
        // 真正的「删会话」在会话抽屉里做——两件事的破坏性不同，不该共用一条命令。
        timeline.clear()
        reducer = EventReducer(timeline)
        timeline.append(MessageTimeline.Role.TRACE, "已清空显示（会话记录仍保留在磁盘）")
      }
      com.tom.rv2ide.ai.agent.command.SlashCommandCatalog.Kind.COMPACT -> {
        // 压缩由内核在下一轮入口做（需要条目序号且结果要落盘），
        // 这里只把请求记下来并说明——不能假装「已经压缩完了」。
        timeline.append(
            MessageTimeline.Role.TRACE,
            "压缩将在下一轮对话开始前执行（内核在入口处理，需要读写会话记录）。")
      }
      com.tom.rv2ide.ai.agent.command.SlashCommandCatalog.Kind.HELP -> {
        timeline.append(
            MessageTimeline.Role.TRACE,
            com.tom.rv2ide.ai.agent.command.SlashCommandCatalog.helpText())
      }
      else -> return false
    }
    chatView.forceScrollToBottom(uiScope)
    return true
  }

  // ---- 附件 ----

  /** 打开文件选择器（多选）。MIME 通配：附件本来就是任意类型。 */
  private fun launchFilePicker() {
    filePickerLauncher?.launch(arrayOf("*/*"))
  }

  /** 打开图片选择器（多选）。 */
  private fun launchImagePicker() {
    imagePickerLauncher?.launch(
        PickVisualMediaRequest(
            ActivityResultContracts.PickVisualMedia
                .ImageOnly))
  }

  /**
   * 拍照：先建临时文件并取 FileProvider uri，再交给系统相机。
   *
   * <p>没有 FileProvider 授权时相机应用写不进我们的 cacheDir（Android 7+ 禁止
   * `file://` 直传），因此必须走 `${pkg}.fileprovider`（见 AndroidManifest 与
   * res/xml/file_paths.xml）。
   */
  private fun takePhoto() {
    // 目录必须与 res/xml/file_paths.xml 的 cache-path 对齐（camera-photos/），
    // 否则 getUriForFile 会因为「路径不在已声明范围内」直接抛 IllegalArgumentException。
    val photoDir = File(cacheDir, "camera-photos").apply { mkdirs() }
    val photoFile = File(photoDir, "camera_${System.currentTimeMillis()}.jpg")
    val uri =
        runCatching { FileProvider.getUriForFile(this, "$packageName.fileprovider", photoFile) }
            .getOrNull()
    if (uri == null) {
      android.widget.Toast.makeText(this, "无法创建拍照文件", android.widget.Toast.LENGTH_SHORT)
          .show()
      return
    }
    cameraPhotoUri = uri
    takePictureLauncher?.launch(uri)
  }

  /**
   * 把选中的 uri 落盘进工作区并挂到待发附件上。
   *
   * <p><b>串行落盘</b>：并发上传会在 `uniqueUploadName` 的「查重名 → 写入」之间竞态，
   * 两个同名文件同时进来会各自看到「不存在」然后互相覆盖。串行是最省事的正确做法
   * （附件数量本来就少，用户感知不到）。
   *
   * <p><b>上限截断要告知</b>：超过上限的静默丢弃会让用户以为「选了但没生效」，
   * 因此走 [partialUploadMessage] 提示。
   */
  private fun handlePickedUris(
      uris: List<android.net.Uri>,
      includeImageData: Boolean,
  ) {
    if (uris.isEmpty()) {
      return
    }
    // 先按剩余槽位截断，避免白读一堆文件再丢掉。
    val room =
        (MAX_PENDING_ATTACHMENTS - pendingAttachments.size)
            .coerceAtLeast(0)
    val selected = uris.take(room)
    if (selected.isEmpty()) {
      android.widget.Toast.makeText(
              this,
              maxAttachmentMessage(
                  this, MAX_PENDING_ATTACHMENTS),
              android.widget.Toast.LENGTH_LONG)
          .show()
      return
    }

    uiScope.launch {
      var ok = 0
      val failures = mutableListOf<String>()
      for (uri in selected) {
        runCatching {
              copyUriToWorkspace(
                  this@ChatActivity, uri, fileAccess, includeImageData = includeImageData)
            }
            .onSuccess { uploaded ->
              pendingAttachments.add(uploaded.toPendingAttachment())
              ok += 1
            }
            .onFailure { error ->
              failures +=
                  (error.message
                      ?: uploadFallbackError(this@ChatActivity))
            }
      }
      val skipped = uris.size - selected.size
      when {
        // 全失败：把第一条原因显示出来，用户才知道是权限、格式还是大小问题。
        ok == 0 && failures.isNotEmpty() ->
            android.widget.Toast.makeText(this@ChatActivity, failures.first(), android.widget.Toast.LENGTH_LONG)
                .show()
        skipped > 0 || failures.isNotEmpty() ->
            android.widget.Toast.makeText(
                    this@ChatActivity,
                    partialUploadMessage(this@ChatActivity, ok),
                    android.widget.Toast.LENGTH_LONG)
                .show()
        else ->
            android.widget.Toast.makeText(
                    this@ChatActivity,
                    uploadSuccessMessage(this@ChatActivity, ok),
                    android.widget.Toast.LENGTH_SHORT)
                .show()
      }
    }
  }

  /**
   * 读取附件正文，供消息气泡上点开附件时预览。
   *
   * <p>读的是落盘后的本地文件（`localPath`）；路径失效（用户清了缓存）时返回 null，
   * 界面据此提示「无法读取」而不是显示空白。
   */
  private suspend fun readAttachmentText(path: String): String? =
      kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching { File(path).takeIf { it.isFile }?.readText() }.getOrNull()
      }

  // ---- 发送 ----

  private fun send(text: String) {
    if (text.isBlank()) {
      return
    }
    // 斜杠命令在本地解释，不发给模型（见 tryHandleSlashCommand 的注释）。
    // **放在 isReady 检查之前**：/help 这类命令不需要 API Key 就能用，
    // 而被「还没配置」挡回去会让人以为命令坏了。
    if (tryHandleSlashCommand(text)) {
      return
    }
    if (!modelSettings.isReady()) {
      showSetupHint()
      return
    }
    // 每轮开始清掉**本会话**的运行内授权记忆。
    //
    // 内核不会自己调这个（ACS 是在 runInConversation 里调 beginRun 的，本 App 没有那层）。
    // 不清的话「允许一次」会退化成「本会话内永久允许」：用户对 file_delete 点过一次
    // 允许，之后模型删任何文件都不再问——与「允许一次」的语义相反。
    // 只清本会话的桶，别的会话不受影响。
    toolSettings.beginRun(conversationId)

    // 把附件快照下来：下面要清空列表（附件只属于这一条消息），
    // 而请求文本要用清理前的数据拼。
    val attachmentsForThisSend = pendingAttachments.toList()

    // 普通附件（非图片）没有多模态表示：把「文件名 + 工作区路径」清单追加到请求文本末尾，
    // 模型据此知道有哪些文件、去哪读它们（它有 file_read 工具）。
    val requestText =
        if (attachmentsForThisSend.isEmpty()) text
        else
            appendAttachmentsToRequest(
                this, text, attachmentsForThisSend)

    // 图片另走多模态 JSON（base64 直送模型，不靠模型自己读文件）。
    // 只带 image != null 的那些；普通文件在上面已并入文本。
    val images =
        attachmentsForThisSend.mapNotNull { attachment ->
          attachment.image?.let {
            com.tom.rv2ide.ai.protocol.ImageInputPayload.ImagePart(it.mimeType, it.base64Data)
          }
        }
    val attachmentsJson =
        com.assistant.engine.ConversationRunner.buildAttachmentsJson(requestText, images)

    // 附件元数据随用户消息落盘，重开会话时气泡上的缩略图靠它回放。
    val attachmentMeta =
        attachmentsForThisSend.map {
          com.tom.rv2ide.ai.protocol.AttachmentMeta(
              it.fileName, it.localPath, it.mimeType, it.sizeBytes, it.isImage)
        }

    // 本地先画用户气泡：内核也会广播 USER_MESSAGE，EventReducer 会判重
    // （末条已是同一内容的用户消息则跳过），所以这里画是安全的。
    timeline.append(MessageTimeline.Role.USER, text)
    chatView.forceScrollToBottom(uiScope)

    // 附件已交给本轮，从输入栏清掉。
    pendingAttachments.clear()

    val handle =
        runner.start(
            conversationId,
            requestText,
            attachmentsJson,
            attachmentMeta,
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
    // 说清入口在哪：不自动跳转之后，用户需要知道去哪配。
    timeline.append(
        MessageTimeline.Role.TRACE,
        "还不能对话：$problem\n点左上角菜单 → 设置，填入服务商与 API Key。",
    )
  }

  private companion object {
    /**
     * 等待用户确认/作答的上限（秒）。
     *
     * <p>超时按**拒绝**处理，不按放行——工具线程不能永久挂住，但更不能因为
     * 「等不到人」就把危险操作静默放过去。
     */
    const val CONFIRM_TIMEOUT_SECONDS = 300L
  }
}
