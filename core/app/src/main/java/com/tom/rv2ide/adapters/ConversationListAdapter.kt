/*
 * This file is part of AndroidCodeStudio.
 *
 * AndroidCodeStudio is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AndroidCodeStudio is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tom.rv2ide.R
import com.tom.rv2ide.ai.agent.conversation.ConversationSummary
import com.tom.rv2ide.databinding.ItemAiConversationBinding
import com.tom.rv2ide.resources.R.string
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 会话列表。数据源是 [ConversationSummary]（由 `AgentOrchestrator.listConversations()` 产出）。
 *
 * <p><b>为什么用 [ListAdapter] + DiffUtil 而不是参考实现的手工 addView</b>：LCP 的
 * `DrawerView` 用「引用相等」做脏检查（`renderedConversations == nextConversations`），
 * 而上层每次 `listConversations()` 都返回**新 List**，照搬会变成每次全量重画——滚动位置
 * 丢失、列表闪烁。ListAdapter 按内容 diff，只更新真正变化的项。
 *
 * <p>时间显示与「当前会话」高亮都在 [bind] 里处理，不放在 ViewHolder 的构造里：
 * ViewHolder 会被复用，构造期只能设置一次的东西（比如点击监听）必须绑在 bind 上。
 */
class ConversationListAdapter(
    private val onOpen: (ConversationSummary) -> Unit,
    private val onDelete: (ConversationSummary) -> Unit,
) : ListAdapter<ConversationSummary, ConversationListAdapter.ViewHolder>(Diff()) {

  /** 当前打开的会话 id，用于高亮。 */
  private var activeId: String? = null

  /** 设置当前会话并刷新高亮。 */
  fun setActive(conversationId: String?) {
    if (activeId == conversationId) {
      return
    }
    val previous = activeId
    activeId = conversationId
    // 只刷新受影响的两项：全量 notifyDataSetChanged 会打断正在进行的滚动。
    currentList.forEachIndexed { index, summary ->
      if (summary.getId() == previous || summary.getId() == conversationId) {
        notifyItemChanged(index)
      }
    }
  }

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
      ViewHolder(
          ItemAiConversationBinding.inflate(LayoutInflater.from(parent.context), parent, false)
      )

  override fun onBindViewHolder(holder: ViewHolder, position: Int) {
    val summary = getItem(position)
    holder.bind(summary, summary.getId() == activeId, onOpen, onDelete)
  }

  class ViewHolder(private val binding: ItemAiConversationBinding) :
      RecyclerView.ViewHolder(binding.root) {

    fun bind(
        summary: ConversationSummary,
        isActive: Boolean,
        onOpen: (ConversationSummary) -> Unit,
        onDelete: (ConversationSummary) -> Unit,
    ) {
      val context = binding.root.context

      val title = summary.getTitle()
      binding.conversationTitle.text =
          if (title.isNullOrBlank()) context.getString(string.ai_conversation_untitled) else title

      // 消息数为 0 的会话是刚新建、还没发过消息的。此时不显示「0 条消息」——
      // 那个信息量为零，只会让标题行显得杂乱。
      val time = formatTime(summary.getModifiedAt())
      binding.conversationMeta.text =
          if (summary.getMessageCount() > 0) {
            context.getString(string.ai_conversation_meta, time, summary.getMessageCount())
          } else {
            time
          }

      // 当前会话用描边 + 主色标题区分。仅靠背景色在深色主题下差异不明显。
      //
      // 取色用主题 attr 而非固定色值：ACS 有多套主题（含深浅色），
      // 写死颜色会在部分主题下对比度不足或与整体不协调。
      val accent =
          com.google.android.material.color.MaterialColors.getColor(
              binding.root,
              com.google.android.material.R.attr.colorPrimaryContainer,
              COLOR_FALLBACK,
          )
      binding.conversationCard.strokeColor =
          if (isActive) accent
          else androidx.core.graphics.ColorUtils.setAlphaComponent(accent, 0x33)
      binding.conversationTitle.setTextColor(
          if (isActive) accent
          else com.google.android.material.color.MaterialColors.getColor(
              binding.root,
              com.google.android.material.R.attr.colorOnSurface,
          )
      )

      binding.conversationCard.setOnClickListener { onOpen(summary) }
      binding.conversationDelete.setOnClickListener { onDelete(summary) }
    }

    /**
     * 时间格式化。
     *
     * <p>同一天只显示时分（「今天」的概念对用户无意义，看到 14:32 自然知道是今天），
     * 跨天显示月/日 + 时分。不显示年份——会话列表里出现去年的会话是极少数，
     * 为它牺牲所有条目的可读性不值得。
     */
    private fun formatTime(timestamp: Long): String {
      if (timestamp <= 0L) {
        return ""
      }
      val now = Date()
      val target = Date(timestamp)
      val sameDay =
          SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(now) ==
              SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(target)
      val pattern = if (sameDay) "HH:mm" else "M/d HH:mm"
      return SimpleDateFormat(pattern, Locale.getDefault()).format(target)
    }
  }

  /**
   * 按 id 判断同一项；id 相同再比标题与时间，决定是否需要重绑。
   *
   * <p>只比 id 会让「重命名后标题不更新」，只比全字段又会让每次刷新都重绑。
   */
  private class Diff : DiffUtil.ItemCallback<ConversationSummary>() {
    override fun areItemsTheSame(
        oldItem: ConversationSummary,
        newItem: ConversationSummary,
    ): Boolean = oldItem.getId() == newItem.getId()

    override fun areContentsTheSame(
        oldItem: ConversationSummary,
        newItem: ConversationSummary,
    ): Boolean =
        oldItem.getTitle() == newItem.getTitle() &&
            oldItem.getModifiedAt() == newItem.getModifiedAt() &&
            oldItem.getMessageCount() == newItem.getMessageCount()
  }

  companion object {
    /** 主题未定义 colorPrimaryContainer 时的兜底色。 */
    private const val COLOR_FALLBACK = 0xFF7AA2F7.toInt()
  }
}
