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

package com.tom.rv2ide.adapters

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.tom.rv2ide.artificial.agent.AssistantMarkdown
import com.tom.rv2ide.databinding.ItemAssistantMessageBinding
import com.tom.rv2ide.databinding.ItemAssistantThinkingBinding
import com.tom.rv2ide.databinding.ItemToolCallBinding
import com.tom.rv2ide.resources.R.string

/**
 * 悬浮助手面板的会话列表：文本消息与工具卡片混排。
 *
 * <p><b>为什么需要工具卡片</b>：把工具调用拼成一行纯文本塞进状态栏，用户无法判断
 * 「这次调用到底改了什么」——参数被截断、输出看不到、失败原因只有一个行内片段。
 * 卡片折叠时给摘要、展开时给完整的输入输出，才使过程可审查。
 *
 * <p><b>精确通知而非整表刷新</b>：流式输出期间每收到一个增量就更新末条消息，
 * `notifyDataSetChanged` 会让滚动位置抖动并重建全部 ViewHolder。
 * [Item] 做成不可变 data class，使"内容变了"这件事在代码里一眼可见。
 */
class AssistantMessageAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

  /** 消息角色。UI 只区分"谁说的"，不关心协议层的具体类型。 */
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
   * @param id 稳定标识：流式更新末条消息时 id 不变，DiffUtil 才能识别为"同一项内容变了"
   * @param diffId 该消息对应一次可回滚的文件改动；null 表示不可回滚（多数消息如此）
   * @param reverted 该改动是否已被撤销。撤销后按钮要变成不可再点的状态——
   *   否则用户会重复点击并收到「已经回滚过了」的错误
   */
  data class Message(
      override val id: Long,
      val role: Role,
      val text: String,
      val diffId: String? = null,
      val reverted: Boolean = false,
  ) : Item()

  /**
   * 一段模型推理过程（思维链）。
   *
   * <p><b>为什么要单独一种 item 而不是塞进 [Message]</b>：推理与正文的展示规则完全不同
   * ——推理默认折叠、限高、弱化配色，正文则完整展开。混在一起会让「折叠哪个」这件事
   * 无法表达。
   *
   * @param text 累积的推理文本。流式期间不断追加
   * @param streaming 是否仍在生成。决定标题文案与进度指示器
   * @param expanded 展开状态。**存在数据里而不是 ViewHolder 里**——RecyclerView 会复用
   *   ViewHolder，把展开状态放在控件上会导致滚动后「展开的是另一条」
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
   * @param summary 折叠态展示的参数摘要（不含大段内容）
   * @param input 完整参数 JSON，展开后可见
   * @param output 工具输出；运行中为空
   * @param expanded 展开状态。**必须存在数据里而不是 ViewHolder 里**——
   *   RecyclerView 会复用 ViewHolder，把展开状态放在控件上会导致滚动后
   *   「展开的是另一条卡片」
   */
  data class ToolCall(
      override val id: Long,
      val toolName: String,
      val summary: String,
      val input: String,
      val output: String = "",
      val status: ToolStatus = ToolStatus.RUNNING,
      val expanded: Boolean = false,
  ) : Item()

  private val items = mutableListOf<Item>()
  private var nextId = 0L

  /**
   * 撤销按钮的点击回调；由视图层注入（回滚需要工作区上下文才能安全执行）。
   *
   * <p>同时传消息 id 与 diffId：调用方需要前者把该条标记为已撤销，后者去执行回滚。
   */
  private var onRevert: ((Long, String) -> Unit)? = null

  fun setOnRevertClickListener(listener: (Long, String) -> Unit) {
    this.onRevert = listener
  }

  // ---- 文本消息 ----

  /** 追加一条消息并返回它的 id；流式更新用该 id 定位。 */
  fun append(role: Role, text: String): Long = append(role, text, null)

  /** 追加一条可回滚的消息。 */
  fun append(role: Role, text: String, diffId: String?): Long {
    val id = nextId++
    items.add(Message(id, role, text, diffId))
    notifyItemInserted(items.size - 1)
    return id
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
    notifyItemChanged(index)
  }

  /**
   * 覆盖指定消息的正文。
   *
   * <p>id 不存在时静默忽略——消息可能已被清空（用户点了"新会话"），
   * 此时迟到的增量不该让界面崩掉。
   */
  fun update(id: Long, text: String) {
    val index = indexOf(id)
    val old = items.getOrNull(index) as? Message ?: return
    if (old.text == text) {
      return
    }
    items[index] = old.copy(text = text)
    notifyItemChanged(index)
  }

  /** 追加到指定消息的正文之后（增量累积用）。 */
  fun appendTo(id: Long, delta: String) {
    val index = indexOf(id)
    val old = items.getOrNull(index) as? Message ?: return
    items[index] = old.copy(text = old.text + delta)
    notifyItemChanged(index)
  }

  /** 按 id 取文本消息；不存在或不是消息时返回 null。 */
  fun find(id: Long): Message? = items.firstOrNull { it.id == id } as? Message

  /** 按 id 取工具卡片；不存在或不是卡片时返回 null。 */
  fun findToolCall(id: Long): ToolCall? = items.firstOrNull { it.id == id } as? ToolCall

  // ---- 工具卡片 ----

  /** 追加一张「运行中」的工具卡片并返回它的 id。 */
  fun appendToolCall(toolName: String, summary: String, input: String): Long {
    val id = nextId++
    items.add(ToolCall(id, toolName, summary, input))
    notifyItemInserted(items.size - 1)
    return id
  }

  /**
   * 回填工具执行结果。
   *
   * <p>id 不存在时静默忽略：用户可能已点了「新会话」清空列表，迟到的结果不该崩。
   */
  fun completeToolCall(id: Long, output: String, isError: Boolean) {
    val index = indexOf(id)
    val old = items.getOrNull(index) as? ToolCall ?: return
    items[index] =
        old.copy(
            output = output,
            status = if (isError) ToolStatus.FAILED else ToolStatus.DONE,
        )
    notifyItemChanged(index)
  }

  /** 切换卡片展开状态。 */
  fun toggleExpanded(id: Long) {
    val index = indexOf(id)
    val old = items.getOrNull(index) as? ToolCall ?: return
    items[index] = old.copy(expanded = !old.expanded)
    notifyItemChanged(index)
  }

  // ---- 思维链 ----

  /**
   * 追加一段推理增量，返回承载它的思维链块 id。
   *
   * <p>[lastThinkingId] 为 null 时新建一块。一次运行里的多轮推理会各建一块——
   * 因为中间隔着工具调用，合并成一块会让「这段推理属于哪一步」看不出来。
   */
  fun appendThinking(delta: String, lastThinkingId: Long?): Long {
    if (lastThinkingId != null) {
      val index = indexOf(lastThinkingId)
      val old = items.getOrNull(index) as? Thinking
      if (old != null) {
        items[index] = old.copy(text = old.text + delta)
        notifyItemChanged(index)
        return lastThinkingId
      }
    }
    val id = nextId++
    items.add(Thinking(id, delta, streaming = true, expanded = false))
    notifyItemInserted(items.size - 1)
    return id
  }

  /** 结束流式：标题从「思考中」切到「已完成思考」，进度指示器隐藏。 */
  fun finishThinking(id: Long) {
    val index = indexOf(id)
    val old = items.getOrNull(index) as? Thinking ?: return
    if (!old.streaming) {
      return
    }
    items[index] = old.copy(streaming = false)
    notifyItemChanged(index)
  }

  fun toggleThinkingExpanded(id: Long) {
    val index = indexOf(id)
    val old = items.getOrNull(index) as? Thinking ?: return
    items[index] = old.copy(expanded = !old.expanded)
    notifyItemChanged(index)
  }

  /** 把仍在流式的思维链块全部收尾（运行结束或失败时调用）。 */
  fun finishAllThinking() {
    for (i in items.indices) {
      val old = items[i] as? Thinking ?: continue
      if (old.streaming) {
        items[i] = old.copy(streaming = false)
        notifyItemChanged(i)
      }
    }
  }

  /** 把指定的工具卡片标记为失败（例如循环被取消时仍在运行的那张）。 */
  fun failToolCall(id: Long, reason: String) {
    val index = indexOf(id)
    val old = items.getOrNull(index) as? ToolCall ?: return
    if (old.status != ToolStatus.RUNNING) {
      return
    }
    items[index] = old.copy(status = ToolStatus.FAILED, output = reason)
    notifyItemChanged(index)
  }

  /** 仍在「运行中」的工具卡片 id；循环异常结束时用来收尾。 */
  fun runningToolCallIds(): List<Long> =
      items.filterIsInstance<ToolCall>().filter { it.status == ToolStatus.RUNNING }.map { it.id }

  /** 末条工具卡片的 id；用于把 TOOL_FINISHED 关联到对应的 TOOL_STARTED。 */
  fun lastToolCallId(): Long? = items.filterIsInstance<ToolCall>().lastOrNull()?.id

  // ---- 列表维护 ----

  fun clear() {
    val oldSize = items.size
    items.clear()
    if (oldSize > 0) {
      notifyItemRangeRemoved(0, oldSize)
    }
  }

  fun isEmpty(): Boolean = items.isEmpty()

  /** 全部项的只读快照，供调试与测试。 */
  fun snapshot(): List<Item> = items.toList()

  private fun indexOf(id: Long): Int = items.indexOfFirst { it.id == id }

  override fun getItemViewType(position: Int): Int =
      when (items[position]) {
        is Message -> TYPE_MESSAGE
        is ToolCall -> TYPE_TOOL_CALL
        is Thinking -> TYPE_THINKING
      }

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
    val inflater = LayoutInflater.from(parent.context)
    return when (viewType) {
      TYPE_TOOL_CALL -> ToolCallVH(ItemToolCallBinding.inflate(inflater, parent, false), this)
      TYPE_THINKING -> ThinkingVH(ItemAssistantThinkingBinding.inflate(inflater, parent, false), this)
      else -> MessageVH(ItemAssistantMessageBinding.inflate(inflater, parent, false))
    }
  }

  override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
    when (val item = items[position]) {
      is Message -> (holder as MessageVH).bind(item, onRevert)
      is ToolCall -> (holder as ToolCallVH).bind(item)
      is Thinking -> (holder as ThinkingVH).bind(item)
    }
  }

  override fun getItemCount(): Int = items.size

  /** 文本消息。 */
  class MessageVH(private val binding: ItemAssistantMessageBinding) :
      RecyclerView.ViewHolder(binding.root) {

    fun bind(message: Message, onRevert: ((Long, String) -> Unit)?) {
      val context = binding.root.context
      val card = binding.messageCard

      binding.messageRole.text =
          context.getString(
              when (message.role) {
                Role.USER -> string.ai_assistant_role_user
                Role.ASSISTANT -> string.ai_assistant_role_assistant
                Role.TRACE -> string.ai_assistant_role_trace
              }
          )

      // 只有助手正文走 Markdown。用户输入与过程日志保持纯文本：
      // 过程日志里大量出现路径与命令，渲染器会把下划线、星号当标记处理导致显示变形。
      if (message.role == Role.ASSISTANT && AssistantMarkdown.looksLikeMarkdown(message.text)) {
        AssistantMarkdown.render(binding.messageText, message.text)
      } else {
        binding.messageText.text = message.text
      }

      // 角色靠配色区分：用户用主色容器、助手用次级容器、过程信息用最低对比度。
      // 色值必须走 Material 库的 attr：本项目 app 模块的 R.attr 里没有这些主题属性。
      val (container, onContainer) =
          when (message.role) {
            Role.USER ->
                com.google.android.material.R.attr.colorPrimaryContainer to
                    com.google.android.material.R.attr.colorOnPrimaryContainer
            Role.ASSISTANT ->
                com.google.android.material.R.attr.colorSecondaryContainer to
                    com.google.android.material.R.attr.colorOnSecondaryContainer
            Role.TRACE ->
                com.google.android.material.R.attr.colorSurfaceContainerHighest to
                    com.google.android.material.R.attr.colorOnSurfaceVariant
          }

      card.setCardBackgroundColor(
          ColorStateList.valueOf(MaterialColors.getColor(card, container))
      )
      val textColor = MaterialColors.getColor(card, onContainer)
      binding.messageRole.setTextColor(textColor)
      binding.messageText.setTextColor(textColor)

      // 撤销按钮：只有携带 diffId 的消息才显示。已撤销时改为禁用并换文案——
      // 让按钮消失会让用户怀疑自己是否点到了，禁用态能明确传达「已经生效了」。
      val diffId = message.diffId
      if (diffId == null) {
        binding.messageRevert.visibility = View.GONE
      } else {
        binding.messageRevert.visibility = View.VISIBLE
        binding.messageRevert.isEnabled = !message.reverted
        binding.messageRevert.setText(
            if (message.reverted) string.ai_assistant_revert_done
            else string.ai_assistant_revert
        )
        binding.messageRevert.setOnClickListener { onRevert?.invoke(message.id, diffId) }
      }
    }
  }

  /** 工具调用卡片：折叠显示摘要，展开显示完整输入输出。 */
  class ToolCallVH(private val binding: ItemToolCallBinding, private val adapter: AssistantMessageAdapter) :
      RecyclerView.ViewHolder(binding.root) {

    fun bind(call: ToolCall) {
      val context = binding.root.context
      binding.toolName.text = call.toolName
      binding.toolSummary.text = call.summary

      binding.toolStatus.setText(
          when (call.status) {
            ToolStatus.RUNNING -> string.ai_assistant_tool_running
            ToolStatus.DONE -> string.ai_assistant_tool_done
            ToolStatus.FAILED -> string.ai_assistant_tool_failed_short
          }
      )
      // 失败用错误色，其余用次级色：用户需要一眼在长列表里找到出错的那一步。
      // colorError 是框架 attr（Material 库的 R.attr 里没有），且主题可能未定义它，
      // 因此取默认值 0 后回退到次级色——否则失败文本会变成透明的，等于没显示。
      val normalColor =
          MaterialColors.getColor(
              binding.root,
              com.google.android.material.R.attr.colorOnSurfaceVariant,
          )
      val errorColor = MaterialColors.getColor(binding.root, android.R.attr.colorError, 0)
      binding.toolStatus.setTextColor(
          if (call.status == ToolStatus.FAILED && errorColor != 0) errorColor else normalColor
      )

      binding.toolDetails.visibility = if (call.expanded) View.VISIBLE else View.GONE
      binding.toolChevron.text =
          context.getString(
              if (call.expanded) string.ai_assistant_tool_collapse
              else string.ai_assistant_tool_expand
          )

      if (call.expanded) {
        binding.toolInput.text = call.input.ifBlank { context.getString(string.ai_assistant_tool_empty) }
        binding.toolOutput.text =
            when {
              call.output.isNotBlank() -> call.output
              call.status == ToolStatus.RUNNING -> context.getString(string.ai_assistant_tool_running)
              else -> context.getString(string.ai_assistant_tool_empty)
            }
      }

      binding.toolCard.setOnClickListener { adapter.toggleExpanded(call.id) }
    }
  }

  /**
   * 思维链折叠块。
   *
   * <p>推理文本量大且通常只在等待期间有参考价值，因此默认折叠、限高 220dp。
   * 标题随 [Thinking.streaming] 切换文案——用户在等待时需要确认模型确实在工作，
   * 一个始终不变的标题会让人怀疑是不是卡住了。
   */
  class ThinkingVH(
      private val binding: ItemAssistantThinkingBinding,
      private val adapter: AssistantMessageAdapter,
  ) : RecyclerView.ViewHolder(binding.root) {

    fun bind(thinking: Thinking) {
      val context = binding.root.context
      binding.thinkingLabel.setText(
          if (thinking.streaming) string.ai_assistant_thinking_running
          else string.ai_assistant_thinking_done
      )
      binding.thinkingProgress.visibility =
          if (thinking.streaming) View.VISIBLE else View.GONE

      // 折叠态不写文本：推理可能几千字，即使不可见也会参与测量。
      binding.thinkingText.text = if (thinking.expanded) thinking.text else ""
      binding.thinkingScroll.visibility = if (thinking.expanded) View.VISIBLE else View.GONE
      binding.thinkingDivider.visibility = if (thinking.expanded) View.VISIBLE else View.GONE
      binding.thinkingChevron.text =
          context.getString(
              if (thinking.expanded) string.ai_assistant_tool_collapse
              else string.ai_assistant_tool_expand
          )

      // 整行可点：只有箭头可点会让折叠区很难命中。
      binding.thinkingHeader.setOnClickListener { adapter.toggleThinkingExpanded(thinking.id) }
    }
  }

  companion object {
    private const val TYPE_MESSAGE = 0
    private const val TYPE_TOOL_CALL = 1
    private const val TYPE_THINKING = 2
  }
}
