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
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isVisible
import com.google.android.material.color.MaterialColors
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.tom.rv2ide.adapters.AssistantMessageAdapter
import com.tom.rv2ide.adapters.ConversationListAdapter
import com.tom.rv2ide.artificial.agent.host.AssistantHost
import com.tom.rv2ide.artificial.agents.Agents
import com.tom.rv2ide.databinding.LayoutAiAssistantBinding
import com.tom.rv2ide.databinding.LayoutAiAssistantFabBinding
import com.tom.rv2ide.resources.R.string
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

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
    private val host: AssistantHost,
    /**
     * 初始形态。主页传 [Mode.SIDEBAR]（浮层，露出项目列表），
     * 编辑器传 [Mode.DOCKED]（贴右侧满高，与文件树抽屉左右对称）。
     */
    private val defaultMode: Mode = Mode.SIDEBAR,
    /**
     * 可选的共享 orchestrator。
     *
     * <p>当同一宿主下有多个视图（例如主页 SIDEBAR 与内联页 INLINE）需要共用同一段对话时，
     * 由宿主（如 MainViewModel）持有唯一实例并注入给每个视图。orchestrator 持有
     * `activeConversationId`，两个实例会各自维护一份会话，导致「同一时刻两个会话」的错配，
     * 因此共享是保证单会话的正确做法。
     *
     * <p>为 null 时（现有所有调用点）每个视图自建实例，行为与抽象前完全一致。
     */
    private val sharedOrchestrator: AgentOrchestrator? = null,
) {

  private val context: Context = host.context
  private val lifecycleScope: LifecycleCoroutineScope = host.lifecycleScope

  /**
   * 挂载面板的真实父容器。
   *
   * <p>刻意保留真实 `ViewGroup` 类型而不抽进接口：面板的定位与拖拽依赖它的具体类型
   * （ConstraintLayout 约束、FrameLayout.LayoutParams、WindowInsets）。见 [AssistantHost.container]。
   */
  private val parent: ViewGroup = host.container

  /** 面板宽度预算。原先取 `parent.resources.displayMetrics.widthPixels`，现由宿主提供。 */
  private fun screenWidthPx(): Int = host.widthPx()

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
    DOCKED,

    /**
     * 内联页形态：占满宿主给定的容器，无边距、无圆角。
     *
     * 与 [FULLSCREEN] 的区别：全屏是「浮层铺开」，四周仍留 8/12dp 边距与圆角，
     * 传达的是「一张盖在内容上的卡片」；内联页要的是「它就是页面本身」，因此
     * 完全不留边距、不做圆角，与容器严丝合缝。宽度由容器决定，不按屏幕比例算。
     */
    INLINE
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
   *
   * <p>传入 `host` 而不是 `context`：附件选择需要宿主能力（Activity 走
   * `registerForActivityResult`，应用外悬浮的 Service 宿主走蹦床 Activity），
   * 由 `host.attachments` 提供，视图本身不必知道当前宿主是哪种形态。
   */
  private val inputFeatures = AssistantInputFeatures(host, binding)

  /**
   * 持久化 diff 存储。
   *
   * <p>用文件实现而非 InMemoryDiffStore：回滚的价值在于「事后反悔」，而事后往往就是
   * 下一次打开应用；内存实现重启后记录全丢，用户点了撤销却找不到记录。
   */
  private val diffStore = AgentOrchestrator.defaultDiffStore(context)

  /**
   * 正在运行的会话 → 其协程 job。
   *
   * <p><b>为什么是 Map 而不是单个 Job</b>：多会话/多项目要能同时跑。单个 job 会迫使
   * 每次切换都 cancel 掉上一个，用户在 A 项目跑的活会在切到 B 时被无声杀掉。
   * 按会话 id 隔离后，切走只是换显示，后台会话继续跑。
   *
   * <p>用 [ConcurrentHashMap]：写入发生在主线程（execute/cancel），而事件回调来自
   * agent 循环线程，两侧都会读。
   */
  private val executionJobs = ConcurrentHashMap<String, Job>()

  /**
   * 每个会话最近一次事件流写到哪张卡片。
   *
   * <p>多会话并行时，同一个视图的 [adapter] 只显示一个会话的消息，因此这些「最近卡片」
   * 指针必须按会话隔离，否则 A 会话的 TOOL_FINISHED 会回填到 B 会话的卡片上。
   */
  private class SessionUiState {
    var streamingMessageId: Long? = null
    var lastToolCardId: Long? = null
    var lastThinkingId: Long? = null
    var streamedThisRun: Boolean = false
    var retryCountThisRun: Int = 0
    var retryCardPinned: Boolean = false
  }

  private val sessionUi = ConcurrentHashMap<String, SessionUiState>()

  private fun uiState(conversationId: String): SessionUiState =
      sessionUi.getOrPut(conversationId) { SessionUiState() }

  /**
   * 本视图的事件接收器。
   *
   * <p>订阅关系是**按会话**建立的（见 [subscribeToConversation]），因此收到的事件一定
   * 属于订阅时那个会话；过滤交给 [handleEvent] 里已有的 `displayedConversationId` 守卫。
   *
   * <p><b>这是本视图唯一的投递路径</b>：发起运行时不再传 listener（否则同一事件会被
   * 投递两遍，每个增量渲染两次）。好处是「谁在跑」与「谁在看」解耦——任何入口打开
   * 同一会话都能实时收到，而不只是发起那一次运行的那个视图。
   *
   * <p>回调来自 agent 循环线程，绝不在其中碰控件——只投递。
   */
  private val broadcastListener =
      com.tom.rv2ide.ai.agent.AgentEvent.Listener { event ->
        // 事件里不带会话 id，因此按「订阅时记下的会话」路由。会话在运行中途被切换时，
        // displayedConversationId 已变，handleEvent 的守卫会丢弃不属于当前显示的增量。
        val id = subscribedConversationId
        if (id == null) {
          return@Listener
        }
        // 回放进行中：先攒起来。直接渲染会与稍后的回放重复（同一段历史出现两遍），
        // 因为回放是整表重画、而此刻列表里还是上一个会话的内容。
        synchronized(pendingLock) {
          if (replayingConversationId == id) {
            pendingLiveEvents.add(event)
            return@Listener
          }
        }
        handleEvent(id, event)
      }

  /**
   * 当前订阅的会话 id。
   *
   * <p>事件本身不带会话维度（[com.tom.rv2ide.ai.agent.AgentEvent] 是纯事件），
   * 因此「这条事件属于哪个会话」只能由订阅时的绑定关系决定。切换显示会话时
   * 必须先退订旧的再订阅新的，否则旧会话的增量会按新 id 渲染。
   *
   * <p>`@Volatile`：在 IO 线程（[loadAndSubscribe] 里订阅时）写，在事件线程
   * （[broadcastListener] 里）读。不加会出现事件线程读到旧值、把增量渲染进错误会话。
   */
  @Volatile private var subscribedConversationId: String? = null

  /**
   * 正在回放的会话 id；非 null 期间实时事件进 [pendingLiveEvents] 而不直接渲染。
   *
   * <p>只在 [pendingLock] 内读写：事件来自 agent 循环线程，而回放与补放在主线程，
   * 两者必须互斥，否则「补放前刚到达的事件」会被 clear 掉、永久丢失。
   */
  private var replayingConversationId: String? = null

  /** 保护 [replayingConversationId] 与 [pendingLiveEvents] 的锁。 */
  private val pendingLock = Any()

  /** 回放期间攒下的实时事件，回放结束后按序补放。 */
  private val pendingLiveEvents =
      java.util.ArrayList<com.tom.rv2ide.ai.agent.AgentEvent>()

  /**
   * 当前**显示**在消息列表里的会话 id。
   *
   * <p>事件入口据此过滤：只有属于当前显示会话的事件才写控件；后台会话的事件仍由
   * orchestrator 的 PersistingListener 落盘（不丢内容），只是不渲染。用户切回该会话时
   * 走 [doOpenConversation] 从日志回放。
   */
  private var displayedConversationId: String? = null

  private var workspace: java.io.File? = null

  /**
   * 流式刷新节流。
   *
   * <p>模型按 token 吐字，一段回答会产生上百个增量。每个都刷一次会让列表重排上百次：
   * 滚动抖动、掉帧，而人眼分辨不出这个粒度。
   *
   * <p>只服务**当前显示**的会话（后台会话事件被 [displayedConversationId] 过滤掉），
   * 因此单个实例足够，不必按会话隔离。
   */
  private val throttle = StreamingThrottle()

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

  private var mode = defaultMode

  /**
   * 复用一个 orchestrator 跨请求。
   *
   * <p>必须复用而非每次新建：orchestrator 持有当前会话 id，新建会让它丢失，
   * 于是每条消息都开一个新会话，历史永远无法续接。
   */
  private val orchestrator: AgentOrchestrator by lazy {
    sharedOrchestrator ?: AgentOrchestrator(context, diffStore)
  }

  /**
   * 本视图是否是当前**安装者**（最后把回调装到 orchestrator 上的视图）。
   *
   * <p>两个回调（危险工具授权、上下文用量）是 orchestrator 的**单例槽位**，多个视图共享
   * 同一个 orchestrator 时会互相覆盖。约定：谁可见谁拥有；可见的视图在 [attach]/[open]
   * 抢过回调。让出时见 [releaseOrchestratorCallbacks]——它会把回调**交接**给另一个存活视图，
   * 而不是直接清空。
   *
   * <p>默认 `false`：视图只有在 [installOrchestratorCallbacks] 之后才算安装者。未 attach
   * 就被销毁的视图不会去动共享 orchestrator 上的回调。
   */
  private var ownsOrchestratorCallbacks = false

  /**
   * 与当前视图共享同一 orchestrator 的存活视图集合。
   *
   * <p>用于让出时挑选交接对象。集合对视图用弱引用（GC 后自动移除），对 orchestrator 也弱引用。
   * 只有 [dispose] 会把视图从集合中摘除；[close] 只是不再当安装者，视图仍是**合法的交接对象**
   * ——它只是被隐藏，[androidx.lifecycle.LifecycleCoroutineScope] 仍然可用，接住回调不会出错。
   */
  private fun liveViewSet(): MutableSet<FloatingAssistantView> =
      liveViews.getOrPut(orchestrator) {
        java.util.Collections.newSetFromMap(
            java.util.WeakHashMap<FloatingAssistantView, Boolean>())
      }

  /**
   * 安装两个回调并把自己登记为当前安装者 / 存活视图。
   *
   * <p>**只应在主线程调用**（[attach]/[open]/[close]/[dispose] 均在主线程）：与
   * [ownsOrchestratorCallbacks] 的读写同线程，避免共享 orchestrator 下两个视图并发抢装
   * 造成回调归属错乱。
   */
  private fun installOrchestratorCallbacks() {
    ownsOrchestratorCallbacks = true
    liveViewSet().add(this)
    // 注意接收者：授权确认器必须装在 **orchestrator 的** AgentToolSettings 上。
    // 原代码在 `orchestrator.apply { settings.setDangerousToolConfirmer(...) }` 里，
    // 那个 `settings` 经隐式接收者解析到 orchestrator.getSettings()——执行器走的是
    // 这条（ToolContext.settings = orchestrator.settings）。若误装到本视图自己的
    // `settings` 字段上，执行器读到的 confirmer 仍为 null，危险工具会被静默拒绝。
    orchestrator.settings.setDangerousToolConfirmer(
        AgentToolSettings.DangerousToolConfirmer { toolName, args ->
          askDangerousToolOnMain(toolName, args)
        }
    )
    // 上下文用量回主线程画圆环。回调来自 agent 循环线程，且每轮都会触发，
    // 因此这里只做一次「写两个字段 + invalidate」。
    orchestrator.setContextUsageListener { used, total ->
      lifecycleScope.launch(Dispatchers.Main) {
        lastContextUsed = used
        lastContextSize = total
        applyContextRing()
      }
    }
  }

  /**
   * 让出回调：**交接给另一个存活视图，而不是清空**。
   *
   * <p>只有确实没有任何其它存活视图共享该 orchestrator 时，才把回调置空。
   *
   * <p>否则会出现评审指出的场景：内联页视图 A 是安装者，A 销毁后回调被清空，而主页
   * SIDEBAR 视图 B 仍活着却没有任何机制重装——B 的上下文圆环停更、危险工具确认被静默
   * 拒绝（[com.tom.rv2ide.artificial.agent.AgentToolSettings.confirmDangerousTool] 在
   * confirmer 为 null 时直接拒绝）。交接后 B 重新安装，两个回调始终有效。
   *
   * <p>非安装者调用本方法（`ownsOrchestratorCallbacks == false`）时不动 orchestrator，
   * 避免把当前安装者刚装上的回调清掉。
   */
  private fun releaseOrchestratorCallbacks() {
    if (!ownsOrchestratorCallbacks) {
      return
    }
    ownsOrchestratorCallbacks = false
    val next = liveViews[orchestrator]?.firstOrNull { it !== this }
    if (next != null) {
      next.installOrchestratorCallbacks()
      return
    }
    // 没有任何其它存活视图：确实无人使用，才清空并移除登记。
    liveViews.remove(orchestrator)
    orchestrator.setContextUsageListener(null)
  }

  /** 从存活视图集合中摘除自己（仅 [dispose] 调用，见 [liveViewSet]）。 */
  private fun detachFromLiveSet() {
    liveViews[orchestrator]?.remove(this)
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

  /**
   * 被外部悬浮（应用外系统悬浮）接管时的可见性存档。
   *
   * <p>存的是「接管前 FAB 是否可见 / 面板是否展开」这一对状态。应用外悬浮关掉后要按原样
   * 恢复，不能一律显示成收起态——用户可能正开着面板去开的悬浮窗。
   *
   * <p>null 表示当前未被接管（正常态）。用 null 而非布尔：需要区分「没被接管」与
   * 「被接管且接管前恰好是收起态」，否则恢复时会误判。
   */
  private var suppressedVisibility: Pair<Boolean, Boolean>? = null

  /**
   * 隐藏本视图，让位给应用外悬浮。
   *
   * <p><b>为什么需要互斥</b>：应用外悬浮（[com.tom.rv2ide.services.AssistantOverlayService]）
   * 挂在系统窗口层，**不看宿主是否在前台**；而本视图挂在主屏/编辑器的容器里。两者同时开着
   * 时，ACS 一在前台就会看到两个悬浮入口叠在一起——用户不知道该点哪个，也分不清哪个在响应。
   * 因此由宿主在 onResume 里按 [com.tom.rv2ide.services.AssistantOverlayService.isShowing]
   * 二选一。
   *
   * <p>只改可见性、不动会话状态：隐藏期间 agent 仍在跑（协程挂在宿主 lifecycleScope 上），
   * 恢复后消息照旧在列表里。重复调用是幂等的。
   */
  fun suppressForExternalOverlay() {
    if (suppressedVisibility != null) {
      return
    }
    suppressedVisibility = fabBinding.root.isVisible to binding.assistantOverlay.isVisible
    fabBinding.root.isVisible = false
    binding.assistantOverlay.isVisible = false
  }

  /** 应用外悬浮关闭后，按 [suppressForExternalOverlay] 存档的可见性恢复本视图。 */
  fun restoreAfterExternalOverlay() {
    val saved = suppressedVisibility ?: return
    suppressedVisibility = null
    // INLINE 形态没有 FAB（见 attach），恢复时不能碰它。
    if (defaultMode != Mode.INLINE) {
      fabBinding.root.isVisible = saved.first
    }
    binding.assistantOverlay.isVisible = saved.second
  }

  /** 把两个视图挂到父容器上。父容器应是 `FrameLayout`（FAB 靠 gravity 定位）。 */
  fun attach() {
    // INLINE 是「页面本身」，没有可收起的宿主：不挂 FAB，也不装拖拽。
    // 其余形态照旧挂 FAB 并装拖拽，行为不变。
    if (defaultMode != Mode.INLINE) {
      // FAB 在 XML 里只有固定尺寸、没有 gravity；放进 FrameLayout 时必须显式给右下角，
      // 否则会落在左上角盖住标题。
      fabBinding.root.layoutParams =
          FrameLayout.LayoutParams(dp(56), dp(56)).apply {
            gravity = Gravity.END or Gravity.BOTTOM
            marginEnd = dp(16)
            bottomMargin = dp(16) + defaultBottomOffsetPx
          }
      parent.addView(fabBinding.root)
    }
    parent.addView(binding.assistantOverlay)

    if (defaultMode != Mode.INLINE) {
      setUpDragging()
    }

    applyMode(defaultMode)

    // 装回调。共享 orchestrator 时这是「当前可见者拥有」的初始声明；不共享时与
    // 抽象前「构造期装一次」等价（attach 在本视图生命周期内只调一次，且在主线程）。
    installOrchestratorCallbacks()

    binding.assistantMessages.layoutManager = LinearLayoutManager(context)
    binding.assistantMessages.adapter = adapter

    adapter.setOnRevertClickListener { messageId, diffId -> revertDiff(messageId, diffId) }
    // 工具卡片的分类色需要按工具名查注册表。注册表在 orchestrator 里，
    // 但适配器不该依赖工具执行层，因此注入一个只做名字→分类映射的窄接口。
    // 传 includeMcp=false：分类色查询不该发起 MCP 网络请求，也不该创建无人释放的连接。
    val registry = orchestrator.buildRegistry(false, null)
    adapter.setCategoryResolver { toolName ->
        registry.getCachedDisplayCategory(com.tom.rv2ide.ai.tool.ToolRegistry.canonicalName(toolName))
    }

    // 不设 OnClickListener：拖动用的 OnTouchListener 会消费全部事件，click 永远不会触发。
    // 打开面板的动作用 ACTION_UP 且未进入拖动时手动调用 open()（见 setUpDragging）。
    binding.assistantSend.setOnClickListener { onSendClicked() }
    binding.assistantStop.setOnClickListener { onStopClicked() }
    // 工具条上的服务商与模型标签：两者都点开同一个选择器。
    // 分成两个可点控件而不是合成一个：服务商名与模型名各自独立省略，
    // 窄面板下仍能读出「哪个服务商」；合成一段时两段文字会一起被压成省略号。
    binding.assistantToolbarModel.setOnClickListener {
      host.dialogs.showModelPicker { refreshModelLabel() }
    }
    binding.assistantToolbarProvider.setOnClickListener {
      host.dialogs.showModelPicker { refreshModelLabel() }
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
    // 全屏键两档语义：
    // - 单击 = 窗口内最大化（现状，行为零变化）；
    // - 长按 = 拉起沉浸式全屏 Activity（真全屏，横屏/沉浸式，是独立的屏幕）。
    //   长按而不是单击：单击已是评审通过的窗口内最大化，改成跳 Activity 会破坏现有习惯；
    //   长按是「同一个按钮上的进阶动作」，与仓库里其它长按用法一致。
    binding.assistantFullscreen.setOnClickListener { toggleFullscreen() }
    binding.assistantFullscreen.setOnLongClickListener {
      launchTrueFullscreen()
      true
    }
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

    // 这里 workspace 还是 null（调用点一律是 attach() 紧接 setWorkspace()），
    // 所以本次调用只是把标题置成静态文案；真正的项目名由紧随其后的 setWorkspace 设置。
    // 留着它是为了让「标题永远有内容」这件事不依赖调用顺序。
    refreshWorkspaceLabel()

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
    // 标题要跟着换项目名，会话列表里各条会话的归属标注也要重算。
    refreshWorkspaceLabel()
    conversationAdapter.setCurrentCwd(workspace?.absolutePath)
  }

  /**
   * 把面板标题替换为当前项目名。
   *
   * <p><b>为什么占标题栏</b>：标题原本只印静态的「AI 助手」，对用户零信息量；而
   * 「这个对话绑在哪个项目」恰恰是用户从界面上看不出来的关键状态。面板顶部是唯一
   * 一直可见的位置，把它放这里不需要新增任何控件，也不占用已经拥挤的工具条。
   *
   * <p>长按显示完整路径（TooltipCompat）：标题用 middle 省略，而项目名常常尾部才有
   * 区分度，省略后可能看不出是哪个——完整路径是兜底的确认手段。
   *
   * <p>无项目时回退为原来的标题文案，而不是留空：空标题会让面板看起来像出了故障。
   */
  private fun refreshWorkspaceLabel() {
    val ws = workspace
    val title = binding.assistantTitle
    if (ws == null) {
      title.text = context.getString(string.ai_assistant_title)
      androidx.appcompat.widget.TooltipCompat.setTooltipText(title, null)
      return
    }
    title.text = ws.name
    androidx.appcompat.widget.TooltipCompat.setTooltipText(title, ws.absolutePath)
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
    // INLINE 没有 FAB 可藏（见 attach）。
    if (defaultMode != Mode.INLINE) {
      fabBinding.assistantFab.isVisible = false
    }
    binding.assistantOverlay.isVisible = true
    // 可见即接管 orchestrator 回调。共享 orchestrator 时，后打开的视图抢过圆环/授权回调；
    // 不共享时重装一次等价（同样的闭包、同样的对象）。
    installOrchestratorCallbacks()
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
    // 记录正在显示的会话：后台会话事件据此被过滤掉。
    displayedConversationId = id
    loadAndSubscribe(id) {}
  }

  /**
   * 订阅指定会话、回放它的历史，并把回放期间到达的实时事件补放上去。
   *
   * <p><b>为什么要分「回放中」与「回放后」两段</b>：订阅与取历史由 orchestrator
   * 原子完成（见 `AgentOrchestrator.subscribeWithHistory`），因此**快照之后**到达的
   * 事件一定不在快照里——它们要么被丢弃（若直接渲染，会与稍后的回放重复）、
   * 要么被漏掉（若等回放结束才订阅）。两者都不行。
   *
   * <p>做法是回放期间把实时事件压进 [pendingLiveEvents]，回放结束后按原序补放。
   * 由于快照与这些事件互斥（同一把锁），补放的内容与快照**不重叠**，也不会漏。
   *
   * <p>整个订阅+读盘在 IO 线程：历史文件可能很大，主线程读会卡住输入框。
   */
  private fun loadAndSubscribe(
      conversationId: String,
      afterReplay: () -> Unit,
  ) {
    synchronized(pendingLock) {
      replayingConversationId = conversationId
      pendingLiveEvents.clear()
    }
    lifecycleScope.launch(Dispatchers.IO) {
      val messages = subscribeToConversation(conversationId)
      withContext(Dispatchers.Main) {
        replayMessages(messages)
        lastOpenedConversationId = conversationId
        updateEmptyState()
        // 回放完成：在锁内取走缓冲并解除缓冲态。取走与解除必须同时发生——若先解除
        // 再取，这中间到达的事件会直接渲染（早于缓冲内容），顺序错乱；若先取再解除，
        // 中间到达的事件会进缓冲却永远没人补放（已取完了）。
        val buffered: List<com.tom.rv2ide.ai.agent.AgentEvent>
        synchronized(pendingLock) {
          buffered = java.util.ArrayList(pendingLiveEvents)
          pendingLiveEvents.clear()
          replayingConversationId = null
        }
        for (event in buffered) {
          handleEvent(conversationId, event)
        }
        syncRunningUiForDisplayed()
        afterReplay()
      }
    }
  }

  /**
   * 订阅指定会话并取回它的历史快照。**必须在 IO 线程调用**（会读盘）。
   *
   * <p>先退订上一个会话：事件本身不带会话维度，同时订阅两个会让增量按错误的会话渲染。
   */
  private fun subscribeToConversation(
      conversationId: String,
  ): List<com.tom.rv2ide.ai.protocol.ModelMessage> {
    if (subscribedConversationId != null && subscribedConversationId != conversationId) {
      orchestrator.unsubscribe(subscribedConversationId, broadcastListener)
    }
    subscribedConversationId = conversationId
    return orchestrator.subscribeWithHistory(conversationId, broadcastListener)
  }

  /** 退订当前会话（视图销毁时调用，避免 orchestrator 的订阅表泄漏视图）。 */
  private fun unsubscribeFromConversation() {
    val id = subscribedConversationId ?: return
    orchestrator.unsubscribe(id, broadcastListener)
    subscribedConversationId = null
  }

  /** 是否已尝试过恢复会话；避免每次 open() 都回放。 */
  private var restoredOnce = false

  fun close() {
    binding.assistantOverlay.isVisible = false
    // INLINE 没有 FAB 可恢复（见 attach）。
    if (defaultMode != Mode.INLINE) {
      fabBinding.assistantFab.isVisible = true
    }
    // 隐藏即让出回调，见 [ownsOrchestratorCallbacks]。
    releaseOrchestratorCallbacks()
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
    // 视图销毁要停掉**所有**会话的运行：协程挂在 lifecycleScope 上会随之取消，
    // 但 orchestrator 的取消令牌与 MCP 连接需要显式收尾。
    cancelAll()
    // 退订事件广播。**必须**做：orchestrator 是进程级单例，不退订会让它的订阅表
    // 一直持有本视图（及其 binding/Adapter），销毁后既泄漏内存，又会在下一个
    // 事件到来时往已 detach 的控件里写数据。
    unsubscribeFromConversation()
    // 销毁是不可逆的：先从存活集合摘除自己，再让出回调。
    // 顺序很关键——若先 release，可能把回调交接回本视图自己（它仍在集合里），
    // 于是销毁后的视图仍持有回调并往已 detach 的控件里写数据。
    detachFromLiveSet()
    releaseOrchestratorCallbacks()
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

  /**
   * 拉起沉浸式全屏 Activity（真全屏）。
   *
   * <p>与 [toggleFullscreen] 的区别：那个只把面板撑满**当前窗口**；本方法开一个独立屏幕
   * （横屏/沉浸式），是用户能真正进入的第三种宿主形态。
   *
   * <p>会话连续性不靠传 id：`AssistantFullscreenActivity` 会
   * `setWorkspace(IProjectManager.getInstance().projectDir)`，而
   * `AgentOrchestrator.setWorkspace` 会恢复该工作区上次的会话。
   *
   * <p>`FLAG_ACTIVITY_NEW_TASK` 对应用外悬浮（Service context）是必需的——从非 Activity
   * context 启动 Activity 必须带它；对 Activity 宿主无害。Android 10 起后台启动 Activity
   * 受限，可能被系统拦截，因此**失败一律吞掉**：这只是多一个入口，拉不起也不该让面板崩。
   */
  private fun launchTrueFullscreen() {
    val intent =
        android.content.Intent(
                context,
                com.tom.rv2ide.activities.editor.AssistantFullscreenActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
      context.startActivity(intent)
    } catch (e: Exception) {
      // 后台启动被拦 / 没有可用的 Activity：静默忽略，不打断当前会话。
    }
  }

  private fun applyMode(newMode: Mode) {
    mode = newMode
    val card = binding.assistantCard
    val params = card.layoutParams as ConstraintLayout.LayoutParams

    // 全屏按钮的语义随当前形态翻转：全屏时说「退出全屏」，否则说「全屏」。
    // 写进 contentDescription 而不是按钮文字（按钮是图标），无障碍服务读它。
    // 末尾补上长按提示：长按是进入沉浸式全屏的入口，纯图标按钮无法自述，
    // 不写进无障碍描述的话读屏用户完全发现不了这个动作。
    val fullscreenLabel =
        context.getString(
            if (newMode == Mode.FULLSCREEN) string.ai_assistant_side
            else string.ai_assistant_fullscreen
        )
    binding.assistantFullscreen.contentDescription =
        "$fullscreenLabel · ${context.getString(string.ai_assistant_fullscreen_hint)}"

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
        val screenWidth = screenWidthPx()
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
        val screenWidth = screenWidthPx()
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
      Mode.INLINE -> {
        // 内联页：占满宿主容器，无边距、无圆角、无描边。
        //
        // 与 FULLSCREEN 的「浮层铺开」相反，这里要的是「它就是页面本身」——
        // 任何留白或圆角都会立刻把它变回盖在内容上的卡片。宽度不按屏幕比例算，
        // 由容器的 MATCH_PARENT 决定（宿主给的容器就是页面的可用区域）。
        params.width = ViewGroup.LayoutParams.MATCH_PARENT
        params.height = ViewGroup.LayoutParams.MATCH_PARENT
        params.marginStart = 0
        params.marginEnd = 0
        params.topMargin = 0
        params.bottomMargin = 0
        card.radius = 0f
        card.strokeWidth = 0
      }
    }

    // 水平对齐方向。
    //
    // 面板在 ConstraintLayout 里同时被 start/end 约束，宽度固定时**默认居中**——
    // DOCKED 形态不设 bias 会落在屏幕中间，看起来还是浮窗而不是贴边面板。
    // 这里显式指定：DOCKED 靠右，其余两种都占满宽度、bias 无影响（设 0.5 保持一致）。
    params.horizontalBias = if (newMode == Mode.DOCKED) 1f else 0.5f

    card.layoutParams = params

    // 标题栏右侧三键在 INLINE 下无意义：
    // - 全屏：页面本身已经占满容器，再切全屏没有目标形态；
    // - 最小化 / 关闭：内联页没有可收起的宿主，收起后无处可去。
    // 保留「菜单（会话抽屉）」与「设置」（在抽屉底部），页面导航由宿主的返回键负责。
    //
    // 只在 INLINE 下改可见性，且每次 applyMode 都显式重设：从 INLINE 切回其他形态
    // （理论上宿主允许时）不会把按钮永久藏掉。
    val chromeVisible = newMode != Mode.INLINE
    binding.assistantFullscreen.isVisible = chromeVisible
    binding.assistantMinimize.isVisible = chromeVisible
    binding.assistantClose.isVisible = chromeVisible

    // 换形态就换了宽度，工具条能放下几个控件随之变化，必须重算。
    //
    // 不重算的后果是单向的：窄形态（贴边）下隐藏了 git 分支标签，之后切到全屏
    // 也不会重新显示——用户会觉得「全屏了还是少个东西」。反方向同理。
    applyToolbarDensity()
  }

  private fun dp(value: Int): Int = (value * density()).toInt()

  /**
   * 显示密度。
   *
   * <p>原实现取 `parent.resources.displayMetrics.density`。Activity 宿主下 `parent` 与
   * `context` 同源（都来自同一个 Activity），两者取到的 Resources 是同一个对象，
   * 因此换成 `context.resources` 对现有调用点零影响；换用宿主 Context 也让非 Activity
   * 宿主能给出自己窗口的密度。
   */
  private fun density(): Float = context.resources.displayMetrics.density

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
        // /clear 清的是当前显示的会话。
        cancel(displayedConversationId)
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
    // 只检查**当前显示的**会话：压缩作用的对象是它，别的会话在跑不影响。
    // 用 orchestrator 的运行态：运行可能在别的入口发起，本视图的 job 表看不到。
    val displayed = displayedConversationId
    if (displayed != null && isConversationRunning(displayed)) {
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
    val currentWorkspace = workspace
    if (currentWorkspace == null || !currentWorkspace.exists()) {
      appendTrace(context.getString(string.ai_assistant_no_workspace))
      return
    }

    // 先确定本次请求落进哪个会话，再据它做重入判定。多会话/多项目并行时，每条消息
    // 都属于一个确定的会话；同一会话禁止重入（否则同一段历史会被两轮并发写入），
    // 不同会话互不阻塞。
    val conversationId = resolveTargetConversation()
    if (conversationId == null) {
      appendTrace(context.getString(string.ai_assistant_error, "IOException", "无法创建会话"))
      return
    }

    // 该会话已在跑（可能是本视图发起的，也可能是**另一个入口**发起的——三个入口
    // 共享 orchestrator 单例）：入队，等它结束后串行发出。
    //
    // 此前这里直接 `return`，用户连发两条时第二条被静默丢弃：界面上什么都没发生，
    // 用户以为已经发出去了。排队让「我发的每条都会被处理」成立，顺序即输入顺序。
    //
    // 判定用 orchestrator.isRunning 而不是本视图的 executionJobs：运行可能在别的
    // 入口发起（A 在跑、用户在 B 里输入），只有 orchestrator 知道全部正在跑的会话。
    if (isConversationRunning(conversationId)) {
      enqueueSend(conversationId, userRequest, reasoningEffort, imagePayload)
      return
    }
    startRun(conversationId, userRequest, reasoningEffort, imagePayload)
  }

  /**
   * 指定会话是否正在运行。
   *
   * <p>以 orchestrator 的取消令牌表为准：运行可能在**别的入口**发起，本视图的 job 表
   * 看不到它，据此判断会让消息在「另一个入口正在跑」时直接发起、两个循环并发写同一份
   * 历史。本视图自己的 job 表仅作兜底（极端时序下令牌已移除而协程还在收尾）。
   */
  private fun isConversationRunning(conversationId: String): Boolean =
      orchestrator.isRunning(conversationId) || executionJobs[conversationId]?.isActive == true

  /**
   * 真正发起一次运行（**不做排队判定**）。
   *
   * <p>与 [execute] 分开是为了让队列消化走这条路：从 `finally` 里取队首再调 [execute]
   * 时，本协程尚未从 [executionJobs] 摘除（`invokeOnCompletion` 还没跑），
   * [isConversationRunning] 仍为 true，于是又会入队——队列永远原地打转。
   * 这里跳过判定直接跑，因为调用方已经确认「当前没有其它运行」。
   */
  private fun startRun(
      conversationId: String,
      userRequest: String,
      reasoningEffort: String?,
      imagePayload: String?,
  ) {
    // 用户在当前视图里发消息，就是要看这个会话：切显示过去，并清掉上一会话的渲染指针。
    displayedConversationId = conversationId
    lastOpenedConversationId = conversationId
    // 先登记回显标记再画气泡：orchestrator 广播该消息时会命中它，避免画第二遍。
    markLocalEcho(conversationId, userRequest)
    adapter.append(AssistantMessageAdapter.Role.USER, userRequest)
    val ui = uiState(conversationId)
    ui.streamingMessageId = null
    ui.streamedThisRun = false
    ui.lastToolCardId = null
    ui.lastThinkingId = null
    // 新一轮开始：清掉该会话上一轮留下的重试结论与计数。
    ui.retryCountThisRun = 0
    ui.retryCardPinned = false
    hideRetryCard()

    val job =
        lifecycleScope.launch(Dispatchers.IO) {
          val agents = Agents(context)
          val providerId = agents.getProvider()
          // 注意：不要在启动前 cancel 本会话的旧 job。这里已确认本会话没有活跃 job
          // （上面的重入判定），启动时再 cancel 会取消掉即将执行的自己。
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
                    // 显式传会话 id：运行期间用户可能切到别的会话，隐式用
                    // orchestrator.activeConversationId 会把历史写进错误的文件。
                    conversationId,
                    providerId,
                    modelId,
                    userRequest,
                    customBaseUrl,
                    // listener 传 null：本视图的事件**只**从会话订阅走（见 broadcastListener）。
                    // 若这里再传 handleEvent，同一事件会被投递两遍——一遍由 orchestrator
                    // 直接回调、一遍由广播给订阅者——于是每个增量渲染两次（气泡里出现
                    // 重复文字、工具卡片出现两张）。订阅是唯一的投递路径。
                    null,
                    imagePayload,
                    reasoningEffort,
                )
            withContext(Dispatchers.Main) {
              // 会话可能已被切走：后台会话的结果不写控件（已落盘，切回时回放）。
              if (displayedConversationId != conversationId) {
                return@withContext
              }
              finishStreaming(ui)
              // 已经流式显示过就不再追加：否则同一段回答会出现两遍。
              if (!ui.streamedThisRun) {
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
              if (displayedConversationId != conversationId) {
                return@withContext
              }
              finishStreaming(ui)
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
            // 运行态**不在这里收尾**：收尾动作（收掉停止键、停状态条、收重试卡片）
            // 统一由 RUN_FINISHED 事件驱动（见 handleEvent）。理由有二：
            //  1. 队列由 orchestrator 在它自己的 finally 里紧接着发起下一条，本视图的
            //     finally 与那个时机有竞态——先收后开会让停止键闪一下。
            //  2. 运行可能由**另一个入口**发起，本视图的 finally 根本不会跑，
            //     那种情况下只有 RUN_FINISHED 能收尾。
            // 这里只做与「本视图自己发起的这次运行」相关的收尾（状态摘要行）。
            withContext(kotlinx.coroutines.NonCancellable) {
              withContext(Dispatchers.Main) {
                if (displayedConversationId == conversationId) {
                  // 重试卡片收掉——**除非它正在报告「重试用尽」**。
                  // 那条信息必须留在屏幕上：用户需要知道失败前重试过几次，
                  // 而运行结束后再没有任何地方会显示它。
                  if (!ui.retryCardPinned) {
                    hideRetryCard()
                  }
                }
              }
            }
          }
        }
    executionJobs[conversationId] = job
    // 结束后自摘，避免 Map 随会话数无限增长（job 已完成时 remove 是 no-op 安全的）。
    job.invokeOnCompletion { executionJobs.remove(conversationId, job) }
  }

  /**
   * 标记「本视图即将发出一条用户消息」，用于在广播回来时跳过它。
   *
   * <p>本视图在 [startRun] 里本地画了用户气泡；同一条消息还会被 orchestrator 广播
   * 回来（[com.tom.rv2ide.ai.agent.AgentEvent.Type.USER_MESSAGE]）。不区分就会出现
   * 两个一模一样的用户气泡。这里记下「刚发的内容」，广播到达时消费掉它。
   *
   * <p>用内容而非 id 匹配：事件里只有文本。风险是「用户连发两条完全相同的消息」时
   * 第二条的回显会被误吞——代价是少画一个气泡（另一条已画出），比多画一个可接受。
   */
  private fun markLocalEcho(conversationId: String, text: String) {
    localEchoes.add(conversationId to text)
    // 上限保护：运行在广播前就失败（例如未设工作区）时标记不会被消费。
    // 不设上限的话，反复失败会让这个列表无界增长。
    while (localEchoes.size > MAX_LOCAL_ECHOES) {
      localEchoes.removeAt(0)
    }
  }

  /** 消费一条待回显标记；命中则说明该事件是本视图自己发的。 */
  private fun consumeLocalEcho(conversationId: String, text: String): Boolean =
      localEchoes.remove(conversationId to text)

  /** 待回显的本地消息（会话 id + 内容）。仅主线程访问。 */
  private val localEchoes = java.util.ArrayList<Pair<String, String>>()

  /**
   * 把一条请求排进 orchestrator 的会话队列，并告诉用户它在队列里的位置。
   *
   * <p>同时把用户气泡画出来（[startRun] 里也画，但那条路径是真正发起运行；排队这条
   * 必须先画）：不画的话用户点了发送却什么都看不到，与「被静默丢弃」观感完全一样
   * ——这正是排队要修的问题。
   */
  private fun enqueueSend(
      conversationId: String,
      text: String,
      reasoningEffort: String?,
      imagePayload: String?,
  ) {
    displayedConversationId = conversationId
    lastOpenedConversationId = conversationId
    markLocalEcho(conversationId, text)
    adapter.append(AssistantMessageAdapter.Role.USER, text)
    scrollToBottom()
    val agents = Agents(context)
    val providerId = agents.getProvider()
    val modelId =
        AgentModelConfigs.modelIdFor(providerId, agents.getAgent(), inputFeatures.currentSlot())
    // 位置取 orchestrator 的队列长度：它是全局真相（本视图看不到别的入口排了什么）。
    val ahead =
        orchestrator.enqueue(
            conversationId,
            providerId,
            modelId,
            text,
            AgentOrchestrator.customBaseUrlFor(context, providerId),
            imagePayload,
            reasoningEffort,
        )
    // 只显示「前面还有几条」，不显示总数：用户关心的是还要等多久（前面几条），
    // 总数对「什么时候轮到我」没有信息量。
    appendTrace(context.getString(string.ai_assistant_queued, ahead))
  }

  /**
   * 本次请求应落进的会话 id。
   *
   * <p><b>优先「当前显示」的会话</b>：用户点开一条会话后 {@link displayedConversationId}
   * 立刻更新，而 {@code orchestrator.activeConversationId} 要等回放完成才更新。若只读后者，
   * 在回放完成前发送会把消息写进**上一个**会话。显示中的会话才是用户此刻在看的那个。
   *
   * <p>显示会话不可用时回退到当前活动会话；都不存在（首次、或已被删除）时新建一个。
   * 新建走同步路径，保证用户消息立即落盘到正确的会话文件。
   */
  private fun resolveTargetConversation(): String? {
    val displayed = displayedConversationId
    if (!displayed.isNullOrEmpty() && orchestrator.conversationStore.exists(displayed)) {
      return displayed
    }
    val active = orchestrator.activeConversationId
    if (!active.isNullOrEmpty() && orchestrator.conversationStore.exists(active)) {
      return active
    }
    return try {
      orchestrator.newConversation().id
    } catch (e: java.io.IOException) {
      com.tom.rv2ide.ai.tool.api.ErrorLog.record("agent", "新建会话失败", e, null)
      null
    }
  }

  /**
   * 渲染一条 agent 事件。
   *
   * <p><b>按会话隔离</b>：事件携带产生它的 [conversationId]。多会话并行时，只有
   * **当前显示**的会话才写控件；后台会话的事件只由 orchestrator 的 PersistingListener
   * 落盘（内容不丢），用户切回时回放。每个会话的流式/工具卡片指针存在 [uiState] 里，
   * 避免 A 会话的 TOOL_FINISHED 回填到 B 会话的卡片。
   */
  private fun handleEvent(conversationId: String, event: com.tom.rv2ide.ai.agent.AgentEvent) {
    val ui = uiState(conversationId)
    when (event.type) {
      com.tom.rv2ide.ai.agent.AgentEvent.Type.RUN_STARTED -> {
        // 运行起点分界：重置本会话的渲染指针，让下一个增量另起一段，而不是追加进
        // 历史里那条「已完成」的助手气泡（见 AgentEvent.Type.RUN_STARTED）。
        // 不碰列表内容——历史照旧显示，只是不再往里续写。
        //
        // 切主线程：本分支由事件线程进入，而 ui 的字段与其它分支一样只在主线程读写
        // （其它分支都在 lifecycleScope.launch(Dispatchers.Main) 里改它）。
        lifecycleScope.launch(Dispatchers.Main) {
          ui.streamingMessageId = null
          ui.lastThinkingId = null
          ui.lastToolCardId = null
          ui.streamedThisRun = false
          // 显示停止键：运行可能是**别的入口**发起的，本视图的 job 表看不到它，
          // 只有这个事件能告诉它「现在有运行，且停止键该出现」。
          if (displayedConversationId == conversationId) {
            setRunningUi(true)
            binding.assistantWorking.bind(isThinking = false)
            binding.assistantWorking.startWorking()
          }
        }
      }
      com.tom.rv2ide.ai.agent.AgentEvent.Type.RUN_FINISHED -> {
        // 运行结束（含失败/取消）。由 orchestrator 在 finally 里广播，因此**任何入口**
        // 都能收到——本视图据此收掉停止键。少了它，别的入口发起并结束的运行会让
        // 本视图的停止键永久停在那里（点下去只会对已经不存在的运行发取消）。
        lifecycleScope.launch(Dispatchers.Main) {
          if (displayedConversationId != conversationId) {
            return@launch
          }
          // 若该会话仍有排队请求，orchestrator 会在同一 finally 里紧接着发起下一条，
          // 那时会再收到 RUN_STARTED。这里先按「已结束」收尾，由后续事件重新点亮。
          setRunningUi(false)
          binding.assistantWorking.stopWorking()
          if (!ui.retryCardPinned) {
            hideRetryCard()
          }
        }
      }
      com.tom.rv2ide.ai.agent.AgentEvent.Type.USER_MESSAGE -> {
        lifecycleScope.launch(Dispatchers.Main) {
          // 本视图自己发出的消息不走这条路：startRun 已本地画过气泡，若这里再画
          // 会出现两个一模一样的用户气泡。用「待回显」标记识别并消费掉它。
          // 标记的读写都必须在主线程（startRun 在主线程登记）。
          if (consumeLocalEcho(conversationId, event.message)) {
            return@launch
          }
          // 订阅方（另一个入口）发来的：画出来，否则 B 看到的是「助手在自言自语」。
          if (displayedConversationId != conversationId) {
            return@launch
          }
          adapter.append(AssistantMessageAdapter.Role.USER, event.message)
          scrollToBottom()
        }
      }
      com.tom.rv2ide.ai.agent.AgentEvent.Type.REASONING_DELTA -> {
        val delta = event.message
        if (delta.isEmpty()) {
          return
        }
        if (displayedConversationId != conversationId) {
          return
        }
        lifecycleScope.launch(Dispatchers.Main) {
          // 返回 -1 表示「纯空白、没建块」——不能把它存进 lastThinkingId，
          // 否则后续增量会去找一个不存在的 id。
          val id = adapter.appendThinking(delta, ui.lastThinkingId)
          if (id >= 0) {
            ui.lastThinkingId = id
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
        if (displayedConversationId != conversationId) {
          return
        }
        // 累积始终发生在数据层（不能丢内容），只有界面刷新被节流。
        lifecycleScope.launch(Dispatchers.Main) {
          val id = ui.streamingMessageId
          if (id == null) {
            // 还没有气泡时，纯空白增量不建气泡。模型「只调用工具、不写正文」的轮次
            // 会先吐出 "\n\n"，那时建出的气泡最终没有内容，渲染成一片空白灰块。
            // 等真正有内容的增量到了再建。
            if (delta.isBlank()) {
              return@launch
            }
            ui.streamingMessageId =
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
        if (displayedConversationId != conversationId) {
          return
        }
        lifecycleScope.launch(Dispatchers.Main) {
          val id = ui.streamingMessageId
          if (text.isNotBlank()) {
            // streamedThisRun 只在**确实往列表里写过内容**时置位。
            // 原先无条件置 true 会掩盖一条路径：没有流式增量（非流式响应）时 id 为 null，
            // 此时若 text 恰好为空，就既没追加本轮输出、又让收尾逻辑以为「已经显示过」，
            // 于是整轮回答在界面上彻底消失。
            ui.streamedThisRun = true
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
            ui.streamingMessageId = null
          }
          // 本轮推理到此结束。AgentSession 的事件顺序是 TURN_FINISHED 先于该轮的
          // TOOL_STARTED，所以在这里收尾最准；置空后，工具之后的新推理会另起一块。
          ui.lastThinkingId?.let { adapter.finishThinking(it) }
          ui.lastThinkingId = null
          finishStreaming(ui)
          scrollToBottom()
        }
      }
      com.tom.rv2ide.ai.agent.AgentEvent.Type.TOOL_STARTED -> {
        val call = event.toolCall
        if (displayedConversationId != conversationId) {
          return
        }
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
          ui.lastToolCardId = id
          // 发生工具调用意味着「这一段推理结束了」：把思维链块收尾并断开，
          // 让工具之后的新推理另起一块。否则整轮的推理会堆在同一块里，
          // 看不出哪段推理导致了哪次调用。
          ui.lastThinkingId?.let { adapter.finishThinking(it) }
          ui.lastThinkingId = null
          // 底部状态条播报这一步在做什么。放在这里（而不是 TOOL_FINISHED）：
          // 用户需要的是「现在在跑什么」，「刚刚跑完了什么」工具卡片已经写了。
          showAction(actionForTool(call.name, call.arguments))
          scrollToBottom()
        }
      }
      com.tom.rv2ide.ai.agent.AgentEvent.Type.TOOL_FINISHED -> {
        val result = event.toolResult
        val call = event.toolCall
        if (displayedConversationId != conversationId) {
          return
        }
        lifecycleScope.launch(Dispatchers.Main) {
          // 回填到最近的卡片。TOOL_STARTED / TOOL_FINISHED 成对出现，
          // 用「最近一张仍在运行中的卡片」关联即可，无需在协议层传 id。
          val cardId = ui.lastToolCardId ?: adapter.lastToolCallId()
          if (cardId != null) {
            adapter.completeToolCall(cardId, result.content, result.isError)
          }
          ui.lastToolCardId = null

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
        ui.retryCountThisRun = Math.max(ui.retryCountThisRun, event.retryAttempt)
        if (displayedConversationId != conversationId) {
          return
        }
        lifecycleScope.launch(Dispatchers.Main) {
          ui.streamingMessageId?.let { adapter.remove(it) }
          ui.streamingMessageId = null
          ui.lastThinkingId?.let { adapter.remove(it) }
          ui.lastThinkingId = null
          ui.lastToolCardId = null
          ui.streamedThisRun = false
          // 节流器要重置：它记着「本轮是否已刷过」，不重置会让重发后的首个增量
          // 被当成「间隔未到」而丢掉，看起来像新回答迟迟不出现。
          throttle.reset()
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
        if (displayedConversationId != conversationId) {
          return
        }
        lifecycleScope.launch(Dispatchers.Main) { appendTrace(event.message) }
      }
      com.tom.rv2ide.ai.agent.AgentEvent.Type.FAILED -> {
        ui.streamedThisRun = true
        ui.retryCardPinned = ui.retryCountThisRun > 0
        if (displayedConversationId != conversationId) {
          return
        }
        lifecycleScope.launch(Dispatchers.Main) {
          // 仍在「运行中」的卡片要收尾，否则会永久停在运行态，用户以为还在跑。
          for (id in adapter.runningToolCallIds()) {
            adapter.failToolCall(id, event.message)
          }
          // 思维链同理：失败时标题必须从「思考中…」切走，否则看起来像还在等。
          adapter.finishAllThinking()
          ui.lastThinkingId = null
          // 重试卡片留在原地改成「重试 N 次仍失败」，而不是直接收掉。
          //
          // **这条是实测反馈的直接来源**：此前重试卡片还没实现，重试信息走
          // appendTrace 进消息列表，用户看到的是「正在重试 1/10」紧接着「请求失败」——
          // 中间九次重试去哪了完全看不出来。留在原地并写明次数，
          // 「重试到第几次才放弃」才是可读的。
          if (ui.retryCountThisRun > 0) {
            binding.assistantRetryCard.isVisible = true
            binding.assistantRetryText.text =
                context.getString(string.ai_assistant_retry_exhausted, ui.retryCountThisRun) +
                    shortErrorCode(event.message).let { if (it.isEmpty()) "" else " · $it" }
          }
          appendTrace("⚠️ ${event.message}")
        }
      }
      else -> {}
    }
  }

  /**
   * 发送按钮的点击：**永远只发送**。
   *
   * <p>运行中则由 [execute] 把消息排进队列，而不是像此前那样让同一个按钮变成「停止」——
   * 那种二合一让「我要再发一条」与「我要停下来」两个互斥意图共用一个控件，想连发的
   * 用户点下去实际取消了任务（实测如此）。停止现在由左侧独立的 [binding.assistantStop]
   * 承担。
   */
  private fun onSendClicked() {
    sendFromInput()
  }

  /** 停止按钮：取消**当前显示会话**的运行。 */
  private fun onStopClicked() {
    val displayed = displayedConversationId ?: return
    // 只取消当前显示的会话：后台会话继续跑（多项目并行）。
    cancel(displayed)
    // 立刻切回非运行态：用户点这个按钮的意图是「现在就停」，让他等 finally 里的恢复
    // 会有一段「点了没反应」的空窗。
    setRunningUi(false)
    binding.assistantWorking.stopWorking()
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
    // 发送键**不再随运行状态换图标**：运行中它仍是「发送」，点它把消息排进队列
    // （见 execute）。运行态由左侧独立的停止键承担——此前两态合一，想连发第二条的
    // 用户点下去会取消正在跑的任务（实测如此）。
    //
    // 停止键只在运行中可见。放在发送键左侧：发送是高频动作，位置不该变。
    binding.assistantStop.isVisible = running
    binding.assistantStop.setOnClickListener { onStopClicked() }

    // 状态条与分隔线在这里一并显隐。
    //
    // **此前它们从未显示过**：XML 里两者都是 visibility="gone"，而代码只调用了
    // startWorking()/stopWorking()——那两个方法只管动画，不管可见性。结果是
    // 动画在一个 GONE 的视图上跑，用户什么都看不到，界面上唯一的运行信号就是
    // 「发送按钮变灰」。这正是「工作状态显示不够明显」的直接原因。
    binding.assistantWorking.isVisible = running
    binding.assistantWorkingDivider.isVisible = running

    // 重试结论的清理由「新一轮开始」（execute）负责，不放在这里：本方法也会被
    // 「切到正在跑的会话」调用，若在此清计数会把该会话已累计的重试次数抹掉。
  }

  /** 隐藏重试卡片。 */
  private fun hideRetryCard() {
    binding.assistantRetryCard.isVisible = false
  }

  /**
   * 按**当前显示会话**的运行状态同步按钮与状态条。
   *
   * <p>多会话并行下，切换会话不能沿用上一个会话的运行态：A 在跑、切到空闲的 B 时
   * 按钮必须回到「发送」；反之切到正在跑的会话要显示「停止」。
   */
  private fun syncRunningUiForDisplayed() {
    // 看 orchestrator 的全局运行态：停止键要能停掉**任何入口**发起的运行
    // （A 在跑、用户切到 B，在 B 里按停止应当能停下 A 的任务）。发送键不受影响
    // ——它永远只发送（运行中则排队），所以「别的入口在跑」不会误伤用户意图。
    val running = displayedConversationId?.let { isConversationRunning(it) } == true
    setRunningUi(running)
    if (running) {
      binding.assistantWorking.startWorking()
    } else {
      binding.assistantWorking.stopWorking()
    }
  }

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
    // 向上取整到至少 1 秒，与 AgentEvent.streamRetrying 里 message 的算法保持一致。
    // 退避首轮是 500ms，若按整数除法会得到「0 秒后重试」——用户看到的是一个
    // 已经过期的倒计时，而事件 message 里写的是「1s」，两处自相矛盾。
    val seconds = Math.max(1L, delayMs / 1000L)
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
  private fun finishStreaming(ui: SessionUiState) {
    // 末尾必刷：被节流合并掉的最后一段内容必须补出去，否则看起来像回答被截断了。
    if (throttle.flush()) {
      scrollToBottom()
    }
    throttle.reset()
    ui.streamingMessageId = null
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
    host.dialogs.openSettings()
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
    host.dialogs.showSingleChoice(string.ai_assistant_permission_title, labels, checked) { which ->
      settings.permissionMode = modes[which]
      refreshPermissionChip()
    }
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
    host.dialogs.showMessage(
        string.ai_assistant_context_usage,
        context.getString(
            string.ai_assistant_context_usage_detail,
            formatTokens(lastContextUsed),
            formatTokens(total),
            percent,
        ),
        string.ai_assistant_compact_now,
        { compactConversation() },
        android.R.string.cancel,
    )
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
   * <p><b>逐级让位</b>（三个阈值均由实测控件宽度推导，各自常量的 KDoc 里有算式）：
   * 模型名永远保留（它是用户最需要看的一格），装不下时先隐服务商、再隐 git：
   * <ul>
   *   <li>≥425dp：全部显示（含服务商标签）
   *   <li>&lt;425dp：隐藏服务商标签（模型名已隐含服务商）
   *   <li>&lt;329dp：隐藏 git 分支
   *   <li>&lt;257dp：把模型名压到 [MODEL_MAX_WIDTH_NARROW_DP]
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
      val widthDp = (bar.width / density()).toInt()
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
      //
      // 不必再与 veryNarrow 相与：PROVIDER_VISIBLE_MIN_DP(425) > MODEL_VISIBLE_MIN_DP(257)，
      // 宽度达到 425 时 veryNarrow 必然为 false，那个条件是恒真的死逻辑。
      binding.assistantToolbarProvider.isVisible = widthDp >= PROVIDER_VISIBLE_MIN_DP

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
   * 刷新工具条上的服务商与模型两个标签。
   *
   * <p>由选择器在每次选择后回调，以及面板 attach 时调用一次。不订阅偏好变更：
   * 目前只有本面板会改这两个值，回调已经覆盖；引入全局监听反而要为「谁改的」
   * 做去重，得不偿失。
   *
   * <p>两者是**分开的两个控件**而不是合成一段「服务商 / 模型」：合成后两段文字在
   * 窄面板里会一起被压成省略号，用户既看不出服务商也看不出模型。分开之后各自独立
   * 省略，且服务商标签可随宽度让位（见 [applyToolbarDensity]）。
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
        // 先设当前工作区再提交列表：归属标注要在 bind 时就能拿到正确值，
        // 否则会先按上一次的项目渲染一遍再纠正，用户能看到一次闪烁。
        conversationAdapter.setCurrentCwd(workspace?.absolutePath)
        conversationAdapter.submitList(summaries)
        conversationAdapter.setActive(orchestrator.activeConversationId)
        binding.assistantConversationsEmpty.isVisible = summaries.isEmpty()
      }
    }
  }

  /**
   * 打开一个历史会话。
   *
   * <p>必须同时做三件事：把 orchestrator 的当前会话指过去（后续请求续接它的历史）、
   * 把该会话的消息回放到界面上、并让面板跟随该会话所属的项目。
   *
   * <p><b>不再弹跨项目确认框</b>：会话绑定 cwd（存在 {@code SessionMetaEntry}），
   * {@code AgentOrchestrator.run} 已改为按会话 cwd 解析工具工作区——跨项目会话
   * 现在会**正确地**作用在它自己的项目上，而不是像以前那样「历史属于 A、工具作用于 B」。
   * 保护没有消失，只是从「弹框阻止」升级为「按会话正确归属」。点一条 B 项目的会话，
   * 面板就切到 B 项目（标题、git 分支、会话归属标注一并更新），这正是用户要的快速切换。
   */
  private fun openConversation(summary: com.tom.rv2ide.ai.agent.conversation.ConversationSummary) {
    doOpenConversation(summary)
  }

  private fun doOpenConversation(
      summary: com.tom.rv2ide.ai.agent.conversation.ConversationSummary
  ) {
    val convId = summary.getId()
    // 切换显示：把该会话标为当前显示，后台会话事件从此不再写控件。
    displayedConversationId = convId
    // 只取消**目标会话自身**正在跑的运行（几乎不可能发生：正在跑的会话被点开时
    // 保留它继续跑——用户要看的是它跑到哪了，不是把它停掉）。
    // 绝不取消其它会话——那会杀掉用户并行跑着的任务。
    lifecycleScope.launch(Dispatchers.IO) {
      // 会话绑定 cwd 时，面板跟随它切项目。在 IO 线程设置：setWorkspace 会读会话列表。
      val cwd = summary.getCwd()
      val projectDir = if (cwd.isNotBlank()) File(cwd) else null
      val switchProject = projectDir != null && projectDir.isDirectory
      if (switchProject) {
        orchestrator.workspace = projectDir
      }
      withContext(Dispatchers.Main) {
        if (switchProject) {
          workspace = projectDir
          // 换了项目就换了仓库：分支标签要重读，旧项目的分支不能留着。
          lastGitBranch = null
          refreshGitBranch()
          refreshWorkspaceLabel()
          conversationAdapter.setCurrentCwd(projectDir.absolutePath)
        }
        orchestrator.openConversation(convId)
        conversationAdapter.setActive(convId)
        // 订阅 + 回放。**不再在切会话时取消该会话的运行**：它可能正在跑，而用户点开
        // 就是想看实时输出——订阅后就能收到后续增量（历史由 subscribeWithHistory 原子取回）。
        loadAndSubscribe(convId) {
          // 打开后自动收起抽屉：用户的意图是「看这个会话」，不是继续浏览列表。
          if (binding.assistantDrawerOverlay.isVisible) {
            toggleConversationPanel()
          }
        }
      }
    }
  }

  /**
   * 把历史消息回放到列表。
   *
   * <p>用户与助手文本、**思考过程**、**工具卡片（含结果）**都还原——三者都在会话日志里：
   * 思考由 {@code AgentOrchestrator.PersistingListener} 累积后写入
   * {@code AssistantMessageEntry.reasoningContent}；工具调用在其 {@code toolCalls} 字段；
   * 工具结果是一条独立的 {@code ToolResultEntry}，折叠后变成 {@code ToolModelMessage}。
   *
   * <p><b>顺序不变量</b>：折叠产出的顺序是「助手条目（含其 toolCalls）→ 各工具结果」，
   * 与实时渲染一致（TURN_FINISHED 先于 TOOL_STARTED/TOOL_FINISHED）。因此这里按顺序
   * 建卡片、并按顺序回填结果即可，不需要靠 id 关联——{@code ToolModelMessage} 自带
   * toolCallId，但结果与调用的**先后**已经足够确定配对。
   */
  private fun replayMessages(messages: List<com.tom.rv2ide.ai.protocol.ModelMessage>) {
    adapter.clear()
    // 重置**当前显示会话**的渲染指针（不是全局字段——那些已按会话隔离）。
    displayedConversationId?.let { id ->
      val ui = uiState(id)
      ui.streamingMessageId = null
      ui.streamedThisRun = false
      ui.lastThinkingId = null
      ui.lastToolCardId = null
    }
    throttle.reset()
    // 待回填的工具卡片，按建立顺序排队。工具结果到达时取队首回填——
    // 与实时路径用 lastToolCardId 的语义一致（成对、按序）。
    val pendingToolCards = java.util.ArrayDeque<Long>()
    for (message in messages) {
      when (message) {
        is com.tom.rv2ide.ai.protocol.UserModelMessage ->
            adapter.append(AssistantMessageAdapter.Role.USER, message.getContent())
        is com.tom.rv2ide.ai.protocol.AssistantModelMessage -> {
          // 顺序与实时渲染一致：先思考块，再工具卡片，最后正文。
          val reasoning = message.getReasoningContent()
          if (!reasoning.isNullOrBlank()) {
            adapter.appendThinking(reasoning, null)
          }
          val calls = message.getToolCalls()
          if (calls != null) {
            for (call in calls) {
              val id =
                  adapter.appendToolCall(
                      toolName = call.name,
                      summary = summarizeArgs(call.arguments),
                      input = call.arguments.orEmpty(),
                  )
              pendingToolCards.addLast(id)
            }
          }
          val text = message.getContent()
          if (!text.isNullOrBlank()) {
            adapter.append(AssistantMessageAdapter.Role.ASSISTANT, text)
          }
        }
        is com.tom.rv2ide.ai.protocol.ToolModelMessage -> {
          // 回填到最早一张仍在等待的卡片。卡片用运行中状态建出来，不回填就会永久停在
          // 「运行中」——历史里一条早已结束的工具显示成还在跑，是明确的错误信息。
          val cardId = pendingToolCards.pollFirst()
          if (cardId != null) {
            adapter.completeToolCall(
                cardId, message.getContent(), message.isToolError())
          } else {
            // 找不到对应卡片（历史被压缩截断，或旧日志里结果条目缺少前导调用）：
            // 单独渲染一张已完成的卡片。宁可多一条，也不静默丢弃——用户需要知道
            // 「这个工具跑过，输出是什么」。
            val id =
                adapter.appendToolCall(
                    toolName = message.getToolName(),
                    summary = "",
                    input = "",
                )
            adapter.completeToolCall(id, message.getContent(), message.isToolError())
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
    host.dialogs.showConfirm(
        string.ai_conversation_delete_confirm_title,
        context.getString(string.ai_conversation_delete_confirm_message),
        string.ai_conversation_delete,
        { deleteConversation(summary) },
    )
  }

  private fun deleteConversation(
      summary: com.tom.rv2ide.ai.agent.conversation.ConversationSummary
  ) {
    val deletedId = summary.getId()
    // 删会话前先停掉它自己的运行（若在跑）：继续写一个已被删除的文件没有意义。
    // 只停这一个，其它并行会话不受影响。
    cancel(deletedId)
    executionJobs.remove(deletedId)
    sessionUi.remove(deletedId)
    // 被删的正是订阅中的会话时要退订：它的文件已不存在，继续收它的增量没有意义，
    // 且 handleEvent 会往一个已清空的列表里写。
    if (subscribedConversationId == deletedId) {
      unsubscribeFromConversation()
    }
    // 该会话排队中的消息也一并丢弃：目标已不存在，发出去只会落进一个新建的会话。
    orchestrator.clearQueue(deletedId)
    lifecycleScope.launch(Dispatchers.IO) {
      try {
        orchestrator.deleteConversation(deletedId)
      } catch (e: java.io.IOException) {
        com.tom.rv2ide.ai.tool.api.ErrorLog.record("agent", "删除会话失败", e, null)
      }
      withContext(Dispatchers.Main) {
        // 删掉的正是当前显示的会话时，必须同时清空消息区——否则会出现
        // 「列表里已经没有它、但消息还留在屏幕上」的矛盾状态，用户继续提问
        // 会落进一个已被删除的会话。
        if (deletedId == lastOpenedConversationId) {
          adapter.clear()
          displayedConversationId = null
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

  /**
   * 新建会话：可选已有项目作为该会话的 cwd。
   *
   * <p>「多项目并行」的关键入口——用户开新会话时直接指定目标项目，之后这个会话的工具
   * 调用固定在那个项目里，与视图当前显示哪个项目无关。只有一个候选项目（或读不到列表）
   * 时不弹选择框，直接在当前项目里新建，避免多余一步。
   */
  private fun startNewConversation() {
    lifecycleScope.launch(Dispatchers.IO) {
      val projects =
          try {
            orchestrator.listSelectableProjects()
          } catch (e: Exception) {
            emptyList()
          }
      withContext(Dispatchers.Main) {
        if (projects.size <= 1) {
          beginNewConversation(projects.firstOrNull())
          return@withContext
        }
        val labels = projects.map { it.name }.toTypedArray()
        val currentIdx =
            projects.indexOfFirst { it.absolutePath == workspace?.absolutePath }.coerceAtLeast(0)
        host.dialogs.showSingleChoice(
            string.ai_conversation_new_project_title,
            labels,
            currentIdx,
        ) { which -> beginNewConversation(projects.getOrNull(which)) }
      }
    }
  }

  /** 真正建会话：把项目目录作为 cwd 写进会话元信息。 */
  private fun beginNewConversation(projectDir: File?) {
    // 退订旧会话：新会话的历史为空，继续收旧会话的增量会让两个会话的内容混在
    // 同一个列表里（旧会话若还在后台跑，它的增量会继续渲染进来）。
    unsubscribeFromConversation()
    adapter.clear()
    displayedConversationId = null
    lastOpenedConversationId = null
    updateEmptyState()
    lifecycleScope.launch(Dispatchers.IO) {
      // 先把 orchestrator 的当前工作区切到目标项目，再新建：newConversation 会把
      // 「本工作区上次会话」键指向新会话，键取决于 workspace，顺序不能反。
      if (projectDir != null && projectDir.isDirectory) {
        orchestrator.workspace = projectDir
      }
      try {
        val summary = orchestrator.newConversation(projectDir?.absolutePath)
        // 订阅也在 IO 线程完成（会读盘，虽然新会话为空）：与上面同一个协程，
        // 保证「建好会话」与「订阅它」之间没有窗口——中间产生的增量不会丢。
        val messages = subscribeToConversation(summary.id)
        withContext(Dispatchers.Main) {
          displayedConversationId = summary.id
          lastOpenedConversationId = summary.id
          if (projectDir != null && projectDir.isDirectory) {
            workspace = projectDir
            lastGitBranch = null
            refreshGitBranch()
            refreshWorkspaceLabel()
            conversationAdapter.setCurrentCwd(projectDir.absolutePath)
          }
          conversationAdapter.setActive(summary.id)
          // 新会话通常为空，但走同一条回放路径：若期间已有内容（例如 orchestrator
          // 自动补了一条），也能一致地渲染出来。
          replayMessages(messages)
          syncRunningUiForDisplayed()
        }
      } catch (e: java.io.IOException) {
        // 开新会话失败不该阻断对话：orchestrator 会在下次 run 时再尝试。
        com.tom.rv2ide.ai.tool.api.ErrorLog.record("agent", "新建会话失败", e, null)
      }
    }
  }

  /**
   * 取消**指定会话**的运行。
   *
   * <p>按会话隔离：只停这一个，其它并行会话继续跑。只有该会话正在被显示时，才收尾
   * 屏幕上的工具卡片与思维链——后台会话的卡片本就不在屏幕上。
   */
  private fun cancel(conversationId: String?) {
    if (conversationId == null) {
      return
    }
    orchestrator.cancel(conversationId)
    executionJobs.remove(conversationId)?.cancel()
    val ui = uiState(conversationId)
    finishStreaming(ui)
    ui.lastToolCardId = null
    ui.lastThinkingId = null
    if (displayedConversationId == conversationId) {
      // 取消时仍在运行的卡片要收尾，否则会永久停在运行态。
      for (id in adapter.runningToolCallIds()) {
        adapter.failToolCall(id, context.getString(string.ai_assistant_tool_cancelled))
      }
      // 仍在流式的思维链块也要收尾：否则标题会永远停在「思考中…」，
      // 用户以为模型还在工作。
      adapter.finishAllThinking()
    }
  }

  /** 取消所有会话的运行（视图销毁时用）。 */
  private fun cancelAll() {
    for (id in executionJobs.keys.toList()) {
      cancel(id)
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
    // 阻塞语义保留在调用方：agent 循环线程建 latch、post 到主线程摆弹窗、再 await。
    // dialogs 只负责把弹窗摆出来并把三个选择回调回来，不感知线程。
    val latch = java.util.concurrent.CountDownLatch(1)
    var accepted = false
    android.os.Handler(android.os.Looper.getMainLooper())
        .post {
          try {
            host.dialogs.showDangerousToolConfirm(
                toolName,
                args,
                { // 允许一次
                  accepted = true
                  latch.countDown()
                },
                { // 始终允许
                  // 写的是「工具 + 参数粒度」规则，不是全局放行——
                  // 对 git status 点「始终允许」不应顺带放行 git push --force。
                  settings.applyDecision(
                      toolName,
                      args,
                      com.tom.rv2ide.ai.tool.DangerousToolDecision.ALLOW_ALWAYS,
                  )
                  accepted = true
                  latch.countDown()
                },
                { // 拒绝
                  latch.countDown()
                },
            )
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

    /**
     * 每个 orchestrator 上「存活的视图」集合，键弱引用 orchestrator。
     *
     * <p>集合内的视图也弱引用（[java.util.WeakHashMap] 的 key 集合）：视图销毁后自动移除，
     * 登记表不成为泄漏点。让出回调时从集合里挑一个其它视图做**交接**（见
     * [releaseOrchestratorCallbacks]），避免一个视图销毁把共享 orchestrator 的回调清空。
     *
     * <p>不共享 orchestrator（现有调用点）时每个视图用自己的实例作键、集合里只有自己，
     * 让出即清空，行为不变。
     */
    private val liveViews =
        java.util.WeakHashMap<
            AgentOrchestrator, MutableSet<FloatingAssistantView>>()

    /** 悬浮按钮落点（相对父容器左上角的像素）。 */
    private const val PREF_FAB_X = "ai_assistant_fab_x"
    private const val PREF_FAB_Y = "ai_assistant_fab_y"

    /** 拖动时四周保留的最小边距（dp）。 */
    private const val EDGE_MARGIN_DP = 8

    /** 会话抽屉滑入/滑出的时长。够快不拖沓，又不至于快到看不出方向。 */
    private const val DRAWER_ANIM_MS = 200L

    /**
     * 工具条宽度低于此值（dp）时隐藏 git 分支标签。
     *
     * <p><b>三个阈值全部由实测控件宽度推导</b>（设备 1080px @ density 440，
     * 即 2.75 px/dp；数字取自 uiautomator 的 bounds）：
     *
     * ```
     *   +            36.0dp
     *   permission   61.1dp
     *   git          68.0dp
     *   provider     92.0dp
     *   ring         24.0dp
     *   model       124.0dp（maxWidth 上限）
     *   间距          4.0dp × 个数
     * ```
     *
     * 累计：
     * - `+` + 权限 + 圆环 + 模型 = 257dp
     * - 再加 git = **329dp** ← 本阈值
     * - 再加服务商 = **425dp** ← [PROVIDER_VISIBLE_MIN_DP]
     *
     * <p>实测参照：侧栏形态工具条 336dp（放不下 git + 服务商，故只隐服务商），
     * 贴边形态 243dp（只留模型）。
     */
    private const val GIT_VISIBLE_MIN_DP = 329

    /**
     * 工具条宽度低于此值（dp）时隐藏服务商名。
     *
     * <p>比 git 更早让位：模型名已经隐含了服务商（「deepseek-chat」一看就知道是哪家），
     * 而 git 分支名没有替代品。隐藏后模型名顶上，用户仍能看出在用什么模型。
     *
     * <p>425dp 是「`+` 36 + 权限 61 + git 68 + 服务商 92 + 圆环 24 + 模型 124
     * + 5×4dp 间距」的实测合计。低于它就必须先让服务商走，否则模型名会被压到
     * 只剩一个省略号——实测侧栏 336dp 时模型名只剩 26dp 正是如此。
     */
    private const val PROVIDER_VISIBLE_MIN_DP = 425

    /**
     * 工具条宽度低于此值（dp）时隐藏 git 并把模型名压到
     * [MODEL_MAX_WIDTH_NARROW_DP]。
     *
     * <p>257dp 是「`+` 36 + 权限 61 + 圆环 24 + 模型 124 + 3×4dp 间距」的实测合计；
     * 低于它时模型名必须收窄才放得下。实测贴边形态 243dp 落在这一档。
     */
    private const val MODEL_VISIBLE_MIN_DP = 257

    /** 模型名的常规最大宽度（dp）。约 13 个半角字符，够显示 `deepseek-v4.1-flash`。 */
    private const val MODEL_MAX_WIDTH_DP = 124

  /** 极窄时模型名的最大宽度（dp）。约 6 个字符——够认出是哪家模型即可。 */
  private const val MODEL_MAX_WIDTH_NARROW_DP = 62

  /**
   * 待回显标记的上限。
   *
   * <p>正常情况下标记会在广播回来时立刻被消费（同一轮内）。只有「运行在广播前就失败」
   * （未设工作区、无法创建会话）才留下未消费的标记，因此一个小上限就够，
   * 作用是防止反复失败导致列表无界增长。
   */
  private const val MAX_LOCAL_ECHOES = 32

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
