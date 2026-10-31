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

package com.assistant.ui.model

/**
 * 会话时间线的**纯数据层**：条目模型 + 增删改，不含任何 View 层类型。
 *
 * <p><b>为什么不是直接搬 ACS 的 `AssistantMessageAdapter`</b>：那个类是
 * `RecyclerView.Adapter`，一半代码是 ViewHolder 与 XML 绑定。剥离后的 App 只有 Compose
 * 一条渲染路径，留着它等于把一套已经废弃的视图层连同 `RecyclerView` / ViewBinding /
 * `com.google.android.material` 一起拖进来——而这些正是本次剥离要去掉的东西。
 *
 * <p>因此这里只保留它真正有价值的那部分：**不可变条目 + 稳定的 id + 分组规则**。
 * 迁移是逐字照搬的（包括那些踩过坑的注释），因为每一条都对应一次实测故障：
 *
 * <ul>
 *   <li>分组必须能穿透（`ToolGroup.children`），漏一处就是「图片不显示 / 子 agent 步骤丢失 /
 *       卡片永久停在运行中」这类静默故障；
 *   <li>推理块要并入末组，否则「每次工具调用前先输出一段推理」会让分组永不生效；
 *   <li>工具结果图片要限张数，否则影子屏自动化一轮几十张就把堆撑爆。
 * </ul>
 *
 * <p><b>与 Compose 的接法</b>：条目是不可变 data class，内容没变时列表里保留的是**同一个
 * 实例**，因此 [AssistantMessageState] 可以按引用相等做记忆化，未变条目复用同一个
 * [AgentUIMessage] 实例，`LazyColumn` 按 `key` 跳过重组。
 */
class MessageTimeline {

  /** 消息角色。UI 只区分「谁说的」，不关心协议层的具体类型。 */
  enum class Role {
    USER,
    ASSISTANT,
    /** 工具调用/系统提示等过程信息，弱化显示。 */
    TRACE
  }

  /** 工具调用状态。 */
  enum class ToolStatus {
    RUNNING,
    DONE,
    FAILED
  }

  /** 列表项。id 稳定，用于流式更新与状态回填。 */
  sealed class Item {
    abstract val id: Long
  }

  /**
   * 一条文本消息。
   *
   * @param id 稳定标识：流式更新末条消息时 id 不变，才能识别为「同一项内容变了」
   * @param diffId 该消息对应一次可回滚的文件改动；null 表示不可回滚（多数消息如此）
   * @param reverted 该改动是否已被撤销。撤销后按钮要变成不可再点的状态——
   *   否则用户会重复点击并收到「已经回滚过了」的错误
   * @param durationMs 本轮耗时（毫秒）；0 表示无数据、不显示
   * @param attachments 该条用户消息携带的附件；无附件时为空表。只带路径不带 base64：
   *   缩略图按需从磁盘解码，条目本身保持轻量（它会进快照并被逐条比较）
   */
  data class Message(
      override val id: Long,
      val role: Role,
      val text: String,
      val diffId: String? = null,
      val reverted: Boolean = false,
      val durationMs: Long = 0L,
      val attachments: List<MessageAttachment> = emptyList(),
  ) : Item()

  /** 消息气泡里的一条附件（已发送状态的只读投影）。 */
  data class MessageAttachment(
      val fileName: String,
      val localPath: String,
      val mimeType: String,
      val sizeBytes: Long,
      val isImage: Boolean,
  )

  /**
   * 一段模型推理过程（思维链）。
   *
   * <p>单独一种 item 而不是塞进 [Message]：推理默认折叠、限高、弱化配色，正文则完整展开。
   * 混在一起会让「折叠哪个」无法表达。
   *
   * @param streaming 是否仍在生成。决定标题文案与进度指示器
   * @param expanded 展开状态。**存在数据里而不是控件里**——视图复用会把状态挂到错误的条目上
   */
  data class Thinking(
      override val id: Long,
      val text: String,
      val streaming: Boolean = true,
      val expanded: Boolean = false,
  ) : Item()

  /**
   * 一次工具调用。
   *
   * @param steps 步骤进度记录（工具执行期间累积）。对子 agent 这类长任务，
   *   它记录运行期间每步在做什么——子 agent 的中间过程刻意不进主对话，
   *   这些行是用户回看时的唯一凭据
   * @param imageBase64 工具结果里的图片（base64，不含 data URL 前缀）；无图为 null。
   *   存 base64 而不是解码后的字节：这个 data class 会进快照并被比较，
   *   持有几 MB 的 ByteArray 会让每次 copy 都复制一份大对象
   */
  data class ToolCall(
      override val id: Long,
      val toolName: String,
      val summary: String,
      val input: String,
      val output: String = "",
      val status: ToolStatus = ToolStatus.RUNNING,
      val expanded: Boolean = false,
      val steps: List<String> = emptyList(),
      val imageBase64: String? = null,
      val imageMimeType: String = "",
  ) : Item()

  /**
   * 一次文件改动的 diff 卡片。
   *
   * @param computing diff 内容仍在后台计算中。为 true 时 [result] 必为 null——
   *   「还没算完」与「记录已过期」都表现为 result==null，但文案必须不同
   */
  data class Diff(
      override val id: Long,
      val filePath: String,
      val diffId: String?,
      val result: DiffResult?,
      val expanded: Boolean = false,
      val reverted: Boolean = false,
      val computing: Boolean = false,
  ) : Item()

  /**
   * 一轮运行的**全部文件改动汇总**卡片。
   *
   * <p>不是一张卡片一次改动：长任务里十几次编辑会在对话流里插进十几张 diff，
   * 用户读的是回答，却被 diff 反复打断。运行结束后出一张汇总卡，
   * 折叠态一行说明「本轮改动 N 个文件 · +X -Y」，展开才逐文件列出。
   */
  data class DiffGroup(
      override val id: Long,
      val children: List<Diff>,
      /** 用户手动展开/折叠的意图；null 表示跟随默认（折叠）。 */
      val pinnedExpanded: Boolean? = null,
  ) : Item() {
    val totalAdded: Int
      get() = children.sumOf { it.result?.added ?: 0 }

    val totalRemoved: Int
      get() = children.sumOf { it.result?.removed ?: 0 }

    /** 去重后的文件数：`children` 是**改动次数**，同一文件改两次会有两项。 */
    val fileCount: Int
      get() = children.map { it.filePath }.distinct().size
  }

  /**
   * 一组连续的工具调用与推理块，折叠态渲染成一行活动摘要。
   *
   * <p>Thinking 也收进组内、不打断分组（实测修正）：每次工具调用**之前**模型都会先输出
   * 一段推理，于是「末项永远是 Thinking」，分组条件永不满足——分组等于没做。
   *
   * @param children 组内的条目，按出现顺序，元素是 [ToolCall] 或 [Thinking]。
   *   **它们不在顶层 items 里**，因此所有按 id 查找的方法都必须能穿透到这里
   * @param pinnedExpanded 用户手动展开/折叠的意图；null 表示「跟随自动策略」
   */
  data class ToolGroup(
      override val id: Long,
      val children: List<Item>,
      val pinnedExpanded: Boolean? = null,
  ) : Item() {
    /** 组内的工具调用（不含推理块）。摘要与失败计数只关心这些。 */
    val toolCalls: List<ToolCall>
      get() = children.filterIsInstance<ToolCall>()
  }

  /** 时间线变化通知。语义等价于 RecyclerView 的「数据集变了」，只是没有位置信息。 */
  fun interface ChangeListener {
    fun onTimelineChanged()
  }

  private val items = mutableListOf<Item>()
  private var nextId = 0L
  private val listeners = LinkedHashSet<ChangeListener>()

  /** 注册变化监听。重复注册同一实例只生效一次。 */
  fun addChangeListener(listener: ChangeListener) {
    listeners.add(listener)
  }

  /** 注销变化监听。重复调用安全。 */
  fun removeChangeListener(listener: ChangeListener) {
    listeners.remove(listener)
  }

  /**
   * 当前是否有运行在进行。
   *
   * <p>工具组的自动折叠依赖它：只有「运行中**且**位于列表末尾」的组才默认展开，
   * 其余一律折叠——一次任务跑完，十几张工具卡片应该自动收成一行。
   */
  var isRunActive: Boolean = false
    set(value) {
      if (field == value) {
        return
      }
      field = value
      // 活动态变化会改变末组的折叠状态，必须重新发布快照，
      // 否则运行结束时最后一组仍停在展开态（用户得滚动一下才收起来）。
      val last = items.lastIndex
      if (last >= 0 && items[last] is ToolGroup) {
        notifyChanged()
      }
    }

  // ---- 文本消息 ----

  /** 追加一条消息并返回它的 id；流式更新用该 id 定位。 */
  fun append(role: Role, text: String): Long = append(role, text, null, 0L)

  fun append(role: Role, text: String, diffId: String?): Long = append(role, text, diffId, 0L)

  fun append(role: Role, text: String, diffId: String?, durationMs: Long): Long {
    val id = nextId++
    items.add(Message(id, role, text, diffId, reverted = false, durationMs = durationMs))
    notifyChanged()
    return id
  }

  /** 追加一条带附件的消息（用户气泡的图片/文件缩略图条用）。 */
  fun appendWithAttachments(
      role: Role,
      text: String,
      attachments: List<MessageAttachment>,
  ): Long {
    val id = nextId++
    items.add(
        Message(
            id,
            role,
            text,
            diffId = null,
            reverted = false,
            durationMs = 0L,
            attachments = attachments))
    notifyChanged()
    return id
  }

  // ---- 改动汇总卡片 ----

  /**
   * 追加一张「本轮全部改动」汇总卡片。
   *
   * @param entries 本轮改动的文件，按发生顺序；diffId 为空时该行只显示文件名
   * @return 各子项的 id，与 [entries] 同序，供异步回填 diff 内容
   */
  fun appendDiffGroup(entries: List<Pair<String, String?>>): List<Long> {
    if (entries.isEmpty()) {
      return emptyList()
    }
    // computing=true：内容由调用方随后异步回填（见 updateDiff）。不标这一位的话，
    // 卡片在回填到达前会显示「记录已过期」——运行刚结束时用户最可能立刻展开。
    val children =
        entries.map { (path, diffId) -> Diff(nextId++, path, diffId, result = null, computing = true) }
    items.add(DiffGroup(nextId++, children))
    notifyChanged()
    return children.map { it.id }
  }

  /**
   * 在顶层或汇总组内替换一张 diff 卡片。
   *
   * <p>汇总组把子项收进 `children` 后它们不在顶层，按 id 找子项的方法都必须能穿透，
   * 否则「展开单文件」「撤销」「回填 diff 内容」会全部静默失效。
   */
  private fun replaceDiff(id: Long, transform: (Diff) -> Diff): Boolean {
    val topIndex = indexOfDiff(id)
    if (topIndex < 0) {
      return false
    }
    when (val top = items[topIndex]) {
      is Diff -> {
        if (top.id == id) {
          items[topIndex] = transform(top)
          notifyChanged()
          return true
        }
      }
      is DiffGroup -> {
        val childIndex = top.children.indexOfFirst { it.id == id }
        if (childIndex >= 0) {
          val next = top.children.toMutableList()
          next[childIndex] = transform(next[childIndex])
          items[topIndex] = top.copy(children = next)
          notifyChanged()
          return true
        }
      }
      else -> {}
    }
    return false
  }

  /** diff 卡片（含汇总组内）所在的顶层下标；未找到为 -1。 */
  private fun indexOfDiff(id: Long): Int =
      items.indexOfFirst { item ->
        when (item) {
          is Diff -> item.id == id
          is DiffGroup -> item.children.any { it.id == id }
          else -> false
        }
      }

  /** 回填 diff 计算结果；同时把 [Diff.computing] 置回 false。 */
  fun updateDiff(id: Long, result: DiffResult?) {
    replaceDiff(id) { it.copy(result = result, computing = false) }
  }

  /** 切换 diff 卡片的展开状态。 */
  fun toggleDiffExpanded(id: Long) {
    replaceDiff(id) { it.copy(expanded = !it.expanded) }
  }

  /** 把 diff 卡片标记为已撤销，使按钮进入禁用态。 */
  fun markDiffReverted(id: Long) {
    replaceDiff(id) { it.copy(reverted = true) }
  }

  /** 切换改动汇总卡片的展开状态；把用户意图写进 [DiffGroup.pinnedExpanded]。 */
  fun toggleDiffGroupExpanded(id: Long, currentlyCollapsed: Boolean) {
    val index = indexOf(id)
    val old = items.getOrNull(index) as? DiffGroup ?: return
    items[index] = old.copy(pinnedExpanded = currentlyCollapsed)
    notifyChanged()
  }

  /**
   * 把某条消息标记为已撤销。
   *
   * <p>只改状态不删消息：撤销是用户可见的动作，消息凭空消失会让人以为列表出了问题。
   */
  fun markReverted(id: Long) {
    val index = indexOf(id)
    val old = items.getOrNull(index) as? Message ?: return
    if (old.reverted) {
      return
    }
    items[index] = old.copy(reverted = true)
    notifyChanged()
  }

  /** 覆盖指定消息的正文。id 不存在时静默忽略——消息可能已被清空（用户点了「新会话」）。 */
  fun update(id: Long, text: String) {
    val index = indexOf(id)
    val old = items.getOrNull(index) as? Message ?: return
    if (old.text == text) {
      return
    }
    items[index] = old.copy(text = text)
    notifyChanged()
  }

  /** 回填本轮耗时到指定消息上。 */
  fun updateDuration(id: Long, durationMs: Long) {
    val index = indexOf(id)
    val old = items.getOrNull(index) as? Message ?: return
    items[index] = old.copy(durationMs = durationMs)
    notifyChanged()
  }

  /** 追加到指定消息的正文之后（增量累积用）。 */
  fun appendTo(id: Long, delta: String) {
    val index = indexOf(id)
    val old = items.getOrNull(index) as? Message ?: return
    items[index] = old.copy(text = old.text + delta)
    notifyChanged()
  }

  /**
   * 移除一条**内容为纯空白**的消息，返回是否真的移除了。
   *
   * <p>模型「只发起工具调用、不写正文」的轮次里，流式阶段仍会吐出纯空白增量（`"\n"`），
   * 建出的助手气泡渲染成一片空白灰块。判定用 isBlank 而非 isEmpty，
   * 因为 `"\n\n"` 正是最常见的形态。
   */
  fun removeIfBlank(id: Long): Boolean {
    val index = indexOf(id)
    val old = items.getOrNull(index) as? Message ?: return false
    if (old.role != Role.ASSISTANT || !old.text.isBlank()) {
      return false
    }
    items.removeAt(index)
    notifyChanged()
    return true
  }

  /**
   * 按 id 移除任意类型的条目（消息 / 工具卡片 / 推理块）。
   *
   * <p>供断流重发使用：重发会产生一份全新的回答，上一次尝试的部分输出必须整条丢弃。
   * **必须穿透分组**：推理块会被收进 [ToolGroup.children]，顶层查找找不到它——
   * 此前正是这里漏了穿透，断流重发时组内推理块清理失败，旧碎片与新内容交错。
   */
  fun remove(id: Long): Boolean {
    val index = indexOf(id)
    if (index >= 0) {
      items.removeAt(index)
      notifyChanged()
      return true
    }
    val groupIndex = items.indexOfFirst { it is ToolGroup && it.children.any { c -> c.id == id } }
    if (groupIndex < 0) {
      return false
    }
    val group = items[groupIndex] as ToolGroup
    val next = group.children.filterNot { it.id == id }
    if (next.isEmpty()) {
      items.removeAt(groupIndex)
    } else {
      items[groupIndex] = group.copy(children = next)
    }
    notifyChanged()
    return true
  }

  /** 按 id 取文本消息；不存在或不是消息时返回 null。 */
  fun find(id: Long): Message? = items.firstOrNull { it.id == id } as? Message

  /** 按 id 取工具卡片（含组内）；不存在时返回 null。 */
  fun findToolCall(id: Long): ToolCall? =
      when (val top = items.getOrNull(indexOfToolCall(id))) {
        is ToolCall -> top.takeIf { it.id == id }
        is ToolGroup -> top.toolCalls.firstOrNull { it.id == id }
        else -> null
      }

  // ---- 工具卡片 ----

  /** 追加一张「运行中」的工具卡片并返回它的 id。 */
  fun appendToolCall(toolName: String, summary: String, input: String): Long {
    val id = nextId++
    val call = ToolCall(id, toolName, summary, input)
    val last = items.lastOrNull()

    // 决策逻辑在 [ToolGrouping]（纯函数、可单测）；这里只负责改列表。
    val decision =
        ToolGrouping.decide(
            lastIsGroup = last is ToolGroup,
            lastToolName = (last as? ToolCall)?.toolName,
            incomingToolName = toolName,
        )
    when (decision) {
      ToolGrouping.Decision.MERGE_INTO_LAST_GROUP -> {
        val group = last as ToolGroup
        items[items.size - 1] = group.copy(children = group.children + call)
      }
      ToolGrouping.Decision.MERGE_LAST_TWO -> {
        val groupId = nextId++
        items[items.size - 1] = ToolGroup(groupId, listOf(last as ToolCall, call))
      }
      ToolGrouping.Decision.APPEND -> items.add(call)
    }
    notifyChanged()
    return id
  }

  /** 回填工具执行结果（无图片）。 */
  fun completeToolCall(id: Long, output: String, isError: Boolean) {
    completeToolCall(id, output, isError, null, "")
  }

  /**
   * 完成一次工具调用，并带上结果里的图片。
   *
   * @param imageBase64 工具结果图片的 base64；无图为 null
   */
  fun completeToolCall(
      id: Long,
      output: String,
      isError: Boolean,
      imageBase64: String?,
      imageMimeType: String,
  ) {
    // 必须穿透分组：工具可能已被并入 ToolGroup，顶层查找会静默失败
    // ——卡片永久停在「运行中」，且图片永远不会出现。
    replaceToolCall(id) { old ->
      old.copy(
          output = output,
          status = if (isError) ToolStatus.FAILED else ToolStatus.DONE,
          imageBase64 = imageBase64,
          imageMimeType = imageMimeType,
      )
    }
    trimToolImages(MAX_TOOL_IMAGES)
  }

  /**
   * 列表里最多保留多少张工具结果图片。
   *
   * <p>模型侧的历史图片已被限制，但 UI 的消息列表是另一份独立数据，每张截图 2–4MB。
   * 影子屏自动化一轮几十张时，两份叠加把堆撑爆（真机 OOM 的实际形态）。
   * 取 4 比模型侧的 3 略宽：卡片里图片被清掉只是不再显示预览，
   * **文字结果与路径都还在**，不影响理解与继续操作。
   */
  private val MAX_TOOL_IMAGES = 4

  /** 只保留最近 [limit] 张工具结果图片，更早的卡片清掉 base64。 */
  private fun trimToolImages(limit: Int) {
    var remaining = limit
    var changed = false
    for (i in items.indices.reversed()) {
      when (val item = items[i]) {
        is ToolCall -> {
          if (item.imageBase64.isNullOrEmpty()) continue
          if (remaining > 0) {
            remaining--
            continue
          }
          items[i] = item.copy(imageBase64 = null, imageMimeType = "")
          changed = true
        }
        is ToolGroup -> {
          var groupChanged = false
          val next = item.children.toMutableList()
          for (j in next.indices.reversed()) {
            val child = next[j] as? ToolCall ?: continue
            if (child.imageBase64.isNullOrEmpty()) continue
            if (remaining > 0) {
              remaining--
              continue
            }
            next[j] = child.copy(imageBase64 = null, imageMimeType = "")
            groupChanged = true
          }
          if (groupChanged) {
            items[i] = item.copy(children = next)
            changed = true
          }
        }
        else -> {}
      }
    }
    if (changed) {
      notifyChanged()
    }
  }

  /**
   * 向「运行中」的工具卡片追加一行步骤记录（无目标 id 的回退路径）。
   *
   * <p>只回填到最近一张仍在运行的卡片。并行派遣时不要用这个重载——
   * 用带 id 的版本按调用归卡。
   */
  fun appendToolStep(step: String) {
    // 必须能看见组内的：分组后运行中的卡片通常就在末组里，
    // 只看顶层会完全找不到目标，子 agent 的步骤会**静默丢失**。
    var target: ToolCall? = null
    for (item in items) {
      when (item) {
        is ToolCall -> if (item.status == ToolStatus.RUNNING) target = item
        is ToolGroup ->
            item.toolCalls.filter { it.status == ToolStatus.RUNNING }.lastOrNull()?.let {
              target = it
            }
        else -> {}
      }
    }
    val running = target ?: return
    replaceToolCall(running.id) { it.copy(steps = it.steps + step) }
  }

  /** 向**指定卡片**追加一行步骤记录（并行派遣的归卡路径）。 */
  fun appendToolStep(id: Long, step: String) {
    replaceToolCall(id) { it.copy(steps = it.steps + step) }
  }

  /** 覆盖指定卡片的步骤列表（回放历史时使用）。 */
  fun setToolSteps(id: Long, steps: List<String>) {
    replaceToolCall(id) { old -> if (old.steps == steps) old else old.copy(steps = steps) }
  }

  /** 所有工具调用，含组内的（顺序保持出现顺序）。 */
  private fun allToolCalls(): List<ToolCall> =
      items.flatMap { item ->
        when (item) {
          is ToolCall -> listOf(item)
          is ToolGroup -> item.toolCalls
          else -> emptyList()
        }
      }

  /** 最近一张仍在运行的工具卡片 id；没有则 null。 */
  fun lastRunningToolCallId(): Long? =
      allToolCalls().lastOrNull { it.status == ToolStatus.RUNNING }?.id

  /** 切换卡片展开状态（含组内子卡片）。 */
  fun toggleExpanded(id: Long) {
    replaceToolCall(id) { old -> old.copy(expanded = !old.expanded) }
  }

  // ---- 思维链 ----

  /**
   * 追加一段推理增量，返回承载它的思维链块 id。
   *
   * <p>[lastThinkingId] 为 null 时新建一块。一次运行里的多轮推理会各建一块——
   * 中间隔着工具调用，合并成一块会让「这段推理属于哪一步」看不出来。
   *
   * @param streaming 新建的块是否标记为「仍在生成」。**回放历史时必须传 false**：
   *     传 true（默认）会让回放出的块永久显示「思考中」与进度动画。
   * @return 承载该增量的块 id；增量为纯空白且无既有块时返回 -1
   */
  fun appendThinking(delta: String, lastThinkingId: Long?, streaming: Boolean = true): Long {
    // 先尝试追加到既有块（**必须穿透分组**）。
    if (lastThinkingId != null) {
      val appended = replaceThinking(lastThinkingId) { it.copy(text = it.text + delta) }
      if (appended) {
        return lastThinkingId
      }
    }
    // 纯空白增量不建块：模型在轮次边界会吐出 "\n"，建出来是一排空折叠条。
    if (delta.isBlank()) {
      return -1L
    }
    val id = nextId++
    // 默认**展开**：流式期间用户最想看的就是模型正在想什么；结束时的自动折叠由
    // [finishThinking] 处理。
    val block = Thinking(id, delta, streaming = streaming, expanded = true)

    // 末项是工具组时把推理块也收进去，**不打断分组**（见 [ToolGroup] 的说明）。
    val last = items.lastOrNull()
    if (last is ToolGroup && ToolGrouping.shouldThinkingJoinGroup(true)) {
      items[items.size - 1] = last.copy(children = last.children + block)
      notifyChanged()
      return id
    }

    items.add(block)
    notifyChanged()
    return id
  }

  /**
   * 收尾一个思维链块：标记不再流式，并按 [autoCollapseThinking] 决定是否自动折叠。
   *
   * <p>只在块**确实在流式**时才动它：重复调用（工具连续调用时每轮都会 finish 一次）
   * 不该反复改写用户的展开态。
   */
  fun finishThinking(id: Long) {
    replaceThinking(id) {
      if (!it.streaming) {
        it
      } else {
        it.copy(streaming = false, expanded = if (autoCollapseThinking) false else it.expanded)
      }
    }
  }

  /** 切换推理块的展开状态。 */
  fun toggleThinkingExpanded(id: Long) {
    replaceThinking(id) { it.copy(expanded = !it.expanded) }
  }

  /**
   * 思考块是否「流式期间展开、结束后自动折叠」。由宿主从偏好读出后设置。
   * 默认 true：与偏好默认值一致，也让无宿主场景有确定行为。
   */
  var autoCollapseThinking: Boolean = true

  /** 把仍在流式的思维链块全部收尾（运行结束或失败时调用），含组内的。 */
  fun finishAllThinking() {
    var changed = false
    for (i in items.indices) {
      when (val top = items[i]) {
        is Thinking -> {
          if (top.streaming) {
            items[i] =
                top.copy(
                    streaming = false,
                    expanded = if (autoCollapseThinking) false else top.expanded,
                )
            changed = true
          }
        }
        is ToolGroup -> {
          if (top.children.none { it is Thinking && it.streaming }) {
            continue
          }
          items[i] =
              top.copy(
                  children =
                      top.children.map {
                        if (it is Thinking && it.streaming) {
                          it.copy(
                              streaming = false,
                              expanded = if (autoCollapseThinking) false else it.expanded,
                          )
                        } else {
                          it
                        }
                      })
          changed = true
        }
        else -> {}
      }
    }
    if (changed) {
      notifyChanged()
    }
  }

  /** 在顶层或组内替换一个推理块。与 [replaceToolCall] 同构。 */
  private fun replaceThinking(id: Long, transform: (Thinking) -> Thinking): Boolean {
    val topIndex =
        items.indexOfFirst { item ->
          when (item) {
            is Thinking -> item.id == id
            is ToolGroup -> item.children.any { it is Thinking && it.id == id }
            else -> false
          }
        }
    if (topIndex < 0) {
      return false
    }
    when (val top = items[topIndex]) {
      is Thinking -> {
        if (top.id == id) {
          items[topIndex] = transform(top)
          notifyChanged()
          return true
        }
      }
      is ToolGroup -> {
        val childIndex = top.children.indexOfFirst { it is Thinking && it.id == id }
        if (childIndex >= 0) {
          val next = top.children.toMutableList()
          next[childIndex] = transform(next[childIndex] as Thinking)
          items[topIndex] = top.copy(children = next)
          notifyChanged()
          return true
        }
      }
      else -> {}
    }
    return false
  }

  /**
   * 把指定的工具卡片标记为失败（例如循环被取消时仍在运行的那张）。
   *
   * <p>必须穿透分组：漏掉会让**被分组的工具在失败后永久停在「运行中」**，
   * 用户以为任务还在跑。
   */
  fun failToolCall(id: Long, reason: String) {
    replaceToolCall(id) { old ->
      if (old.status != ToolStatus.RUNNING) old
      else old.copy(status = ToolStatus.FAILED, output = reason)
    }
  }

  /** 仍在「运行中」的工具卡片 id；循环异常结束时用来收尾。 */
  fun runningToolCallIds(): List<Long> =
      allToolCalls().filter { it.status == ToolStatus.RUNNING }.map { it.id }

  /** 末条工具卡片的 id；用于把 TOOL_FINISHED 关联到对应的 TOOL_STARTED。 */
  fun lastToolCallId(): Long? = allToolCalls().lastOrNull()?.id

  // ---- 列表维护 ----

  /** 清空全部条目。 */
  fun clear() {
    if (items.isEmpty()) {
      return
    }
    items.clear()
    notifyChanged()
  }

  /** 本时间线全部渲染态的不可变快照（跨视图重建时搬运）。 */
  data class State(val items: List<Item>, val nextId: Long, val isRunActive: Boolean)

  /** 导出当前状态，供 [restoreState] 在新视图里恢复。 */
  fun exportState(): State = State(items.toList(), nextId, isRunActive)

  /**
   * 用 [exportState] 的快照恢复。
   *
   * <p>`nextId` 必须一起搬：漏了的话新实例从 0 重新分配 id，与仍在使用的
   * `streamingMessageId` / `toolCardIdByCallId`（它们记的是旧 id）对不上，
   * 后续增量会另起气泡。
   */
  fun restoreState(state: State) {
    items.clear()
    items.addAll(state.items)
    nextId = state.nextId
    batchDepth = 0
    batchDirty = false
    isRunActive = state.isRunActive
    notifyChanged()
  }

  // ---- 批量模式（历史回放分帧） ----

  /**
   * 批量深度：>0 时所有通知抑制为「脏标记」，[endBatch] 时一次性补发。
   *
   * <p>历史回放逐条 append 会逐条通知，数百条历史就是数百次全量重算，
   * 主线程连续掉帧。批量区间内只标脏，收帧时一次通知。
   */
  private var batchDepth = 0
  private var batchDirty = false

  /** 进入批量模式。嵌套安全；[endBatch] 必须与 [beginBatch] 成对（try/finally）。 */
  fun beginBatch() {
    if (batchDepth == 0) {
      batchDirty = false
    }
    batchDepth++
  }

  /** 结束批量模式：最外层收帧，一次性补发变更通知。 */
  fun endBatch() {
    check(batchDepth > 0) { "endBatch without beginBatch" }
    batchDepth--
    if (batchDepth == 0 && batchDirty) {
      batchDirty = false
      notifyChanged()
    }
  }

  /** 批量模式中跳过通知的统一入口；各变更点用 [notifyCompat] 包裹。 */
  private fun notifyCompat(notify: () -> Unit) {
    if (batchDepth > 0) {
      batchDirty = true
    } else {
      notify()
    }
  }

  /** 通知所有监听者。 */
  private fun notifyChanged() {
    notifyCompat { dispatchChanged() }
  }

  private fun dispatchChanged() {
    // 复制一份再遍历：监听者可能在回调里注销自己（宿主销毁路径）。
    for (listener in listeners.toList()) {
      listener.onTimelineChanged()
    }
  }

  fun isEmpty(): Boolean = items.isEmpty()

  /** 全部项的只读快照。 */
  fun snapshot(): List<Item> = items.toList()

  private fun indexOf(id: Long): Int = items.indexOfFirst { it.id == id }

  /**
   * 工具调用在列表里的位置（可穿透分组）。
   *
   * <p>所有「按工具 id 定位」的操作都必须走这里，否则会出现静默故障：
   * 图片不显示、子 agent 步骤丢失、卡片永久停在「运行中」。
   *
   * @return 顶层下标；未找到为 -1
   */
  private fun indexOfToolCall(id: Long): Int =
      items.indexOfFirst { item ->
        when (item) {
          is ToolCall -> item.id == id
          is ToolGroup -> item.children.any { it.id == id }
          else -> false
        }
      }

  /**
   * 在某个顶层条目的范围内替换一个工具调用。
   *
   * <p>落在组内时**派生一个新的 [ToolGroup]** 而不是就地改 children——
   * data class 的 children 是 `List`，就地改会让新旧状态指向同一份数据，
   * 快照对比与记忆化都会失效。
   *
   * @return 是否找到了目标
   */
  private fun replaceToolCall(id: Long, transform: (ToolCall) -> ToolCall): Boolean {
    val topIndex = indexOfToolCall(id)
    if (topIndex < 0) {
      return false
    }
    when (val top = items[topIndex]) {
      is ToolCall -> {
        if (top.id == id) {
          items[topIndex] = transform(top)
          notifyChanged()
          return true
        }
      }
      is ToolGroup -> {
        val childIndex = top.children.indexOfFirst { it is ToolCall && it.id == id }
        if (childIndex >= 0) {
          val next = top.children.toMutableList()
          next[childIndex] = transform(next[childIndex] as ToolCall)
          items[topIndex] = top.copy(children = next)
          notifyChanged()
          return true
        }
      }
      else -> {}
    }
    return false
  }

  /** 切换工具组的展开状态；把用户意图写进 [ToolGroup.pinnedExpanded]。 */
  fun toggleGroupExpanded(id: Long, currentlyCollapsed: Boolean) {
    val index = indexOf(id)
    val old = items.getOrNull(index) as? ToolGroup ?: return
    // 记「展开与否」而不是「折叠与否」：pinnedExpanded 的语义是展开意图。
    items[index] = old.copy(pinnedExpanded = currentlyCollapsed)
    notifyChanged()
  }
}
