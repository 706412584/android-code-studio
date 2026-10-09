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
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.isVisible
import com.google.android.material.color.MaterialColors
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.recyclerview.widget.LinearLayoutManager
import com.tom.rv2ide.activities.TerminalActivity
import com.tom.rv2ide.adapters.AssistantMessageAdapter
import com.tom.rv2ide.adapters.ConversationListAdapter
import com.tom.rv2ide.artificial.agent.compose.compat.toAnswerList
import com.tom.rv2ide.artificial.agent.compose.compat.toPendingUserQuestion
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
    INLINE,

    /** 编辑器侧栏标签页：占满容器，不挂 FAB，但保留进入真全屏的入口。 */
    EMBEDDED
  }

  private val fabBinding =
      LayoutAiAssistantFabBinding.inflate(LayoutInflater.from(context), parent, false)
  private val binding =
      LayoutAiAssistantBinding.inflate(LayoutInflater.from(context), parent, false)

  /**
   * 聊天界面外观配置（字号 / 卡片大小 / 字体颜色 / 头像）。
   *
   * <p>在这里构造并注入适配器：外观改动要**立刻反映到已渲染的消息上**，
   * 因此适配器必须在每次 `bind()` 时读最新值（而不是在构造时快照一次）。
   * 用户在设置页改完返回时，列表会因重新布局触发 bind，从而应用新值。
   */
  private val uiStyleStore = AssistantUiStyleStore(context)

  /**
   * 外观设置变更监听（字号 / 卡片大小 / 颜色 / 头像）。
   *
   * <p><b>为什么必须监听而不是等下次 bind</b>：设置页在本视图之外（另开界面），
   * 改完返回时本视图既不重建也不重新 bind 已渲染的消息——用户会看到「设置没生效」，
   * 只有滚出屏幕再滚回来才变。监听偏好变更即时重绑，改动立刻可见。
   *
   * <p>只认这四个键：同一偏好文件里还有权限模式、MCP 等十来个键，
   * 全部重绑会在无关设置（如切换 shell 后端）上也刷新一遍列表。
   */
  private val uiStyleKeys =
      setOf(
          AssistantUiStyleStore.KEY_TEXT_SIZE,
          AssistantUiStyleStore.KEY_CARD_SCALE,
          AssistantUiStyleStore.KEY_TEXT_COLOR,
          AssistantUiStyleStore.KEY_AVATAR_PATH,
          AssistantUiStyleStore.KEY_AVATAR_BUILTIN,
      )

  private val uiStylePrefs =
      context.applicationContext.getSharedPreferences("ai_agent_tools", Context.MODE_PRIVATE)

  private val uiStyleListener =
      android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null) {
          return@OnSharedPreferenceChangeListener
        }
        // 渲染路径的开关单独处理：它要的是「装/卸 Compose 面板」，不是下面那种重绑。
        // 混进重绑分支只会刷新一遍列表，路径依旧不变——表现为「开关点了没反应」。
        if (key == com.tom.rv2ide.artificial.agent.compose.AssistantComposeRender.KEY_ENABLED) {
          binding.root.post { applyComposeRenderPath() }
          return@OnSharedPreferenceChangeListener
        }
        // 工具状态条开关：同样单独处理——它改的是**可见性**，不是样式重绑。
        // 混进重绑分支只会刷新一遍列表，状态条照旧显示/隐藏——表现为「开关点了没反应」。
        if (key == com.tom.rv2ide.artificial.agent.compose.AssistantToolStatusBarPref.KEY_ENABLED) {
          binding.root.post { applyToolStatusBarVisibility() }
          return@OnSharedPreferenceChangeListener
        }
        if (key !in uiStyleKeys) {
          return@OnSharedPreferenceChangeListener
        }
        // 回调来自写入线程（设置页主线程），但保守起见投递到主线程再碰控件——
        // 与 [broadcastListener] 同一纪律。
        binding.root.post {
          if (adapter.itemCount > 0) {
            // 与 open() 里同一做法：保留滚动位置的整表重绑。
            adapter.notifyItemRangeChanged(0, adapter.itemCount)
          }
        }
      }

  private val adapter = AssistantMessageAdapter(uiStyleStore)
  private val settings = AgentToolSettings(context)

  /**
   * Compose 渲染路径的面板；为 null 时消息区走 [adapter] + RecyclerView（XML 路径）。
   *
   * <p>两条路径**并存**而不是替换：Compose 侧组件尚未真机验证，作为回退必须能随时切回来。
   * 是否启用由 [com.tom.rv2ide.artificial.agent.compose.AssistantComposeRender] 决定，
   * 默认关闭。两者共用同一个 [adapter] 作为数据源——Compose 读它的快照、写它的展开态，
   * 因此切换路径不会丢消息，也不会出现两份互不同步的对话。
   */
  private var composePanel: com.tom.rv2ide.artificial.agent.compose.AssistantComposePanelHost? = null

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
  private val inputFeatures = AssistantInputFeatures(host, binding).also { it.owner = this }

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
   * 本轮改动过的文件（按会话隔离），运行结束时汇总成一张卡片。
   *
   * <p>只在主线程读写：写入来自 TOOL_FINISHED（主线程分支），取走发生在 RUN_FINISHED
   * （同样主线程），因此用普通 MutableMap 即可，不需要并发容器。
   */
  private val pendingDiffs =
      HashMap<String, MutableList<Pair<String, String?>>>()

  /**
   * 正在运行的工具的实时输出累积（键 = 工具卡片 id）。
   *
   * <p>PROGRESS 事件按 [runningToolCardId] 找到目标卡片后把步骤行追加进来，
   * TOOL_FINISHED 时移除——键消失即「该工具不再运行」，浮动状态条据此切换运行态。
   * 写入只在主线程（与 pendingDiffs 同一纪律）。
   */
  private val runningToolOutput = HashMap<Long, StringBuilder>()

  /** 当前正在运行的工具卡片 id；无运行中工具时为 null。与 ui.lastToolCardId 的区别：
   * 它跨会话全局唯一——PROGRESS 不带会话校验也能安全找到目标。 */
  private var runningToolCardId: Long? = null

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

    /**
     * 本次运行的起始时刻（`SystemClock.elapsedRealtime()`）；0 表示未在运行。
     *
     * <p>为什么必须视图侧自己记：`AgentSession` 的 `startedAt` 是方法内局部变量，
     * `turnStarted` / `turnFinished` 事件都不带时间戳，`AgentRunResult` 也只有
     * turns / toolCallCount。要在协议层补时间戳就得改事件契约与全部构造点，
     * 而「这一轮花了多久」只是展示信息——在入口记两个时刻成本低得多。
     *
     * <p>用 `elapsedRealtime` 而不是 `currentTimeMillis`：后者会被系统对时调整，
     * 跨一次校时就会算出负耗时。
     */
    var runStartedAtMs: Long = 0L

    /** 本轮收尾的助手消息 id；耗时最终回填到它身上。 */
    var runEndMessageId: Long? = null
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
   * 会话抽屉的 Compose 状态桥（2026-10-09 起 XML RecyclerView 退役）。
   *
   * <p>三个 mutableStateOf 分别承载「列表 / 当前会话 / 当前工作区」：宿主原有的
   * `conversationAdapter.setXxx` 调用点全部平移到这里，Compose 抽屉按状态重组。
   * 投影（`ConversationSummary` → `ChatSession`）在 [projectSessions] 完成。
   */
  private val drawerSessions = mutableStateOf<List<com.tom.rv2ide.artificial.agent.compose.components.independent.ChatSession>>(emptyList())
  private val drawerActiveId = mutableStateOf<String?>(null)
  private val drawerCurrentCwd = mutableStateOf("")

  /** Compose 输入栏的状态桥：XML 输入区退役后，输入文本/忙碌/附件/模型都走这里。 */
  internal val inputBar = AssistantInputBarBridge()

  /** 会话搜索关键词与命中（会话 Tab 的搜索框）。命中在 reloadConversations 时懒查询。 */
  val chatSearchQuery = mutableStateOf("")
  private val chatSearchState = mutableStateOf(com.tom.rv2ide.artificial.agent.compose.components.independent.ChatSearchState())

  /** 抽屉「文件」Tab 的宿主状态机（读目录/新建/重命名/粘贴冲突等）。 */
  private val drawerFileHost = AssistantDrawerFileHost()

  /**
   * 文件 Tab 的工作区搜索引擎。工作区就绪时创建一次（walk 引擎直读宿主文件）；
   * 无工作区时为 null，抽屉退回「搜索不可用」空态。
   */
  private var drawerSearchEngine: com.tom.rv2ide.artificial.agent.compose.compat.WorkspaceSearchEngine? = null

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
    orchestrator.settings.setQuestionAsker(
        AgentToolSettings.QuestionAsker { questions ->
          askUserQuestionsOnMain(questions)
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
    if (defaultMode != Mode.INLINE && defaultMode != Mode.EMBEDDED) {
      fabBinding.root.isVisible = saved.first
    }
    binding.assistantOverlay.isVisible = saved.second
    // 隐藏期间运行可能已结束（或新起）——恢复可见时把运行态拉回当前真相，
    // 否则停止键与状态条会停在隐藏前的状态。
    if (saved.second) {
      syncRunningUiForDisplayed()
    }
  }

  /** 把两个视图挂到父容器上。父容器应是 `FrameLayout`（FAB 靠 gravity 定位）。 */
  fun attach() {
    // INLINE/EMBEDDED 是页面本身，没有可收起的宿主：不挂 FAB，也不装拖拽。
    // 其余形态照旧挂 FAB 并装拖拽，行为不变。
    if (defaultMode != Mode.INLINE && defaultMode != Mode.EMBEDDED) {
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

    if (defaultMode != Mode.INLINE && defaultMode != Mode.EMBEDDED) {
      setUpDragging()
    }

    applyMode(defaultMode)

    // 装回调。共享 orchestrator 时这是「当前可见者拥有」的初始声明；不共享时与
    // 抽象前「构造期装一次」等价（attach 在本视图生命周期内只调一次，且在主线程）。
    installOrchestratorCallbacks()

    // 外观设置变更时即时重绑消息（见 [uiStyleListener]）。注册放在 attach：
    // 视图未挂载时不需要刷新，而 dispose 会对称注销，不会泄漏。
    uiStylePrefs.registerOnSharedPreferenceChangeListener(uiStyleListener)

    binding.assistantMessages.layoutManager = LinearLayoutManager(context)
    binding.assistantMessages.adapter = adapter
    // 工具结果图片的解码要在视图销毁时一并取消，因此用本视图的 lifecycleScope，
    // 而不是让适配器自建一个（那样会在 detach 后仍往 ImageView 里写）。
    adapter.imageScope = lifecycleScope

    adapter.setOnRevertClickListener { messageId, diffId -> revertDiff(messageId, diffId) }
    adapter.onMessageActionRequested = { message, anchor ->
      showMessageActionsMenu(message, anchor)
    }
    // 工具卡片的分类色需要按工具名查注册表。注册表在 orchestrator 里，
    // 但适配器不该依赖工具执行层，因此注入一个只做名字→分类映射的窄接口。
    // 传 includeMcp=false：分类色查询不该发起 MCP 网络请求，也不该创建无人释放的连接。
    val registry = orchestrator.buildRegistry(false, null)
    adapter.setCategoryResolver { toolName ->
        registry.getCachedDisplayCategory(com.tom.rv2ide.ai.tool.ToolRegistry.canonicalName(toolName))
    }

    // Compose 渲染路径（并存开关，默认关）。接在适配器配置之后：面板要读适配器快照，
    // 且回滚/菜单回调都落在本类已有方法上，早于此处装上会拿到未配置完的适配器。
    // 与偏好变更走同一个入口，避免「初装」与「改开关」两条路径的判定逻辑分叉。
    applyComposeRenderPath()

    // Compose 输入栏取代了 XML 输入区：发送/停止/模型选择/权限切换不再挂在
    // XML 控件上，回调由 installInputBar() 直接闭包进 ChatInputBar 的参数。
    inputFeatures.onNotice = { appendTrace(it) }
    // 槽位切换发生在 inputFeatures 内部，而模型标签由本类渲染——切完要刷新。
    inputFeatures.onModelChanged = {
      refreshModelLabel()
      // 换模型 = 换窗口，旧用量对新窗口无意义。
      refreshContextRingFromConfig()
    }

    // 顶栏由 Compose 装配（installHeaderCompose，XML 顶栏退役）：
    // 左菜单开抽屉、新建会话回调闭包进 ChatHeader。
    // 待办卡的展开/收起改由 Compose 输入栏的 TodoDashboardBar 承载；
    // XML 任务卡只保留只读展示（refreshTodos 双路渲染的另一半）。

    // 抽屉遮罩点击关闭。
    binding.assistantDrawerScrim.setOnClickListener { toggleConversationPanel() }

    // 抽屉底部三个动作改由 Compose 抽屉的设置卡承载（见 installDrawerCompose）。

    refreshModelLabel()

    // 斜杠命令投影一次：catalog 是静态表，attach 时写进桥即可。
    inputBar.slashCommands =
        com.tom.rv2ide.ai.agent.command.SlashCommandCatalog.definitions().map { d ->
          com.tom.rv2ide.artificial.agent.compose.components.independent.InputSlashCommand(
              name = d.getName(),
              description = d.getDescription(),
              acceptsArgs = d.getUsage().contains(' '),
          )
        }
    inputFeatures.owner = this
    // Compose 顶栏装配（XML 标题栏退役）。
    installHeaderCompose()
    // Compose 输入栏装配（XML 输入区退役）。
    installInputBar()
    // 浮动工具状态条装配（数据源依赖 Compose 面板状态；XML 渲染路径下为空白，见方法文档）。
    installToolStatusBar()
    // 会话抽屉的 Compose 装配。XML RecyclerView 已退役；动作回调仍落回本类既有方法，
    // 会话数据继续走 orchestrator.listConversations()（reloadConversations 投影后提交）。
    installDrawerCompose()

    // 项目名刷新已随 XML 标题退役（refreshWorkspaceLabel 现为空操作），保留调用点
    // 是为了将来若顶栏加回项目标识只需在那一处恢复逻辑。

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
    // XML 圆环退役：进度写进输入栏桥，SendButton 外圈画出占用。
    val total = lastContextSize
    if (total <= 0) {
      inputBar.tokenProgress = 0f
      return
    }
    inputBar.tokenProgress = (lastContextUsed.toFloat() / total).coerceIn(0f, 1f)
    inputBar.tokenEstimated = false
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
    drawerCurrentCwd.value = workspace?.absolutePath ?: ""
    drawerFileHost.setWorkspace(workspace)
    drawerSearchEngine =
        workspace?.let {
          com.tom.rv2ide.artificial.agent.compose.compat.WorkspaceSearchEngine(
              com.tom.rv2ide.artificial.agent.compose.compat.HostFileAccessProvider(it))
        }
  }

  /** 在编辑器里打开工作区文件（文件 Tab 点文件）。经 [AssistantHost.onOpenFileRequested] 交给宿主。 */
  private fun openWorkspaceFile(path: String) {
    val root = drawerFileHost.workspaceRoot ?: return
    val prefix = "~/workspace"
    val file =
        when {
          path == prefix -> root
          path.startsWith("/") -> File(root, path.removePrefix("/"))
          else -> File(path)
        }
    if (!file.isFile) {
      return
    }
    // 搜索命中走这条链路时会在 request() 里预存行号（见 DrawerSearchOpenRequest 文档）；
    // 文件树点击没有预存，lineFor 返回 0，两条来源共用一个出口互不干扰。
    val line =
        com.tom.rv2ide.artificial.agent.compose.components.independent.DrawerSearchOpenRequest
            .lineFor(path)
    if (line > 0) {
      host.onOpenFileAtLineRequested(file, line)
    } else {
      host.onOpenFileRequested(file)
    }
  }

  /** 把一条工作区路径以「附件描述」的形式塞进输入框（文件 Tab 的加入输入动作）。 */
  private fun addPathToInput(path: String) {
    val existing = inputBar.text
    val line = context.getString(string.ai_assistant_attachment_marker, path)
    inputBar.text = (if (existing.isBlank()) line else existing + "\n" + line)
  }

  /** 会话搜索命中 → 打开该会话（行级跳转由接线层的 lineFor 通道完成，当前先开会话）。 */
  private fun openSearchHitInEditor(
      hit: com.tom.rv2ide.artificial.agent.compose.components.independent.ChatSearchHit
  ) {
    toggleConversationPanel()
    openConversationById(hit.sessionId)
  }

  /**
   * 项目名刷新。XML 标题（assistantTitle）已随顶栏退役。
   *
   * <p>Compose 顶栏（ChatHeader）与 Aharou 一致**不设标题位**：会话名在抽屉里可改名，
   * 顶栏把空间留给按钮；项目归属在抽屉会话行与文件 Tab 的 cwd 上仍然可见。
   * 保留空方法收拢全部旧调用点（attach/setWorkspace/openConversation/beginNewConversation）。
   */
  private fun refreshWorkspaceLabel() {}

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
    if (defaultMode != Mode.INLINE && defaultMode != Mode.EMBEDDED) {
      fabBinding.assistantFab.isVisible = false
    }
    binding.assistantOverlay.isVisible = true
    // 通知宿主让位：编辑器据此收缩，把空间真正让给面板（见 AssistantHost.onPanelOpened）。
    // 放在可见性设置**之后**——让位会改变宿主的布局参数，若面板尚未可见，
    // 用户会先看到编辑器缩了一下、面板才出现。
    host.onPanelOpened()
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
    // **每次打开都要同步运行态**，不能只依赖 restoreConversationIfNeeded：
    // 那个只在首次执行（restoredOnce 守卫），第二次打开时直接返回，于是
    // 「另一个入口正在跑」这件事本视图完全不知道——停止键不出现、状态条不启动，
    // 用户看到的是一个「空闲」的面板，实际后台有任务在跑。
    // syncRunningUiForDisplayed 读 orchestrator 的全局运行态（不依赖本视图的 job 表），
    // 因此对「别的入口发起的运行」同样有效。
    syncRunningUiForDisplayed()
    // 外观设置（字号/卡片大小/颜色/头像）可能刚在设置页改过，让已渲染的消息重新绑定。
    //
    // **必须显式触发**：bind() 里每次读最新值，但 RecyclerView 重新布局**不会**
    // 重新 bind 已绑定的 ViewHolder（只重新 layout）。不调这一句的话，用户改完字号
    // 返回面板看到的还是旧字号，只有滚出屏幕再滚回来才生效——会以为设置没用。
    //
    // 用 notifyItemRangeChanged 而不是 notifyDataSetChanged：前者保留滚动位置，
    // 后者会跳回顶部。
    if (adapter.itemCount > 0) {
      adapter.notifyItemRangeChanged(0, adapter.itemCount)
    }
    refreshTodos()
    updateEmptyState()
  }

  /**
   * 首次打开面板时，把「本工作区上次打开的会话」回放到列表。
   *
   * <p>只在首次做：后续 open/close 不该重复回放——那会把用户当前正在进行的
   * 对话重置回历史状态，看起来像消息凭空消失。
   *
   * <p><b>但订阅与「显示会话」标记不能省</b>：早先的实现把「有内容就整体跳过」
   * 当作优化，后果是这种视图**从未订阅过事件流**——它显示着会话，却收不到任何
   * 实时增量，也认不出「这个会话正在跑」（停止键不出现、状态条不启动），
   * 必须点一下会话列表（走 doOpenConversation 重订阅）才能同步。
   * 现在拆开：回放可以跳过，**订阅与 displayedConversationId 必须建立**。
   */
  private fun restoreConversationIfNeeded() {
    if (restoredOnce) {
      return
    }
    restoredOnce = true
    val id = orchestrator.activeConversationId
    if (id.isNullOrEmpty()) {
      return
    }
    // 记录正在显示的会话：后台会话事件据此被过滤掉。
    // 早先这里在 adapter.itemCount > 0 时整体 return，把「订阅」也一并跳过了——
    // 那种视图显示着会话却收不到任何事件（见上方 KDoc）。回放可以跳过，
    // 订阅不能：loadAndSubscribe 内部会重放历史（此时列表已有内容会被覆盖），
    // 因此有内容时只订阅、不回放。
    displayedConversationId = id
    if (adapter.itemCount > 0) {
      lifecycleScope.launch(Dispatchers.IO) {
        subscribeToConversation(id)
        withContext(Dispatchers.Main) { syncRunningUiForDisplayed() }
      }
      return
    }
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
        // 任务卡片也要跟着换会话。此前只在 open() 与工具结束时刷新，导致
        // 「A 会话有 5 条待办 → 点开 B 会话 → 卡片仍显示 A 的清单」——
        // 数据层已按会话隔离（见 AgentOrchestrator.todoStoreFor），但 UI 没重读。
        // 放在这里而不是各个调用点：切会话的三条路径（点列表/新建/恢复）都走本方法。
        refreshTodos()
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
    // 面板收起 = 宿主可以收回让出的空间。必须与 open() 的 onPanelOpened 对称，
    // 否则收起后编辑器永远停在半屏/半宽，用户得重启 Activity 才能恢复。
    host.onPanelClosed()
    // INLINE 没有 FAB 可恢复（见 attach）。
    if (defaultMode != Mode.INLINE && defaultMode != Mode.EMBEDDED) {
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
    // 注销外观监听：SharedPreferences 持有监听器的强引用，不注销会让本视图
    // （及其 binding/adapter）随偏好文件一起存活到进程结束。
    uiStylePrefs.unregisterOnSharedPreferenceChangeListener(uiStyleListener)
    // 丢弃未汇总的改动记录：视图已销毁，它们永远不会被渲染成卡片了。
    // 不清理虽然随本视图一起被回收（不是进程级泄漏），但留着会让「这个会话还有
    // 待汇总改动」这个判断在别处读到陈旧数据。
    pendingDiffs.clear()
    // 注销 Compose 面板对适配器的数据观察者。适配器是本类的字段、(可能)与其它视图共享
    // orchestrator 的生命周期，不注销会让它继续持有面板的 mutableStateOf 与消息快照。
    // 未决提问同样要收尾，否则提问方一直等到超时（见 uninstallComposePanel）。
    composePanel?.cancelPendingQuestion()
    composePanel?.state?.dispose()
    composePanel = null
  }

  /**
   * 在全屏与「常态」之间切换。
   *
   * <p>常态是宿主的初始形态（主页 SIDEBAR、编辑器 DOCKED），不是写死的 SIDEBAR：
   * 编辑器里退出全屏必须回到贴边形态，回到浮层会让面板又变成盖在代码上的卡片。
   */
  /**
   * 拉起沉浸式全屏 Activity（真全屏）。
   *
   * <p>开一个独立屏幕（隐藏系统栏），助手占据整块屏，背后没有编辑器。
   * 这是全屏键单击与长按的共同落点——编辑器改为并列分栏后，
   * 「把面板撑满当前窗口」已无观感变化，因此那条路径被移除（见 attach 里的按钮接线）。
   *
   * <p>[Mode.FULLSCREEN] 仍然保留：真全屏 Activity 内部正是用它把面板铺满那个窗口
   * （见 `AssistantFullscreenActivity.bindLayout`），只是编辑器宿主不再通过按钮进入该形态。
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

    when (newMode) {
      Mode.FULLSCREEN -> {
        // 铺满且**无边框**：本形态的唯一使用者是 AssistantFullscreenActivity
        // （真全屏宿主，见其类文档），那里窗口本身就是整块屏幕，面板即页面本身。
        // 早先留 8/12dp 边距 + 20dp 圆角 + 1dp 描边是想让它在宿主内「像一张卡片」，
        // 但在独立全屏窗口里，这一圈留白只会让用户看到「没铺满的浮窗 + 一道边框」。
        // 与 INLINE 同理：任何留白或圆角都会立刻把它变回盖在内容上的卡片。
        params.width = ViewGroup.LayoutParams.MATCH_PARENT
        params.height = ViewGroup.LayoutParams.MATCH_PARENT
        params.marginStart = 0
        params.marginEnd = 0
        params.topMargin = 0
        params.bottomMargin = 0
        card.radius = 0f
        card.strokeWidth = 0
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
        // 贴边、无外边距、无圆角——形态随屏幕宽度分化。
        //
        // 与 SIDEBAR 的区别不只是宽度：浮层形态（圆角 + 四周留白）传达的是
        // 「这是一张盖在内容上的卡片」，而编辑器需要的是「这是界面的一半」。
        // 留白和圆角会立刻把面板变回弹窗观感——这正是之前"割裂感"的来源之一。
        //
        // **方向必须与宿主让出的空间吻合**（见 ActivityHost.applyYield）：
        // - 宽屏（≥600dp）：编辑器让出右侧，面板贴右侧满高，构成左右分栏。
        // - 窄屏（<600dp）：编辑器让出下半屏，面板必须贴**底部**、占满宽度。
        //   早先这里无条件贴右侧满高，与「让出下半屏」的空间完全不吻合——
        //   实测 392dp 手机上，编辑器 [0,75][1080,1180] 与面板 [346,75][1080,2210]
        //   在右上角重叠，面板仍然盖住编辑器上半屏的右侧，只是看起来像分栏。
        //
        // 比例与方向都读宿主（isWideScreen / yieldFraction），不在这里另算一份：
        // 让位量由宿主决定、面板尺寸由本类决定，两者必须严格互补，各算一份必然漂移。
        val screenWidth = screenWidthPx()
        val available = host.heightPx()
        val panelFraction = 1f - host.yieldFraction()

        if (host.isWideScreen()) {
          // 宽度：屏幕的 68%，并夹在 [260dp, min(420dp, 屏宽-100dp)] 之间。
          // 四个约束各解决一件事：
          // - 260dp 下限：标题栏有 1 个左按钮 + 3 个右按钮，每个都是 48dp 的可点区域
          //   （无障碍最小触控尺寸，不能再压），共 192dp。低于 260dp 时标题会被挤成
          //   「AI …」——实测 236dp 面板就是这样。
          // - 420dp 上限：平板上不限宽会让面板宽到像全屏，失去"贴在一边"的意义
          // - 屏宽-100dp：要给编辑器留出可见宽度，否则用户看不见自己在改哪个文件。
          //
          // 下限必须再对上限取一次 min：窄屏上「屏宽-100dp」可能小于 260dp，
          // 直接 coerceIn(260dp, 那个值) 会抛
          // IllegalArgumentException: Cannot coerce value to an empty range。
          val upper = minOf(dp(420), screenWidth - dp(100))
          val lower = minOf(dp(260), upper)
          params.width = (screenWidth * 0.68f).toInt().coerceIn(lower, upper)
          params.height = ViewGroup.LayoutParams.MATCH_PARENT
        } else {
          // 窄屏：贴底、全宽、占「让位后剩下的那份」，与编辑器严丝合缝。
          params.width = ViewGroup.LayoutParams.MATCH_PARENT
          params.height = (available * panelFraction).toInt()
        }
        params.marginStart = 0
        params.marginEnd = 0
        params.topMargin = 0
        params.bottomMargin = 0
        card.radius = 0f
        // 保留 1dp 描边：面板与编辑器内容用的是同一个 colorSurface，
        // 不画边界时两者连成一片，看不出面板从哪里开始。
        // 宽屏时它起**左**分界线作用（上下右三边压在屏幕边缘不可见）；
        // 窄屏时起**上**分界线作用（左右下三边不可见）。同一个用意。
        card.strokeWidth = dp(1)
      }
      Mode.INLINE, Mode.EMBEDDED -> {
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
    // 垂直对齐方向：窄屏的 DOCKED 是「贴底面板」，高度只有半屏，不设 bias 会**垂直居中**，
    // 于是面板浮在屏幕中间、上下都露出编辑器，又变回浮窗观感。
    // 宽屏 DOCKED 高度是 MATCH_PARENT，bias 无影响；其余形态同理。
    params.verticalBias = if (newMode == Mode.DOCKED && !host.isWideScreen()) 1f else 0.5f

    card.layoutParams = params

    // 顶栏右侧三键已随 Compose 顶栏退役：INLINE/EMBEDDED 下不再需要单独隐藏
    // （Compose 顶栏没有这三个键，形态差异由 showMenuButton 与宿主返回键承载）。

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
    val typed = inputBar.text.trim()
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
        inputBar.text = ""
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

    inputBar.text = ""
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
          // 工具组的自动折叠依赖「是否有运行在进行」：只有运行中且位于末尾的组默认展开。
          // 不置位的话，末组会在运行期间就折叠起来，用户看不到正在跑的工具。
          adapter.isRunActive = true
          // 耗时计时起点。放在 RUN_STARTED 而不是 TURN_STARTED：「这一轮花了多久」指的是
          // 用户发出请求到收到最终回答，包含中间的每一次工具往返。
          ui.runStartedAtMs = android.os.SystemClock.elapsedRealtime()
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
            // 后台会话的改动汇总无处可渲染（适配器只显示当前会话）：直接丢弃，
            // 否则这些条目会永远留在表里。与改动前的行为一致——那时 diff 卡片
            // 同样只在会话被显示时才插入。
            pendingDiffs.remove(conversationId)
            return@launch
          }
          // 若该会话仍有排队请求，orchestrator 会在同一 finally 里紧接着发起下一条，
          // 那时会再收到 RUN_STARTED。这里先按「已结束」收尾，由后续事件重新点亮。
          setRunningUi(false)
          binding.assistantWorking.stopWorking()
          if (!ui.retryCardPinned) {
            hideRetryCard()
          }
          // 运行结束 = 末组不再活动，应收起来（十几张工具卡片收成一行）。
          // 放在 displayedConversationId 守卫**之后**：后台会话的结束不该改变
          // 当前显示会话的折叠状态。
          adapter.isRunActive = false

          // 本轮全部改动在这里落成一张汇总卡片（见 flushDiffSummary）。
          // 位置必须在运行结束：运行中插的卡片会被后续消息推到上面去，
          // 用户下次看到它时已经不在「刚才那轮」的位置了。
          flushDiffSummary(conversationId)

          // 回填本轮耗时到收尾的助手消息上。耗时是展示信息，由视图侧自己记
          // （RUN_STARTED 起点、RUN_FINISHED 终点），不走协议层——协议层要补时间戳
          // 得改事件契约与全部构造点，而它只是展示信息。
          val startMs = ui.runStartedAtMs
          if (startMs > 0L) {
            val elapsed = android.os.SystemClock.elapsedRealtime() - startMs
            ui.runEndMessageId?.let { id ->
              adapter.updateDuration(id, elapsed)
            }
            ui.runStartedAtMs = 0L
            ui.runEndMessageId = null
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
          // 记录本轮结束的助手消息 id，用于 RUN_FINISHED 时回填耗时。
          ui.runEndMessageId = ui.streamingMessageId
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
          // 浮动状态条的实时输出从这张卡片开始累积；键存在即「运行中」。
          runningToolCardId = id
          runningToolOutput[id] = StringBuilder()
          publishLiveToolOutput()
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
            // 带上结果里的图片：`phone_screenshot`、`image_generation` 这类工具的产出
            // 就是一张图，不带的话用户只能看到「工具跑完了」却看不到图。
            adapter.completeToolCall(
                cardId,
                result.content,
                result.isError,
                if (result.hasImage()) result.imageBase64 else null,
                result.imageMimeType,
            )
          }
          ui.lastToolCardId = null
          // 该工具的实时输出收尾：从映射里移除（键消失 = 不再运行），状态条随之切到完成态。
          runningToolCardId?.let { runningToolOutput.remove(it) }
          runningToolCardId = null
          publishLiveToolOutput()

          // 有 diffId 说明这次调用改了文件。**先记下来，不立刻插卡片**——
          // 本轮全部改动在 RUN_FINISHED 时汇总成一张卡片（见 flushDiffSummary）。
          // 早先是改一个文件就插一张 diff，长任务里十几次编辑会把用户正在读的回答
          // 反复顶开，而且「一共改了哪些文件」要一张张翻才知道。
          val diffId = result.diffId
          if (!result.isError && diffId.isNotEmpty()) {
            recordChangedFile(conversationId, call?.arguments, diffId)
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
      com.tom.rv2ide.ai.agent.AgentEvent.Type.PROGRESS -> {
        // 步骤级进度（子 agent 运行期间为主）。两条去向：
        //  1. 底部状态条——「此刻在做什么」的实时播报（主要用途）；
        //  2. 运行中工具卡片的步骤区——供用户回看（子 agent 的过程不进主对话，
        //     卡片里的步骤行是唯一凭据）。
        // 不落盘：进度是瞬时状态，回放历史时没有意义（PersistingListener 的 default 分支）。
        val line = event.message
        if (line.isEmpty() || displayedConversationId != conversationId) {
          return
        }
        lifecycleScope.launch(Dispatchers.Main) {
          showAction(line)
          adapter.appendToolStep(line)
          // 浮动状态条的实时输出：与卡片步骤区同源（都是 PROGRESS 行），
          // 累积进当前运行中工具的缓冲；没有运行中工具时丢弃（状态条已无从显示）。
          runningToolCardId?.let { id ->
            runningToolOutput[id]?.appendLine(line)
            publishLiveToolOutput()
          }
        }
      }
      com.tom.rv2ide.ai.agent.AgentEvent.Type.FAILED -> {
        ui.streamedThisRun = true
        ui.retryCardPinned = ui.retryCountThisRun > 0
        if (displayedConversationId != conversationId) {
          return
        }
        lifecycleScope.launch(Dispatchers.Main) {
          // 失败也是「运行结束」：末组不该继续以活动态展开。
          // 不置位的话，失败后末组停在展开态，直到用户手动折叠。
          adapter.isRunActive = false
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
   * 用户点下去实际取消了任务（实测如此）。停止由 Compose 输入栏的停止键（SendButton 的 showStop 分支）
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
    inputBar.isBusy = running

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
   * 本轮改动过的文件，按会话暂存；运行结束时一次性汇总成一张卡片。
   *
   * <p>路径从工具参数里取：工具结果本身不带路径（{@code ToolResult} 只有 diffId），
   * 而用户需要看到「改的是哪个文件」才能判断要不要撤销。
   *
   * <p><b>为什么先暂存而不是立刻插卡片</b>：改一个文件冒一张卡会在长任务里把用户
   * 正在读的回答反复顶开，而且「这一轮一共改了哪些文件」要一张张翻才知道。
   * 汇总成一张可折叠的卡片之后，运行中对话流保持干净，运行结束给一份完整清单。
   */
  private fun recordChangedFile(conversationId: String, arguments: String?, diffId: String) {
    val path = parsePathArg(arguments) ?: context.getString(string.ai_assistant_unknown_file)
    pendingDiffs.getOrPut(conversationId) { mutableListOf() }.add(path to diffId)
  }

  /**
   * 把本轮暂存的改动汇总成一张卡片，并逐个异步算出 diff 内容。
   *
   * <p>必须在**运行结束**时调用（见 RUN_FINISHED 分支）：这是「会话结束后显示全部改动汇总」
   * 的落点。调用时机与插入位置都要在此刻，因为运行中不断有新消息插到列表末尾，
   * 早插的卡片会被后续内容推到上面去。
   *
   * <p><b>为什么 diff 内容仍然现算而不是随卡片一起给</b>：{@code DiffRecord} 只存改动前后的
   * 完整内容快照（回滚需要精确原文，快照是最可靠的形式），没有存 diff 结果，因此卡片先以
   * 「无内容」插入，再在后台线程算完回填——这样插入本身不阻塞主线程，也不会让运行结束时
   * 卡一下（十几个文件逐个 diff 可能上百毫秒）。
   */
  private fun flushDiffSummary(conversationId: String) {
    val pending = pendingDiffs.remove(conversationId) ?: return
    if (pending.isEmpty()) {
      return
    }
    val childIds = adapter.appendDiffGroup(pending)
    scrollToBottom()

    for ((index, id) in childIds.withIndex()) {
      val diffId = pending[index].second
      lifecycleScope.launch(Dispatchers.Default) {
        // findById 可能返回 null：FileDiffStore 会裁剪旧记录（每文件 20 条 / 总量 8MB），
        // 历史会话里的 diffId 未必还在。此时保持 result=null，卡片显示「记录已过期」。
        val record = diffStore.findById(diffId)
        val built =
            record?.let {
              AssistantDiffBuilder.build(
                  oldContent = it.oldContent,
                  newContent = it.newContent,
                  // isOldExists() 是 Java 侧的 boolean 访问器，Kotlin 不会把它合成属性
                  // （isXxx 只对 Kotlin 声明的属性生效），必须显式调用。
                  oldExists = it.isOldExists,
              )
            }
        withContext(Dispatchers.Main) { adapter.updateDiff(id, built) }
      }
    }
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
          // 消息与 diff 卡片共用这一个回滚入口（两者的撤销按钮语义相同），
          // 因此两处都要标记——按 id 找不到对应类型时各自静默跳过。
          adapter.markReverted(messageId)
          adapter.markDiffReverted(messageId)
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
    // 传「AI 设置屏的 children」而不是屏幕本身。
    //
    // IDEPreferencesFragment 在顶层只接受两种类型：IPreferenceScreen（渲染成可点击入口）
    // 与 IPreferenceGroup（渲染成 PreferenceCategory）。直接传 AIAgentPreferencesScreen
    // 会在展开其 children 时抛 ClassCastException——那层 children 是普通 Preference，
    // 而 addChildren 对非 Screen/Group 的分支仍按 Group 处理。
    // 把 children 展开到顶层则每一层都符合上述两种类型。
    val screen = com.tom.rv2ide.preferences.AIAgentPreferencesScreen()
    val intent =
        android.content.Intent(context, com.tom.rv2ide.activities.PreferencesActivity::class.java)
    intent.putParcelableArrayListExtra(
        com.tom.rv2ide.activities.PreferencesActivity.EXTRA_DIRECT_CHILDREN,
        ArrayList(screen.children),
    )
    intent.putExtra(
        com.tom.rv2ide.activities.PreferencesActivity.EXTRA_DIRECT_TITLE,
        context.getString(screen.title),
    )
    context.startActivity(intent)
  }

  /**
   * 弹出消息操作菜单（复制原文、复制纯文本、引用提问）。
   */
  private fun showMessageActionsMenu(message: AssistantMessageAdapter.Message, anchor: View) {
    val items = mutableListOf<Pair<String, () -> Unit>>()
    items.add(context.getString(string.ai_assistant_action_copy_raw) to {
      copyToClipboard("Markdown", message.text)
    })

    if (AssistantMarkdown.looksLikeMarkdown(message.text)) {
      items.add(context.getString(string.ai_assistant_action_copy_plain) to {
        copyToClipboard("Text", AssistantMessageActions.toPlainText(message.text))
      })
    }

    items.add(context.getString(string.ai_assistant_action_quote) to {
      showQuoteReplyDialog(message)
    })

    val titles = items.map { it.first }.toTypedArray()
    com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
        .setItems(titles) { _, which ->
          items[which].second.invoke()
        }
        .show()
  }

  /**
   * 引用提问对话框：输入对当前消息的追问，确认后回填并附带引用前缀。
   */
  private fun showQuoteReplyDialog(message: AssistantMessageAdapter.Message) {
    val input = android.widget.EditText(context).apply {
      hint = context.getString(string.ai_assistant_quote_input_hint)
      setPadding(dp(16), dp(16), dp(16), dp(16))
    }

    com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
        .setTitle(string.ai_assistant_quote_dialog_title)
        .setView(input)
        .setPositiveButton(android.R.string.ok) { _, _ ->
          val question = input.text?.toString()?.trim().orEmpty()
          val ref = ChatReference(
              messageId = message.id,
              role = if (message.role == AssistantMessageAdapter.Role.USER) "user" else "assistant",
              quote = message.text.take(300),
          )
          val quoteBlock = AssistantMessageActions.referenceContext(listOf(ref))
          val fullText = if (quoteBlock.isNotEmpty()) "$quoteBlock\n\n$question" else question

          val current = inputBar.text
          if (current.isBlank()) {
            inputBar.text = fullText
          } else {
            inputBar.text = ("$current\n\n$fullText")
          }
          // Compose 输入框无 Selection 概念外泄：外部 set 值时组件把光标移到末尾。
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
  }

  private fun copyToClipboard(label: String, content: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
    if (clipboard != null) {
      clipboard.setPrimaryClip(android.content.ClipData.newPlainText(label, content))
      appendTrace(context.getString(string.ai_assistant_code_copied))
    }
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
    // XML 权限 chip 退役：权限短名仍在（点击入口由 `+` 菜单与设置页承载），
    // 面板上不再有常驻 chip——与 Aharou 的输入栏一致。
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
      lastGitBranch = null
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
        // 缓存下来；XML 的 git chip 已随工具条退役，分支名留给设置页与抽屉。
        lastGitBranch = branch
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
    // XML 工具条已退役：Compose 输入栏 / 顶栏内部自管密度。
    // 保留空方法收拢全部旧调用点。
  }

  /** 上一次 git 分支查询的结果；避免每次宽度变化都去读一次仓库。 */
  private var lastGitBranch: String? = null

  /** 仅按已缓存的仓库状态重设 git 标签可见性。XML chip 退役后为空操作。 */
  private fun refreshGitBranchVisibilityOnly() {}

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
    // 模型入口在输入栏芯片（ChatInputBar 的 ModelIconButton）。它按 provider 列表
    // 渲染选择面板，所以这里必须把**真实的**已配置服务商投影进去——空列表会弹出
    // 一张没有内容的卡片（曾经就是这个问题）。
    inputBar.modelName = agents.getAgent()
    val currentModel = agents.getAgent()
    // currentProvider 用于选中态与 logo 识别；models 里要含当前模型，
    // 否则面板出现「服务商在列表里、正在用的模型却不在」的矛盾。
    inputBar.currentProvider =
        com.tom.rv2ide.artificial.agent.compose.components.independent.ProviderSelectionTarget(
            id = providerId,
            name = providerLabel,
            isEnabled = usable,
            models = if (currentModel.isBlank()) emptyList() else listOf(currentModel),
            effectiveModel = currentModel,
        )
    inputBar.providers =
        AssistantModelPicker.configuredProviderEntries(context).map { entry ->
          com.tom.rv2ide.artificial.agent.compose.components.independent.ProviderSelectionTarget(
              id = entry.id,
              name = entry.label,
              models = entry.models,
              // 从已配置清单来的服务商必然可用（configuredProviders 已按同一判据筛过）。
              isEnabled = true,
              effectiveModel =
                  if (entry.id == providerId && currentModel.isNotBlank()) {
                    currentModel
                  } else {
                    entry.models.firstOrNull().orEmpty()
                  },
          )
        }
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
  /**
   * 把输入栏装进 [binding.assistantInputBarCompose]。
   *
   * <p>状态全部读 [inputBar] 桥；动作直接闭包回本类既有方法（sendFromInput /
   * onStopClicked / showModelPicker），与 XML 时代的点击监听一一对应。
   * 附件的增删仍在 [AssistantInputFeatures]（已改为读写桥）。
   */
  private fun installInputBar() {
    val darkTheme =
        run {
          val tv = android.util.TypedValue()
          val resolved = context.theme.resolveAttribute(android.R.attr.colorBackground, tv, true)
          val bg = if (resolved) tv.data else 0xFF07111F.toInt()
          androidx.core.graphics.ColorUtils.calculateLuminance(bg) <= 0.5
        }
    binding.assistantInputBarCompose.setContent {
      com.tom.rv2ide.artificial.agent.compose.theme.AIEditorTheme(darkTheme = darkTheme) {
        val bridge = inputBar
        com.tom.rv2ide.artificial.agent.compose.components.independent.ChatInputBar(
            value = bridge.text,
            onValueChange = { bridge.text = it },
            onSend = {
              sendFromInput()
            },
            onStop = { onStopClicked() },
            onForceStop = {
              displayedConversationId?.let { cancel(it) }
              setRunningUi(false)
            },
            isBusy = bridge.isBusy,
            canForceStop = bridge.canForceStop,
            activeModelName = bridge.modelName,
            currentProvider = bridge.currentProvider,
            providers = bridge.providers,
            // 选中即写入偏好（AssistantModelPicker.applySelection 与 XML 选择器同源），
            // 再刷新标签——模型名、logo、面板选中态全都依赖它。
            onSelectModel = { providerId, model ->
              com.tom.rv2ide.artificial.agent.AssistantModelPicker.applySelection(
                  context, providerId, model)
              refreshModelLabel()
            },
            currentMode = bridge.mode,
            onToggleMode = { next ->
              bridge.mode = next
            },
            reasoningEffort = bridge.reasoningEffort,
            onReasoningEffortChange = { bridge.reasoningEffort = it },
            pendingAttachments = bridge.pendingAttachments,
            onRemoveAttachment = { idx ->
              if (idx in bridge.pendingAttachments.indices) bridge.pendingAttachments.removeAt(idx)
            },
            onReadAttachment = { path ->
              kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { java.io.File(path).takeIf { it.isFile }?.readText() }.getOrNull()
              }
            },
            canUploadFiles = true,
            canUploadImages = true,
            onUploadFile = { inputFeatures.pickFileAttachment() },
            onUploadImage = { inputFeatures.pickImageAttachment() },
            onTakePhoto = { inputFeatures.pickImageAttachment() },
            slashCommands = bridge.slashCommands,
            queuedRequests = bridge.queuedRequests,
            tokenProgress = bridge.tokenProgress,
            tokenEstimated = bridge.tokenEstimated,
            todoItems = bridge.todoItems,
            sessionId = displayedConversationId ?: "",
        )
      }
    }
  }

  /**
   * 把浮动工具状态条装进 [binding.assistantToolStatusBarCompose]。
   *
   * <p>**数据源是 Compose 面板的状态**（[AssistantComposePanelHost.state]），而不是 adapter：
   * 状态条要的 [AgentUIMessage]（id 为 String、与 liveToolOutput 的键同源）正是
   * `state.messages` 的元素类型；从 adapter 取还得再过一次映射。也因此本装配
   * **依赖 Compose 渲染路径**——XML 路径下 composePanel 为 null，状态条不装（保持空白）。
   *
   * <p>可见性由组件自己管（toolMessages 为空时 `return`，不渲染任何东西），
   * 因此宿主无需在运行态切换时增删视图。
   *
   * <p><b>整条默认隐藏</b>：由 [AssistantToolStatusBarPref] 控制（默认 false）——
   * 见该对象的类文档。开关变更时由 [applyToolStatusBarVisibility] 即时显隐。
   *
   * <p>onOpenDetail 传空实现：详情面板（AharouComputerSheet）尚未接线，
   * 点缩略图暂无下钻；先保证状态条本身的运行/翻页/停止可用。
   */
  private fun installToolStatusBar() {
    val darkTheme =
        run {
          val tv = android.util.TypedValue()
          val resolved = context.theme.resolveAttribute(android.R.attr.colorBackground, tv, true)
          val bg = if (resolved) tv.data else 0xFF07111F.toInt()
          androidx.core.graphics.ColorUtils.calculateLuminance(bg) <= 0.5
        }
    applyToolStatusBarVisibility()
    binding.assistantToolStatusBarCompose.setContent {
      com.tom.rv2ide.artificial.agent.compose.theme.AIEditorTheme(darkTheme = darkTheme) {
        val bridge = inputBar
        // messages 是 mutableStateOf——这里直接读它，TOOL 消息随流式追加自然触发重组。
        // takeLast(8) 与 Aharou 一致：状态条只回看最近一串工具，不是完整历史。
        val toolMessages =
            (composePanel?.state?.messages?.value ?: emptyList())
                .filter { it.role == com.tom.rv2ide.artificial.agent.compose.model.MessageRole.TOOL }
                .takeLast(8)
        com.tom.rv2ide.artificial.agent.compose.components.independent.FloatingToolStatusBar(
            toolMessages = toolMessages,
            liveOutputFor = { id -> bridge.liveToolOutput[id] },
            onOpenDetail = {},
            onStop = {
              displayedConversationId?.let { cancel(it) }
              setRunningUi(false)
            },
        )
      }
    }
  }

  /**
   * 按偏好显隐工具状态条。
   *
   * <p>用 GONE/VISIBLE 而不是装卸 ComposeView：状态条的内容由 [inputBar] 桥与
   * [composePanel] 状态驱动，视图本身无需销毁——隐藏期间不占布局空间，
   * 开销只是一次测量跳过（GONE 不参与测量）。
   *
   * <p>开关变更与装配时都会调用；重复调用幂等。
   */
  private fun applyToolStatusBarVisibility() {
    val enabled =
        com.tom.rv2ide.artificial.agent.compose.AssistantToolStatusBarPref.isEnabled(context)
    binding.assistantToolStatusBarCompose.isVisible = enabled
  }

  /**
   * 把顶栏装进 [binding.assistantHeaderCompose]（Compose 装配）。
   *
   * <p>XML 顶栏（菜单/标题/全屏/最小化/关闭五控件）退役，其入口由移植的 [ChatHeader]
   * 承接并重组：左菜单开抽屉、右侧新建会话 + Git / 终端 + 全屏二选一。
   * 关闭键不再恢复——侧栏有系统的返回键，再放一颗是多余的。
   *
   * <p><b>入口映射与 Aharou 的差异</b>：Aharou 的工作台有 5 个入口（Terminal/Git/Browser/
   * Sandbox/Shared）。ACS 只保留 Git 与终端——另三个（浏览器/沙箱/共享）没有对应功能，
   * 留着就是点了没反应的占位按钮。
   *
   * <p>两个保留入口的落点**不同**，因为 ACS 的载体不同：
   * - Git 是侧栏标签页 → 经 [AssistantHost.onOpenSidebarPage] 切页；
   * - 终端是独立 Activity（侧栏没有终端页，`TerminalSidebarAction` 未注册）→ 直接启动。
   *
   * <p>全屏 / 最小化合并为**一颗二选一按钮**（ACS 自有，Aharou 顶栏没有）：
   * 非全屏显示全屏图标、全屏显示最小化图标。分成两颗常驻按钮在窄侧栏里放不下。
   *
   * <p>模型切换**不在顶栏**，在输入栏芯片（[ChatInputBar]）——与 Aharou 一致。
   * 顶栏只保留导航类入口，避免在 300dp 宽的侧栏里挤掉按钮。
   *
   * <p>token 统计：ChatHeader 的 TokenStats 只在远程模式（connectionState != null）显示，
   * ACS 恒传 null，因此 inputTokens/outputTokens 恒传 0 不占位。
   *
   * <p>定时任务胶囊：ScheduledTaskPill 的数据源（定时任务）ACS 尚未接线，task 恒传 null
   * （组件内部对 null 直接不渲染）。
   */
  private fun installHeaderCompose() {
    val darkTheme =
        run {
          val tv = android.util.TypedValue()
          val resolved = context.theme.resolveAttribute(android.R.attr.colorBackground, tv, true)
          val bg = if (resolved) tv.data else 0xFF07111F.toInt()
          androidx.core.graphics.ColorUtils.calculateLuminance(bg) <= 0.5
        }
    binding.assistantHeaderCompose.setContent {
      com.tom.rv2ide.artificial.agent.compose.theme.AIEditorTheme(darkTheme = darkTheme) {
        com.tom.rv2ide.artificial.agent.compose.components.independent.ChatHeader(
            inputTokens = 0,
            outputTokens = 0,
            sessionId = displayedConversationId,
            onOpenDrawer = { toggleConversationPanel() },
            onNewChat = { startNewConversation() },
            // Git：切到编辑器侧栏的 Git 标签页（宿主实现）。宿主不支持（主页/真全屏/
            // 应用外悬浮）时点击无反应——这是 onOpenSidebarPage 的默认空实现约定。
            onNavigateToGit = {
              host.onOpenSidebarPage(com.tom.rv2ide.actions.sidebar.GitClientAction.ID)
            },
            // 终端：ACS 的终端是独立 Activity（与主页动作列表的「终端」一致），
            // 侧栏**没有**终端页——`TerminalSidebarAction` 并未注册进侧栏
            // （见 EditorSidebarActions.registerActions），走切页会找不到目标而静默失败。
            //
            // NEW_TASK 对应用外悬浮（Service context）是必需的，对 Activity 宿主无害。
            onNavigateToTerminal = {
              try {
                context.startActivity(
                    android.content.Intent(context, TerminalActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
              } catch (e: Exception) {
                // 后台启动 Activity 受限（Android 10+）/ 无可用 Activity：静默忽略，
                // 这只是多一个入口，不该让面板崩。
              }
            },
            currentMode = inputBar.mode,
            onToggleMode = { next -> inputBar.mode = next },
            connectionState = null,
            showMenuButton = true,
            // 二选一：非全屏显示全屏图标，全屏显示最小化图标。
            // 本视图在编辑器/侧栏宿主里不是 FULLSCREEN，因此恒为 false；
            // 真全屏宿主（AssistantFullscreenActivity，Mode.FULLSCREEN）为 true。
            isFullscreen = mode == Mode.FULLSCREEN,
            onToggleFullscreen = {
              if (mode == Mode.FULLSCREEN) {
                // 全屏里「最小化」= 退出这个独立窗口，回到编辑器。
                // 用 finish 而不是 close()：宿主是 AssistantFullscreenActivity，
                // 它的 onClosed 已接 finish（见 bindLayout），close() 会走到同一条路。
                close()
              } else {
                launchTrueFullscreen()
              }
            },
        )
      }
    }
  }

  /**
   * 把会话抽屉装进 [binding.assistantDrawerCompose]（Compose 装配）。
   *
   * <p>**会话数据仍然由本类的 [reloadConversations] 拉取**：listConversations 读盘，
   * 每次展开重拉一次，投影后写进 [drawerSessions]——与原适配器 submitList 同构。
   * 文件 Tab 的目录操作直接落在 orchestrator 的文件工具语义上（容器路径 =
   * 工作区绝对路径），重命名/删除后调用 [refreshBrowse] 重拉。
   *
   * <p>设置只在这里解析一次：`darkTheme` 用与 [AssistantComposePanelHost] 相同的
   * colorBackground 亮度判据（ACS 可应用内切主题而不改系统 uiMode）。
   */
  private fun installDrawerCompose() {
    val drawerView = binding.assistantDrawerCompose
    val darkTheme =
        run {
          val tv = android.util.TypedValue()
          val resolved = context.theme.resolveAttribute(android.R.attr.colorBackground, tv, true)
          val bg = if (resolved) tv.data else 0xFF07111F.toInt()
          androidx.core.graphics.ColorUtils.calculateLuminance(bg) <= 0.5
        }
    drawerView.setContent {
      com.tom.rv2ide.artificial.agent.compose.theme.AIEditorTheme(darkTheme = darkTheme) {
        com.tom.rv2ide.artificial.agent.compose.components.independent.ChatDrawerContent(
            sessions = drawerSessions.value,
            currentWorkspacePath = drawerCurrentCwd.value,
            currentSessionId = drawerActiveId.value,
            agentStates = emptyMap(),
            onSelect = { session ->
              toggleConversationPanel()
              openConversationById(session.id)
            },
            onDelete = { session -> confirmDeleteConversationById(session.id) },
            onRename = { session, title -> renameConversation(session.id, title) },
            onTogglePin = { session -> togglePinConversation(session.id) },
            onExport = { copyConversation() },
            browseState = drawerFileHost.browseState,
            expandedPaths = drawerFileHost.expandedPaths,
            fileOpPaths = drawerFileHost.fileOpPaths.toSet(),
            clipboard = drawerFileHost.clipboard,
            pasteConflict = drawerFileHost.pasteConflict,
            onToggleExpand = { path -> drawerFileHost.toggleExpand(path) },
            onOpenFile = { path -> openWorkspaceFile(path) },
            onRefreshBrowse = { drawerFileHost.refreshExpanded() },
            onCreateFile = { dir, name -> drawerFileHost.createEntry(dir, name, isFolder = false) },
            onCreateFolder = { dir, name -> drawerFileHost.createEntry(dir, name, isFolder = true) },
            onRenameEntry = { path, name -> drawerFileHost.renameEntry(path, name) },
            onDeleteEntry = { path -> drawerFileHost.deleteEntry(path) },
            onCopyEntry = { src, dst -> drawerFileHost.copyEntry(src, dst, cut = false) },
            onCutEntry = { src, dst -> drawerFileHost.copyEntry(src, dst, cut = true) },
            onAddToInput = { path -> addPathToInput(path) },
            onPasteEntry = { dir, onConflict -> drawerFileHost.paste(dir, onConflict) },
            onPasteOverwrite = { drawerFileHost.pasteOverwrite() },
            onCancelPasteOverwrite = { drawerFileHost.cancelPasteOverwrite() },
            onClearClipboard = { drawerFileHost.clearClipboard() },
            onNavigateToSettings = { openAssistantSettings() },
            searchQuery = chatSearchQuery.value,
            searchState = chatSearchState.value,
            onSearchQueryChange = { q -> chatSearchQuery.value = q },
            onClearSearch = { chatSearchQuery.value = "" },
            onOpenSearchHit = { hit -> openSearchHitInEditor(hit) },
            fileSearchEngine = drawerSearchEngine,
        )
      }
    }
  }

  /** `ConversationSummary`（JSONL 会话）→ 抽屉用的 `ChatSession` 投影。 */
  private fun projectSessions(
      summaries: List<com.tom.rv2ide.ai.agent.conversation.ConversationSummary>
  ): List<com.tom.rv2ide.artificial.agent.compose.components.independent.ChatSession> =
      summaries.map { s ->
        com.tom.rv2ide.artificial.agent.compose.components.independent.ChatSession(
            id = s.getId(),
            title = s.getTitle(),
            createdAt = s.getCreatedAt(),
            updatedAt = s.getModifiedAt(),
            workspacePath = s.getCwd(),
        )
      }

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
        // 先设当前工作区再提交列表：归属标注要在投影时就能拿到正确值，
        // 否则会先按上一次的项目渲染一遍再纠正，用户能看到一次闪烁。
        drawerCurrentCwd.value = workspace?.absolutePath ?: ""
        drawerSessions.value = projectSessions(summaries)
        drawerActiveId.value = orchestrator.activeConversationId
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
          drawerCurrentCwd.value = projectDir.absolutePath
          drawerFileHost.setWorkspace(projectDir)
        }
        orchestrator.openConversation(convId)
        drawerActiveId.value = convId
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
            // streaming=false：这是历史回放，推理早已结束。传默认值 true 会让
            // 回放出的块永久显示「思考中」与进度动画（重启后历史里全是卡住的
            // 思考块）——历史条目只记录「思考过什么」，不记录「是否仍在思考」。
            adapter.appendThinking(reasoning, null, streaming = false)
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
            // 历史里的工具图片：必须用 fromImageResult 而不是 fromRawInputJson。
            // 两者只差一个 kind 字符串，用错**不会报错**——fromRawInputJson 只认用户附件的
            // kind，解析工具图片恒返回 null，于是历史截图会全部消失且没有任何提示。
            val image = com.tom.rv2ide.ai.protocol.ImageInputPayload
                .fromImageResult(message.getRawInputJson())
            adapter.completeToolCall(
                cardId,
                message.getContent(),
                message.isToolError(),
                image?.dataBase64,
                image?.mimeType.orEmpty(),
            )
            // 步骤随结果恢复（子代理过程不进主对话，展开卡片看步骤是回看的唯一凭据）。
            // 旧日志没有 steps 字段，此时列表为空、步骤区隐藏，与从前行为一致。
            if (message.steps.isNotEmpty()) {
              adapter.setToolSteps(cardId, message.steps)
            }
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
            // 图片同样要带过来。上面那个分支的注释已经写明「用错 kind 会让历史截图
            // 全部消失且没有任何提示」——本分支先前只传三个参数，图片就是在**这里**丢的：
            // 卡片不是配对回填、而是新建时，走的就是这条路径，于是所有「没有配对前导调用」
            // 的历史工具图（包括重装后回放的会话）都不显示。两者必须一致。
            val image = com.tom.rv2ide.ai.protocol.ImageInputPayload
                .fromImageResult(message.getRawInputJson())
            adapter.completeToolCall(
                id,
                message.getContent(),
                message.isToolError(),
                image?.dataBase64,
                image?.mimeType.orEmpty(),
            )
            if (message.steps.isNotEmpty()) {
              adapter.setToolSteps(id, message.steps)
            }
          }
        }
        else -> {}
      }
    }
    updateEmptyState()
    scrollToBottom()
  }

  /** 删除会话前确认。删除不可撤销，静默删除会让误触的代价过大。 */
  // ── Compose 会话抽屉的会话操作入口（按 id，替代原适配器回调） ──

  private fun openConversationById(id: String) {
    drawerSessions.value.firstOrNull { it.id == id }?.let { session ->
      openConversation(
          com.tom.rv2ide.ai.agent.conversation.ConversationSummary(
              session.id, session.title, session.workspacePath,
              session.createdAt, session.updatedAt, 0))
    }
  }

  private fun confirmDeleteConversationById(id: String) {
    drawerSessions.value.firstOrNull { it.id == id }?.let { session ->
      confirmDeleteConversation(
          com.tom.rv2ide.ai.agent.conversation.ConversationSummary(
              session.id, session.title, session.workspacePath,
              session.createdAt, session.updatedAt, 0))
    }
  }

  private fun renameConversation(id: String, title: String) {
    if (title.isBlank()) return
    lifecycleScope.launch(Dispatchers.IO) {
      try {
        orchestrator.renameConversation(id, title.trim())
      } catch (e: java.io.IOException) {
        com.tom.rv2ide.ai.tool.api.ErrorLog.record("agent", "重命名会话失败", e, null)
      }
      withContext(Dispatchers.Main) { reloadConversations() }
    }
  }

  /**
   * 置顶切换。ACS 的 JSONL 会话存储没有置顶位（ConversationSummary 无该字段），
   * 存在面板偏好里（会话 id 列表）；投影时按它排序。
   */
  private fun togglePinConversation(id: String) {
    val prefs = context.applicationContext.getSharedPreferences("ai_agent_tools", android.content.Context.MODE_PRIVATE)
    val key = "assistant_pinned_sessions"
    val pinned = prefs.getStringSet(key, emptySet())?.toMutableSet() ?: mutableSetOf()
    if (!pinned.add(id)) pinned.remove(id)
    prefs.edit().putStringSet(key, pinned).apply()
    reloadConversations()
  }

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
          // 会话已不存在，它的待办卡片必须一起收掉——否则屏幕上留着一条
          // 属于已删除会话的清单，用户点进新会话还会以为那是自己的任务。
          refreshTodos()
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
    val panel = composePanel
    if (panel != null) {
      panel.scrollToBottom(lifecycleScope)
      return
    }
    binding.assistantMessages.post {
      val count = adapter.itemCount
      if (count > 0) {
        binding.assistantMessages.scrollToPosition(count - 1)
      }
    }
  }

  /**
   * 装上 Compose 渲染路径，并把 XML 路径的消息列表收起来。
   *
   * <p>ComposeView 由代码加进 `assistantMessages` 所在的 FrameLayout，而不是写进
   * `layout_ai_assistant.xml`：**两条路径必须占据同一个位置**，写进布局会让「哪个可见」
   * 这件事分散在两处（布局里的 visibility 与代码里的 visibility），关掉开关时容易漏改一处，
   * 表现为两块消息区上下叠着。加在同一个父容器里，切换只需动一个 visibility。
   *
   * <p>XML 列表是隐藏而非移除：ConversationListAdapter 与滚动位置等都挂在它上面，移除后
   * 再切回来要全部重建；隐藏则两条路径随时可切。
   */
  private fun installComposePanel() {
    val container = binding.assistantMessages.parent as? android.view.ViewGroup ?: return
    val state = com.tom.rv2ide.artificial.agent.compose.AssistantMessageState(adapter)
    val callbacks =
        object : com.tom.rv2ide.artificial.agent.compose.AssistantPanelCallbacks {
          override fun onToggleExpanded(messageId: Long) {
            // 展开态存在适配器数据里，且三种可展开条目（工具卡 / 推理块 / 差异卡）各有自己的
            // 翻转方法。这里按 id 逐个调用，找不到对应类型的会各自静默跳过——
            // 与 revertDiff 同时调 markReverted + markDiffReverted 是同一个套路，
            // 好处是面板不必知道这条消息属于哪一种（那是映射层的知识）。
            adapter.toggleExpanded(messageId)
            adapter.toggleThinkingExpanded(messageId)
            adapter.toggleDiffExpanded(messageId)
          }

          override fun onToggleGroup(groupId: Long, currentlyCollapsed: Boolean) {
            // 组的展开意图存在 ToolGroup.pinnedExpanded，与单条消息的展开态是两套状态，
            // 因此必须走 toggleGroupExpanded 而不是 onToggleExpanded 那三种翻转。
            adapter.toggleGroupExpanded(groupId, currentlyCollapsed)
          }

          override fun onRevert(messageId: Long, diffId: String) {
            // 完全复用 XML 路径的回滚实现：工作区校验、IO 线程执行、结果提示、
            // 标记已撤销后禁用按钮，全部同一套行为。
            revertDiff(messageId, diffId)
          }

          override fun onMoreClick(messageId: Long) {
            // 复用既有的长按菜单（复制原文/纯文、引用提问）。菜单的锚点需要 View，
            // Compose 没有对应的锚点概念，传面板自身的 ComposeView——
            // 弹出位置锚在面板上，与 XML 路径锚在条目上的观感一致。
            val message = adapter.find(messageId) ?: return
            val anchor = composePanel?.view ?: return
            showMessageActionsMenu(message, anchor)
          }
        }

    // 图像查看器需要把「容器路径」解析成宿主文件，故把工作区根包成 FileAccessProvider。
    // 取不到工作区时传 null：面板仍可显示带 localPath / 内联 base64 的图，
    // 只有「只有容器路径」的那种图看不了——比整块面板不可用要好。
    val fileAccess =
        workspace?.let {
          com.tom.rv2ide.artificial.agent.compose.compat.HostFileAccessProvider(it)
        }
    val panel =
        com.tom.rv2ide.artificial.agent.compose.AssistantComposePanelHost(
            context, state, callbacks, fileAccess)
    // 插到**索引 0**，而不是 append 到末尾：该 FrameLayout 里还有「无消息时的提示文案」
    // 与「回到底部」按钮，它们必须盖在消息区之上。append 会让消息区排在它们后面，
    // 把提示文案整块遮住——「没有消息」的提示就永远看不见了。
    // 插到 0 同时也在 RecyclerView（已 GONE）之下，层级与 XML 路径一致。
    container.addView(
        panel.view,
        0,
        android.widget.FrameLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
        ),
    )
    binding.assistantMessages.visibility = android.view.View.GONE
    composePanel = panel
  }

  /**
   * 按当前偏好装或卸 Compose 渲染路径。
   *
   * <p>设置页改完开关后**立刻**生效：否则用户点了开关、回到面板却毫无变化，只能靠重启面板
   * 或重开 App 才看到效果——那等于让用户怀疑开关坏了。两条路径共用同一个 [adapter]，
   * 装卸都不会丢消息（Compose 读它的快照、写它的展开态）。
   */
  private fun applyComposeRenderPath() {
    val want = com.tom.rv2ide.artificial.agent.compose.AssistantComposeRender.isEnabled(context)
    when {
      want && composePanel == null -> installComposePanel()
      !want && composePanel != null -> uninstallComposePanel()
    }
  }

  /**
   * 卸下 Compose 渲染路径，恢复 XML 列表。
   *
   * <p>顺序要紧：先 `dispose()` 再移除视图。反过来的话，视图已 detach 而观察者仍挂在适配器上，
   * 期间若有消息更新会往一个已无宿主的 state 里写，白做一次映射。
   */
  private fun uninstallComposePanel() {
    val panel = composePanel ?: return
    // 先收尾未决提问：提问方在另一线程用 latch 等答案，面板一拆就再没人能回答它，
    // 只能白等到 180 秒超时。这里主动以「取消」结束它。
    panel.cancelPendingQuestion()
    panel.state.dispose()
    (panel.view.parent as? android.view.ViewGroup)?.removeView(panel.view)
    binding.assistantMessages.visibility = android.view.View.VISIBLE
    composePanel = null
  }

  private fun updateEmptyState() {
    binding.assistantEmpty.isVisible = adapter.isEmpty()
  }

  /**
   * 把实时输出缓冲投影到输入栏桥，驱动浮动状态条重组。
   *
   * <p>每次 PROGRESS 全量重建一个小 Map：条目数 = 运行中工具数（通常 0-1 个），
   * 重建比增量维护简单且无一致性风险。
   *
   * <p>必须在主线程调用（写 mutableStateOf + 读 runningToolOutput）。
   */
  private fun publishLiveToolOutput() {
    inputBar.liveToolOutput =
        runningToolOutput.mapKeys { (id, _) -> id.toString() }
            .mapValues { (_, buf) -> buf.toString() }
  }

  /**
   * 刷新任务卡片。
   *
   * <p>没有任务时整卡隐藏，而不是显示一个空卡片——空卡片会长期占据输入区上方
   * 的空间，而「没有任务」是常态（普通问答不产生任务清单）。
   */
  private fun refreshTodos() {
    // 按**当前显示的**会话读取：待办是会话级的，读全局的会把上一个项目的
    // 任务清单显示在刚开的会话里（用户以为 agent 搞错了项目）。
    val todos = orchestrator.getTodos(displayedConversationId)
    // 双路渲染：XML 任务卡（assistantTodos）与 Compose 输入栏的 TodoDashboardBar
    // 同时接收数据。XML 卡即将退役；过渡期两处同显，避免一次切换丢信息。
    inputBar.todoItems =
        todos.map { item ->
          com.tom.rv2ide.artificial.agent.compose.components.tools.ParsedTodoItem(
              id = item.content,
              subject = item.content,
              description = "",
              status = item.status,
              priority = 0,
              order = 0,
          )
        }
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
            drawerCurrentCwd.value = projectDir.absolutePath
            drawerFileHost.setWorkspace(projectDir)
          }
          drawerActiveId.value = summary.id
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

  private fun askUserQuestionsOnMain(
      questions: List<com.tom.rv2ide.ai.tool.ToolSettingsPort.Question>
  ): List<String>? {
    if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
      return null
    }
    val latch = java.util.concurrent.CountDownLatch(1)
    var result: List<String>? = null
    android.os.Handler(android.os.Looper.getMainLooper())
        .post {
          try {
            // Compose 路径开着时由面板弹对话框，否则走既有的 XML 弹窗。两条路都在**已解析出
            // 答案**后回调同一个 lambda，所以下面的等待与超时逻辑对两者一视同仁。
            val panel = composePanel
            if (panel != null) {
              panel.askQuestion(
                  questions.toPendingUserQuestion(id = "ask-" + System.nanoTime()),
                  { answer ->
                    result = answer?.toAnswerList()
                    latch.countDown()
                  },
              )
            } else {
              host.dialogs.showUserQuestions(
                  questions,
                  { answers ->
                    result = answers
                    latch.countDown()
                  },
                  {
                    result = null
                    latch.countDown()
                  },
              )
            }
          } catch (e: Exception) {
            latch.countDown()
          }
        }
    // 用户看题并作答可能需要较长时间，给 180 秒超时。
    latch.await(180, java.util.concurrent.TimeUnit.SECONDS)
    return result
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
        is AssistantMessageAdapter.ToolGroup -> {
          // 导出时把组展开成逐个工具，而不是只写一行摘要：
          // 导出是给人复盘用的，摘要（「读取 5 个文件」）丢掉了「读了哪几个」这个关键信息，
          // 而屏幕上之所以折叠是为了省空间——导出没有这个约束。
          // 用 toolCalls 而不是 children：组内还可能含推理块（见 ToolGroup 的注释），
          // 它没有 toolName/output，直接遍历 children 会编译不过——这是好事，
          // 类型系统在这里挡住了「把推理块当工具导出」的错误。
          for (child in item.toolCalls) {
            sb.append("- ")
                .append(
                    if (child.status == AssistantMessageAdapter.ToolStatus.FAILED) "✗ " else "✓ ")
                .append(child.toolName)
            if (child.output.isNotBlank()) {
              sb.append("：").append(child.output.take(2000))
              if (child.output.length > 2000) {
                sb.append("…（已截断，共 ").append(child.output.length).append(" 字符）")
              }
            }
            sb.append('\n')
          }
        }
        is AssistantMessageAdapter.Diff -> {
          // 改动内容要进导出：它是「AI 到底改了什么」的唯一记录。
          // 用标准 diff 记法（+/- 前缀）而不是屏幕上的行号排版——导出目标是
          // 编辑器或 issue，行号列与竖线在那里是噪声，而 +/- 是通用记法。
          appendDiffToExport(sb, item)
        }
        is AssistantMessageAdapter.DiffGroup -> {
          // 汇总卡片在导出时展开成逐个文件：导出是给人复盘用的，
          // 「本轮改动了哪几个文件」在屏幕上折叠是为了省空间，导出没有这个约束。
          for (child in item.children) {
            appendDiffToExport(sb, child)
          }
        }
      }
    }
    return sb.toString().trim()
  }

  /** 把一个文件的改动写成 Markdown 段。屏幕上的单卡片与汇总组共用，避免两处格式分叉。 */
  private fun appendDiffToExport(sb: StringBuilder, item: AssistantMessageAdapter.Diff) {
    sb.append("**✎ ").append(item.filePath).append("**\n\n")
    val result = item.result
    if (result == null) {
      sb.append(context.getString(string.ai_assistant_diff_expired)).append("\n\n")
    } else {
      sb.append("```diff\n")
          .append(AssistantDiffRenderer.toPlainText(result).trimEnd('\n'))
          .append("\n```\n\n")
    }
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
