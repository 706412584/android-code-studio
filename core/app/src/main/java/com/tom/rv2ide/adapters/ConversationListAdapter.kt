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
import androidx.core.view.isVisible
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

  /**
   * 当前工作区绝对路径，用于判断每条会话是否属于当前项目。
   *
   * <p>null / 空串表示「不知道当前项目」（如未打开任何项目），此时不做归属判断，
   * 一律按中性样式显示——不能因为拿不到工作区就把所有会话都标成「其他项目」。
   */
  private var currentCwd: String? = null

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

  /**
   * 设置当前工作区并刷新归属标注。
   *
   * <p>归属变化会影响**每一条**（当前项目的从「其他」变「本项目」，反之亦然），
   * 所以这里全量刷新。它只在切换项目时发生，频率远低于滚动，不会打断交互。
   */
  fun setCurrentCwd(cwd: String?) {
    if (currentCwd == cwd) {
      return
    }
    currentCwd = cwd
    notifyItemRangeChanged(0, itemCount)
  }

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
      ViewHolder(
          ItemAiConversationBinding.inflate(LayoutInflater.from(parent.context), parent, false)
      )

  override fun onBindViewHolder(holder: ViewHolder, position: Int) {
    val summary = getItem(position)
    holder.bind(summary, summary.getId() == activeId, currentCwd, onOpen, onDelete)
  }

  class ViewHolder(private val binding: ItemAiConversationBinding) :
      RecyclerView.ViewHolder(binding.root) {

    fun bind(
        summary: ConversationSummary,
        isActive: Boolean,
        currentCwd: String?,
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

      bindProjectRow(summary, currentCwd, context)

      binding.conversationCard.setOnClickListener { onOpen(summary) }
      binding.conversationDelete.setOnClickListener { onDelete(summary) }
    }

    /**
     * 渲染「这条会话属于哪个项目」。
     *
     * <p>三种情况分开处理，不能混为一谈：
     * - 会话没记 cwd（老会话、或创建时未打开项目）→ 显示「未知项目」，中性色。
     * - 当前也没有打开项目（[currentCwd] 为空）→ 无从比较，按中性色显示项目名，
     *   不谎报「其他项目」。
     * - 会话 cwd 与当前项目不同 → 加「⚠」前缀并用 error 色：这是用户最需要
     *   提前知道的情况（点开会把历史与当前项目错配）。
     *
     * <p>取色一律走主题 attr，理由同上面的强调色——ACS 有多套主题。
     */
    private fun bindProjectRow(
        summary: ConversationSummary,
        currentCwd: String?,
        context: android.content.Context,
    ) {
      val row = binding.conversationProjectRow
      val label = binding.conversationProject
      val cwd = summary.getCwd()

      if (cwd.isBlank()) {
        row.isVisible = true
        label.text = context.getString(string.ai_conversation_project_unknown)
        label.setTextColor(onSurfaceVariant(context))
        return
      }

      val name = projectNameOf(cwd)
      val known = !currentCwd.isNullOrBlank()
      val foreign = known && currentCwd != cwd

      row.isVisible = true
      if (foreign) {
        label.text = "⚠ " + name
        // colorError 是框架 attr（Material 库的 R.attr 里没有它）。取默认值 0 表示
        // 主题未定义，此时回退到次级色——绝不能把文字设成透明，那等于信息没显示。
        val errorColor =
            com.google.android.material.color.MaterialColors.getColor(
                binding.root, android.R.attr.colorError, 0)
        label.setTextColor(if (errorColor != 0) errorColor else onSurfaceVariant(context))
        // 无障碍：图标被标为 no，颜色又不传达信息给读屏，必须写进 contentDescription。
        label.contentDescription = context.getString(string.ai_conversation_project_other)
      } else {
        label.text = name
        label.setTextColor(onSurfaceVariant(context))
        label.contentDescription = null
      }
    }

    private fun onSurfaceVariant(context: android.content.Context): Int =
        com.google.android.material.color.MaterialColors.getColor(
            binding.root,
            com.google.android.material.R.attr.colorOnSurfaceVariant,
            COLOR_FALLBACK,
        )

    /**
     * 取路径最后一段作为项目名。
     *
     * <p>只显示 basename 而不是完整路径：抽屉只有 240dp 宽，完整路径（
     * `/data/data/com.tom.rv2ide/files/home/ACSProjects/MyGameActivity`）
     * 会把这一行撑成一串省略号。完整路径放在标题栏的 tooltip 里。
     *
     * <p>末尾有分隔符时先剥掉，否则 `.../MyGameActivity/` 会取到空串。
     */
    private fun projectNameOf(cwd: String): String {
      val trimmed = cwd.trimEnd('/', '\\')
      val cut = trimmed.lastIndexOfAny(charArrayOf('/', '\\'))
      val name = if (cut >= 0) trimmed.substring(cut + 1) else trimmed
      return name.ifBlank { trimmed.ifBlank { cwd } }
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
