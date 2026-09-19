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
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isVisible
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.tom.rv2ide.adapters.AssistantMessageAdapter
import com.tom.rv2ide.adapters.ConversationListAdapter
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
    /**
     * 初始形态。主页传 [Mode.SIDEBAR]（浮层，露出项目列表），
     * 编辑器传 [Mode.DOCKED]（贴右侧满高，与文件树抽屉左右对称）。
     */
    private val defaultMode: Mode = Mode.SIDEBAR,
) {

  /** 面板形态。 */
  enum class Mode {
    /** 占满可用区域，只留少量边距。 */
    FULLSCREEN,

    /** 固定宽度贴右侧的浮层，露出主屏内容。主页用这个。 */
    SIDEBAR,

    /**
     * 贴右侧满高、无外边距无圆角。编辑器用这个。
     *
     * 编辑器已经有自己的侧栏抽屉（文件树）与底部构建面板，再叠一张居中的浮层卡片
     * 会与它们争夺空间，观感上也不像编辑器的一部分。贴边后它与文件树抽屉左右对称。
     */
    DOCKED
  }

  private val fabBinding =
      LayoutAiAssistantFabBinding.inflate(LayoutInflater.from(context), parent, false)
  private val binding =
      LayoutAiAssistantBinding.inflate(LayoutInflater.from(context), parent, false)

  private val adapter = AssistantMessageAdapter()
  private val settings = AgentToolSettings(context)

  /**
   * 输入区的附加功能：模型槽位 / 附件 / 推理强度。
   *
   * <p>拆成独立对象而不是继续堆在本类里：本类已 1400 余行，主体职责是消息列表与运行循环；
   * 这三项是纯输入区的局部状态，与消息流无关。它们只在发送时被查询一次。
   */
  private val inputFeatures = AssistantInputFeatures(context, binding)

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
   * 当前正在累积的思维链块 id。
   *
   * <p>同一轮推理的增量要落到同一块里；一旦发生工具调用就置空，让下一段推理另起一块
   * ——中间隔着工具调用，合并成一块会让「这段推理属于哪一步」看不出来。
   */
  private var lastThinkingId: Long? = null

  /**
   * 会话列表适配器。
   *
   * <p>`lateinit` 而不是构造时创建：它需要 `binding`（在 attach 里才 inflate），
   * 而构造顺序上 binding 已就绪，但适配器的回调又要引用本类的方法——
   * 放在 attach 里初始化最清晰。
   */
  private lateinit var conversationAdapter: ConversationListAdapter

  /**
   * 最近一次通过列表打开的会话 id。
   *
   * <p>用来判断「被删除的是不是屏幕上正在显示的那个」。不能拿
   * `orchestrator.activeConversationId` 代替：那个值在删除时会被清空，
   * 拿它比较时已经晚了。
   */
  private var lastOpenedConversationId: String? = null

  /**
   * 本次运行是否已经通过事件流写出过文本。
   *
   * <p>决定收尾时要不要再补一条最终输出：已经流式显示过就不能再追加，否则同一段回答
   * 会在列表里出现两遍。
   */
  private var streamedThisRun = false

  private var mode = defaultMode

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
      // 上下文用量回主线程画圆环。回调来自 agent 循环线程，且每轮都会触发，
      // 因此这里只做一次「写两个字段 + invalidate」。
      setContextUsageListener { used, total ->
        lifecycleScope.launch(Dispatchers.Main) {
          lastContextUsed = used
          lastContextSize = total
          applyContextRing()
        }
      }
    }
  }

  /** 最近一次已知的上下文用量；未运行过时为 0。 */
  private var lastContextUsed = 0

  /** 当前模型的上下文窗口；0 表示未配置。 */
  private var lastContextSize = 0

  /** 是否已展开面板。 */
  val isOpen: Boolean
    get() = binding.assistantOverlay.isVisible

  /**
   * 首次显示时按钮距底边的额外距离。
   *
   * <p>编辑界面底部常驻一个折叠态的 bottom sheet（约 56dp + 导航栏），默认落点若只留
   * 16dp 会正好压在上面。由调用方传入该高度，避免把编辑界面的布局细节写进这个通用视图。
   */
  var defaultBottomOffsetPx: Int = 0

  /** 把两个视图挂到父容器上。父容器应是 `FrameLayout`（FAB 靠 gravity 定位）。 */
  fun attach() {
    // FAB 在 XML 里只有固定尺寸、没有 gravity；放进 FrameLayout 时必须显式给右下角，
    // 否则会落在左上角盖住标题。
    fabBinding.root.layoutParams =
        FrameLayout.LayoutParams(dp(56), dp(56)).apply {
          gravity = Gravity.END or Gravity.BOTTOM
          marginEnd = dp(16)
          bottomMargin = dp(16) + defaultBottomOffsetPx
        }
    parent.addView(fabBinding.root)
    parent.addView(binding.assistantOverlay)

    setUpDragging()

    applyMode(defaultMode)

    binding.assistantMessages.layoutManager = LinearLayoutManager(context)
    binding.assistantMessages.adapter = adapter

    adapter.setOnRevertClickListener { messageId, diffId -> revertDiff(messageId, diffId) }
    // 工具卡片的分类色需要按工具名查注册表。注册表在 orchestrator 里，
    // 但适配器不该依赖工具执行层，因此注入一个只做名字→分类映射的窄接口。
    val registry = orchestrator.buildRegistry()
    adapter.setCategoryResolver { toolName ->
        registry.getCachedDisplayCategory(com.tom.rv2ide.ai.tool.ToolRegistry.canonicalName(toolName))
    }

    // 不设 OnClickListener：拖动用的 OnTouchListener 会消费全部事件，click 永远不会触发。
    // 打开面板的动作用 ACTION_UP 且未进入拖动时手动调用 open()（见 setUpDragging）。
    binding.assistantSend.setOnClickListener { onSendClicked() }
    // 工具条上的服务商与模型标签：两者都点开同一个选择器。
    // 分成两个可点控件而不是合成一个：服务商名与模型名各自独立省略，
    // 窄面板下仍能读出「哪个服务商」；合成一段时两段文字会一起被压成省略号。
    binding.assistantToolbarModel.setOnClickListener {
      AssistantModelPicker.show(context) { refreshModelLabel() }
    }
    binding.assistantToolbarProvider.setOnClickListener {
      AssistantModelPicker.show(context) { refreshModelLabel() }
    }
    // 上下文圆环：点开占用详情，并就地提供「压缩上下文」入口。
    binding.assistantContextRing.setOnClickListener { showContextUsage() }
    // 权限模式标签：点开三档选择。改完立即生效——权限判定每次工具调用都重读偏好。
    binding.assistantPermissionChip.setOnClickListener { showPermissionPicker() }
    // git 分支标签：点它刷新一次（外部可能在终端里切过分支）。
    binding.assistantGitChip.setOnClickListener { refreshGitBranch() }
    inputFeatures.onNotice = { appendTrace(it) }
    // 槽位切换发生在 inputFeatures 内部，而模型标签由本类渲染——切完要刷新。
    inputFeatures.onModelChanged = {
      refreshModelLabel()
      // 换模型 = 换窗口，旧用量对新窗口无意义。
      refreshContextRingFromConfig()
    }

    // 标题栏：左菜单开抽屉，右侧全屏/最小化/关闭。
    binding.assistantMenu.setOnClickListener { toggleConversationPanel() }
    binding.assistantTodos.todoHeader.setOnClickListener { toggleTodos() }
    binding.assistantFullscreen.setOnClickListener { toggleFullscreen() }
    // 最小化与关闭都是收起面板（再点 FAB 可打开），行为一致，语义不同：
    // 关闭是「我不需要它了」，最小化是「先收起来，等下还要用」。
    // 两者都保留面板状态，不做额外区分——差别只在用户的预期，不在实现。
    binding.assistantMinimize.setOnClickListener { close() }
    binding.assistantClose.setOnClickListener { close() }

    // 抽屉遮罩点击关闭。
    binding.assistantDrawerScrim.setOnClickListener { toggleConversationPanel() }

    // 抽屉底部的三个动作（原溢出菜单的去处）。
    binding.assistantNewConversation.setOnClickListener {
      toggleConversationPanel()
      startNewConversation()
    }
    binding.assistantCopyConversation.setOnClickListener { copyConversation() }
    binding.assistantSettings.setOnClickListener { openAssistantSettings() }

    refreshModelLabel()

    // 会话列表
    conversationAdapter =
        ConversationListAdapter(
            onOpen = { summary -> openConversation(summary) },
            onDelete = { summary -> confirmDeleteConversation(summary) },
        )
    binding.assistantConversations.layoutManager = LinearLayoutManager(context)
    binding.assistantConversations.adapter = conversationAdapter

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

    // 工具条控件的初始状态。
    setRunningUi(false)
    applyToolbarDensity()
    // 上下文窗口在「还没运行过」时也要能判断：模型配了窗口才显示圆环，
    // 否则圆环是空的、点了只说「未知」，不如直接不显示。
    refreshContextRingFromConfig()
  }

  /**
   * 用当前模型配置初始化圆环。
   *
   * <p>只在没有实际用量时用（首次打开、切了模型）：有真实用量时应该显示那个值，
   * 而不是被重置成 0。切换模型会改变窗口大小，此时旧用量对新窗口无意义，
   * 因此一并清掉。
   *
   * <p>走 IO 线程：`contextSizeForActiveConfig` 会读服务商配置文件
   * （`ProviderConfigStore.load()` 每次从磁盘解析 JSON），放主线程会在
   * 打开面板时卡一下。这是「圆环怎么显示」的装饰性判断，晚几十毫秒无妨。
   */
  private fun refreshContextRingFromConfig() {
    lastContextUsed = 0
    lifecycleScope.launch(Dispatchers.IO) {
      val size = orchestrator.contextSizeForActiveConfig()
      withContext(Dispatchers.Main) {
        lastContextSize = size
        applyContextRing()
      }
    }
  }

  /**
   * 按当前已知用量渲染圆环，并写好无障碍描述。
   *
   * <p>描述必须**在这里**渲染好再设进去：XML 的 `android:contentDescription="@string/…"`
   * 不做占位符替换，无障碍服务会逐字读出「上下文占用 %1$s」（实测 UI dump 里
   * 就是这个字符串）。
   */
  private fun applyContextRing() {
    val ring = binding.assistantContextRing
    val total = lastContextSize
    if (total <= 0) {
      ring.clear()
      return
    }
    val percent = (lastContextUsed * 100 / total).coerceIn(0, 100)
    ring.show(
        lastContextUsed.toFloat() / total,
        context.getString(
            string.ai_assistant_context_usage_short,
            percent,
            formatTokens(lastContextUsed),
            formatTokens(total),
        ),
    )
  }

  /** 设置工作区。主屏上用户可能先选项目，因此每次打开面板前都更新。 */
  fun setWorkspace(workspace: java.io.File?) {
    this.workspace = workspace
    orchestrator.workspace = workspace
    // 换了项目就换了仓库：分支标签要重新读，且上一个项目的分支不能留着。
    lastGitBranch = null
    refreshGitBranch()
  }

  /**
   * 让悬浮按钮可拖动，并把位置持久化。
   *
   * <p><b>为什么用绝对坐标 + FrameLayout 而非继续用 gravity</b>：拖动后要停留在用户
   * 松手的位置，gravity 只有 9 个离散值，表达不了。改成 LEFT|TOP + margin 后位置连续可调。
   *
   * <p><b>拖动与点击的区分</b>：按下后先不判定，只有当位移超过 touchSlop 才进入拖动模式，
   * 否则抬手时仍走 click 打开面板。若直接用 ACTION_DOWN 启动拖动，轻点就会变成「拖动 0 像素」，
   * 面板再也打不开。
   *
   * <p><b>边界钳制</b>：拖动范围限制在父容器内，且底部额外留出系统导航栏高度——否则用户能把
   * 按钮拖到导航键下面，之后既看不见也点不到。
   */
  private fun setUpDragging() {
    val fab = fabBinding.root
    val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop
    var downRawX = 0f
    var downRawY = 0f
    var startX = 0
    var startY = 0
    var dragging = false

    restoreFabPosition()

    fab.setOnTouchListener { _, event ->
      when (event.actionMasked) {
        android.view.MotionEvent.ACTION_DOWN -> {
          downRawX = event.rawX
          downRawY = event.rawY
          val lp = fab.layoutParams as FrameLayout.LayoutParams
          startX = lp.leftMargin
          startY = lp.topMargin
          dragging = false
          true
        }
        android.view.MotionEvent.ACTION_MOVE -> {
          val dx = event.rawX - downRawX
          val dy = event.rawY - downRawY
          if (!dragging && (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop)) {
            dragging = true
          }
          if (dragging) {
            moveFabTo(startX + dx.toInt(), startY + dy.toInt())
          }
          true
        }
        android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
          if (dragging) {
            // 拖动结束不回弹，直接记住落点——用户摆哪儿就是哪儿。
            persistFabPosition()
          } else {
            open()
          }
          dragging = false
          true
        }
        else -> false
      }
    }
  }

  /**
   * 把按钮移到父容器内的 (x, y)，越界则钳制到合法范围。
   *
   * <p>四周留 [EDGE_MARGIN_DP] 的边距：贴到 0 会让按钮的圆角与屏幕边缘相切，
   * 看起来像被裁掉了，而且贴边后很难再按住拖回来。
   */
  private fun moveFabTo(x: Int, y: Int) {
    val fab = fabBinding.root
    val lp = fab.layoutParams as FrameLayout.LayoutParams
    lp.gravity = Gravity.START or Gravity.TOP
    val margin = dp(EDGE_MARGIN_DP)
    val minX = margin
    val minY = margin
    val maxX = (parent.width - fab.width - margin).coerceAtLeast(minX)
    val maxY = (parent.height - fab.height - bottomInset() - margin).coerceAtLeast(minY)
    lp.leftMargin = x.coerceIn(minX, maxX)
    lp.topMargin = y.coerceIn(minY, maxY)
    fab.layoutParams = lp
  }

  /** 底部安全距离：系统导航栏高度。取不到时退回 0。 */
  private fun bottomInset(): Int =
      androidx.core.view.ViewCompat.getRootWindowInsets(parent)
          ?.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
          ?.bottom ?: 0

  private fun fabPrefs() =
      androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)

  /** 保存落点。存的是相对父容器左上角的像素偏移。 */
  private fun persistFabPosition() {
    val lp = fabBinding.root.layoutParams as FrameLayout.LayoutParams
    fabPrefs()
        .edit()
        .putInt(PREF_FAB_X, lp.leftMargin)
        .putInt(PREF_FAB_Y, lp.topMargin)
        .apply()
  }

  /**
   * 恢复上次的落点。
   *
   * <p>在布局完成后再恢复：此时 parent.width/height 才有真实值，否则钳制会按 0 计算，
   * 位置一律被压到左上角。
   */
  private fun restoreFabPosition() {
    val prefs = fabPrefs()
    if (!prefs.contains(PREF_FAB_X)) {
      return
    }
    val x = prefs.getInt(PREF_FAB_X, 0)
    val y = prefs.getInt(PREF_FAB_Y, 0)
    parent.post { moveFabTo(x, y) }
  }

  fun open() {
    fabBinding.assistantFab.isVisible = false
    binding.assistantOverlay.isVisible = true
    // 服务商/模型可能刚在设置页被改过，打开时重读一次。只在 attach 时读会让
    // 「设置里改了、回到面板显示的还是旧值」。
    refreshModelLabel()
    // 权限模式同理：设置页改完回来要看到新值。
    refreshPermissionChip()
    // 模型可能被换过，窗口大小随之变化——旧用量对新窗口无意义，重置。
    refreshContextRingFromConfig()
    // **必须在这里重算工具条密度**：本方法之前的每一次 applyToolbarDensity() 调用
    // （attach、setWorkspace、applyMode）都发生在面板仍为 GONE 时，那时
    // bar.width == 0，函数会提前返回。面板刚可见的这一刻才是第一次能测到真实宽度。
    //
    // 不在这里重算的实测后果：贴边（DOCKED）形态下面板宽 734px，工具条只有 243dp，
    // 而服务商标签（阈值 300dp 才隐藏）不会隐藏，模型名被挤到不足 20dp——
    // 只剩一个省略号。这正是阈值逻辑看起来「没生效」的原因。
    applyToolbarDensity()
    // 首次打开时回放已恢复的会话。放在 open() 而不是 attach()：
    // attach 发生在 Activity onCreate 期间，此时读磁盘会拖慢启动；
    // 而用户看到面板时再加载，感知上反而更快。
    restoreConversationIfNeeded()
    refreshTodos()
    updateEmptyState()
  }

  /**
   * 首次打开面板时，把「本工作区上次打开的会话」回放到列表。
   *
   * <p>只在首次做：后续 open/close 不该重复回放——那会把用户当前正在进行的
   * 对话重置回历史状态，看起来像消息凭空消失。
   */
  private fun restoreConversationIfNeeded() {
    if (restoredOnce) {
      return
    }
    restoredOnce = true
    val id = orchestrator.activeConversationId
    if (id.isNullOrEmpty() || adapter.itemCount > 0) {
      return
    }
    lifecycleScope.launch(Dispatchers.IO) {
      val messages = orchestrator.loadConversationMessages(id)
      if (messages.isEmpty()) {
        return@launch
      }
      withContext(Dispatchers.Main) {
        replayMessages(messages)
        lastOpenedConversationId = id
        updateEmptyState()
      }
    }
  }

  /** 是否已尝试过恢复会话；避免每次 open() 都回放。 */
  private var restoredOnce = false

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

  /**
   * 在全屏与「常态」之间切换。
   *
   * <p>常态是宿主的初始形态（主页 SIDEBAR、编辑器 DOCKED），不是写死的 SIDEBAR：
   * 编辑器里退出全屏必须回到贴边形态，回到浮层会让面板又变成盖在代码上的卡片。
   */
  private fun toggleFullscreen() {
    applyMode(if (mode == Mode.FULLSCREEN) defaultMode else Mode.FULLSCREEN)
  }

  private fun applyMode(newMode: Mode) {
    mode = newMode
    val card = binding.assistantCard
    val params = card.layoutParams as ConstraintLayout.LayoutParams

    // 全屏按钮的语义随当前形态翻转：全屏时说「退出全屏」，否则说「全屏」。
    // 写进 contentDescription 而不是按钮文字（按钮是图标），无障碍服务读它。
    binding.assistantFullscreen.contentDescription =
        context.getString(
            if (newMode == Mode.FULLSCREEN) string.ai_assistant_side
            else string.ai_assistant_fullscreen
        )

    when (newMode) {
      Mode.FULLSCREEN -> {
        params.width = ViewGroup.LayoutParams.MATCH_PARENT
        params.height = ViewGroup.LayoutParams.MATCH_PARENT
        params.marginStart = dp(8)
        params.marginEnd = dp(8)
        params.topMargin = dp(12)
        params.bottomMargin = dp(12)
        card.radius = dp(20).toFloat()
        card.strokeWidth = dp(1)
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
        params.topMargin = dp(12)
        params.bottomMargin = dp(12)
        card.radius = dp(20).toFloat()
        card.strokeWidth = dp(1)
      }
      Mode.DOCKED -> {
        // 贴右侧满高、无外边距、无圆角。
        //
        // 与 SIDEBAR 的区别不只是宽度：浮层形态（圆角 + 四周留白）传达的是
        // 「这是一张盖在内容上的卡片」，而编辑器需要的是「这是界面的一半」。
        // 留白和圆角会立刻把面板变回弹窗观感——这正是之前"割裂感"的来源之一。
        //
        // 宽度：屏幕的 68%，并夹在 [260dp, min(420dp, 屏宽-100dp)] 之间。
        // 四个约束各解决一件事：
        // - 260dp 下限：标题栏有 1 个左按钮 + 3 个右按钮，每个都是 48dp 的可点区域
        //   （无障碍最小触控尺寸，不能再压），共 192dp。低于 260dp 时标题会被挤成
        //   「AI …」——实测 236dp 面板就是这样。
        // - 420dp 上限：平板上不限宽会让面板宽到像全屏，失去"贴在一边"的意义
        // - 屏宽-100dp：手机竖屏下要给编辑器留出可见宽度，否则用户看不见自己在改
        //   哪个文件。这条在小屏上通常是最紧的约束。
        //
        // 下限必须再对上限取一次 min：窄屏上「屏宽-100dp」可能小于 260dp，
        // 直接 coerceIn(260dp, 那个值) 会抛
        // IllegalArgumentException: Cannot coerce value to an empty range。
        val screenWidth = parent.resources.displayMetrics.widthPixels
        val upper = minOf(dp(420), screenWidth - dp(100))
        val lower = minOf(dp(260), upper)
        params.width = (screenWidth * 0.68f).toInt().coerceIn(lower, upper)
        params.height = ViewGroup.LayoutParams.MATCH_PARENT
        params.marginStart = 0
        params.marginEnd = 0
        params.topMargin = 0
        params.bottomMargin = 0
        card.radius = 0f
        // 保留 1dp 描边：面板与编辑器内容用的是同一个 colorSurface，
        // 不画边界时两者连成一片，看不出面板从哪里开始。
        // 描边在上下右三边正好压在屏幕边缘（不可见），实际只起左分界线的作用。
        card.strokeWidth = dp(1)
      }
    }

    // 水平对齐方向。
    //
    // 面板在 ConstraintLayout 里同时被 start/end 约束，宽度固定时**默认居中**——
    // DOCKED 形态不设 bias 会落在屏幕中间，看起来还是浮窗而不是贴边面板。
    // 这里显式指定：DOCKED 靠右，其余两种都占满宽度、bias 无影响（设 0.5 保持一致）。
    params.horizontalBias = if (newMode == Mode.DOCKED) 1f else 0.5f

    card.layoutParams = params

    // 换形态就换了宽度，工具条能放下几个控件随之变化，必须重算。
    //
    // 不重算的后果是单向的：窄形态（贴边）下隐藏了 git 分支标签，之后切到全屏
    // 也不会重新显示——用户会觉得「全屏了还是少个东西」。反方向同理。
    applyToolbarDensity()
  }

  private fun dp(value: Int): Int =
      (value * parent.resources.displayMetrics.density).toInt()

  private fun sendFromInput() {
    val typed = binding.assistantInput.text?.toString()?.trim().orEmpty()
    val attachments = inputFeatures.attachmentContext()
    // 「只附了文件、没打字」也是有效请求：用户附一张图再点发送是很自然的动作，
    // 按空文本丢弃会让附件静默消失。此时用附件名当占位文本，至少列表里看得出
    // 这条消息带了什么。
    if (typed.isEmpty() && attachments.isEmpty()) {
      return
    }

    // 斜杠命令是纯本地操作：不发给模型、不消耗额度。
    // 只有已知命令名才算命令——「/etc/hosts 是干什么的」是普通消息（见 SlashCommandCatalog）。
    //
    // 命令分支**不清空附件**：命令不消费附件，顺手清掉等于把用户刚选的文件弄丢了，
    // 而他只是先发了一条 /help。
    if (typed.isNotEmpty()) {
      val parsed = com.tom.rv2ide.ai.agent.command.SlashCommandCatalog.parse(typed)
      if (parsed.isCommand) {
        binding.assistantInput.setText("")
        handleCommand(parsed)
        return
      }
    }

    val request = if (typed.isEmpty()) inputFeatures.attachmentNames() else typed
    // 图片走多模态通道：打包成协议层认识的 payload，而不是把路径写进文本。
    // 必须在 clearAttachments() 之前构建——清空后就读不到附件了。
    val imagePayload = inputFeatures.imageRawInputJson(request)
    // 有图片时，附件文本里要剔除那一张（它已由 payload 承载），否则模型会同时
    // 收到「图片内容」和「图片路径」两份信息，可能重复处理或困惑于该用哪个。
    val textAttachments = if (imagePayload != null) inputFeatures.attachmentContextExcludingImage() else attachments

    binding.assistantInput.setText("")
    inputFeatures.clearAttachments()
    // 非图片附件仍以文本形式追加在用户请求之后。空串时拼接结果与原来完全一致，
    // 保证无附件路径的行为不变。
    //
    // 推理强度随请求走：它在输入区可选，而每轮都可能被改，因此每次发送都重读一次，
    // 而不是在 attach 时读一次缓存。
    execute(request + textAttachments, inputFeatures.reasoningEffort(), imagePayload)
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

      com.tom.rv2ide.ai.agent.command.SlashCommandCatalog.Kind.COMPACT ->
          compactConversation()

      else -> {}
    }
  }

  /**
   * 手动压缩当前会话的上下文。
   *
   * <p>与参考项目（cc-haha）一致，触发方式是发一条 `/compact` 命令而不是单独的 API——
   * 压缩本身由 {@code AgentOrchestrator} 内部的 ContextCompactor 完成，这里只负责
   * 发起与呈现结果。
   *
   * <p>运行中不允许压缩：压缩要重写会话历史，而正在进行的运行还在往历史里追加，
   * 两者并发会写出错乱的记录。
   */
  private fun compactConversation() {
    if (executionJob?.isActive == true) {
      appendTrace(context.getString(string.ai_assistant_compact_busy))
      return
    }
    lifecycleScope.launch(Dispatchers.IO) {
      val result =
          try {
            orchestrator.compactActiveConversation()
          } catch (e: Exception) {
            com.tom.rv2ide.ai.tool.api.ErrorLog.record("agent", "手动压缩失败", e, null)
            null
          }
      withContext(Dispatchers.Main) {
        if (result == null) {
          appendTrace(context.getString(string.ai_assistant_compact_failed))
          return@withContext
        }
        appendTrace(context.getString(string.ai_assistant_compact_done, result))
      }
    }
  }

  private fun execute(
      userRequest: String,
      reasoningEffort: String? = null,
      imagePayload: String? = null,
  ) {
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
    lastThinkingId = null

    executionJob =
        lifecycleScope.launch(Dispatchers.IO) {
          val agents = Agents(context)
          val providerId = agents.getProvider()
          // 传当前槽位：此前不传，modelIdFor 无条件用主模型，
          // 于是界面「切到 Opus」改了偏好但请求仍发主模型（假反馈）。
          val modelId =
              AgentModelConfigs.modelIdFor(
                  providerId,
                  agents.getAgent(),
                  inputFeatures.currentSlot(),
              )
          val customBaseUrl = AgentOrchestrator.customBaseUrlFor(context, providerId)

          withContext(Dispatchers.Main) {
            // 运行中把发送按钮换成「停止」。原先只是置灰：用户看到一个灰掉的按钮，
            // 既不知道能不能中断，也不知道它什么时候会恢复——而 agent 跑几分钟是常态。
            // 换成停止按钮之后，同一个位置始终是可点的，且语义随状态翻转。
            setRunningUi(true)
            // 运行中清空摘要行：上一次的「已完成 · 3 轮」留在那里会与正在进行的运行
            // 混在一起，看起来像这次已经结束了。运行状态由下方的 WorkingStatusView 承担。
            setStatus(null)
            // 首字节可能要等好几秒，静态文字无法区分「在工作」和「卡死了」。
            binding.assistantWorking.bind(isThinking = false)
            binding.assistantWorking.startWorking()
          }

          try {
            val result =
                orchestrator.run(
                    providerId,
                    modelId,
                    userRequest,
                    customBaseUrl,
                    { event -> handleEvent(event) },
                    imagePayload,
                    reasoningEffort,
                )
            withContext(Dispatchers.Main) {
              finishStreaming()
              // 已经流式显示过就不再追加：否则同一段回答会出现两遍。
              if (!streamedThisRun) {
                appendAssistant(
                    result.output.ifBlank { context.getString(string.ai_assistant_no_output) }
                )
              }
              setStatus(
                  context.getString(
                      if (result.isFailed) string.ai_assistant_status_failed
                      else string.ai_assistant_status_done,
                      result.turns,
                      result.toolCallCount,
                  ))
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
            // 否则按钮会永久停留在「停止」态，用户再也发不出请求。
            withContext(kotlinx.coroutines.NonCancellable) {
              withContext(Dispatchers.Main) {
                setRunningUi(false)
                // 状态条同理：不在这里停，取消/异常后动画会一直转，
                // 看起来像还在跑。
                binding.assistantWorking.stopWorking()
                // 重试卡片收掉——**除非它正在报告「重试用尽」**。
                // 那条信息必须留在屏幕上：用户需要知道失败前重试过几次，
                // 而运行结束后再没有任何地方会显示它。
                if (!retryCardPinned) {
                  hideRetryCard()
                }
              }
            }
          }
        }
  }

  private fun handleEvent(event: com.tom.rv2ide.ai.agent.AgentEvent) {
    when (event.type) {
      com.tom.rv2ide.ai.agent.AgentEvent.Type.REASONING_DELTA -> {
        val delta = event.message
        if (delta.isEmpty()) {
          return
        }
        lifecycleScope.launch(Dispatchers.Main) {
          // 返回 -1 表示「纯空白、没建块」——不能把它存进 lastThinkingId，
          // 否则后续增量会去找一个不存在的 id。
          val id = adapter.appendThinking(delta, lastThinkingId)
          if (id >= 0) {
            lastThinkingId = id
          }
          // 收到推理增量 = 模型在思考。这个区分有实际意义：推理模型思考半分钟是正常的，
          // 显示「思考中」用户不会以为出了问题。
          //
          // 清掉工具动作：上一个工具已经结束，此时模型回到推理阶段，状态条继续写着
          // 「正在读取 xxx」会让人以为文件还没读完。
          binding.assistantWorking.setAction(null)
          binding.assistantWorking.bind(isThinking = true)
          if (throttle.onDelta()) {
            scrollToBottom()
          }
        }
      }
      com.tom.rv2ide.ai.agent.AgentEvent.Type.TEXT_DELTA -> {
        val delta = event.message
        if (delta.isEmpty()) {
          return
        }
        // 累积始终发生在数据层（不能丢内容），只有界面刷新被节流。
        lifecycleScope.launch(Dispatchers.Main) {
          val id = streamingMessageId
          if (id == null) {
            // 还没有气泡时，纯空白增量不建气泡。模型「只调用工具、不写正文」的轮次
            // 会先吐出 "\n\n"，那时建出的气泡最终没有内容，渲染成一片空白灰块。
            // 等真正有内容的增量到了再建。
            if (delta.isBlank()) {
              return@launch
            }
            streamingMessageId =
                adapter.append(AssistantMessageAdapter.Role.ASSISTANT, delta)
          } else {
            adapter.appendTo(id, delta)
          }
          // 开始输出正文 = 思考结束，进入「生成回复」。
          // 同样清掉工具动作——正文已经在流出来了，状态条不该还说「正在执行 xxx」。
          binding.assistantWorking.setAction(
              context.getString(string.ai_assistant_work_generating))
          binding.assistantWorking.bind(isThinking = false)
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
          val id = streamingMessageId
          if (text.isNotBlank()) {
            // streamedThisRun 只在**确实往列表里写过内容**时置位。
            // 原先无条件置 true 会掩盖一条路径：没有流式增量（非流式响应）时 id 为 null，
            // 此时若 text 恰好为空，就既没追加本轮输出、又让收尾逻辑以为「已经显示过」，
            // 于是整轮回答在界面上彻底消失。
            streamedThisRun = true
            if (id != null) {
              // 只有非空才覆盖。该轮的规范输出为空是常见情况——模型这一轮只发起工具调用、
              // 没写正文（output 已被 ToolCallTextParser 剥掉标记后变成空串）。
              // 无条件覆盖会把已经流式显示出来的正文抹掉，用户看到气泡突然变空。
              adapter.update(id, text)
            } else {
              appendAssistant(text)
            }
          } else if (id != null) {
            // 本轮没有正文，但流式阶段建过气泡（增量全是空白，或后来被覆盖成空）。
            // 留着就是一个内容为空的灰块——删掉，工具卡片自己已经说明了这一步做了什么。
            adapter.removeIfBlank(id)
            streamingMessageId = null
          }
          // 本轮推理到此结束。AgentSession 的事件顺序是 TURN_FINISHED 先于该轮的
          // TOOL_STARTED，所以在这里收尾最准；置空后，工具之后的新推理会另起一块。
          lastThinkingId?.let { adapter.finishThinking(it) }
          lastThinkingId = null
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
          // 发生工具调用意味着「这一段推理结束了」：把思维链块收尾并断开，
          // 让工具之后的新推理另起一块。否则整轮的推理会堆在同一块里，
          // 看不出哪段推理导致了哪次调用。
          lastThinkingId?.let { adapter.finishThinking(it) }
          lastThinkingId = null
          // 底部状态条播报这一步在做什么。放在这里（而不是 TOOL_FINISHED）：
          // 用户需要的是「现在在跑什么」，「刚刚跑完了什么」工具卡片已经写了。
          showAction(actionForTool(call.name, call.arguments))
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

          // 每次工具结束都刷新任务卡片。看起来比必要的频繁，但 todo_update 是
          // 唯一会改清单的工具，而这里无法预知下一个调用是不是它——按名字判断
          // 会把「工具改名」变成静默失效（卡片不再更新，且没有报错）。
          // 读的是内存中的清单，代价只有一次列表拷贝。
          refreshTodos()
        }
      }
      com.tom.rv2ide.ai.agent.AgentEvent.Type.STREAM_RETRYING -> {
        // 丢弃本轮已渲染的部分输出。
        //
        // 重发会产生一份**全新的**回答，协议层的累积状态已重建。旧的部分若留在
        // 列表里，用户会看到两段内容并存（而且第二段从中间开始，读起来像乱码）。
        //
        // 必须同时清掉推理块与工具卡片状态：推理块与正文同属本轮输出；
        // 而 lastToolCardId 指向的卡片若留着，重发后 TOOL_FINISHED 会回填到
        // 一张属于上一次尝试的卡片上。
        //
        // 事件同时携带 attempt / maxAttempts / delayMs，用来渲染底部固定卡片
        // （见 showRetryCard）。**不再往消息列表追加**：一次运行最多重试 10 次，
        // 每次插一条会把对话流刷满，而用户只关心「现在第几次、还要等多久」。
        lifecycleScope.launch(Dispatchers.Main) {
          streamingMessageId?.let { adapter.remove(it) }
          streamingMessageId = null
          lastThinkingId?.let { adapter.remove(it) }
          lastThinkingId = null
          lastToolCardId = null
          streamedThisRun = false
          // 节流器要重置：它记着「本轮是否已刷过」，不重置会让重发后的首个增量
          // 被当成「间隔未到」而丢掉，看起来像新回答迟迟不出现。
          throttle.reset()
          retryCountThisRun = Math.max(retryCountThisRun, event.retryAttempt)
          showRetryCard(
              event.retryAttempt,
              event.retryMaxAttempts,
              event.retryDelayMs,
              event.retryReason,
          )
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
          // 思维链同理：失败时标题必须从「思考中…」切走，否则看起来像还在等。
          adapter.finishAllThinking()
          lastThinkingId = null
          // 重试卡片留在原地改成「重试 N 次仍失败」，而不是直接收掉。
          //
          // **这条是实测反馈的直接来源**：此前重试卡片还没实现，重试信息走
          // appendTrace 进消息列表，用户看到的是「正在重试 1/10」紧接着「请求失败」——
          // 中间九次重试去哪了完全看不出来。留在原地并写明次数，
          // 「重试到第几次才放弃」才是可读的。
          if (retryCountThisRun > 0) {
            binding.assistantRetryCard.isVisible = true
            binding.assistantRetryText.text =
                context.getString(string.ai_assistant_retry_exhausted, retryCountThisRun) +
                    shortErrorCode(event.message).let { if (it.isEmpty()) "" else " · $it" }
            retryCardPinned = true
          }
          appendTrace("⚠️ ${event.message}")
        }
      }
      else -> {}
    }
  }

  /**
   * 发送按钮的点击：运行中 = 停止，空闲 = 发送。
   *
   * <p>一个按钮承担两个动作，而不是在运行时禁用发送、另加一个停止按钮：
   * 工具条在贴边形态下整行只有约 236dp，多一个按钮就要挤掉模型标签或圆环。
   * 而「发送」与「停止」本来就是同一位置的互斥状态，参考项目（cc-haha）
   * 也是同一个按钮换图标。
   */
  private fun onSendClicked() {
    if (executionJob?.isActive == true) {
      cancel()
      // 取消后立刻切回发送态：用户点这个按钮的意图是「我现在要输入」，
      // 让他等 finally 里的恢复会有一段「点了没反应」的空窗。
      setRunningUi(false)
      return
    }
    sendFromInput()
  }

  /**
   * 切换发送按钮的运行态外观，并显示/隐藏底部动作条。
   *
   * <p>运行态的信号有三处，缺一不可：
   * <ol>
   *   <li>发送按钮变成停止（error 配色的方块）——「能中断」这件事必须可见
   *   <li>底部动作条出现（点阵动画 + 当前动作文案）——「在做什么」必须可见
   *   <li>按钮本身**不禁用**——禁用会让停止这个动作也无从触发
   * </ol>
   * 此前只有「按钮置灰」一条，用户既不知道能不能中断，也看不出 agent 在读文件还是卡住了。
   */
  private fun setRunningUi(running: Boolean) {
    val send = binding.assistantSend
    send.setIconResource(
        if (running) com.tom.rv2ide.R.drawable.ic_stop_generation
        else com.tom.rv2ide.R.drawable.ic_arrow_upward)
    send.contentDescription =
        context.getString(
            if (running) string.ai_assistant_stop else string.ai_assistant_send)
    // 运行中用 errorContainer 底 + onErrorContainer 图标：与「发送」的实心主色形成
    // 明确对比，扫一眼就知道现在处于哪个状态。
    //
    // attr 的命名空间要逐个确认，写错编译不过：
    // - colorPrimary 只在框架里（android.R.attr），Material 的 R.attr 里没有
    // - colorOnPrimary 只在 Material 里（Material 的 R.attr），框架里没有
    // - colorErrorContainer / colorOnErrorContainer 都是 Material 独有
    val bgAttr =
        if (running) com.google.android.material.R.attr.colorErrorContainer
        else android.R.attr.colorPrimary
    val fgAttr =
        if (running) com.google.android.material.R.attr.colorOnErrorContainer
        else com.google.android.material.R.attr.colorOnPrimary
    send.setBackgroundTintList(
        android.content.res.ColorStateList.valueOf(MaterialColors.getColor(send, bgAttr)))
    send.setIconTint(
        android.content.res.ColorStateList.valueOf(MaterialColors.getColor(send, fgAttr)))

    // 状态条与分隔线在这里一并显隐。
    //
    // **此前它们从未显示过**：XML 里两者都是 visibility="gone"，而代码只调用了
    // startWorking()/stopWorking()——那两个方法只管动画，不管可见性。结果是
    // 动画在一个 GONE 的视图上跑，用户什么都看不到，界面上唯一的运行信号就是
    // 「发送按钮变灰」。这正是「工作状态显示不够明显」的直接原因。
    binding.assistantWorking.isVisible = running
    binding.assistantWorkingDivider.isVisible = running

    if (running) {
      // 新一轮开始：清掉上一轮留下的重试结论与计数。
      retryCountThisRun = 0
      retryCardPinned = false
      hideRetryCard()
    }
  }

  /** 隐藏重试卡片。 */
  private fun hideRetryCard() {
    binding.assistantRetryCard.isVisible = false
  }

  /**
   * 本次运行发生过几次重试。
   *
   * <p>用来在最终失败时补一句「重试 N 次仍失败」：否则用户看到重试卡片一闪、
   * 然后直接是失败提示，无法判断到底是「重试都没成功」还是「重试被拒绝了」。
   * 这两种情况的可操作性完全不同——前者等网络恢复即可，后者要去看错误码。
   */
  private var retryCountThisRun = 0

  /**
   * 重试卡片是否处于「钉住」状态（正在显示「重试用尽」的结论）。
   *
   * <p>钉住时不被运行结束的收尾逻辑清掉。下一次运行开始时由
   * [setRunningUi] 解开——否则上一轮的结论会一直挂在新一轮的对话上。
   */
  private var retryCardPinned = false

  /**
   * 显示/更新重试卡片。
   *
   * <p><b>为什么做成固定卡片而不是往消息列表里追加一行</b>：一次运行最多重试 10 次，
   * 每次追加一行会把对话流刷满，而用户只关心「现在第几次、还要等多久」。固定卡片
   * 原地更新，对话流保持干净。这也是参考项目（cc-haha）的做法——它的重试提示
   * 固定在输入区上方，并且**前 3 次不显示**（`retryAttempt < 4` 时 return null）：
   * 偶发的一两次重连不值得打扰用户，连续失败才需要让人知道。
   *
   * <p>错误码单独拼在尾部：只写「连接中断」用户无法区分「网络抖动」与
   * 「服务端 503」，而这两者的应对完全不同（前者等，后者可能要去换模型）。
   */
  private fun showRetryCard(attempt: Int, maxAttempts: Int, delayMs: Long, reason: String) {
    if (!AssistantActionText.shouldShowRetry(attempt)) {
      return
    }
    binding.assistantRetryCard.isVisible = true
    val seconds = Math.max(0L, delayMs / 1000L)
    val base =
        if (delayMs > 0) {
          context.getString(string.ai_assistant_retry_retrying, seconds, attempt, maxAttempts)
        } else {
          context.getString(string.ai_assistant_retry_waiting, attempt, maxAttempts)
        }
    val code = AssistantActionText.shortErrorCode(reason)
    binding.assistantRetryText.text = if (code.isEmpty()) base else "$base · $code"
  }

  /** 见 [AssistantActionText.shortErrorCode]。 */
  private fun shortErrorCode(reason: String): String = AssistantActionText.shortErrorCode(reason)

  /**
   * 把当前动作播报到状态条上。
   *
   * <p>由 [handleEvent] 在 TOOL_STARTED 时调用，文案按工具名与参数拼。
   * 拼不出具体对象时退回「正在执行 <工具名>」——显示工具名也比只显示「处理中」有用，
   * 用户至少知道 agent 卡在哪一类操作上。
   */
  private fun showAction(text: CharSequence) {
    binding.assistantWorking.setAction(text)
  }

  /**
   * 工具调用 → 一行动作文案。
   *
   * <p>实现抽在 [AssistantActionText]：那段逻辑全是纯函数，留在本类里（需要
   * Context + ViewBinding 才能构造）就一条测试都写不了，而它恰好是用户每天
   * 看到最多的一行字。
   */
  private fun actionForTool(toolName: String, arguments: String?): CharSequence =
      AssistantActionText.describe(context, toolName, arguments)

  /** 结束流式段：下一次增量会新建一条消息。 */
  private fun finishStreaming() {
    // 末尾必刷：被节流合并掉的最后一段内容必须补出去，否则看起来像回答被截断了。
    if (throttle.flush()) {
      scrollToBottom()
    }
    throttle.reset()
    streamingMessageId = null
  }

  /**
   * 追加一条助手消息。
   *
   * <p>空白内容直接丢弃：模型「只调用工具、不写正文」的轮次会产出 `"\n\n"` 这类内容，
   * 渲染出来是一个空的灰气泡。放在这里拦截而不是逐个调用点判断，是因为漏一处就会
   * 在界面上堆一片空白块。
   */
  private fun appendAssistant(text: String) {
    if (text.isBlank()) {
      return
    }
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


  /**
   * 打开 AI 助手设置。
   *
   * <p>直达 AI 设置屏，而不是打开设置首页：AI 设置挂在「配置 → AI 助手」下，
   * 从助手面板点进去要逐层展开三层。用户点「设置」的意图是改 AI 配置，
   * 不是浏览全部 IDE 设置。
   *
   * <p>复用 [com.tom.rv2ide.preferences.AIAgentPreferencesScreen] 而不是新建一套设置界面：
   * 那里已有 10 个设置项（权限模式、Shell 后端、MCP、记忆、Skill、自定义 agent、
   * 授权规则、提示词模板…），再造一份就是第三份实现。
   */
  private fun openAssistantSettings() {
    AssistantSettings.open(context)
  }

  /**
   * 上下文占用详情 + 压缩入口。
   *
   * <p>圆环只给比例，这里给数字。压缩入口放在同一处而不是另做按钮：
   * 「看占用」与「占用高时压缩」是同一个动作的两半，用户看到 92% 的下一个念头
   * 就是「怎么腾空间」。
   */
  /**
   * 刷新权限模式标签。
   *
   * <p>标签只写**短名**（自动放行 / 需确认 / 只读）：工具条整行在贴边形态下约 236dp，
   * 「危险工具需确认」七个字会挤掉模型名。完整说明放在弹窗里。
   */
  private fun refreshPermissionChip() {
    val chip = binding.assistantPermissionChip
    val mode = settings.permissionMode
    chip.text = context.getString(permissionShortLabelRes(mode))
    chip.contentDescription = context.getString(permissionLongLabelRes(mode))
  }

  /**
   * 弹权限模式选择。
   *
   * <p>三档与设置页（{@code PermissionModePreference}）一致，文案共用同一批字符串资源。
   *
   * <p>每项写「短名 — 说明」而不是只写说明：工具条上的标签用的是**短名**
   * （自动放行 / 需确认 / 只读），弹窗里若只写说明，同一个档位在界面上就有两个
   * 不同的名字，用户会以为是两个独立设置。短名在前是因为它才是选中后要显示的那个。
   */
  private fun showPermissionPicker() {
    val modes =
        arrayOf(
            com.tom.rv2ide.ai.tool.ToolSettingsPort.PERMISSION_AUTO,
            com.tom.rv2ide.ai.tool.ToolSettingsPort.PERMISSION_CONFIRM,
            com.tom.rv2ide.ai.tool.ToolSettingsPort.PERMISSION_READONLY,
        )
    val labels =
        modes
            .map {
              context.getString(permissionShortLabelRes(it)) +
                  " — " +
                  context.getString(permissionLongLabelRes(it))
            }
            .toTypedArray()
    val checked = modes.indexOf(settings.permissionMode).coerceAtLeast(0)
    MaterialAlertDialogBuilder(context)
        .setTitle(string.ai_assistant_permission_title)
        .setSingleChoiceItems(labels, checked) { dialog, which ->
          settings.permissionMode = modes[which]
          refreshPermissionChip()
          dialog.dismiss()
        }
        .show()
  }

  /** 权限模式 → 工具条上的短标签。 */
  private fun permissionShortLabelRes(mode: String): Int =
      when (mode) {
        com.tom.rv2ide.ai.tool.ToolSettingsPort.PERMISSION_AUTO ->
            string.ai_assistant_permission_auto
        com.tom.rv2ide.ai.tool.ToolSettingsPort.PERMISSION_READONLY ->
            string.ai_assistant_permission_readonly
        else -> string.ai_assistant_permission_confirm
      }

  /** 权限模式 → 弹窗里的完整说明。 */
  private fun permissionLongLabelRes(mode: String): Int =
      when (mode) {
        com.tom.rv2ide.ai.tool.ToolSettingsPort.PERMISSION_AUTO ->
            string.ai_assistant_permission_auto_desc
        com.tom.rv2ide.ai.tool.ToolSettingsPort.PERMISSION_READONLY ->
            string.ai_assistant_permission_readonly_desc
        else -> string.ai_assistant_permission_confirm_desc
      }

  private fun showContextUsage() {
    val total = lastContextSize
    if (total <= 0) {
      // 未运行过或模型没配上下文窗口。此时圆环是空的，用户点它多半是想知道
      // 「为什么不显示」——如实说明，而不是弹一个 0/0。
      appendTrace(context.getString(string.ai_assistant_context_unknown))
      return
    }
    val percent = (lastContextUsed * 100 / total).coerceIn(0, 100)
    MaterialAlertDialogBuilder(context)
        .setTitle(string.ai_assistant_context_usage)
        .setMessage(
            context.getString(
                string.ai_assistant_context_usage_detail,
                formatTokens(lastContextUsed),
                formatTokens(total),
                percent,
            ))
        .setPositiveButton(string.ai_assistant_compact_now) { _, _ -> compactConversation() }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
  }

  /**
   * token 数 → 紧凑可读的串（12.3k / 64k）。
   *
   * <p>不用 String.format 的 %d 直接显示原值：64000 这种数字要在对话框里数位数，
   * 而「64k」一眼就懂。低于 1000 时保留原值——「0.5k」比「512」更难读。
   */
  private fun formatTokens(value: Int): String =
      if (value < 1000) value.toString()
      else String.format(java.util.Locale.US, "%.1fk", value / 1000.0)

  /**
   * 刷新工具条上的 git 分支。
   *
   * <p>在 IO 线程读仓库：JGit 打开仓库要读 `.git` 目录，是文件 I/O，不能放主线程。
   * 失败（不是 git 仓库、仓库损坏）时整块隐藏——显示一个空的分支标签会让用户
   * 以为仓库状态读取失败了。
   */
  private fun refreshGitBranch() {
    val ws = workspace
    if (ws == null || !ws.exists()) {
      binding.assistantGitChip.isVisible = false
      return
    }
    lifecycleScope.launch(Dispatchers.IO) {
      val branch =
          try {
            val manager = com.tom.rv2ide.git.GitManager(ws.absolutePath)
            if (!manager.openRepository()) null else manager.getCurrentBranch()
          } catch (e: Exception) {
            // 非 git 项目会走到这里（openRepository 返回 false 时上面已返回 null），
            // 损坏的仓库则抛异常——两者都不该让界面崩。
            null
          }
      withContext(Dispatchers.Main) {
        // 缓存下来，宽度变化时不必重新读磁盘就能恢复标签。
        lastGitBranch = branch
        val chip = binding.assistantGitChip
        if (branch.isNullOrBlank()) {
          chip.isVisible = false
        } else {
          chip.isVisible = true
          binding.assistantGitBranch.text = branch
        }
        // 分支标签显隐会改变工具条剩余宽度，重新算一次密度。
        applyToolbarDensity()
      }
    }
  }

  /**
   * 按工具条实测宽度决定显示哪些控件。
   *
   * <p><b>为什么按实测宽度而不是按面板形态</b>：面板宽度由形态（全屏/侧栏/贴边）
   * 与屏幕宽度共同决定，形态只是间接量——同样是贴边形态，平板上可能是 420dp，
   * 手机上只有 260dp。参考项目（cc-haha）同样用 ResizeObserver 量实际宽度，
   * 而不是 CSS 媒体查询。
   *
   * <p><b>逐级让位</b>（阈值由实测控件宽度定，见 [GIT_VISIBLE_MIN_DP] 的注释）：
   * 模型名永远保留（它是用户最需要看的一格），装不下时先隐服务商、再隐 git：
   * <ul>
   *   <li>≥320dp：全部显示
   *   <li>&lt;300dp：隐藏服务商标签（模型名已隐含服务商）
   *   <li>&lt;240dp：隐藏 git 分支，并把模型名压到 [MODEL_MAX_WIDTH_NARROW_DP]
   * </ul>
   * 用嵌套的 `if` 而不是三个独立判断：三者共享同一个宽度预算，独立判断在中间档
   * 会出现「服务商和 git 都还在、模型被挤没」的组合。
   *
   * <p><b>宽度为 0 时必须直接返回</b>：面板在 attach 阶段是 `gone`，此时
   * `bar.width` 为 0。把它当成「极窄」会永久隐藏 git 标签——之后宽度正常了
   * 也不会恢复，因为 `lastGitBranch` 已被写成一个看似合法的值。
   * 0 的含义是「还没布局」，不是「很窄」。这也是 [open] 里必须重算一次的原因：
   * 本方法在此之前的所有调用点都发生在面板不可见时。
   */
  private fun applyToolbarDensity() {
    val bar = binding.assistantToolbar
    bar.post {
      val widthDp = (bar.width / parent.resources.displayMetrics.density).toInt()
      if (widthDp <= 0) {
        return@post
      }

      // 极窄档：连 git 也让位，模型名收窄。
      val veryNarrow = widthDp < MODEL_VISIBLE_MIN_DP
      if (veryNarrow) {
        binding.assistantGitChip.isVisible = false
      } else {
        // 够宽时按仓库状态决定——refreshGitBranch 已经写好了 isVisible，
        // 这里只在「之前被窄宽度压掉」的情况下重新问一次。
        refreshGitBranchVisibilityOnly()
      }

      // 服务商名：比 git 更早让位。它的信息在模型名旁边（「deepseek-chat」
      // 已经暗示了服务商），而 git 分支名没有替代品。
      binding.assistantToolbarProvider.isVisible =
          widthDp >= PROVIDER_VISIBLE_MIN_DP && !veryNarrow

      // 模型名：极窄时压到一半宽。
      //
      // 用 maxWidth(dp) 而不是 maxEms：实测 maxEms 在 `layout_width=wrap_content`
      // 的中文文本上不生效——provider 标签设了 maxEms=7 仍然渲染出全部 12 个字符
      // （UI dump 里 bounds 宽 308px）。dp 是确定的长度，不受字体度量影响。
      binding.assistantToolbarModel.maxWidth =
          dp(if (veryNarrow) MODEL_MAX_WIDTH_NARROW_DP else MODEL_MAX_WIDTH_DP)
    }
  }

  /** 上一次 git 分支查询的结果；避免每次宽度变化都去读一次仓库。 */
  private var lastGitBranch: String? = null

  /** 仅按已缓存的仓库状态重设 git 标签可见性，不重新读磁盘。 */
  private fun refreshGitBranchVisibilityOnly() {
    val branch = lastGitBranch
    if (branch.isNullOrBlank()) {
      binding.assistantGitChip.isVisible = false
      return
    }
    binding.assistantGitChip.isVisible = true
    binding.assistantGitBranch.text = branch
  }

  /**
   * 刷新工具条上的模型标签。
   *
   * <p>由选择器在每次选择后回调，以及面板 attach 时调用一次。不订阅偏好变更：
   * 目前只有本面板会改这两个值，回调已经覆盖；引入全局监听反而要为「谁改的」
   * 做去重，得不偿失。
   *
   * <p>只显示模型名（服务商名进 contentDescription）：工具条在贴边形态下整行只有
   * 约 236dp，「DeepSeek / deepseek-chat」这种两段文本会被压成两个省略号，
   * 而用户切换时记住的是模型名。
   */
  private fun refreshModelLabel() {
    val agents = Agents(context)
    val providerId = agents.getProvider()
    val providerLabel = com.tom.rv2ide.artificial.agent.ProviderPresets.labelFor(providerId)
    // 未配置密钥的服务商加「⚠」前缀：否则用户切过去、发一条消息、收到
    // 「未配置有效的 API 密钥」，要绕一圈才知道问题在哪。
    val usable =
        AgentModelConfigs.isProviderUsable(
            providerId,
            AgentOrchestrator.customBaseUrlFor(context, providerId),
        )
    val providerChip = binding.assistantToolbarProvider
    providerChip.text =
        if (usable) providerLabel else "⚠ " + providerLabel
    providerChip.contentDescription = providerLabel

    val modelChip = binding.assistantToolbarModel
    modelChip.text = agents.getAgent()
    modelChip.contentDescription = AssistantModelPicker.summaryLabel(context)
  }

  /**
   * 设置运行摘要行。
   *
   * <p>空串等价于隐藏：这个 TextView 没有固定高度，显示空串会留下一段无法解释的
   * 空白（面板顶部到消息列表之间多出一条缝隙），而用户看不出那里本该有什么。
   */
  private fun setStatus(text: CharSequence?) {
    if (text.isNullOrBlank()) {
      binding.assistantStatus.text = null
      binding.assistantStatus.isVisible = false
    } else {
      binding.assistantStatus.text = text
      binding.assistantStatus.isVisible = true
    }
  }

  // ---- 会话列表 ----

  /** 切换会话列表的显示。与消息列表互斥——面板不宽，并排会把两边都挤得不可用。 */
  /**
   * 开关会话抽屉。
   *
   * <p>抽屉从左侧滑出（`translationX` 从 `-width` 到 `0`），而不是直接切 visibility：
   * 滑动给了「它是从侧边拉出来的」这一空间暗示，用户知道点遮罩或再点菜单能收回去。
   * 直接显隐则像内容被替换，用户会去找返回键。
   */
  private fun toggleConversationPanel() {
    // 每次切换都递增。关闭动画的收尾回调会比对它，只有仍是最新一轮才真正隐藏——
    // 否则「关到一半又点开」时，迟到的收尾回调会把刚打开的抽屉隐藏掉。
    drawerGeneration++
    val generation = drawerGeneration
    val drawer = binding.assistantDrawer
    val overlay = binding.assistantDrawerOverlay

    if (!overlay.isVisible) {
      overlay.isVisible = true
      // 宽度与初始位移都必须在布局完成后算：
      // - 抽屉宽度上限 = 容器的 78%。贴边形态下面板可能只有 260dp，固定 240dp
      //   会盖掉几乎全部对话，右侧留一条对话区才能提示「后面还有内容」。
      // - 滑入动画要先把抽屉移到容器外，需要 drawer.width。
      // 点击发生的时刻 overlay 刚可见、宽高仍是 0，此时计算会得到 0 上限而静默失效，
      // 因此统一放进 post{}。
      drawer.post {
        if (generation != drawerGeneration) {
          return@post
        }
        val maxWidth = (overlay.width * 0.78f).toInt()
        if (maxWidth > 0 && drawer.width > maxWidth) {
          drawer.layoutParams =
              (drawer.layoutParams as FrameLayout.LayoutParams).apply { width = maxWidth }
        }
        drawer.translationX = -drawer.width.toFloat()
        drawer.animate().translationX(0f).setDuration(DRAWER_ANIM_MS).start()
      }
      reloadConversations()
    } else {
      drawer
          .animate()
          .translationX(-drawer.width.toFloat())
          .setDuration(DRAWER_ANIM_MS)
          .withEndAction {
            if (generation == drawerGeneration) {
              overlay.isVisible = false
              drawer.translationX = 0f
            }
          }
          .start()
    }
  }

  /** 抽屉开关的轮次号，见 [toggleConversationPanel]。 */
  private var drawerGeneration = 0

  /** 重新拉取会话列表。每次展开都重拉：会话可能被另一处（如另一个面板实例）改动过。 */
  private fun reloadConversations() {
    lifecycleScope.launch(Dispatchers.IO) {
      val summaries =
          try {
            orchestrator.listConversations()
          } catch (e: java.io.IOException) {
            // 列表读不出来不该让界面崩：记日志并显示空态，用户仍可新建会话。
            com.tom.rv2ide.ai.tool.api.ErrorLog.record("agent", "读取会话列表失败", e, null)
            emptyList()
          }
      withContext(Dispatchers.Main) {
        conversationAdapter.submitList(summaries)
        conversationAdapter.setActive(orchestrator.activeConversationId)
        binding.assistantConversationsEmpty.isVisible = summaries.isEmpty()
      }
    }
  }

  /**
   * 打开一个历史会话。
   *
   * <p>必须同时做两件事：把 orchestrator 的当前会话指过去（后续请求续接它的历史），
   * 以及把该会话的消息回放到界面上。只做前者会让用户看到旧会话的内容却在新会话里提问。
   */
  private fun openConversation(summary: com.tom.rv2ide.ai.agent.conversation.ConversationSummary) {
    // 切换会话要中断正在进行的运行：它的输出属于旧会话，继续跑会写错地方。
    cancel()
    lifecycleScope.launch(Dispatchers.IO) {
      val messages = orchestrator.loadConversationMessages(summary.getId())
      withContext(Dispatchers.Main) {
        orchestrator.openConversation(summary.getId())
        lastOpenedConversationId = summary.getId()
        replayMessages(messages)
        conversationAdapter.setActive(summary.getId())
        // 打开后自动收起抽屉：用户的意图是「看这个会话」，不是继续浏览列表。
        if (binding.assistantDrawerOverlay.isVisible) {
          toggleConversationPanel()
        }
      }
    }
  }

  /**
   * 把历史消息回放到列表。
   *
   * <p>只回放用户与助手的文本，工具调用与推理不还原：会话日志里它们是独立条目类型，
   * 还原需要重建完整的卡片与折叠状态，而历史消息的主要用途是「找回上下文」，
   * 文本已经够用。宁可少显示，也不显示错的。
   */
  private fun replayMessages(messages: List<com.tom.rv2ide.ai.protocol.ModelMessage>) {
    adapter.clear()
    streamingMessageId = null
    streamedThisRun = false
    lastThinkingId = null
    lastToolCardId = null
    for (message in messages) {
      when (message) {
        is com.tom.rv2ide.ai.protocol.UserModelMessage ->
            adapter.append(AssistantMessageAdapter.Role.USER, message.getContent())
        is com.tom.rv2ide.ai.protocol.AssistantModelMessage -> {
          val text = message.getContent()
          if (!text.isNullOrBlank()) {
            adapter.append(AssistantMessageAdapter.Role.ASSISTANT, text)
          }
        }
        else -> {}
      }
    }
    updateEmptyState()
    scrollToBottom()
  }

  /** 删除会话前确认。删除不可撤销，静默删除会让误触的代价过大。 */
  private fun confirmDeleteConversation(
      summary: com.tom.rv2ide.ai.agent.conversation.ConversationSummary
  ) {
    androidx.appcompat.app.AlertDialog.Builder(context)
        .setTitle(string.ai_conversation_delete_confirm_title)
        .setMessage(string.ai_conversation_delete_confirm_message)
        .setPositiveButton(string.ai_conversation_delete) { _, _ -> deleteConversation(summary) }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
  }

  private fun deleteConversation(
      summary: com.tom.rv2ide.ai.agent.conversation.ConversationSummary
  ) {
    lifecycleScope.launch(Dispatchers.IO) {
      try {
        orchestrator.deleteConversation(summary.getId())
      } catch (e: java.io.IOException) {
        com.tom.rv2ide.ai.tool.api.ErrorLog.record("agent", "删除会话失败", e, null)
      }
      withContext(Dispatchers.Main) {
        // 删掉的正是当前显示的会话时，必须同时清空消息区——否则会出现
        // 「列表里已经没有它、但消息还留在屏幕上」的矛盾状态，用户继续提问
        // 会落进一个已被删除的会话。
        if (summary.getId() == lastOpenedConversationId) {
          adapter.clear()
          streamingMessageId = null
          streamedThisRun = false
          lastThinkingId = null
          lastToolCardId = null
          lastOpenedConversationId = null
          updateEmptyState()
        }
        reloadConversations()
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

  /**
   * 刷新任务卡片。
   *
   * <p>没有任务时整卡隐藏，而不是显示一个空卡片——空卡片会长期占据输入区上方
   * 的空间，而「没有任务」是常态（普通问答不产生任务清单）。
   */
  private fun refreshTodos() {
    val todos = orchestrator.todos
    val card = binding.assistantTodos.todoCard
    if (todos.isEmpty()) {
      card.isVisible = false
      return
    }
    card.isVisible = true

    val total = todos.size
    val done = todos.count { it.status == com.tom.rv2ide.ai.tool.TodoItem.STATUS_COMPLETED }
    binding.assistantTodos.todoTitle.text =
        context.getString(string.ai_assistant_todo_progress, done, total)
    binding.assistantTodos.todoProgress.max = total
    binding.assistantTodos.todoProgress.setProgressCompat(done, true)

    // 重建清单。条目数通常个位数，全量重建比 diff 更简单且不会错——
    // 这里没有列表复用，重建代价是一次几次 View 的创建。
    val list = binding.assistantTodos.todoList
    list.removeAllViews()
    for (item in todos) {
      val row = TextView(context).apply {
        text = todoGlyph(item.status) + " " + item.content
        textSize = 13f
        setPadding(0, dp(4), 0, dp(4))
        // 已完成的条目弱化：视线应落在「还没做的」上。
        setTextColor(
            MaterialColors.getColor(
                list,
                if (item.status == com.tom.rv2ide.ai.tool.TodoItem.STATUS_COMPLETED)
                    com.google.android.material.R.attr.colorOnSurfaceVariant
                else com.google.android.material.R.attr.colorOnSurface,
            )
        )
      }
      list.addView(row)
    }
  }

  /**
   * 任务卡片的展开/收起。默认折叠——清单通常 5-10 条，默认展开会持续占掉
   * 面板约 1/3 高度，而折叠态的进度数字（2/5）已能回答「还剩几条」。
   */
  private fun toggleTodos() {
    val b = binding.assistantTodos
    val expand = !b.todoScroll.isVisible
    b.todoScroll.isVisible = expand
    b.todoDivider.isVisible = expand
    b.todoChevron.text = if (expand) "▾" else "▸"
  }

  /** 任务状态 → 前缀符号。用符号而非图标：一行一条，符号更紧凑且不打断文字阅读。 */
  private fun todoGlyph(status: String): String =
      when (status) {
        com.tom.rv2ide.ai.tool.TodoItem.STATUS_COMPLETED -> "✓"
        com.tom.rv2ide.ai.tool.TodoItem.STATUS_IN_PROGRESS -> "◐"
        else -> "○"
      }

  private fun startNewConversation() {
    cancel()
    adapter.clear()
    streamingMessageId = null
    streamedThisRun = false
    lastThinkingId = null
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
    lastThinkingId = null
    // 取消时仍在运行的卡片要收尾，否则会永久停在运行态。
    for (id in adapter.runningToolCallIds()) {
      adapter.failToolCall(id, context.getString(string.ai_assistant_tool_cancelled))
    }
    // 仍在流式的思维链块也要收尾：否则标题会永远停在「思考中…」，
    // 用户以为模型还在工作。
    adapter.finishAllThinking()
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
        is AssistantMessageAdapter.Thinking -> {
          // 推理内容要进导出：它是「模型为什么这么做」的唯一记录，
          // 缺了它，导出的对话在复盘时看不出决策依据。
          if (item.text.isNotBlank()) {
            sb.append("<details><summary>")
                .append(context.getString(string.ai_assistant_thinking_done))
                .append("</summary>\n\n")
                .append(item.text)
                .append("\n\n</details>\n\n")
          }
        }
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

    /** 悬浮按钮落点（相对父容器左上角的像素）。 */
    private const val PREF_FAB_X = "ai_assistant_fab_x"
    private const val PREF_FAB_Y = "ai_assistant_fab_y"

    /** 拖动时四周保留的最小边距（dp）。 */
    private const val EDGE_MARGIN_DP = 8

    /** 会话抽屉滑入/滑出的时长。够快不拖沓，又不至于快到看不出方向。 */
    private const val DRAWER_ANIM_MS = 200L

    /**
     * 工具条宽度低于此值（dp）时隐藏服务商标签。
     *
     * <p>阈值由实测控件宽度定（设备 1080px @ density 440，即 2.75 px/dp）：
     * `+` 36 + 权限胶囊 61 + 服务商 92 + 圆环 24 + 模型 124 = 337dp，
     * 加固定间距约 18dp 共 355dp。服务商 + git（约 50dp）同时在场需要约 320dp，
     * 故取 320。
     *
     * <p>实测参照：侧栏形态工具条 360dp（全部显示），贴边形态 243dp（只留模型）。
     */
    private const val GIT_VISIBLE_MIN_DP = 320

    /**
     * 工具条宽度低于此值（dp）时隐藏服务商名。
     *
     * <p>比 git 更早让位：模型名已经隐含了服务商（「deepseek-chat」一看就知道是哪家），
     * 而 git 分支名没有替代品。隐藏后模型名顶上，用户仍能看出在用什么模型。
     *
     * <p>300dp = 剩余控件（`+` 36 + 权限 61 + 圆环 24 + 模型 124 + 间距 18）的
     * 约 263dp，加上服务商自身 92dp 的下界。低于它时必须让服务商先走，
     * 否则模型名会被压到只剩一个省略号——实测贴边形态 243dp 正是如此。
     */
    private const val PROVIDER_VISIBLE_MIN_DP = 300

    /**
     * 工具条宽度低于此值（dp）时隐藏 git 并把模型名压到
     * [MODEL_MAX_WIDTH_NARROW_DP]。
     *
     * <p>240dp 是「`+` 36 + 权限 61 + 圆环 24 + 模型 62 + 间距 18 = 201dp」
     * 这条底线之上的余量；再窄就只剩图标，模型名必须收窄才放得下。
     */
    private const val MODEL_VISIBLE_MIN_DP = 240

    /** 模型名的常规最大宽度（dp）。约 13 个半角字符，够显示 `deepseek-v4.1-flash`。 */
    private const val MODEL_MAX_WIDTH_DP = 124

    /** 极窄时模型名的最大宽度（dp）。约 6 个字符——够认出是哪家模型即可。 */
    private const val MODEL_MAX_WIDTH_NARROW_DP = 62

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
