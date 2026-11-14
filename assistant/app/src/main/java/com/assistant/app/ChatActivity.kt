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

  /** 正在操作的消息（长按「更多」时置入）；null = 不显示菜单。 */
  private var messageForActions by mutableStateOf<com.assistant.ui.model.AgentUIMessage?>(null)

  /** 正在编辑的消息；null = 不显示编辑框。与 [messageForActions] 互斥（编辑时先关菜单）。 */
  private var messageForEdit by mutableStateOf<com.assistant.ui.model.AgentUIMessage?>(null)

  /** 待删除确认的消息；null = 不显示删除确认框。 */
  private var messageForDelete by mutableStateOf<com.assistant.ui.model.AgentUIMessage?>(null)

  /** 会话搜索词（抽屉）。改了要重算 searchHits。 */
  private var sessionSearchQuery by mutableStateOf("")

  /** 搜索命中的会话 id 集合 → 置顶/高亮用；搜索结果本身在 searchHitsOf() 里算。 */
  private var sessionSearchHits by
      mutableStateOf(listOf<com.assistant.ui.components.independent.ChatSearchHit>())

  /**
   * 置顶的会话 id。
   *
   * <p>存 SharedPreferences 而不是走内核：`ConversationStore` 只能 append
   * 已定义的条目类型（meta/title/消息），加「置顶」要么扩内核接口、要么借 title
   * 条目塞私货——两种都比在宿主侧存一份差。置顶是纯粹的界面偏好，不该进会话记录。
   *
   * <p>用 SnapshotStateList 而不是普通 Set：切换置顶要立刻重排列表。
   */
  private val pinnedSessionIds =
      androidx.compose.runtime.mutableStateListOf<String>().also { list ->
        // 启动时从磁盘读一次。放在 also 里是为了让声明与初始化在同一处。
        list.addAll(readPinnedIds())
      }

  private val uiScope = CoroutineScope(Dispatchers.Main)

  /**
   * 当前会话 id；**null 表示「草稿态」**——还没发过任何消息，磁盘上不存在这个会话。
   *
   * <p><b>为什么允许为 null</b>：会话应当**首次发送时才创建**（与 ACS 一致，见
   * `AgentOrchestrator.newConversation` 的调用时机）。此前在 onCreate 里就
   * `ensureConversation()`，于是每次冷启动都落下一个空会话文件——用户看到列表里
   * 堆着几十条没有一条消息的「新的对话」。
   *
   * <p>创建点只有一处：[ensureConversationForSend]（[send] 里落盘前调）。
   */
  private var conversationId: String? = null

  /** 工作区：模型能访问的文件范围。 */
  private lateinit var workspace: File

  /**
   * 本实例**已生效**的工作区路径。
   *
   * <p>用来在 [onResume] 里判断用户在设置页是否换了工作区。工具注册表、runner、
   * 文件树、`fileAccess` 都绑在 `workspace` 这个 File 上，逐个换新对象容易漏一处
   * 而留下「一半指向新目录」的隐蔽 bug，因此改变时整体 recreate。
   */
  private var appliedWorkspacePath: String = ""

  /** 侧边栏「文件」Tab 的状态持有者（目录树 / 展开 / 剪切板）。 */
  private lateinit var browser: WorkspaceBrowser

  /** 本次运行的 MCP 连接；Activity 销毁时逐个关闭。 */
  private val mcpClients = ArrayList<com.tom.rv2ide.ai.tool.mcp.McpClient>()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // IPv4 偏好在 AssistantApplication 里设（必须早于任何 InetAddress 使用）。

    modelSettings = ModelSettings(this)
    // 工作区来自偏好（用户可在设置里选外部目录），失效路径由 resolve 回退默认。
    workspace = WorkspacePrefs.resolve(this)
    appliedWorkspacePath = workspace.absolutePath
    browser = WorkspaceBrowser(workspace)
    conversationStore = FileConversationStore(File(filesDir, "conversations"))

    // 会话 id 的三种来源，优先级从高到低：
    //  1. pendingSessionToOpen —— 用户点了「别的项目」的会话，切工作区后要打开的就是它
    //  2. savedInstanceState —— 因切换工作区/改设置而重建，沿用原会话
    //  3. 都没有 —— 保持**草稿态**（conversationId = null），首次发送才建
    // 1 必须压过 2：recreate() 保存的 STATE_CONVERSATION_ID 是**旧**会话，
    // 照它恢复的话用户点了新会话却会看到旧的。
    val wantedId = pendingSessionToOpen ?: savedInstanceState?.getString(STATE_CONVERSATION_ID)
    pendingSessionToOpen = null
    if (wantedId != null && conversationStore.read(wantedId).isNotEmpty()) {
      conversationId = wantedId
      currentTitle =
          conversationStore.list().firstOrNull { it.id == wantedId }?.title ?: "新的对话"
    } else {
      // **不建会话**：草稿态。见 conversationId 的注释——启动就建会在列表里堆空会话。
      conversationId = null
      currentTitle = "新的对话"
    }
    // 记录本次是否来自恢复（决定下面是否回放）。用局部变量而不是再判 savedInstanceState：
    // pendingSessionToOpen 那条路径同样需要回放。
    val restoredId = conversationId?.takeIf { wantedId != null && it == wantedId }

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
    // diff 记录：接上它，工具写文件的改动才有记录、才能回滚。
    //
    // 不接的后果（此前就是）：气泡上的「Undo」按钮是个谎——它只改内存标记并把按钮变灰，
    // **文件从未恢复**，而用户看到「Undone」会以为改动已撤销。
    // FileDiffStore 落盘在 filesDir（进程重启后仍可回滚），不是 InMemoryDiffStore。
    diffStore = com.tom.rv2ide.ai.tool.FileDiffStore(File(filesDir, "diffs.jsonl"))
    val diffRecorder = com.tom.rv2ide.ai.tool.DiffRecorder(diffStore)
    val executor = ToolExecutor(registry, permissionService, diffRecorder)
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
                // **真回滚**：把文件恢复成改动前的内容。
                //
                // 曾只 markReverted（改内存标记 + 按钮变灰），而文件没动——
                // 按钮文案却写着 Undone。那是明确的谎：用户以为改动撤销了，
                // 实际上文件还是模型改过的样子。
                uiScope.launch {
                  val result =
                      kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        diffReverter.revert(diffId, revertContext())
                      }
                  if (result.isSuccess) {
                    timeline.markReverted(messageId)
                    timeline.markDiffReverted(messageId)
                    // 工作区被改回，文件树要重读——不刷新的话用户看到的是回滚前的树。
                    browser.refresh()
                    android.widget.Toast.makeText(
                            this@ChatActivity,
                            "已还原文件",
                            android.widget.Toast.LENGTH_SHORT)
                        .show()
                  } else {
                    // 失败要如实说，且**不标记为已回滚**——标记了就再也看不到这个按钮，
                    // 用户会以为成功了。原因由内核给出（记录不存在 / 已经回滚过 / 路径越界）。
                    android.widget.Toast.makeText(
                            this@ChatActivity,
                            "还原失败：${result.message}",
                            android.widget.Toast.LENGTH_LONG)
                        .show()
                  }
                }
              }

              override fun onMoreClick(message: com.assistant.ui.model.AgentUIMessage) {
                // 由一个 Compose 状态驱动弹窗（见 setContent 里的 MessageActionDialog）。
                messageForActions = message
              }
            },
        )

    // 重建后回放当前会话，让用户看到的还是同一个对话（见上面恢复 conversationId）。
    if (restoredId != null) {
      replayCurrentSession()
    }

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

    // 推理强度：每次运行前读一次偏好，用户在输入栏改档位对下一次发送立即生效。
    // 内核原生支持这个参数（AgentSession.run 的 reasoningEffort），此前恒传 null，
    // 于是输入栏那个选择器点了没有任何效果。
    runner.setReasoningEffortProvider { toKernelReasoningEffort(reasoningEffortOf()) }

    // 拒绝恢复开关:每次运行前读一次,设置里关闭后下一次发送即回退「只跑一次」。
    runner.setRefusalRecoveryEnabledProvider { com.assistant.ui.AssistantPrefs.refusalRecoveryEnabled(this) }
    runner.setRefusalRecoveryMaxAttemptsProvider {
      com.assistant.ui.AssistantPrefs.refusalRecoveryMaxAttempts(this)
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

            // 「新建会话」时的目录选择框读的是 Activity 字段（见 newChatWorkspaces）：
            // 它既要能被 Compose 读到、又要能被 newChat() 写入，因此做成字段。
            @Suppress("UNUSED_EXPRESSION") newChatWorkspaces

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
                // ---- 长粘贴 ----
                // 不接的话：输入框被整段粘贴撑爆，且发出去的是 [Pasted#1] 这种标记
                // （展开动作在 send() 里做）。
                pastedTexts = pastedTexts,
                onStashPaste = { text ->
                  val id = pasteSeq++
                  pastedTexts.add(
                      com.assistant.ui.components.independent.PastedText(id = id, text = text))
                  com.assistant.ui.components.independent.PastedText.placeholderFor(id)
                },
                onRemovePaste = { index ->
                  if (index in pastedTexts.indices) pastedTexts.removeAt(index)
                },
                // ---- 排队 / 强制停止 ----
                // 忙碌时输入栏提示「回车发送将自动排队」，队列必须真的有：没有的话
                // 那条提示就是假承诺，用户按了回车消息会静默消失。
                queuedRequests = queuedRequests,
                onRemoveQueued = { id -> queuedRequests.removeAll { it.id == id } },
                onMoveQueued = { from, to ->
                  // 上移/下移：交换相邻两项。边界由组件保证（它不会越界调）。
                  if (from in queuedRequests.indices && to in queuedRequests.indices) {
                    val item = queuedRequests.removeAt(from)
                    queuedRequests.add(to, item)
                  }
                },
                onEditQueued = { req ->
                  // 取回输入框编辑：从队列移除并填进输入框，而不是就地改
                  // ——用户要的是「改完再发」，改完立刻重发会与他的编辑动作打架。
                  queuedRequests.removeAll { it.id == req.id }
                  pendingInput = req.request
                },
                // 插队：移到队首，下一轮结束就先发它。
                onInterjectQueued = { id ->
                  val index = queuedRequests.indexOfFirst { it.id == id }
                  if (index > 0) {
                    val item = queuedRequests.removeAt(index)
                    queuedRequests.add(0, item)
                  }
                },
                // 长按停止键 = 强制打断。本 App 没有「软打断」（区分「停当前步」与
                // 「停整轮」需要内核支持中途取消单步），因此两者是同一个动作。
                canForceStop = busy,
                onForceStop = { runner.cancel() },
                // ---- 模型芯片 ----
                // 不传的话点开是一片空白（面板读的是这两个参数）。
                currentProvider = providerTarget(),
                providers = providerTargets(),
                onSelectModel = { providerId, model ->
                  modelSettings.setProviderId(providerId)
                  modelSettings.setModelId(model)
                  // 触发重组：顶栏副标题与芯片都显示模型名，改了要立刻反映。
                  headerRefresh++
                },
                // 对话模式：初值来自偏好（设置页/斜杠命令改的就是它），
                // 在输入栏切换后写回——不写回则每次进页面都回到默认。
                initialMode = chatModeOf(),
                onModeChange = { newMode ->
                  // 反向映射必须显式写：内核只认 chat/plan/agent/control，
                  // 直接 name.lowercase() 会写出 "build"，内核按未知值回退成默认
                  // ——用户切了档却没有任何变化。
                  val kernelId =
                      when (newMode) {
                        com.assistant.ui.compat.AgentMode.PLAN -> "plan"
                        com.assistant.ui.compat.AgentMode.BUILD -> "chat"
                        com.assistant.ui.compat.AgentMode.AUTO -> "agent"
                      }
                  com.assistant.ui.AssistantPrefs.setChatMode(this@ChatActivity, kernelId)
                },
                // 推理强度：初值来自偏好，改动落盘并即时进请求。
                initialReasoning = reasoningEffortOf(),
                onReasoningChange = { effort ->
                  com.assistant.ui.AssistantPrefs.setReasoningEffort(
                      this@ChatActivity, effort.name)
                },
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
                // 点占用环打开详情（含手动压缩入口）。
                // 只在窗口已配置时传：未配置时组件也不渲染那颗按钮，传了是死代码。
                onContextUsageClick =
                    if (contextWindowTokens > 0) {
                      { showContextUsage = true }
                    } else {
                      null
                    },
                onOpenSessions = {
                  sessions = loadSessions()
                  // 文件树可能被模型改动过（工具写了文件），每次打开重读一遍。
                  browser.refresh()
                  showDrawer = true
                },
                onNewChat = { newChat() },
                drawer = {
                  AssistantSessionsDrawer(
                      sessions = sessions,
                      currentSessionId = conversationId,
                      workspace = workspace,
                      browser = browser,
                      // ---- 搜索 / 置顶 ----
                      // 搜索在宿主侧做（渲染层拿不到会话正文），结果以 hits 回传。
                      searchQuery = sessionSearchQuery,
                      onSearchQueryChange = { q ->
                        sessionSearchQuery = q
                        searchSessions(q)
                      },
                      pinnedSessionIds = pinnedSessionIds.toSet(),
                      searchHits = sessionSearchHits,
                      onSelect = { row ->
                        openSession(row)
                        showDrawer = false
                      },
                      onNewSession = { newChat() },
                      onDelete = { row ->
                        try {
                          conversationStore.delete(row.id)
                        } catch (e: Exception) {
                          android.util.Log.w("ChatActivity", "删除会话失败: ${row.id}", e)
                        }
                        if (row.id == conversationId) {
                          // 删的是当前会话 → 回到草稿态（不急着再建一个空会话）。
                          conversationId = null
                          currentTitle = "新的对话"
                          timeline.clear()
                          reducer = EventReducer(timeline)
                          showDrawer = false
                        }
                        sessions = loadSessions()
                        if (sessions.isEmpty()) {
                          conversationId = null
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
                      // 置顶：存宿主 SharedPreferences（见 togglePin 的注释）。
                      onTogglePin = { row -> togglePin(row.id) },
                      onExport = { row -> exportSession(row) },
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

            // ---- 新建会话：选择工作区 ----
            // 多个候选目录时出现（见 newChat 的注释）。选中目录后切到草稿态。
            newChatWorkspaces?.let { paths ->
              com.assistant.ui.components.independent.NewSessionWorkspaceDialog(
                  paths = paths,
                  currentPath = workspace.absolutePath,
                  onPick = { path ->
                    newChatWorkspaces = null
                    startNewChatIn(File(path))
                  },
                  onDismiss = { newChatWorkspaces = null },
              )
            }

            // ---- 上下文占用详情 ----
            if (showContextUsage) {
              com.assistant.ui.components.independent.ContextUsageDialog(
                  usedTokens = contextUsedTokens,
                  windowTokens = contextWindowTokens,
                  onCompact = {
                    // 压缩由内核在下一轮入口做（需要条目序号、结果要落盘），
                    // 这里只如实记一句，不假装已经压完。
                    timeline.append(
                        MessageTimeline.Role.TRACE,
                        "压缩将在下一轮对话开始前执行（内核在入口处理）。")
                    chatView.forceScrollToBottom(uiScope)
                  },
                  onDismiss = { showContextUsage = false },
              )
            }

            // ---- 消息操作菜单 ----
            // 放在 AssistantChatScreen 之外、同一个 setContent 作用域内：
            // 它们是模态弹窗，不属于对话页的布局，但必须能读到同一份状态。
            messageForActions?.let { target ->
              com.assistant.ui.components.independent.MessageActionDialog(
                  message = target,
                  onDismiss = { messageForActions = null },
                  onAction = { action ->
                    when (action) {
                      com.assistant.ui.components.independent.MessageAction.COPY -> {
                        val cm =
                            getSystemService(android.content.Context.CLIPBOARD_SERVICE) as
                                android.content.ClipboardManager
                        cm.setPrimaryClip(
                            android.content.ClipData.newPlainText("assistant", target.content))
                        android.widget.Toast.makeText(
                                this@ChatActivity, "已复制", android.widget.Toast.LENGTH_SHORT)
                            .show()
                      }
                      com.assistant.ui.components.independent.MessageAction.EDIT -> {
                        messageForActions = null
                        messageForEdit = target
                      }
                      com.assistant.ui.components.independent.MessageAction.REGENERATE -> {
                        messageForActions = null
                        regenerate()
                      }
                      com.assistant.ui.components.independent.MessageAction.DELETE -> {
                        messageForActions = null
                        messageForDelete = target
                      }
                    }
                  },
              )
            }

            messageForEdit?.let { target ->
              com.assistant.ui.components.independent.EditMessageContentDialog(
                  initialText = target.content,
                  onDismiss = { messageForEdit = null },
                  onConfirm = { newText ->
                    messageForEdit = null
                    // 编辑用户消息 = 改写后重发；编辑助手回答没有重发生效的对象，
                    // 只把界面上的文本改掉（并如实提示，不假装模型重答了）。
                    if (target.role == com.assistant.ui.model.MessageRole.USER) {
                      send(newText)
                    } else {
                      // 界面上改掉文本。**如实提示不会重答**——用户改的是显示，
                      // 磁盘上的会话记录没变，重开会话还是原文。
                      timeline.update(target.id.toLongOrNull() ?: return@EditMessageContentDialog, newText)
                      android.widget.Toast.makeText(
                              this@ChatActivity,
                              "已修改显示内容（不会重新生成回答）",
                              android.widget.Toast.LENGTH_LONG)
                          .show()
                    }
                  },
              )
            }

            // ---- 删除确认 ----
            messageForDelete?.let { target ->
              androidx.compose.material3.AlertDialog(
                  onDismissRequest = { messageForDelete = null },
                  title = { androidx.compose.material3.Text("删除这条消息？") },
                  text = {
                    androidx.compose.material3.Text(
                        "将从会话记录中删除（含关联的工具结果），后续对话不再携带这条消息。此操作不可撤销。")
                  },
                  confirmButton = {
                    androidx.compose.material3.TextButton(
                        onClick = {
                          messageForDelete = null
                          deleteMessage(target)
                        }) {
                      androidx.compose.material3.Text("删除")
                    }
                  },
                  dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { messageForDelete = null }) {
                      androidx.compose.material3.Text("取消")
                    }
                  },
              )
            }
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
   * 保存当前会话 id，供 recreate / 进程重建后恢复（见 onCreate 的恢复逻辑）。
   *
   * <p>还没被消费的 [pendingSessionToOpen] 单独带一份：它是「切工作区时要打开的
   * 那条会话」，与当前会话不同（见 [openSession]）。
   */
  override fun onSaveInstanceState(outState: Bundle) {
    super.onSaveInstanceState(outState)
    // 草稿态（null）不写：重建后会重新落在草稿态，不需要一条「空 id」记录。
    conversationId?.let { outState.putString(STATE_CONVERSATION_ID, it) }
    pendingSessionToOpen?.let { outState.putString(STATE_PENDING_SESSION, it) }
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
    maybeRebuildForWorkspaceChange()
  }

  /**
   * 工作区变了就重建 Activity。
   *
   * <p><b>为什么不就地换</b>：工作区被至少五处持有——工具注册表（ToolSetFactory 把
   * 路径烘进文件工具）、[ConversationRunner]、[browser]、`fileAccess`、以及
   * `revertContext()`。逐个替换要求每处都提供「换根」入口，漏一处就会出现
   * 「模型写到了新目录、而文件树还显示旧目录」这类难以察觉的错位。
   * recreate 是唯一能保证一致的做法（历史仍在磁盘，重建后照常回放）。
   *
   * <p><b>忙碌时不打断</b>：正在跑的一轮绑着当前工作区，中途重建会把它的输出丢掉。
   * 提示用户等本轮结束后自动生效——`onFinished` 里补一次检查。
   */
  private fun maybeRebuildForWorkspaceChange() {
    val resolved = WorkspacePrefs.resolve(this).absolutePath
    if (resolved == appliedWorkspacePath) {
      return
    }
    if (runner.isRunning()) {
      workspaceChangePending = true
      return
    }
    recreate()
  }

  /** 顶栏重算触发器（见 [onResume]）。 */
  private var headerRefresh by mutableStateOf(0)

  /** 因本轮仍在运行而推迟的工作区切换（见 [maybeRebuildForWorkspaceChange]）。 */
  private var workspaceChangePending = false

  /**
   * 「新建会话」的目录选择框：非 null 时显示，元素是候选目录绝对路径。
   *
   * <p>做成 Activity 字段而不是 Compose 内的 `remember`：写入方在 [newChat]（普通
   * 方法），读取方在 Compose 树里，局部状态两边够不着。用 Compose 状态是为了让
   * 赋值能触发重组。
   */
  private var newChatWorkspaces by mutableStateOf<List<String>?>(null)

  /**
   * 「切工作区时必须打开这条会话」的待办（见 [openSession] / [recreateWithSession]）。
   *
   * <p>**不能用 recreate() + savedInstanceState 的当前会话 id 代替**：那保存的是
   * 旧会话，用户点的是新会话却会看到旧的。recreate 会换掉 Activity 实例，因此这个值
   * 必须经 [onSaveInstanceState] 传下去（见那边）。
   */
  private var pendingSessionToOpen: String? = null

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
              // **必须带会话自己的工作区**（来自 meta 的 cwd）：抽屉按它把会话
              // 分到各个工作区文件夹。统一填当前工作区的话，历史会话会全部挤进
              // 一个文件夹、标签还跟着当前目录走，看起来像「会话被迁移了」。
              workspacePath = s.cwd,
          )
        }
      } catch (e: Exception) {
        android.util.Log.w("ChatActivity", "会话列表读取失败", e)
        emptyList()
      }

  /** 切到另一个会话：必要时先切工作区，再清空列表并从磁盘回放。 */
  private fun openSession(row: com.assistant.ui.SessionRow) {
    // 会话属于别的工作区时，工作区要跟着切（ACS 同语义：FloatingAssistantView 的
    // doOpenConversation 会用会话的 cwd 调 setWorkspace）。
    //
    // **不切的话分类就是误导**：抽屉里明明把会话归到了 A 目录，点开却在 B 目录的
    // 上下文里继续跑——模型写文件会写错地方，而用户以为自己打开的是 A 的会话。
    //
    // 走「改偏好 + recreate」：工具注册表/runner/文件树都绑在工作区上，和设置页改
    // 工作区同一条路径才不会漏一处。recreate 前把目标会话记进 pendingSessionToOpen
    // （经 onSaveInstanceState 传下去），否则重建后恢复的是**旧**会话。
    if (row.workspacePath.isNotBlank() && row.workspacePath != workspace.absolutePath) {
      val target = File(row.workspacePath)
      if (target.isDirectory) {
        if (runner.isRunning()) {
          // 正在跑的一轮绑着当前工作区，中途重建会丢掉它的输出。
          android.widget.Toast.makeText(
                  this,
                  "当前对话还在运行，等它结束再打开别的项目的会话",
                  android.widget.Toast.LENGTH_LONG)
              .show()
          return
        }
        WorkspacePrefs.save(this, target)
        // recreate 会重新读偏好；两步之间不留「偏好改了但实例还是旧工作区」的空档。
        recreateWithSession(row.id)
        return
      }
      // 目录不存在（外部存储被拔/目录被删）：如实说，并留在本工作区。
      android.widget.Toast.makeText(
              this, "该会话的工作区已不存在：${row.workspacePath}", android.widget.Toast.LENGTH_LONG)
          .show()
    }
    conversationId = row.id
    currentTitle = row.title
    timeline.clear()
    reducer = EventReducer(timeline)
    replayEntries(row.id)?.let { error ->
      timeline.append(MessageTimeline.Role.TRACE, "会话回放失败：$error")
    }
    chatView.forceScrollToBottom(uiScope)
  }

  /**
   * 新建对话。
   *
   * <p><b>与 ACS 对齐的两点</b>（见 `FloatingAssistantView.startNewConversation`）：
   *
   * 1. **不立刻落盘**，只把界面切到草稿态。会话在首次发送时才创建——否则每点一次
   *    「+」就多一个空会话，列表很快被「新的对话」淹没（本 App 此前就是这样）。
   * 2. **多工作区时先让用户选目录**：会话绑定哪个工作区决定了模型能改哪些文件，
   *    猜错会写错地方。只有一个候选（就是当前工作区）时不弹窗，直接用它。
   *
   * <p>候选目录 = 当前工作区 + 已有会话出现过的工作区（去重）。这与抽屉里文件夹的
   * 来源一致，用户看到的就是他真能用到的那些。
   */
  private fun newChat() {
    clearToDraft()
    val candidates = selectableWorkspaces()
    if (candidates.size <= 1) {
      // 只有当前工作区：不用问，直接切成草稿（目录不变）。
      return
    }
    newChatWorkspaces = candidates.map { it.absolutePath }
  }

  /**
   * 重建，并让重建后的实例打开 [sessionId]。
   *
   * <p>直接 `recreate()` 恢复的是 savedInstanceState 里的**当前**会话，点开别的项目
   * 的会话时会变成「点了新的却停在旧的」。目标 id 经 [pendingSessionToOpen] 走
   * onSaveInstanceState 传下去（recreate 会换掉 Activity 实例）。
   */
  private fun recreateWithSession(sessionId: String) {
    pendingSessionToOpen = sessionId
    recreate()
  }

  /**
   * 回到草稿态：清空界面，不建会话。
   *
   * <p>**不重置工作区**：用户只是要开一条新会话，不是要换项目。
   */
  private fun clearToDraft() {
    conversationId = null
    currentTitle = "新的对话"
    timeline.clear()
    reducer = EventReducer(timeline)
    chatView.forceScrollToBottom(uiScope)
  }

  /**
   * 可选的会话工作区：当前工作区优先，其后是历史会话用过且仍存在的目录。
   *
   * <p>过滤掉不存在的目录：选了也访问不到，列出来只会让用户白点一次。
   */
  private fun selectableWorkspaces(): List<File> {
    val current = workspace
    val seen = linkedSetOf(current.absolutePath)
    val result = mutableListOf(current)
    for (row in loadSessions()) {
      val path = row.workspacePath
      if (path.isBlank() || !seen.add(path)) {
        continue
      }
      val dir = File(path)
      if (dir.isDirectory) {
        result += dir
      }
    }
    return result
  }

  /**
   * 在指定目录开一条新会话（草稿态）。
   *
   * <p>目标目录与当前工作区不同时走「改偏好 + recreate」——与设置页切换工作区、
   * 从抽屉打开别的项目的会话是同一条路径，工具注册表/runner/文件树才会一起换。
   * 重建时不带会话 id（[pendingSessionToOpen] 保持 null），于是 onCreate 落在草稿态。
   */
  private fun startNewChatIn(target: File) {
    if (target.absolutePath == workspace.absolutePath) {
      clearToDraft()
      return
    }
    if (runner.isRunning()) {
      android.widget.Toast.makeText(
              this, "当前对话还在运行，等它结束再切到别的项目", android.widget.Toast.LENGTH_LONG)
          .show()
      return
    }
    WorkspacePrefs.save(this, target)
    // 目标会话 id 不设：重建后落到草稿态（见 onCreate 的 wantedId 分支）。
    pendingSessionToOpen = null
    recreate()
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
    val id = modelSettings.providerId()
    // **查记录表，不是预设表**：预设只是「新建时的填充模板」，用户真正的配置在
    // ProviderRecords。这里曾与 providerTarget() 犯同一个错——用「自定义」建的记录
    // 在预设表里找不到，于是顶栏显示「未配置 · <模型名>」，而模型其实是好的。
    val record = modelSettings.providerRecords().find(id)
    val label =
        record?.label?.takeIf { it.isNotBlank() }
            ?: record?.baseUrl?.takeIf { it.isNotBlank() }
            ?: ProviderPresets.find(id)?.label
            ?: "未配置"
    return "$label · ${modelSettings.modelId()}"
  }

  /** 顶栏副标题前的服务商 logo。 */
  /**
   * 偏好里的推理强度档位名 → 输入栏枚举。
   *
   * <p>存的是枚举名（渲染层不认识内核词表），空串或未知值回落到 DEFAULT。
   */
  private fun reasoningEffortOf(): com.assistant.ui.compat.ReasoningEffort {
    val name = com.assistant.ui.AssistantPrefs.reasoningEffort(this)
    return com.assistant.ui.compat.ReasoningEffort.entries.firstOrNull { it.name == name }
        ?: com.assistant.ui.compat.ReasoningEffort.DEFAULT
  }

  /**
   * 输入栏枚举 → 内核要的词。
   *
   * <p>两套词表的差别是真的：内核只有 `off` / `low` / `medium` / `high`，
   * 而输入栏有 7 档（含 `DEFAULT` / `XHIGH` / `MAX`）。必须显式映射：
   * - DEFAULT → 空串（让内核用服务商默认，这是「不干预」的正确表达，不是 off）
   * - NONE → "off"
   * - XHIGH / MAX 都没有对应的内核档位，**上取到 high** 而不是静默降成默认
   *   ——用户选「更强」时不该被悄悄改弱
   */
  private fun toKernelReasoningEffort(effort: com.assistant.ui.compat.ReasoningEffort): String =
      when (effort) {
        com.assistant.ui.compat.ReasoningEffort.DEFAULT -> ""
        com.assistant.ui.compat.ReasoningEffort.NONE -> "off"
        com.assistant.ui.compat.ReasoningEffort.LOW -> "low"
        com.assistant.ui.compat.ReasoningEffort.MEDIUM -> "medium"
        com.assistant.ui.compat.ReasoningEffort.HIGH -> "high"
        com.assistant.ui.compat.ReasoningEffort.XHIGH -> "high"
        com.assistant.ui.compat.ReasoningEffort.MAX -> "high"
      }

  /**
   * 偏好里的对话模式 id → 输入栏的三档枚举。
   *
   * <p><b>两套词汇不是一一对应</b>，必须映射：
   * 内核/设置页有 4 档（`chat` / `plan` / `agent` / `control`），
   * 而输入栏的芯片只有 3 个（`BUILD` / `PLAN` / `AUTO`）。
   *
   * <p>映射取舍：
   * - `plan` → PLAN（同名同义）
   * - `chat` → BUILD：两者都是「不改文件」的保守档，语义最接近
   * - `agent` / `control` → AUTO：都是「可执行」；两者在权限门（是否逐次确认）上
   *   的差别由设置页的权限模式单独管，不该挤进这个三档芯片
   *
   * <p>反向映射见 [onModeChange]：芯片切档写回时用 `agent` / `plan` / `chat`，
   * 不写 `control`——`control` 与 `agent` 的区别已在权限设置里表达，
   * 芯片无法表达它，写回它会丢掉用户原本的设置。
   */
  private fun chatModeOf(): com.assistant.ui.compat.AgentMode =
      when (com.assistant.ui.AssistantPrefs.chatMode(this)) {
        "plan" -> com.assistant.ui.compat.AgentMode.PLAN
        "chat" -> com.assistant.ui.compat.AgentMode.BUILD
        // agent / control / 未知值 → AUTO（保守：至少是可执行档）
        else -> com.assistant.ui.compat.AgentMode.AUTO
      }

  /**
   * 当前服务商（含它的模型清单），投影成渲染层的形状。
   *
   * <p><b>必须查 ProviderRecords 而不是 ProviderPresets</b>：用户真正的配置存在记录表里，
   * 预设只是「新建时的填充模板」。此前这里查预设表，用户用「自定义」建的记录在预设里
   * 找不到 → 返回 null → 输入栏的模型芯片没有服务商 → 点开是一片空白
   * （用户实测「点击编辑框的模型图标是空的」）。
   */
  private fun providerTarget(): com.assistant.ui.components.independent.ProviderSelectionTarget? {
    val id = modelSettings.providerId()
    val record = modelSettings.providerRecords().find(id)
    if (record != null) {
      return com.assistant.ui.components.independent.ProviderSelectionTarget(
          id = record.id,
          name = record.label.ifBlank { record.baseUrl },
          models = record.slotModels.filter { it.isNotBlank() },
          protocol = record.protocolType.toUiProtocol(),
          effectiveModel = modelSettings.modelId(),
      )
    }
    // 记录里没有（用户还没配过、或配置被清）时退化到预设表——至少能显示个名字，
    // 而不是让整个芯片空白。
    val preset = ProviderPresets.find(id) ?: return null
    return com.assistant.ui.components.independent.ProviderSelectionTarget(
        id = preset.id,
        name = preset.label,
        models = preset.models,
        protocol = preset.protocolType.toUiProtocol(),
        effectiveModel = modelSettings.modelId(),
    )
  }

  /**
   * 全部可选服务商（供模型芯片的弹出面板）。
   *
   * <p>只给**可用**的（baseUrl 与主模型都非空）：把残缺的配置也列出来，
   * 用户选中后发不出消息，还得回去查为什么。
   */
  private fun providerTargets():
      List<com.assistant.ui.components.independent.ProviderSelectionTarget> =
      modelSettings
          .providerRecords()
          .all()
          .filter { it.isUsable() }
          .map { record ->
            com.assistant.ui.components.independent.ProviderSelectionTarget(
                id = record.id,
                name = record.label.ifBlank { record.baseUrl },
                models = record.slotModels.filter { it.isNotBlank() },
                protocol = record.protocolType.toUiProtocol(),
                effectiveModel = modelSettings.modelId(),
            )
          }

  /** 协议类型 → 渲染层枚举（渲染层不依赖内核，因此要显式映射）。 */
  private fun com.tom.rv2ide.ai.protocol.ModelProtocolType.toUiProtocol() =
      if (this == com.tom.rv2ide.ai.protocol.ModelProtocolType.ANTHROPIC_MESSAGES) {
        com.assistant.ui.components.independent.ProviderProtocol.ANTHROPIC_MESSAGES
      } else {
        com.assistant.ui.components.independent.ProviderProtocol.OPENAI_COMPATIBLE
      }

  override fun onDestroy() {
    runner.cancel()
    // 关闭 MCP 长连接：不关的话 SSE 传输会一直挂着，进程退出前都不释放。
    mcpClients.forEach { runCatching { it.close() } }
    mcpClients.clear()
    chatView.release()
    super.onDestroy()
  }

  /**
   * 把本轮改动汇总卡的内容算出来后填进卡片。
   *
   * <p><b>为什么必须做</b>：卡片插入时标的是 `computing=true`，内容由调用方随后回填。
   * 不回填的话卡片一直停在「计算中」——用户看不到改了什么、增减多少行，
   * 而且回滚按钮的可用性也依赖这份内容。
   *
   * <p>在 IO 线程算：diff 要对整个文件做行级比对，大文件上很贵。
   */
  private fun fillRunDiffs() {
    val cards = reducer.lastRunDiffCards
    if (cards.isEmpty()) {
      return
    }
    uiScope.launch {
      for ((cardId, diffId) in cards) {
        val result =
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
              val record = runCatching { diffStore.findById(diffId) }.getOrNull()
              if (record == null) {
                null
              } else {
                // 用自带的纯 JVM 行级 diff（见 AssistantDiff 的注释：仓库里那个字符级
                // 实现会为 3000 行文件分配 9e4×9e4 的 int 矩阵，先 OOM）。
                com.assistant.ui.model.AssistantDiffBuilder.build(
                    oldContent = record.oldContent.orEmpty(),
                    newContent = record.newContent.orEmpty(),
                    oldExists = record.isOldExists,
                )
              }
            }
        // record 找不到时传 null：卡片会退成「记录已过期」而不是永远转圈——
        // 转圈会让用户以为还在算，实际永远不会出结果。
        timeline.updateDiff(cardId, result)
      }
    }
  }

  // ---- 导出会话 ----

  /**
   * 把会话导出成纯文本并交给系统分享。
   *
   * <p><b>为什么导成文本而不是原始 JSONL</b>：`.jsonl` 每行一个事件对象，用户打开
   * 看到的是 `{"type":"assistant","uuid":...}`——那不是「导出聊天记录」。转成
   * 「谁说了什么」的可读文本才符合预期。
   *
   * <p>写进 `cacheDir/exports/`（与 `file_paths.xml` 的声明对齐）：分享是跨应用读文件，
   * Android 7+ 必须经 FileProvider 换成 content://；放 cache 而不是 filesDir，
   * 因为这些导出文件是一次性的，被系统清理掉正合适。
   */
  private fun exportSession(row: SessionRow) {
    uiScope.launch {
      val result =
          kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
              val entries = conversationStore.read(row.id)
              val text = buildString {
                append("# ").append(row.title).append("\n\n")
                for (loc in entries) {
                  when (val e = loc.entry) {
                    is com.tom.rv2ide.ai.agent.conversation.UserMessageEntry ->
                        append("## 我\n\n").append(e.content).append("\n\n")
                    is com.tom.rv2ide.ai.agent.conversation.AssistantMessageEntry ->
                        append("## 助手\n\n").append(e.content).append("\n\n")
                    else -> Unit
                  }
                }
              }
              val dir = File(cacheDir, "exports").apply { mkdirs() }
              // 文件名用标题（已过滤路径字符）而不是会话 id：用户分享出去后
              // 文件名要能认出来是哪个会话。
              val safeName = row.title.replace(Regex("""[\\/:*?"<>|\s]+"""), "_").take(40)
              val file = File(dir, "${safeName.ifBlank { "session" }}.md")
              file.writeText(text)
              file
            }
          }
      val file = result.getOrNull()
      if (file == null) {
        android.widget.Toast.makeText(
                this@ChatActivity, "导出失败：无法读取会话", android.widget.Toast.LENGTH_LONG)
            .show()
        return@launch
      }
      shareExportedFile(file)
    }
  }

  /** 把导出文件交给系统分享面板。 */
  private fun shareExportedFile(file: File) {
    val uri =
        runCatching { FileProvider.getUriForFile(this, "$packageName.fileprovider", file) }
            .getOrNull()
    if (uri == null) {
      android.widget.Toast.makeText(this, "导出失败：无法生成分享链接", android.widget.Toast.LENGTH_LONG)
          .show()
      return
    }
    val intent =
        android.content.Intent(android.content.Intent.ACTION_SEND).apply {
          type = "text/markdown"
          putExtra(android.content.Intent.EXTRA_STREAM, uri)
          // 必须显式授权：不授权时接收方拿到 uri 也读不到内容（表现为「分享了空文件」）。
          addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    runCatching {
          startActivity(android.content.Intent.createChooser(intent, "导出会话"))
        }
        .onFailure {
          android.widget.Toast.makeText(this, "没有可用的分享应用", android.widget.Toast.LENGTH_SHORT)
              .show()
        }
  }

  // ---- 回滚 ----

  /**
   * 回滚用的工具上下文。
   *
   * <p>回滚要校验目标路径**仍在工作区内**（否则一条被篡改的 diff 记录就能把文件
   * 写到工作区外）。`DiffReverter` 因此要求传 context；为 null 时它会直接拒绝执行。
   *
   * <p>只填路径相关字段：回滚不执行工具、不读权限、不弹窗，其余字段无意义。
   */
  private fun revertContext(): com.tom.rv2ide.ai.tool.ToolContext =
      com.tom.rv2ide.ai.tool.ToolContext.builder()
          .homePath(workspace.absolutePath)
          .openProjectPath(workspace.absolutePath)
          .conversationId(conversationId.orEmpty())
          .settings(toolSettings)
          .build()

  // ---- 排队 ----

  /**
   * 取队首发出。
   *
   * <p>**先移除再发**：`send` 会重新走一遍完整流程（包括「忙碌时再入队」的判断），
   * 若不移除，刚发出的这条会因为 runner 状态还是旧值而被又塞回队里，造成死循环。
   *
   * <p>只发一条：`send` 之后 runner 又忙了，剩下的等下一轮 onFinished 再取——这样
   * 顺序有保证，也不会在一条内递归地把整队发完（那样会绕过排队语义）。
   */
  private fun drainQueue() {
    val next = queuedRequests.firstOrNull() ?: return
    queuedRequests.removeAt(0)
    send(next.request)
  }

  // ---- 会话搜索 / 置顶 ----

  /** 每个命中片段的最大长度；太长会把列表项撑高。 */
  private val SNIPPET_MAX = 80

  /**
   * 等待发送的排队消息。
   *
   * <p>**为什么必须有**：输入栏在忙碌时会提示「回车发送将自动排队」。没有这个队列，
   * 那条提示就是假的——用户按了回车，消息被丢弃，界面上什么都不会发生。
   * 要么实现队列，要么改掉那句提示；前者是用户真正需要的（长回答期间想接着说）。
   */
  private val queuedRequests =
      androidx.compose.runtime.mutableStateListOf<
          com.assistant.ui.components.independent.QueuedRequest>()

  /** 排队消息的自增 id（组件用它做 key 与增删标识）。 */
  private var queuedSeq = 0

  /**
   * 长粘贴的暂存区。
   *
   * <p>**不接的后果不只是「输入框被撑爆」**：输入框里的 `[Pasted#1]` 只是标记，
   * 发送时必须展开回原文，否则模型收到的是一串标记而不是内容。
   * 组件提供了 `expandPastePlaceholders`，展开动作在宿主发送前完成。
   */
  private val pastedTexts =
      androidx.compose.runtime.mutableStateListOf<
          com.assistant.ui.components.independent.PastedText>()

  /** 粘贴块的自增 id。 */
  private var pasteSeq = 1

  /** 是否显示上下文占用详情（点占用环打开）。 */
  private var showContextUsage by mutableStateOf(false)

  /** 文件改动记录（落盘）。回滚按钮靠它找到旧内容。 */
  private lateinit var diffStore: com.tom.rv2ide.ai.tool.FileDiffStore

  /** 回滚执行器；用 [diffStore] 构造。 */
  private val diffReverter by lazy { com.tom.rv2ide.ai.tool.DiffReverter(diffStore) }

  /**
   * 扫会话记录找匹配。
   *
   * <p><b>在 IO 线程做</b>：每个会话要整读一遍 JSONL，会话多时是几十 MB 的顺序读。
   * 在主线程做会明显卡住抽屉输入。
   *
   * <p><b>匹配标题与正文</b>：只匹配标题的话，「我记得说过某个词」就搜不到——
   * 那正是用户最需要搜索的场景。
   */
  private fun searchSessions(query: String) {
    val q = query.trim()
    if (q.isEmpty()) {
      sessionSearchHits = emptyList()
      return
    }
    val targets = loadSessions()
    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
      val hits = mutableListOf<com.assistant.ui.components.independent.ChatSearchHit>()
      for (row in targets) {
        // 会话记录读失败（文件被外部删了等）不该让整次搜索失败：跳过它继续。
        val entries =
            runCatching { conversationStore.read(row.id) }.getOrNull() ?: continue
        // 标题命中：给一条以标题为内容的命中，用户能看出「是这个会话」。
        if (row.title.contains(q, ignoreCase = true)) {
          hits +=
              com.assistant.ui.components.independent.ChatSearchHit(
                  sessionId = row.id,
                  sessionTitle = row.title,
                  messageId = "title",
                  snippet = row.title,
                  timestamp = row.updatedAt,
              )
        }
        for (loc in entries) {
          val text =
              when (val e = loc.entry) {
                is com.tom.rv2ide.ai.agent.conversation.UserMessageEntry -> e.content
                is com.tom.rv2ide.ai.agent.conversation.AssistantMessageEntry -> e.content
                else -> null
              } ?: continue
          if (text.isBlank() || !text.contains(q, ignoreCase = true)) {
            continue
          }
          hits +=
              com.assistant.ui.components.independent.ChatSearchHit(
                  sessionId = row.id,
                  sessionTitle = row.title,
                  messageId = loc.ordinal.toString(),
                  snippet = snippetAround(text, q),
                  timestamp = row.updatedAt,
              )
          // 每个会话最多给 3 条：命中太多会把抽屉塞满，反而找不到是哪个会话。
          if (hits.count { it.sessionId == row.id } >= 3) break
        }
      }
      runOnUiThread { sessionSearchHits = hits }
    }
  }

  /** 取命中词周围的片段，两端按需加省略号。 */
  private fun snippetAround(text: String, query: String): String {
    val index = text.indexOf(query, ignoreCase = true)
    if (index < 0) {
      return text.take(SNIPPET_MAX)
    }
    val half = (SNIPPET_MAX - query.length).coerceAtLeast(10) / 2
    val start = (index - half).coerceAtLeast(0)
    val end = (index + query.length + half).coerceAtMost(text.length)
    val body = text.substring(start, end).replace('\n', ' ')
    val prefix = if (start > 0) "…" else ""
    val suffix = if (end < text.length) "…" else ""
    return prefix + body + suffix
  }

  /** 从磁盘读置顶集合。 */
  private fun readPinnedIds(): List<String> =
      runCatching {
            getSharedPreferences(PREFS_UI, MODE_PRIVATE)
                .getStringSet(KEY_PINNED, emptySet())
                ?.toList()
                .orEmpty()
          }
          .getOrDefault(emptyList())

  /** 切换某会话的置顶并落盘。 */
  private fun togglePin(sessionId: String) {
    if (sessionId in pinnedSessionIds) {
      pinnedSessionIds.remove(sessionId)
    } else {
      pinnedSessionIds.add(sessionId)
    }
    // 必须写一份新集合：getStringSet 返回的实例不保证是拷贝，
    // 改原集合再 put 同一引用可能不触发落盘。
    getSharedPreferences(PREFS_UI, MODE_PRIVATE)
        .edit()
        .putStringSet(KEY_PINNED, LinkedHashSet(pinnedSessionIds))
        .apply()
  }

  // ---- 消息操作 ----

  /**
   * 重新生成：用最后一条用户消息再跑一次。
   *
   * <p><b>为什么取「时间线里最后一条 USER」而不是界面消息列表</b>：重发的语义是
   * 「同一个问题再答一遍」。界面列表受展开态、思考块拆条等渲染细节影响；
   * 时间线里的 `Role.USER` 条目才是真正发出过的那句话。
   *
   * <p>**不删除旧回答**：新回答追加在其后。变体切换（`‹ 1/2 ›`）要求内核支持
   * 「同一条消息的多个版本」，那是另一件事——这里如实追加，不假装有变体。
   */
  private fun regenerate() {
    // 用 snapshot() 而不是内部列表：时间线的 items 是私有的，快照是它公开的读取入口。
    val lastUser =
        timeline
            .snapshot()
            .filterIsInstance<MessageTimeline.Message>()
            .lastOrNull { it.role == MessageTimeline.Role.USER }
    if (lastUser == null) {
      android.widget.Toast.makeText(
              this, "找不到可重新生成的用户消息", android.widget.Toast.LENGTH_SHORT)
          .show()
      return
    }
    send(lastUser.text)
  }

  /**
   * 删除一条消息：磁盘条目（含其后连续的工具结果行）+ 时间线条目。
   *
   * <p>ordinal 为 null 的消息（实时流式产生的）按「最后一条同内容条目」兜底定位——
   * 它们的落盘由持久化层在 TURN_FINISHED 时完成，UI 拿不到序号。
   * 删除在 IO 线程做（重写整个会话文件），完成后回主线程刷新渲染。
   */
  private fun deleteMessage(target: com.assistant.ui.model.AgentUIMessage) {
    val id = conversationId
    if (id == null) {
      // 草稿态：消息只存在于内存，直接移除显示即可。
      target.id.toLongOrNull()?.let { timeline.remove(it) }
      return
    }
    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
      try {
        val entries = conversationStore.read(id)
        val ordinalsToDelete = mutableListOf<Int>()

        val targetOrdinal = target.entryOrdinal
        if (targetOrdinal != null) {
          ordinalsToDelete.add(targetOrdinal)
          // 连带：该条之后连续的 ToolResultEntry（直到下一个非工具结果条目）。
          // 用户消息不连带（语义上用户只删自己那句话，助手回复独立存在）。
          if (target.role == com.assistant.ui.model.MessageRole.ASSISTANT) {
            for (loc in entries) {
              if (loc.ordinal > targetOrdinal) {
                if (loc.entry is com.tom.rv2ide.ai.agent.conversation.ToolResultEntry) {
                  ordinalsToDelete.add(loc.ordinal)
                } else {
                  break
                }
              }
            }
          }
        } else {
          // 兜底：按内容 + 角色匹配，从后往前找第一条（而非 lastOrNull——
          // 同正文出现多次时 lastOrNull 会跳到最末尾那条，与用户选中的早期消息不符）。
          val match =
              entries.reversed().firstOrNull { loc ->
                when (val e = loc.entry) {
                  is com.tom.rv2ide.ai.agent.conversation.UserMessageEntry ->
                      target.role == com.assistant.ui.model.MessageRole.USER && e.content == target.content
                  is com.tom.rv2ide.ai.agent.conversation.AssistantMessageEntry ->
                      target.role == com.assistant.ui.model.MessageRole.ASSISTANT && e.content == target.content
                  else -> false
                }
              }
          if (match != null) {
            ordinalsToDelete.add(match.ordinal)
            if (match.entry is com.tom.rv2ide.ai.agent.conversation.AssistantMessageEntry) {
              for (loc in entries) {
                if (loc.ordinal > match.ordinal) {
                  if (loc.entry is com.tom.rv2ide.ai.agent.conversation.ToolResultEntry) {
                    ordinalsToDelete.add(loc.ordinal)
                  } else {
                    break
                  }
                }
              }
            }
          }
        }

        val removed =
            if (ordinalsToDelete.isEmpty()) 0
            else conversationStore.deleteEntries(id, ordinalsToDelete)

        runOnUiThread {
          target.id.toLongOrNull()?.let { timeline.remove(it) }
          timeline.append(
              MessageTimeline.Role.TRACE,
              if (removed > 0) "已删除 $removed 条记录" else "已移除显示（未找到对应磁盘记录）")
          chatView.forceScrollToBottom(uiScope)
        }
      } catch (e: Exception) {
        runOnUiThread {
          android.widget.Toast.makeText(
                  this@ChatActivity, "删除失败：${e.message}", android.widget.Toast.LENGTH_SHORT)
              .show()
        }
      }
    }
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
        // 只是切到草稿态，下次发送才落盘（见 newChat 的注释）。
        conversationId = null
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

  private fun send(rawText: String) {
    // 展开长粘贴的占位符：[Pasted#1] 只是输入框里的显示标记，
    // 必须换回原文再发给模型，否则模型收到的是一串标记而不是内容。
    // 展开是单遍扫描（未知 id 原样保留，手打的标记不会让发送失败）。
    val expanded =
        com.assistant.ui.components.independent.expandPastePlaceholders(rawText, pastedTexts)
    val text = expanded.first
    // 被消费的块从缓冲移除；用户手工删掉标记但留着 chip 的情况由组件自己的
    // pastedIdsIn 对齐，这里只处理「本条消息用掉了哪些」。
    if (expanded.second.isNotEmpty()) {
      pastedTexts.removeAll { it.id in expanded.second }
    }

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
    // 忙碌时入队，而不是丢弃。
    //
    // 输入栏在忙碌时提示「回车发送将自动排队」——那是对用户的承诺。此前没有队列，
    // 用户按了回车消息就没了，且界面上毫无反馈（最坏的一种：静默丢弃）。
    if (runner.isRunning()) {
      queuedRequests.add(
          com.assistant.ui.components.independent.QueuedRequest(
              id = "q${queuedSeq++}",
              request = text,
              currentFile = null,
              selectedCode = null,
              projectRoot = workspace.absolutePath,
          ))
      timeline.append(
          MessageTimeline.Role.TRACE, "已排队（前面还有 1 轮在进行）：$text")
      chatView.forceScrollToBottom(uiScope)
      return
    }

    // 草稿态 → 现在真的要说第一句话了，这才落盘建会话。
    // **必须在这个位置**（排队判断之后、所有落盘动作之前）：排队中的消息不该建会话
    // 文件（那又变回「空会话堆积」），而本轮一旦开始就一定会写用户消息与回复。
    val activeConversationId = ensureConversationForSend()

    // 每轮开始清掉**本会话**的运行内授权记忆。
    //
    // 内核不会自己调这个（ACS 是在 runInConversation 里调 beginRun 的，本 App 没有那层）。
    // 不清的话「允许一次」会退化成「本会话内永久允许」：用户对 file_delete 点过一次
    // 允许，之后模型删任何文件都不再问——与「允许一次」的语义相反。
    // 只清本会话的桶，别的会话不受影响。
    toolSettings.beginRun(activeConversationId)

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
            activeConversationId,
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
                  // 回填改动汇总卡的内容（摘要、增删行数、逐行 diff）。
                  fillRunDiffs()
                  // 本轮结束后补做被推迟的工作区切换（见 maybeRebuildForWorkspaceChange）。
                  if (workspaceChangePending) {
                    workspaceChangePending = false
                    recreate()
                    return@runOnUiThread
                  }
                  // 本轮结束，接着发队首。
                  //
                  // 在 onFinished 里发而不是轮询 runner 状态：轮询要在「刚空闲」的
                  // 那一帧抢跑，与用户手动发送竞争；而这里是本轮确实结束的唯一时点。
                  drainQueue()
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
   * 把某个会话的磁盘记录回放进时间线。
   *
   * <p><b>为什么不复用 `ConversationHistory.fold`</b>：fold 产出的是**模型消息**
   * （给 API 用），而界面要的是**条目**（带工具卡）。两者信息量不同，直接映射会丢掉
   * 工具调用的渲染态。因此这里只还原用户/助手文本与工具调用结果，其余渲染态
   * （展开、耗时）按默认值给。
   *
   * <p>会话切换（[openSession]）与重建后恢复（[replayCurrentSession]）共用这一套规则：
   * 各写一份的话，将来新增条目类型时必有一边漏改。
   *
   * @return 失败原因；null 表示回放成功
   */
  private fun replayEntries(conversationId: String): String? {
    return try {
      for (location in conversationStore.read(conversationId)) {
        when (val entry = location.entry) {
          is com.tom.rv2ide.ai.agent.conversation.UserMessageEntry -> {
            val text = entry.content
            if (!text.isNullOrBlank()) {
              // meta 条目是系统注记（拒绝恢复的重试提示、上下文压缩提示等），
              // 不是用户发言：渲染为弱化的 TRACE 行，与实时流里的呈现一致。
              if (entry.isMeta) {
                timeline.append(MessageTimeline.Role.TRACE, text)
              } else {
                timeline.append(
                    MessageTimeline.Role.USER, text, null, 0L, location.ordinal)
              }
            }
          }
          is com.tom.rv2ide.ai.agent.conversation.AssistantMessageEntry -> {
            val text = entry.content
            if (!text.isNullOrBlank()) {
              timeline.append(
                  MessageTimeline.Role.ASSISTANT, text, null, 0L, location.ordinal)
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
              timeline.completeToolCall(id, entry.content.orEmpty(), entry.isError)
            }
          }
          else -> Unit
        }
      }
      null
    } catch (e: Exception) {
      android.util.Log.w("ChatActivity", "会话回放失败: $conversationId", e)
      e.message ?: "未知错误"
    }
  }

  /**
   * 重建后回放当前会话。
   *
   * <p>与 [openSession] 的区别只是**不改动会话 id 与标题**——那两项已在 onCreate 里
   * 从 savedInstanceState 恢复，再走 openSession 会多做一次 `list()` 读盘。
   */
  private fun replayCurrentSession() {
    val id = conversationId ?: return
    replayEntries(id)?.let { error ->
      timeline.append(MessageTimeline.Role.TRACE, "会话回放失败：$error")
    }
  }

  /**
   * 取本轮要写进去的会话 id：草稿态则**现在**建，已有则直接用。
   *
   * <p>这是全 App 唯一的会话创建点（见 [conversationId] 的注释）。放在发送路径上
   * 而不是启动/新建时，就不会留下一条消息都没有的空会话。
   *
   * <p>创建成功后立刻回写 [conversationId] 并刷新抽屉数据：不刷新的话，用户发完
   * 第一句话再打开抽屉会看不到这条会话（列表是上次读盘的结果）。
   */
  private fun ensureConversationForSend(): String {
    conversationId?.let { return it }
    val id =
        try {
          conversationStore
              .create(
                  "新的对话",
                  workspace.absolutePath,
                  modelSettings.modelId(),
                  com.tom.rv2ide.ai.tool.ToolSettingsPort.PERMISSION_CONFIRM,
              )
              .id
        } catch (e: Exception) {
          // 存储不可用时退化成纯内存会话：id 仍需合法（FileConversationStore 校验），
          // 用固定的合法串，落盘会失败但不影响本轮对话。
          android.util.Log.w("ChatActivity", "会话创建失败，退化为内存会话", e)
          "00000000-0000-0000-0000-000000000000"
        }
    conversationId = id
    return id
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

    /** 界面偏好的 prefs 名（置顶等纯界面状态存在这里，不进会话记录）。 */
    const val PREFS_UI = "assistant_ui"

    /** 保存/恢复当前会话 id（见 onCreate 的恢复逻辑）。 */
    const val STATE_CONVERSATION_ID = "assistant_conversation_id"

    /** 切工作区时待打开的会话 id（见 openSession / recreateWithSession）。 */
    const val STATE_PENDING_SESSION = "assistant_pending_session"

    /** 置顶会话 id 集合的键。 */
    const val KEY_PINNED = "pinned_sessions"
  }
}
