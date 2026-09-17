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
  private val diffStore = com.tom.rv2ide.ai.tool.InMemoryDiffStore()

  private var executionJob: Job? = null
  private var workspace: java.io.File? = null

  /** 流式输出正在写入的那条助手消息 id；null 表示当前没有进行中的流。 */
  private var streamingMessageId: Long? = null

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

    fabBinding.assistantFab.setOnClickListener { open() }
    binding.assistantClose.setOnClickListener { close() }
    binding.assistantLayoutToggle.setOnClickListener {
      applyMode(if (mode == Mode.FULLSCREEN) Mode.SIDEBAR else Mode.FULLSCREEN)
    }
    binding.assistantNewConversation.setOnClickListener { startNewConversation() }
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
    execute(request)
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
        lifecycleScope.launch(Dispatchers.Main) { appendStreamingDelta(delta) }
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
          appendTrace(
              context.getString(
                  string.ai_assistant_tool_started,
                  call.name,
                  summarizeArgs(call.arguments),
              )
          )
        }
      }
      com.tom.rv2ide.ai.agent.AgentEvent.Type.TOOL_FINISHED -> {
        val result = event.toolResult
        if (result.isError) {
          lifecycleScope.launch(Dispatchers.Main) {
            appendTrace(context.getString(string.ai_assistant_tool_failed, result.content.take(300)))
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
          appendTrace("⚠️ ${event.message}")
        }
      }
      else -> {}
    }
  }

  /**
   * 流式增量：首段创建一条助手消息，之后追加。
   *
   * <p>之所以要区分"首段"：模型可能在正文之前先输出工具调用的文本形态，此时
   * [com.tom.rv2ide.ai.agent.AgentEvent.Type.TOOL_STARTED] 已经往列表里插过过程条目。
   * 若不新建而是复用末条，过程信息会被当成助手正文覆盖掉。
   */
  private fun appendStreamingDelta(delta: String) {
    val id = streamingMessageId
    if (id == null) {
      val newId = adapter.append(AssistantMessageAdapter.Role.ASSISTANT, delta)
      streamingMessageId = newId
    } else {
      adapter.appendTo(id, delta)
    }
    scrollToBottom()
  }

  /** 结束流式段：下一次增量会新建一条消息。 */
  private fun finishStreaming() {
    streamingMessageId = null
  }

  private fun appendAssistant(text: String) {
    adapter.append(AssistantMessageAdapter.Role.ASSISTANT, text)
    scrollToBottom()
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
                  accepted = true
                  settings.confirmDangerousTools(true)
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
  }
}
