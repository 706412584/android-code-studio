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

package com.tom.rv2ide.artificial.agent

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.tom.rv2ide.adapters.AssistantMessageAdapter
import com.tom.rv2ide.artificial.agents.Agents
import com.tom.rv2ide.databinding.LayoutAiAssistantBinding
import com.tom.rv2ide.databinding.LayoutAiAssistantFabBinding
import com.tom.rv2ide.resources.R.string
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 主屏上的悬浮 AI 助手：一个可收起的圆形入口 + 可全屏/侧栏切换的对话面板。
 *
 * <p><b>为什么直接持有 [AgentOrchestrator] 而不是复用
 * [com.tom.rv2ide.handlers.AgentRequestHandler]</b>：后者是
 * [com.tom.rv2ide.fragments.ChatFragment] 的控件渲染器——它把事件塞进
 * `statusText`/`summaryText` 两个 TextView，还要求传入
 * [com.tom.rv2ide.adapters.FileModificationAdapter] 与编辑器刷新回调，且完全忽略
 * [com.tom.rv2ide.ai.agent.AgentEvent.Type.TEXT_DELTA]。这里的界面是消息列表，
 * 需要的是事件流本身（含流式增量），而不是被渲染过的状态文本。两者的 UI 契约不同，
 * 强行复用会把 ChatFragment 的控件假设带进来。
 *
 * <p><b>会话连续性</b>：orchestrator 只创建一次并在整个视图生命周期内复用——它持有当前
 * 会话 id，每次新建都会让历史断掉。工作区在 [setWorkspace] 时更新（主屏上用户可能切换项目）。
 *
 * <p><b>线程</b>：agent 循环跑在 [Dispatchers.IO]，所有控件写入切回主线程。事件回调本身
 * 来自循环线程，因此 [handleEvent] 内部不做直接控件操作，只投递到主线程。
 */
class FloatingAssistantView(
    private val context: Context,
    private val lifecycleScope: LifecycleCoroutineScope,
    private val parent: ViewGroup,
) {

  /** 面板形态。 */
  enum class Mode {
    FULLSCREEN,
    SIDEBAR
  }

  private val fabBinding =
      LayoutAiAssistantFabBinding.inflate(LayoutInflater.from(context), parent, false)
  private val binding =
      LayoutAiAssistantBinding.inflate(LayoutInflater.from(context), parent, false)

  private val adapter = AssistantMessageAdapter()
  private val settings = AgentToolSettings(context)

  /**
   * 持久化 diff 存储。
   *
   * <p>用文件实现而非 InMemoryDiffStore：回滚的价值在于「事后反悔」，而事后往往就是
   * 下一次打开应用；内存实现重启后记录全丢，用户点了撤销却找不到记录。
   */
  private val diffStore = AgentOrchestrator.defaultDiffStore(context)

  private var executionJob: Job? = null
  private var workspace: java.io.File? = null

  /** 流式输出正在写入的那条助手消息 id；null 表示当前没有进行中的流。 */
  private var streamingMessageId: Long? = null

  /**
   * 流式刷新节流。
   *
   * <p>模型按 token 吐字，一段回答会产生上百个增量。每个都刷一次会让列表重排上百次：
   * 滚动抖动、掉帧，而人眼分辨不出这个粒度。
   */
  private val throttle = StreamingThrottle()

  /**
   * 最近一张「运行中」的工具卡片 id。
   *
   * <p>TOOL_STARTED / TOOL_FINISHED 成对出现且顺序执行，因此用「最近一张」即可关联，
   * 不必让协议层额外传 id。完成后置空，避免迟到的结果回填到错误的卡片。
   */
  private var lastToolCardId: Long? = null

  /**
   * 本次运行是否已经通过事件流写出过文本。
   *
   * <p>决定收尾时要不要再补一条最终输出：已经流式显示过就不能再追加，否则同一段回答
   * 会在列表里出现两遍。
   */
  private var streamedThisRun = false

  private var mode = Mode.SIDEBAR

  /**
   * 复用一个 orchestrator 跨请求。
   *
   * <p>必须复用而非每次新建：orchestrator 持有当前会话 id，新建会让它丢失，
   * 于是每条消息都开一个新会话，历史永远无法续接。
   */
  private val orchestrator: AgentOrchestrator by lazy {
    AgentOrchestrator(context, diffStore).apply {
      settings.setDangerousToolConfirmer(
          AgentToolSettings.DangerousToolConfirmer { toolName, args ->
            askDangerousToolOnMain(toolName, args)
          }
      )
    }
  }

  /** 是否已展开面板。 */
  val isOpen: Boolean
    get() = binding.assistantOverlay.isVisible

  /** 把两个视图挂到父容器上。父容器应是 `FrameLayout`（FAB 靠 gravity 定位）。 */
  fun attach() {
    // FAB 在 XML 里只有固定尺寸、没有 gravity；放进 FrameLayout 时必须显式给右下角，
    // 否则会落在左上角盖住标题。
    fabBinding.root.layoutParams =
        FrameLayout.LayoutParams(dp(56), dp(56)).apply {
          gravity = Gravity.END or Gravity.BOTTOM
          marginEnd = dp(16)
          bottomMargin = dp(16)
        }
    parent.addView(fabBinding.root)
    parent.addView(binding.assistantOverlay)

    applyMode(Mode.SIDEBAR)

    binding.assistantMessages.layoutManager = LinearLayoutManager(context)
    binding.assistantMessages.adapter = adapter

    adapter.setOnRevertClickListener { messageId, diffId -> revertDiff(messageId, diffId) }

    fabBinding.assistantFab.setOnClickListener { open() }
    binding.assistantClose.setOnClickListener { close() }
    binding.assistantLayoutToggle.setOnClickListener {
      applyMode(if (mode == Mode.FULLSCREEN) Mode.SIDEBAR else Mode.FULLSCREEN)
    }
    binding.assistantNewConversation.setOnClickListener { startNewConversation() }
    binding.assistantExport.setOnClickListener { copyConversation() }
    binding.assistantSend.setOnClickListener { sendFromInput() }

    // 回车即发送：面板输入框是多行的，若不拦截回车，用户按回车只会换行。
    binding.assistantInput.setOnEditorActionListener { _, actionId, event ->
      val isSendAction = actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND
      val isEnter =
          event != null &&
              event.keyCode == android.view.KeyEvent.KEYCODE_ENTER &&
              event.action == android.view.KeyEvent.ACTION_DOWN
      if (isSendAction || isEnter) {
        sendFromInput()
        true
      } else {
        false
      }
    }

    updateEmptyState()
  }

  /** 设置工作区。主屏上用户可能先选项目，因此每次打开面板前都更新。 */
  fun setWorkspace(workspace: java.io.File?) {
    this.workspace = workspace
    orchestrator.workspace = workspace
  }

  fun open() {
    fabBinding.assistantFab.isVisible = false
    binding.assistantOverlay.isVisible = true
    updateEmptyState()
  }

  fun close() {
    binding.assistantOverlay.isVisible = false
    fabBinding.assistantFab.isVisible = true
  }

  /** 折叠面板（返回键用）。@return 是否消费了这次返回 */
  fun collapseIfOpen(): Boolean {
    if (!isOpen) {
      return false
    }
    close()
    return true
  }

  /** 释放资源：取消进行中的运行，避免视图销毁后回调仍写控件。 */
  fun dispose() {
    cancel()
  }

  private fun applyMode(newMode: Mode) {
    mode = newMode
    val card = binding.assistantCard
    val params = card.layoutParams as ViewGroup.MarginLayoutParams

    when (newMode) {
      Mode.FULLSCREEN -> {
        params.width = ViewGroup.LayoutParams.MATCH_PARENT
        params.height = ViewGroup.LayoutParams.MATCH_PARENT
        params.marginStart = dp(8)
        params.marginEnd = dp(8)
        binding.assistantLayoutToggle.setText(string.ai_assistant_side)
      }
      Mode.SIDEBAR -> {
        // 侧栏宽度取屏幕的 88%，至少 280dp：窄屏上纯比例会挤到不可用，
        // 宽屏上固定宽度又会留下大片空白。
        val screenWidth = parent.resources.displayMetrics.widthPixels
        val width = maxOf(dp(280), (screenWidth * 0.88f).toInt())
        params.width = minOf(width, screenWidth - dp(16))
        params.height = ViewGroup.LayoutParams.MATCH_PARENT
        params.marginStart = dp(8)
        params.marginEnd = dp(8)
        binding.assistantLayoutToggle.setText(string.ai_assistant_fullscreen)
      }
    }
    card.layoutParams = params
  }

  private fun dp(value: Int): Int =
      (value * parent.resources.displayMetrics.density).toInt()

  private fun sendFromInput() {
    val request = binding.assistantInput.text?.toString()?.trim().orEmpty()
    if (request.isEmpty()) {
      return
    }
    binding.assistantInput.setText("")

    // 斜杠命令是纯本地操作：不发给模型、不消耗额度。
    // 只有已知命令名才算命令——「/etc/hosts 是干什么的」是普通消息（见 SlashCommandCatalog）。
    val parsed = com.tom.rv2ide.ai.agent.command.SlashCommandCatalog.parse(request)
    if (parsed.isCommand) {
      handleCommand(parsed)
      return
    }
    execute(request)
  }

  /** 执行一条斜杠命令。 */
  private fun handleCommand(parsed: com.tom.rv2ide.ai.agent.command.SlashCommandCatalog.Parsed) {
    val kind = parsed.kind
    if (kind == com.tom.rv2ide.ai.agent.command.SlashCommandCatalog.Kind.INVALID) {
      appendTrace("⚠️ ${parsed.error}")
      return
    }
    when (kind) {
      com.tom.rv2ide.ai.agent.command.SlashCommandCatalog.Kind.HELP ->
          appendTrace(com.tom.rv2ide.ai.agent.command.SlashCommandCatalog.helpText())

      com.tom.rv2ide.ai.agent.command.SlashCommandCatalog.Kind.MODE -> {
        val mode = parsed.modeOrNull()
        if (mode == null) {
          appendTrace("⚠️ ${parsed.error}")
          return
        }
        // 写进偏好：模式是持久设置，下次打开应用仍然生效。
        com.tom.rv2ide.artificial.agent.PrefsChatModeStore(context).set(mode)
        appendTrace(context.getString(string.ai_assistant_mode_changed, mode.label))
      }

      com.tom.rv2ide.ai.agent.command.SlashCommandCatalog.Kind.MODEL -> {
        // 模型名必须在当前服务商的预设列表里，否则请求必然失败。
        // 用户手打一个模型名很可能拼错，这里直接拒绝并给出可用值。
        val provider = Agents(context).getProvider()
        val models = ProviderPresets.modelsFor(provider)
        if (models.contains(parsed.argument)) {
          Agents(context).setAgent(parsed.argument)
          appendTrace(context.getString(string.ai_assistant_model_changed, parsed.argument))
        } else {
          appendTrace(
              context.getString(
                  string.ai_assistant_model_unknown,
                  parsed.argument,
                  provider,
                  models.take(8).joinToString(", "),
              ))
        }
      }

      com.tom.rv2ide.ai.agent.command.SlashCommandCatalog.Kind.NEW_CONVERSATION ->
          startNewConversation()

      com.tom.rv2ide.ai.agent.command.SlashCommandCatalog.Kind.CLEAR -> {
        cancel()
        adapter.clear()
        updateEmptyState()
      }

      else -> {}
    }
  }

  private fun execute(userRequest: String) {
    if (executionJob?.isActive == true) {
      return
    }
    val currentWorkspace = workspace
    if (currentWorkspace == null || !currentWorkspace.exists()) {
      appendTrace(context.getString(string.ai_assistant_no_workspace))
      return
    }

    cancel()
    adapter.append(AssistantMessageAdapter.Role.USER, userRequest)
    streamingMessageId = null
    streamedThisRun = false
    lastToolCardId = null

    executionJob =
        lifecycleScope.launch(Dispatchers.IO) {
          val agents = Agents(context)
          val providerId = agents.getProvider()
          val modelId = AgentModelConfigs.modelIdFor(providerId, agents.getAgent())
          val customBaseUrl =
              if (providerId == "localllm") readDefaultPref("local_llm_base_url") else null

          withContext(Dispatchers.Main) {
            binding.assistantSend.isEnabled = false
            binding.assistantProgress.isVisible = true
            binding.assistantStatus.text =
                context.getString(string.ai_assistant_status_running, providerId, modelId)
          }

          try {
            val result =
                orchestrator.run(providerId, modelId, userRequest, customBaseUrl) { event ->
                  handleEvent(event)
                }
            withContext(Dispatchers.Main) {
              finishStreaming()
              // 已经流式显示过就不再追加：否则同一段回答会出现两遍。
              if (!streamedThisRun) {
                appendAssistant(
                    result.output.ifBlank { context.getString(string.ai_assistant_no_output) }
                )
              }
              binding.assistantStatus.text =
                  context.getString(
                      if (result.isFailed) string.ai_assistant_status_failed
                      else string.ai_assistant_status_done,
                      result.turns,
                      result.toolCallCount,
                  )
            }
          } catch (e: kotlinx.coroutines.CancellationException) {
            // 用户取消不是错误，静默收尾；已流式输出的内容保留在列表里。
            throw e
          } catch (e: Throwable) {
            withContext(Dispatchers.Main) {
              finishStreaming()
              appendTrace(
                  context.getString(
                      string.ai_assistant_error,
                      e.javaClass.simpleName,
                      e.message ?: "",
                  )
              )
            }
            com.tom.rv2ide.ai.tool.api.ErrorLog.record(
                "agent",
                "悬浮助手运行抛出异常",
                e,
                "provider=$providerId model=$modelId",
            )
          } finally {
            // 必须放在 finally：run() 抛异常或协程被取消时也要恢复 UI，
            // 否则发送按钮会永久停留在禁用状态，用户再也发不出请求。
            withContext(kotlinx.coroutines.NonCancellable) {
              withContext(Dispatchers.Main) {
                binding.assistantSend.isEnabled = true
                binding.assistantProgress.isVisible = false
              }
            }
          }
        }
  }

  private fun handleEvent(event: com.tom.rv2ide.ai.agent.AgentEvent) {
    when (event.type) {
      com.tom.rv2ide.ai.agent.AgentEvent.Type.TEXT_DELTA -> {
        val delta = event.message
        if (delta.isEmpty()) {
          return
        }
        // 累积始终发生在数据层（不能丢内容），只有界面刷新被节流。
        lifecycleScope.launch(Dispatchers.Main) {
          val id = streamingMessageId
          if (id == null) {
            streamingMessageId =
                adapter.append(AssistantMessageAdapter.Role.ASSISTANT, delta)
          } else {
            adapter.appendTo(id, delta)
          }
          if (throttle.onDelta()) {
            scrollToBottom()
          }
        }
      }
      com.tom.rv2ide.ai.agent.AgentEvent.Type.TURN_FINISHED -> {
        // 用轮次的**规范输出**覆盖流式累积：模型可能把工具调用写成正文文本形态，
        // 累积的增量里含标记，而事件里的 message 已经过 ToolCallTextParser 剥离。
        val text = event.message
        lifecycleScope.launch(Dispatchers.Main) {
          streamedThisRun = true
          val id = streamingMessageId
          if (id != null) {
            adapter.update(id, text)
          } else if (text.isNotBlank()) {
            appendAssistant(text)
          }
          finishStreaming()
          scrollToBottom()
        }
      }
      com.tom.rv2ide.ai.agent.AgentEvent.Type.TOOL_STARTED -> {
        val call = event.toolCall
        lifecycleScope.launch(Dispatchers.Main) {
          // 卡片替代原来的纯文本过程行：折叠态给摘要，展开看完整输入输出。
          // 展开时展示**原始**参数 JSON 而不做美化：参数里可能有含换行的长内容
          // （例如要写入的文件正文），重新序列化会改变转义形式，用户拿它对照文件
          // 内容时会对不上。
          val id =
              adapter.appendToolCall(
                  toolName = call.name,
                  summary = summarizeArgs(call.arguments),
                  input = call.arguments.orEmpty(),
              )
          lastToolCardId = id
          scrollToBottom()
        }
      }
      com.tom.rv2ide.ai.agent.AgentEvent.Type.TOOL_FINISHED -> {
        val result = event.toolResult
        val call = event.toolCall
        lifecycleScope.launch(Dispatchers.Main) {
          // 回填到最近的卡片。TOOL_STARTED / TOOL_FINISHED 成对出现，
          // 用「最近一张仍在运行中的卡片」关联即可，无需在协议层传 id。
          val cardId = lastToolCardId ?: adapter.lastToolCallId()
          if (cardId != null) {
            adapter.completeToolCall(cardId, result.content, result.isError)
          }
          lastToolCardId = null

          // 有 diffId 说明这次调用改了文件。插一条**带撤销按钮**的条目——
          // 这是用户能真正看到「AI 改了什么、怎么改回来」的唯一入口。
          val diffId = result.diffId
          if (!result.isError && diffId.isNotEmpty()) {
            appendChangedFile(call?.name.orEmpty(), call?.arguments, diffId)
          }
        }
      }
      com.tom.rv2ide.ai.agent.AgentEvent.Type.CONTEXT_COMPACTED -> {
        // 压缩是有损的，必须让用户看到——否则会困惑于模型为何遗忘先前的要求。
        lifecycleScope.launch(Dispatchers.Main) { appendTrace(event.message) }
      }
      com.tom.rv2ide.ai.agent.AgentEvent.Type.FAILED -> {
        lifecycleScope.launch(Dispatchers.Main) {
          streamedThisRun = true
          // 仍在「运行中」的卡片要收尾，否则会永久停在运行态，用户以为还在跑。
          for (id in adapter.runningToolCallIds()) {
            adapter.failToolCall(id, event.message)
          }
          appendTrace("⚠️ ${event.message}")
        }
      }
      else -> {}
    }
  }

  /** 结束流式段：下一次增量会新建一条消息。 */
  private fun finishStreaming() {
    // 末尾必刷：被节流合并掉的最后一段内容必须补出去，否则看起来像回答被截断了。
    if (throttle.flush()) {
      scrollToBottom()
    }
    throttle.reset()
    streamingMessageId = null
  }

  private fun appendAssistant(text: String) {
    adapter.append(AssistantMessageAdapter.Role.ASSISTANT, text)
    scrollToBottom()
  }

  /**
   * 记录一次文件改动，并挂上撤销按钮。
   *
   * <p>路径从工具参数里取：工具结果本身不带路径（{@code ToolResult} 只有 diffId），
   * 而用户需要看到「改的是哪个文件」才能判断要不要撤销。
   */
  private fun appendChangedFile(toolName: String, arguments: String?, diffId: String) {
    val path = parsePathArg(arguments) ?: context.getString(string.ai_assistant_unknown_file)
    adapter.append(
        AssistantMessageAdapter.Role.TRACE,
        context.getString(string.ai_assistant_file_changed, path),
        diffId,
    )
    scrollToBottom()
  }

  /**
   * 执行撤销。
   *
   * <p>在 IO 线程做（要读写文件），结果回主线程提示。成功后把对应消息标为已撤销，
   * 使按钮进入禁用态——否则用户会重复点击并收到「已经回滚过了」。
   */
  private fun revertDiff(messageId: Long, diffId: String) {
    val ws = workspace
    if (ws == null || !ws.exists()) {
      appendTrace(context.getString(string.ai_assistant_no_workspace))
      return
    }
    lifecycleScope.launch(Dispatchers.IO) {
      val toolContext =
          com.tom.rv2ide.ai.tool.ToolContext.builder().homePath(ws.absolutePath).build()
      val result =
          com.tom.rv2ide.ai.tool.DiffReverter(diffStore).revert(diffId, toolContext)
      withContext(Dispatchers.Main) {
        if (result.isSuccess) {
          adapter.markReverted(messageId)
          appendTrace(context.getString(string.ai_assistant_revert_ok, result.message))
        } else {
          appendTrace(context.getString(string.ai_assistant_revert_failed, result.message))
        }
      }
    }
  }

  private fun appendTrace(text: String) {
    adapter.append(AssistantMessageAdapter.Role.TRACE, text)
    scrollToBottom()
  }

  private fun scrollToBottom() {
    updateEmptyState()
    binding.assistantMessages.post {
      val count = adapter.itemCount
      if (count > 0) {
        binding.assistantMessages.scrollToPosition(count - 1)
      }
    }
  }

  private fun updateEmptyState() {
    binding.assistantEmpty.isVisible = adapter.isEmpty()
  }

  private fun startNewConversation() {
    cancel()
    adapter.clear()
    streamingMessageId = null
    streamedThisRun = false
    updateEmptyState()
    lifecycleScope.launch(Dispatchers.IO) {
      try {
        orchestrator.newConversation()
      } catch (e: java.io.IOException) {
        // 开新会话失败不该阻断对话：orchestrator 会在下次 run 时再尝试。
        com.tom.rv2ide.ai.tool.api.ErrorLog.record("agent", "新建会话失败", e, null)
      }
    }
  }

  private fun cancel() {
    orchestrator.cancel()
    executionJob?.cancel()
    executionJob = null
    finishStreaming()
    lastToolCardId = null
    // 取消时仍在运行的卡片要收尾，否则会永久停在运行态。
    for (id in adapter.runningToolCallIds()) {
      adapter.failToolCall(id, context.getString(string.ai_assistant_tool_cancelled))
    }
  }

  /**
   * 从 agent 循环线程调用，切主线程弹窗等待用户决定。
   * 面板不可见或超时则拒绝，宁可让工具失败也不无确认执行。
   */
  private fun askDangerousToolOnMain(toolName: String, args: String?): Boolean {
    if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
      return false
    }
    val latch = java.util.concurrent.CountDownLatch(1)
    var accepted = false
    android.os.Handler(android.os.Looper.getMainLooper())
        .post {
          try {
            AlertDialog.Builder(context)
                .setTitle(string.ai_assistant_dangerous_title)
                .setMessage(
                    context.getString(
                        string.ai_assistant_dangerous_message,
                        toolName,
                        args?.take(500) ?: "",
                    )
                )
                .setCancelable(false)
                .setPositiveButton(string.ai_assistant_dangerous_allow_once) { _, _ ->
                  accepted = true
                  latch.countDown()
                }
                .setNeutralButton(string.ai_assistant_dangerous_allow_always) { _, _ ->
                  // 写的是「工具 + 参数粒度」规则，不是全局放行——
                  // 对 git status 点「始终允许」不应顺带放行 git push --force。
                  settings.applyDecision(
                      toolName,
                      args,
                      com.tom.rv2ide.ai.tool.DangerousToolDecision.ALLOW_ALWAYS,
                  )
                  accepted = true
                  latch.countDown()
                }
                .setNegativeButton(string.ai_assistant_dangerous_deny) { _, _ -> latch.countDown() }
                .show()
          } catch (e: Exception) {
            latch.countDown()
          }
        }
    latch.await(120, java.util.concurrent.TimeUnit.SECONDS)
    return accepted
  }

  private fun readDefaultPref(key: String): String? =
      androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
          .getString(key, null)

  /**
   * 把当前对话复制到剪贴板。
   *
   * <p>导出的内容取自列表里已有的消息（含工具卡片），而不是重新读会话日志：
   * 用户看到的就是他要导出的，两者必须一致。
   */
  private fun copyConversation() {
    val markdown = buildMarkdownExport()
    if (markdown.isBlank()) {
      appendTrace(context.getString(string.ai_assistant_export_empty))
      return
    }
    val clipboard =
        context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as? android.content.ClipboardManager
    if (clipboard == null) {
      appendTrace(context.getString(string.ai_assistant_export_failed))
      return
    }
    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("AI 对话", markdown))
    appendTrace(context.getString(string.ai_assistant_export_copied, markdown.length))
  }

  /** 按当前列表内容拼出 Markdown。 */
  private fun buildMarkdownExport(): String {
    val snapshot = adapter.snapshot()
    if (snapshot.isEmpty()) {
      return ""
    }
    val sb = StringBuilder()
    sb.append("# AI 对话\n\n")
    for (item in snapshot) {
      when (item) {
        is AssistantMessageAdapter.Message ->
            sb.append("**")
                .append(
                    context.getString(
                        when (item.role) {
                          AssistantMessageAdapter.Role.USER -> string.ai_assistant_role_user
                          AssistantMessageAdapter.Role.ASSISTANT ->
                              string.ai_assistant_role_assistant
                          AssistantMessageAdapter.Role.TRACE -> string.ai_assistant_role_trace
                        }))
                .append("**\n\n")
                .append(item.text)
                .append("\n\n")
        is AssistantMessageAdapter.ToolCall -> {
          sb.append("- ")
              .append(if (item.status == AssistantMessageAdapter.ToolStatus.FAILED) "✗ " else "✓ ")
              .append(item.toolName)
          if (item.output.isNotBlank()) {
            // 截断：工具输出可能极大，导出是给人读的。
            val preview = item.output.take(2000)
            sb.append("：").append(preview)
            if (item.output.length > 2000) {
              sb.append("…（已截断，共 ").append(item.output.length).append(" 字符）")
            }
          }
          sb.append('\n')
        }
      }
    }
    return sb.toString().trim()
  }

  companion object {
    private fun summarizeArgs(args: String?): String {
      if (TextUtils.isEmpty(args)) {
        return ""
      }
      return try {
        val obj = org.json.JSONObject(args!!)
        obj.keys()
            .asSequence()
            .map { "$it=${obj.optString(it).take(60)}" }
            .joinToString(" ")
      } catch (e: Exception) {
        args.orEmpty().take(100)
      }
    }

    /**
     * 从工具参数里取目标路径。
     *
     * <p>覆盖三种参数形态：{@code file_path}（写/改）、{@code paths} 数组（删除）、
     * {@code path}（部分工具）。取不到返回 null，由调用方显示「未知文件」。
     */
    private fun parsePathArg(args: String?): String? {
      if (TextUtils.isEmpty(args)) {
        return null
      }
      return try {
        val obj = org.json.JSONObject(args!!)
        obj.optString("file_path").ifBlank { null }
            ?: obj.optJSONArray("paths")?.optString(0)?.ifBlank { null }
            ?: obj.optString("path").ifBlank { null }
      } catch (e: Exception) {
        null
      }
    }
  }
}
