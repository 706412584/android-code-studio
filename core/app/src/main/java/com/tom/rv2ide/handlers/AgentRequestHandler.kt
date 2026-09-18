package com.tom.rv2ide.handlers

import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleCoroutineScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.textview.MaterialTextView
import com.tom.rv2ide.adapters.FileModificationAdapter
import com.tom.rv2ide.artificial.agent.AgentModelConfigs
import com.tom.rv2ide.artificial.agent.AgentOrchestrator
import com.tom.rv2ide.artificial.agent.AgentToolSettings
import com.tom.rv2ide.artificial.agents.Agents
import com.tom.rv2ide.ai.agent.AgentEvent
import com.tom.rv2ide.ai.tool.DangerousToolDecision
import com.tom.rv2ide.resources.R.string
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * agent 模式的请求执行器：把用户请求交给 [AgentOrchestrator] 的工具调用循环，
 * 并把 [AgentEvent] 流渲染到 ChatFragment 现有的状态/摘要控件上。
 *
 * 与旧路径 [AIRequestHandler] 的区别：旧路径一次请求只生成文本、按 FILE_TO_MODIFY
 * 标记写文件；这里由模型自主调用 read/write/shell/build/install/launch/logcat 工具
 * 多轮完成整个任务。UI 刻意复用同一组控件——过程日志显示在 statusText，
 * 最终输出在 summaryText，暂不做独立的工具卡片渲染。
 */
class AgentRequestHandler(
    private val lifecycleScope: LifecycleCoroutineScope,
    private val context: android.content.Context,
    private val workspacePath: String,
    private val statusText: MaterialTextView,
    private val summaryText: MaterialTextView,
    private val summaryCard: android.view.View,
    private val progressIndicator: CircularProgressIndicator,
    private val executeBtn: MaterialButton,
    private val fileModificationAdapter: FileModificationAdapter,
    private val onRunFinished: () -> Unit
) {

    private val settings = AgentToolSettings(context)

    /**
     * 持久化 diff 存储（与悬浮助手共用同一份）。
     *
     * <p>用文件实现而非 InMemoryDiffStore：回滚的价值在于「事后反悔」，而事后往往就是
     * 下一次打开应用；内存实现重启后记录全丢，用户点了撤销却找不到记录。
     */
    private val diffStore = AgentOrchestrator.defaultDiffStore(context)
    private var executionJob: Job? = null

    /**
     * 复用同一个 orchestrator 跨请求。
     *
     * <p>必须复用而非每次新建：orchestrator 持有当前会话 id，新建会让它丢失，
     * 于是每条消息都开一个新会话，历史永远无法续接。
     */
    private val orchestrator: AgentOrchestrator by lazy {
        AgentOrchestrator(context, diffStore).apply {
            workspace = java.io.File(workspacePath)
            settings.setDangerousToolConfirmer(
                AgentToolSettings.DangerousToolConfirmer { toolName, args ->
                    askDangerousToolOnMain(toolName, args)
                }
            )
        }
    }

    /** 新建会话：之后的请求不再续接旧历史。 */
    fun startNewConversation() {
        orchestrator.newConversation()
    }

    /** 列出全部会话摘要，供会话列表展示。 */
    fun listConversations() = orchestrator.listConversations()

    /** 切换到既有会话，之后的请求会在其历史上续接。 */
    fun openConversation(conversationId: String) {
        orchestrator.openConversation(conversationId)
    }

    fun execute(userRequest: String) {
        cancel()
        executionJob = lifecycleScope.launch(Dispatchers.IO) {
            val orch = orchestrator

            val agents = Agents(context)
            val providerId = agents.getProvider()
            val modelId = AgentModelConfigs.modelIdFor(providerId, agents.getAgent())
            val customBaseUrl = AgentOrchestrator.customBaseUrlFor(context, providerId)

            withContext(Dispatchers.Main) {
                executeBtn.isEnabled = false
                progressIndicator.visibility = View.VISIBLE
                // summaryCard 在布局里默认 gone，必须显式显示，否则最终输出永远看不到
                summaryCard.visibility = View.VISIBLE
                statusText.text = "🤖 Agent 模式（$providerId / $modelId）\n"
                summaryText.text = "（运行中…）"
            }

            try {
                val result = orch.run(
                    providerId,
                    modelId,
                    userRequest,
                    customBaseUrl
                ) { event -> handleEvent(event) }

                withContext(Dispatchers.Main) {
                    summaryCard.visibility = View.VISIBLE
                    summaryText.text = result.output.ifBlank { "（模型没有返回文本）" }
                    statusText.text =
                        (if (result.isFailed) "❌ Agent 运行结束（失败）" else "✅ Agent 运行结束") +
                            "  轮次: ${result.turns}  工具调用: ${result.toolCallCount}"
                    onRunFinished()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 用户取消：不是错误，静默收尾
                throw e
            } catch (e: Throwable) {
                // 不能让异常只留在日志里——用户需要知道为什么没有结果
                withContext(Dispatchers.Main) {
                    statusText.text = "❌ Agent 异常终止"
                    summaryText.text = "${e.javaClass.simpleName}: ${e.message}"
                }
                com.tom.rv2ide.ai.tool.api.ErrorLog.record(
                    "agent", "Agent 运行抛出异常", e, "provider=$providerId model=$modelId"
                )
            } finally {
                // 必须放在 finally：run() 抛异常或协程被取消时也要恢复 UI，
                // 否则按钮会永久停留在禁用状态，用户再也发不出请求。
                withContext(kotlinx.coroutines.NonCancellable) {
                    withContext(Dispatchers.Main) {
                        executeBtn.isEnabled = true
                        progressIndicator.visibility = View.GONE
                        if (summaryText.text.isNullOrBlank() || summaryText.text == "（运行中…）") {
                            summaryText.text = "（未产生输出）"
                        }
                    }
                }
            }
        }
    }

    private fun handleEvent(event: AgentEvent) {
        when (event.type) {
            AgentEvent.Type.TURN_STARTED ->
                appendStatus("— 第 ${event.message.removePrefix("turn ")} 轮")
            AgentEvent.Type.TOOL_STARTED -> {
                val call = event.toolCall
                appendStatus("🔧 ${call.name} ${summarizeArgs(call.arguments)}")
            }
            AgentEvent.Type.TOOL_FINISHED -> {
                val result = event.toolResult
                val call = event.toolCall
                if (call != null && !result.isError &&
                    call.name in FILE_WRITE_TOOLS
                ) {
                    lifecycleScope.launch(Dispatchers.Main) {
                        fileModificationAdapter.addItem(java.io.File(
                            parsePathArg(call.arguments) ?: return@launch
                        ).name)
                    }
                }
                if (result.isError) {
                    appendStatus("   ↳ 失败: ${result.content.take(200)}")
                }
            }
            AgentEvent.Type.FAILED -> appendStatus("⚠️ ${event.message}")
            else -> {}
        }
    }

    private fun appendStatus(line: String) {
        lifecycleScope.launch(Dispatchers.Main) {
            statusText.text = statusText.text.toString() + line + "\n"
        }
    }

    /**
     * 从 executor 后台线程调用，切主线程弹窗等待用户决定。
     * fragment 不可见或超时则拒绝，宁可让工具失败也不无确认执行。
     */
    private fun askDangerousToolOnMain(toolName: String, args: String?): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) return false
        val latch = java.util.concurrent.CountDownLatch(1)
        var accepted = false
        Handler(Looper.getMainLooper()).post {
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
                            DangerousToolDecision.ALLOW_ALWAYS,
                        )
                        accepted = true
                        latch.countDown()
                    }
                    .setNegativeButton(string.ai_assistant_dangerous_deny) { _, _ ->
                        latch.countDown()
                    }
                    .show()
            } catch (e: Exception) {
                latch.countDown()
            }
        }
        latch.await(120, java.util.concurrent.TimeUnit.SECONDS)
        return accepted
    }

    fun cancel() {
        orchestrator.cancel()
        executionJob?.cancel()
        executionJob = null
    }

    companion object {
        private val FILE_WRITE_TOOLS = setOf("file_write", "file_edit")

        private fun summarizeArgs(args: String?): String {
            if (args.isNullOrBlank()) return ""
            return try {
                val obj = org.json.JSONObject(args)
                obj.keys().asSequence()
                    .map { "$it=${obj.optString(it).take(60)}" }
                    .joinToString(" ")
            } catch (e: Exception) {
                args.take(100)
            }
        }

        private fun parsePathArg(args: String?): String? {
            if (args.isNullOrBlank()) return null
            return try {
                org.json.JSONObject(args).optString("file_path").ifBlank { null }
            } catch (e: Exception) {
                null
            }
        }
    }

}
