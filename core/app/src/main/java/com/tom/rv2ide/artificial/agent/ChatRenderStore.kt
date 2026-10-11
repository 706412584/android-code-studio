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

import com.tom.rv2ide.adapters.AssistantMessageAdapter
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

/**
 * 跨视图重建存活的聊天渲染态。
 *
 * <p><b>解决什么</b>：[FloatingAssistantView] 是 Activity/Fragment 作用域的——配置变更
 * （`fontScale` / `density` / `uiMode` 等**未**声明在 `configChanges` 里的项）或系统回收
 * 会重建宿主，于是视图重建、适配器与全部渲染指针归零。此前唯一的数据源就是这个视图内的
 * 适配器，因此重建后列表必然为空，只能靠 `clear()` + 从磁盘 JSONL 回放重新填——实测表现是
 * 「整表闪一下、滚动位置丢失、流式正文断成两条」。
 *
 * <p>本类把**会话级**状态提到进程级：视图重建后直接接管同一个适配器实例，
 * [FloatingAssistantView.restoreConversationIfNeeded] 的 `adapter.itemCount > 0` 判据
 * 自然成立，于是走「只订阅、不回放」分支，列表与滚动位置原样保留。
 *
 * <p><b>为什么适配器指针也必须一起搬</b>：只留列表、丢掉指针，重建后到达的 `TEXT_DELTA`
 * 找不到 `streamingMessageId` 会另起一段气泡，正文被劈成两条；`TOOL_FINISHED` 也会回填到
 * 历史里最后一张无关卡片（见 [SessionUiState] 与 [RenderSlot.toolCardIdByCallId] 的说明）。
 *
 * <p><b>什么不放这里</b>：视图本地态——滚动位置、抽屉开关、FAB 位置、输入框文本。
 * 它们本就该随视图重建，搬过来反而会带来「新页面继承旧滚动位置」这类错配。
 *
 * <p><b>归属</b>：按 [AgentOrchestrator] 索引（与 `FloatingAssistantView.liveViews`
 * 同一模式）。共享 orchestrator 的宿主（侧栏 / 真全屏 / 应用外悬浮）因此能互相复用；
 * 不共享时各自独立，行为与从前一致。
 */
internal class ChatRenderStore {

  /**
   * 一个 orchestrator 的渲染槽位。
   *
   * <p>适配器实例被**复用**而不是搬运状态：这样条目、id 计数器、展开态、运行态全部天然
   * 延续，不需要逐个字段导出导入（也就不会漏字段）。
   *
   * <p>复用带来两个必须显式处理的副作用：`imageScope` 与两个点击回调原先指向旧视图，
   * 由新视图在 [FloatingAssistantView.attach] 里重新赋值覆盖。
   */
  class RenderSlot {

    /**
     * 当前适配器；null 表示还没有视图用过。
     *
     * <p>**不随视图销毁清空**——这正是本类存在的意义。只在会话切换时被替换
     * （见 [conversationId] 的判据）。
     */
    var adapter: AssistantMessageAdapter? = null

    /**
     * [adapter] 当前显示的是哪个会话。
     *
     * <p>重建后要确认适配器装的仍是同一个会话才复用：期间用户可能在另一个入口切了会话
     * （共享 orchestrator 时完全可能），此时旧适配器的内容属于别的会话，复用会显示错内容。
     */
    var conversationId: String? = null

    /**
     * 当前视图正在显示的会话（对应 `FloatingAssistantView.displayedConversationId`）。
     *
     * <p>与 [conversationId] 分开：[conversationId] 描述「适配器里装的是什么」，
     * 本字段描述「视图认为自己在看什么」。重建后由新视图恢复本字段，
     * 使 `open()` 能识别出「列表已有内容、只需订阅不必回放」。
     */
    var displayedConversationId: String? = null

    /**
     * 当前占用 [adapter] 的视图（弱引用）。
     *
     * <p>用于判断适配器是否「空闲」：重建是「旧视图 dispose → 新视图 attach」的顺序，
     * 因此新视图接手时旧视图已释放，槽位可复用。若两个视图同时存活（主页 SIDEBAR +
     * 内联页这类组合），后到的视图不能抢占——一个 `RecyclerView.Adapter` 不能同时挂在
     * 两个列表上。此时它自建一个，退化为旧行为。
     */
    var owner: WeakReference<FloatingAssistantView>? = null

    /**
     * 每个会话「最近一次事件流写到哪张卡片」。
     *
     * <p>多会话并行时适配器只显示一个会话的消息，因此这些指针必须按会话隔离，否则
     * A 会话的 `TOOL_FINISHED` 会回填到 B 会话的卡片上。
     */
    val sessionUi = ConcurrentHashMap<String, SessionUiState>()

    /**
     * 工具调用 id → 卡片 id（**并行执行的核心配对表**）。
     *
     * <p>并行派遣时多张卡片同时处于运行态，事件按各自完成顺序到达——没有配对表，
     * 先完成的子代理会把结果写进另一张还在跑的卡片。
     */
    val toolCardIdByCallId = HashMap<String, Long>()

    /** 正在运行的工具的实时输出累积（键 = 工具卡片 id）。 */
    val runningToolOutput = HashMap<Long, StringBuilder>()

    /** 当前正在运行的工具卡片 id；无运行中工具时为 null。 */
    var runningToolCardId: Long? = null

    /**
     * 本轮改动过的文件（按会话隔离），运行结束时汇总成一张卡片。
     *
     * <p>类型与 [FloatingAssistantView] 原字段一致（外层按会话 id 分桶）：视图侧直接委托到
     * 这里，因此 30 余处 `pendingDiffs.getOrPut(...)` / `remove(...)` 调用点无需改动。
     */
    val pendingDiffs = HashMap<String, MutableList<Pair<String, String?>>>()
  }

  companion object {

    /**
     * 每个 orchestrator 一份槽位。
     *
     * <p>键弱引用 orchestrator：orchestrator 是进程级单例（见 [AssistantOrchestratorProvider]），
     * 因此槽位实际与进程同寿；弱引用只是防御「将来有人建临时 orchestrator」。
     *
     * <p>[java.util.WeakHashMap] 非线程安全，而视图构造在主线程、事件回调在循环线程也可能
     * 首次触发取用——用 `@Synchronized` 守住（临界区极短，无争用）。
     */
    private val slots = java.util.WeakHashMap<AgentOrchestrator, RenderSlot>()

    /** 取（或创建）某 orchestrator 的槽位。 */
    @Synchronized
    fun slotFor(orchestrator: AgentOrchestrator): RenderSlot =
        slots.getOrPut(orchestrator) { RenderSlot() }
  }
}

/**
 * 每个会话「最近一次事件流写到哪张卡片」。
 *
 * <p>从 `FloatingAssistantView` 的私有嵌套类提出来：它现在归 [ChatRenderStore.RenderSlot]
 * 按会话持有，视图只是读取者。
 */
internal class SessionUiState {
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
