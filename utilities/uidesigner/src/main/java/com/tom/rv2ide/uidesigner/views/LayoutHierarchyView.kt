/*
 * This file is part of AndroidIDE and AndroidCodeStudio.
 * Licensed under the GNU General Public License, version 3 or any later version.
 */
package com.tom.rv2ide.uidesigner.views

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Rect
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatImageButton
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.view.doOnPreDraw
import androidx.core.view.isVisible
import androidx.core.view.updatePaddingRelative
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textview.MaterialTextView
import com.tom.rv2ide.uidesigner.R
import com.tom.rv2ide.uidesigner.models.LayoutDocument
import com.tom.rv2ide.uidesigner.utils.Widgets
import com.tom.rv2ide.uidesigner.viewmodel.WorkspaceSnapshot
import kotlin.math.roundToInt

/** 树行只显示状态；选中项来自工作区，而不是 RecyclerView 的位置。 */
internal class LayoutHierarchyView(
    context: Context,
    private val collapsed: MutableSet<Long>,
    private val select: (Long) -> Unit,
) : LinearLayout(context) {
  private val search = TextInputEditText(context).apply {
    isSingleLine = true
    setTextAppearance(R.style.TextAppearance_Material3_BodyMedium)
  }
  private val list = RecyclerView(context).apply {
    layoutManager = LinearLayoutManager(context)
    itemAnimator = null
    clipToPadding = false
    // 抽屉整体滚动，树按内容高度展开。
    isNestedScrollingEnabled = false
  }
  private val empty = MaterialTextView(context).apply {
    setText(R.string.designer_outline_empty)
    setPadding(dp(12), dp(12), dp(12), dp(12))
    setTextAppearance(R.style.TextAppearance_Material3_BodyMedium)
  }
  private var snapshot: WorkspaceSnapshot? = null
  private var isUnrendered: (LayoutDocument.Node) -> Boolean = { false }
  private val adapter = TreeAdapter(select) { id ->
    if (!collapsed.add(id)) collapsed.remove(id)
    submit()
  }

  init {
    orientation = VERTICAL
    updatePaddingRelative(start = dp(8), end = dp(8), bottom = dp(8))
    addView(TextInputLayout(context).apply {
      hint = context.getString(R.string.designer_outline_search)
      endIconMode = TextInputLayout.END_ICON_CLEAR_TEXT
      addView(search, LayoutParams(-1, -2))
    }, LayoutParams(-1, -2))
    addView(empty, LayoutParams(-1, -2))
    addView(list, LayoutParams(-1, -2))
    list.adapter = adapter
    search.doAfterTextChanged { submit() }
  }

  fun bind(state: WorkspaceSnapshot, unrendered: (LayoutDocument.Node) -> Boolean) {
    val changed = snapshot?.selectedId != state.selectedId || snapshot?.document !== state.document
    snapshot = state
    isUnrendered = unrendered
    if (changed) {
      val selected = state.document.node(state.selectedId)
      selected?.path?.let { path ->
        for (depth in path.indices) state.document.at(path.take(depth))?.let { collapsed.remove(it.id) }
      }
      if (selected != null && !matches(selected, search.text.toString().trim())) search.setText("")
    }
    submit(revealSelection = changed && state.selectedId != state.document.root?.id)
  }

  private fun submit(revealSelection: Boolean = false) {
    val state = snapshot ?: return
    val query = search.text.toString().trim()
    val rows = ArrayList<TreeRow>()
    val document = state.document
    val visible = if (query.isEmpty()) null else buildSet<List<Int>> {
      for (node in document.nodes) if (matches(node, query)) {
        for (depth in 0..node.path.size) add(node.path.take(depth))
      }
    }
    fun visit(node: LayoutDocument.Node) {
      if (visible != null && node.path !in visible) return
      val children = document.children(node)
      val expandable = visible == null && children.isNotEmpty()
      val expanded = node.id !in collapsed || visible != null
      rows.add(TreeRow(node.id, Widgets.displayName(context, node), node.tag, node.xmlId, node.path.size, expandable, expanded,
          node.id == state.selectedId, isUnrendered(node), children.isNotEmpty() || node.tag == "merge"))
      if (expanded) children.forEach(::visit)
    }
    document.root?.let(::visit)
    empty.isVisible = rows.isEmpty()
    adapter.submitList(rows) {
      val id = state.selectedId ?: return@submitList
      if (revealSelection && isShown) list.doOnPreDraw {
        if (snapshot?.selectedId != id) return@doOnPreDraw
        val row = list.findViewHolderForItemId(id)?.itemView ?: return@doOnPreDraw
        row.requestRectangleOnScreen(Rect(0, 0, row.width, row.height), true)
      }
    }
  }

  private fun matches(node: LayoutDocument.Node, query: String) =
      node.tag.contains(query, ignoreCase = true) || node.xmlId?.contains(query, ignoreCase = true) == true ||
          Widgets.displayName(context, node).contains(query, ignoreCase = true)

  private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

  private data class TreeRow(
      val id: Long, val title: String, val tag: String, val xmlId: String?, val depth: Int, val expandable: Boolean,
      val expanded: Boolean, val selected: Boolean, val unrendered: Boolean, val group: Boolean,
  )

  private inner class TreeAdapter(
      private val onSelect: (Long) -> Unit,
      private val onToggle: (Long) -> Unit,
  ) : ListAdapter<TreeRow, TreeAdapter.Holder>(object : DiffUtil.ItemCallback<TreeRow>() {
    override fun areItemsTheSame(old: TreeRow, new: TreeRow) = old.id == new.id
    override fun areContentsTheSame(old: TreeRow, new: TreeRow) = old == new
  }) {
    init { setHasStableIds(true) }
    override fun getItemId(position: Int) = getItem(position).id
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(LinearLayout(parent.context))
    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

    inner class Holder(private val row: LinearLayout) : RecyclerView.ViewHolder(row) {
      private val chevron = AppCompatImageButton(context).apply {
        setImageResource(R.drawable.ic_chevron_right)
        setColorFilter(MaterialColors.getColor(this, R.attr.colorOnSurfaceVariant))
        val style = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
        setBackgroundResource(style.getResourceId(0, 0))
        style.recycle()
      }
      private val icon = AppCompatImageView(context).apply {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
      }
      private val tagText = MaterialTextView(context).apply {
        setTextAppearance(R.style.TextAppearance_Material3_BodyMedium)
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.MIDDLE
      }
      private val detail = MaterialTextView(context).apply {
        setTextAppearance(R.style.TextAppearance_Material3_BodySmall)
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
      }
      private val highlight = MaterialShapeDrawable(ShapeAppearanceModel.builder().setAllCornerSizes(dp(12).toFloat()).build())
      private val label = LinearLayout(context).apply {
        orientation = VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        updatePaddingRelative(start = dp(8), end = dp(6), top = dp(4), bottom = dp(4))
        addView(tagText, LayoutParams(-1, -2))
        addView(detail, LayoutParams(-1, -2))
      }
      init {
        row.layoutParams = RecyclerView.LayoutParams(-1, -2)
        row.gravity = Gravity.CENTER_VERTICAL
        row.minimumHeight = dp(48)
        row.addView(chevron, LayoutParams(dp(44), dp(48)))
        row.addView(icon, LayoutParams(dp(18), dp(18)))
        row.addView(label, LayoutParams(0, -2, 1f))
        row.background = highlight
        val style = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
        row.foreground = style.getDrawable(0)
        style.recycle()
        row.setOnClickListener { if (bindingAdapterPosition != RecyclerView.NO_POSITION) onSelect(getItem(bindingAdapterPosition).id) }
        chevron.setOnClickListener { if (bindingAdapterPosition != RecyclerView.NO_POSITION) onToggle(getItem(bindingAdapterPosition).id) }
      }

      fun bind(item: TreeRow) {
        row.isSelected = item.selected
        row.updatePaddingRelative(start = dp(10 * item.depth.coerceAtMost(5)))
        val color = MaterialColors.getColor(row, if (item.selected) R.attr.colorOnSecondaryContainer else R.attr.colorOnSurface)
        highlight.fillColor = ColorStateList.valueOf(if (item.selected) MaterialColors.getColor(row, R.attr.colorSecondaryContainer) else 0)
        chevron.visibility = if (item.expandable) VISIBLE else INVISIBLE
        chevron.rotation = if (item.expanded) 90f else if (layoutDirection == LAYOUT_DIRECTION_RTL) 180f else 0f
        chevron.contentDescription = context.getString(if (item.expanded) R.string.designer_collapse else R.string.designer_expand, item.title)
        icon.setImageResource(if (item.group) R.drawable.ic_tree else R.drawable.ic_widget_view)
        icon.setColorFilter(color)
        tagText.text = item.title
        tagText.setTextColor(color)
        val simpleTag = item.tag.substringAfterLast('.')
        detail.text = listOfNotNull(simpleTag.takeIf { it != item.title }, item.xmlId,
            context.getString(R.string.designer_node_unrendered).takeIf { item.unrendered }).joinToString(" · ")
        detail.isVisible = detail.text.isNotEmpty()
        detail.setTextColor(MaterialColors.getColor(row, if (item.unrendered) R.attr.colorError else R.attr.colorOnSurfaceVariant))
      }
    }
  }
}
