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
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isVisible
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
    }
  }

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
    binding.assistantSend.setOnClickListener { sendFromInput() }
    binding.assistantModelBar.setOnClickListener {
      AssistantModelPicker.show(context) { refreshModelLabel() }
    }

    // 标题栏：左菜单开抽屉，右侧全屏/最小化/关闭。
    binding.assistantMenu.setOnClickListener { toggleConversationPanel() }
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
  }

  /** 设置工作区。主屏上用户可能先选项目，因此每次打开面板前都更新。 */
  fun setWorkspace(workspace: java.io.File?) {
    this.workspace = workspace
    orchestrator.workspace = workspace
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
    lastThinkingId = null

    executionJob =
        lifecycleScope.launch(Dispatchers.IO) {
          val agents = Agents(context)
          val providerId = agents.getProvider()
          val modelId = AgentModelConfigs.modelIdFor(providerId, agents.getAgent())
          val customBaseUrl = AgentOrchestrator.customBaseUrlFor(context, providerId)

          withContext(Dispatchers.Main) {
            binding.assistantSend.isEnabled = false
            binding.assistantProgress.isVisible = true
            // 运行中清空摘要行：上一次的「已完成 · 3 轮」留在那里会与正在进行的运行
            // 混在一起，看起来像这次已经结束了。运行状态由下方的 WorkingStatusView 承担。
            setStatus(null)
            // 首字节可能要等好几秒，静态文字无法区分「在工作」和「卡死了」。
            binding.assistantWorking.bind(isThinking = false)
            binding.assistantWorking.startWorking()
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
            // 否则发送按钮会永久停留在禁用状态，用户再也发不出请求。
            withContext(kotlinx.coroutines.NonCancellable) {
              withContext(Dispatchers.Main) {
                binding.assistantSend.isEnabled = true
                binding.assistantProgress.isVisible = false
                // 状态条同理：不在这里停，取消/异常后动画会一直转，
                // 看起来像还在跑。
                binding.assistantWorking.stopWorking()
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
          // 开始输出正文 = 思考结束，进入「处理中」。
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
          // 思维链同理：失败时标题必须从「思考中…」切走，否则看起来像还在等。
          adapter.finishAllThinking()
          lastThinkingId = null
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
   * 刷新标题栏下方的「服务商 / 模型」文案。
   *
   * <p>由选择器在每次选择后回调，以及面板 attach 时调用一次。不订阅偏好变更：
   * 目前只有本面板会改这两个值，回调已经覆盖；引入全局监听反而要为「谁改的」
   * 做去重，得不偿失。
   */
  private fun refreshModelLabel() {
    binding.assistantModelLabel.text = AssistantModelPicker.summaryLabel(context)
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
