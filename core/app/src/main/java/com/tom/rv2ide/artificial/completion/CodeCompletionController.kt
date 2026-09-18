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
 * along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.artificial.completion

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.LifecycleCoroutineScope
import com.tom.rv2ide.artificial.agents.AIAgentManager
import com.tom.rv2ide.managers.CodeCompletionManager
import com.tom.rv2ide.ui.CodeEditorView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 编辑器内联 AI 补全的托管。
 *
 * <p><b>为什么从 [com.tom.rv2ide.fragments.ChatFragment] 搬出来</b>：补全只与「当前打开
 * 哪个文件」有关，与「聊天面板是否显示」无关，但原先唯一的挂载点在 ChatFragment 里，
 * 而 ChatFragment 只能由旧侧栏 AI 标签页创建。于是移除侧栏入口会让补全**静默失效**
 * ——没有任何界面再去调 `CodeCompletionManager.setup()`，用户不会收到任何提示，
 * 只是补全再也不出现。
 *
 * <p><b>三份轮询合成一份</b>：原实现同时跑着「文件切换轮询」（500ms）与「开关状态轮询」
 * （200ms）两个循环，各自读同一份 SharedPreferences。这里改成——开关用偏好变更监听
 * （事件驱动，无轮询），文件切换保留一次轮询（编辑器切文件没有广播可听）。
 *
 * <p><b>线程</b>：轮询跑在 [Dispatchers.Main]。它只做「比较 File 引用」与「读偏好」两件
 * 极轻的事，放主线程反而省去切线程的开销；而 [CodeCompletionManager.setup] 内部自己
 * 会切到 IO 并延迟，不会阻塞。
 */
class CodeCompletionController(
    context: Context,
    private val host: Host,
    private val lifecycleScope: LifecycleCoroutineScope,
) {

  /**
   * 托管方需要提供的东西：当前编辑器。
   *
   * <p>不直接接收 `EditorHandlerActivity` 而用这个窄接口：本类只需要「当前编辑器」一件事，
   * 依赖整个 Activity 会把编辑界面的 Activity 层级
   * （BaseEditorActivity → ProjectHandlerActivity → EditorHandlerActivity）绑进
   * 一个补全控制器里。实现方只需覆写 `currentCodeEditor` 一个方法。
   */
  interface Host {
    /** 当前正在编辑的视图；没有打开文件时返回 null。 */
    fun currentCodeEditor(): CodeEditorView?
  }

  private val appContext: Context = context.applicationContext

  /**
   * 补全所用的 AI agent。
   *
   * <p>注意这条链路走的是**旧路径**：`AICodeCompletionService` 依赖
   * `AIAgent.generateCode`，与新的工具调用 agent（AgentOrchestrator）无关。
   * 两者目前并存，补全尚未迁到新路径。
   *
   * <p>`AIAgentManager` 内部的默认服务商是硬编码的 `"gemini"`，与用户在助手面板里
   * 选的服务商（`Agents.getProvider()`）无关。因此这里构造后显式同步一次：用户配了
   * DeepSeek 密钥、选了 deepseek，补全却因为「gemini 没有密钥」而挂不上，
   * 是没有任何界面提示的静默失效。
   */
  private val aiAgent: AIAgentManager by lazy {
    AIAgentManager(appContext).also { syncProvider(it) }
  }

  private val completionManager: CodeCompletionManager by lazy {
    CodeCompletionManager.getInstance(appContext, lifecycleScope, aiAgent)
  }

  /**
   * 把补全用的 agent 切到用户实际选择的服务商。
   *
   * @return 是否切换成功（目标服务商没有可用密钥时为 false）
   */
  private fun syncProvider(agent: AIAgentManager): Boolean {
    val selected = com.tom.rv2ide.artificial.agents.Agents(appContext).getProvider()
    if (selected == agent.getCurrentProviderId()) {
      return true
    }
    return agent.setProvider(selected)
  }

  private var fileMonitorJob: Job? = null

  /** 上次已挂载补全的文件。用它判断「编辑器切了文件」。 */
  private var lastMonitoredFile: java.io.File? = null

  /** 正在 setup 中。轮询期间跳过，避免同一个文件被重复挂载。 */
  private var settingUp = false

  private val prefs: SharedPreferences
    get() = appContext.getSharedPreferences("ai_preferences", Context.MODE_PRIVATE)

  /**
   * 开关变更监听。
   *
   * <p>用事件而不是轮询：设置页写入后系统会回调，延迟是 0；原先 200ms 轮询纯粹是为了
   * 「不引入监听器」，代价是关掉开关后补全还能多响应 200ms。
   */
  private val preferenceListener =
      SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != KEY_CODE_COMPLETION) {
          return@OnSharedPreferenceChangeListener
        }
        if (isEnabled()) {
          // 从关到开：立刻尝试挂载，不必等下一次文件轮询。
          attachToCurrentEditor()
        } else {
          completionManager.cleanup()
          lastMonitoredFile = null
        }
      }

  /** 开始托管。由 Activity 的 onResume 调用。 */
  fun start() {
    prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
    startFileMonitoring()
  }

  /**
   * 停止托管。由 Activity 的 onPause 调用。
   *
   * <p>这里**不** cleanup：Activity 只是暂时不可见（切到后台、弹了别的界面），
   * 补全的监听挂在编辑器上，重新可见时仍然有效。真正释放由 [dispose] 负责。
   */
  fun stop() {
    prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener)
    fileMonitorJob?.cancel()
    fileMonitorJob = null
  }

  /** 释放资源。由 Activity 销毁时调用。 */
  fun dispose() {
    stop()
    completionManager.cleanup()
    lastMonitoredFile = null
  }

  private fun isEnabled(): Boolean = prefs.getBoolean(KEY_CODE_COMPLETION, true)

  /**
   * 轮询当前编辑器文件，变化时重挂补全。
   *
   * <p>切文件没有可监听的广播：编辑器是动态 add 到 container 里的，也没有对外暴露
   * 切换事件，因此只能轮询。500ms 是「用户感知不到延迟」与「空转开销可忽略」的折中。
   */
  private fun startFileMonitoring() {
    fileMonitorJob?.cancel()
    fileMonitorJob =
        lifecycleScope.launch(Dispatchers.Main) {
          while (true) {
            delay(FILE_POLL_MS)
            if (settingUp || !isEnabled()) {
              continue
            }
            val current = host.currentCodeEditor()?.file
            if (current != null && current != lastMonitoredFile) {
              lastMonitoredFile = current
              attachToCurrentEditor()
            }
          }
        }
  }

  /**
   * 给当前编辑器挂上补全。
   *
   * <p>延迟一拍再取编辑器：切文件时 `getCurrentEditor()` 可能已经指向新编辑器，但它的
   * 视图还没完成布局，此时 `suggestionView` 尚未创建，直接挂会以「Editor or SuggestionView
   * is null」失败。
   */
  private fun attachToCurrentEditor() {
    if (settingUp) {
      return
    }
    settingUp = true
    lifecycleScope.launch(Dispatchers.Main) {
      delay(SETUP_DELAY_MS)
      try {
        val editorView = host.currentCodeEditor()
        val editor = editorView?.editor
        val suggestionView = editorView?.suggestionView
        if (editor == null || suggestionView == null) {
          return@launch
        }
        // 每次挂载前重新同步服务商：用户可能在两次挂载之间换了服务商，
        // 而 AIAgentManager 不会自己跟着变。
        if (!syncProvider(aiAgent)) {
          val provider = com.tom.rv2ide.artificial.agents.Agents(appContext).getProvider()
          // 区分两种失败：注册表里没有这个服务商（旧路径只实现了 6 个），
          // 与注册了但没配密钥。前者的提示若是「没有密钥」，用户会去反复检查密钥，
          // 而真正该做的是换一个服务商。
          val reason =
              if (!com.tom.rv2ide.artificial.agents.AIAgentRegistry.hasProvider(provider)) {
                "当前服务商不支持代码补全（补全走旧路径，仅支持 gemini/openai/claude/grok/deepseek/本地模型）"
              } else {
                "当前服务商没有可用的 API 密钥"
              }
          com.tom.rv2ide.ai.tool.api.ErrorLog.record("completion", "代码补全未启用：$reason", null,
              "provider=$provider")
          return@launch
        }
        completionManager.setup(editor, suggestionView, onReady = {}, onError = { e ->
          com.tom.rv2ide.ai.tool.api.ErrorLog.record(
              "completion",
              "代码补全挂载失败",
              e,
              "file=${editorView.file?.name}",
          )
        })
      } finally {
        // 必须放 finally：异常时若不解除，后面的轮询会永远以为「正在 setup」而不再尝试。
        settingUp = false
      }
    }
  }

  companion object {

    /** 补全开关的偏好键。存储名是 "ai_preferences"。 */
    const val KEY_CODE_COMPLETION = "code_completion_enabled"

    private const val FILE_POLL_MS = 500L
    private const val SETUP_DELAY_MS = 200L
  }
}
