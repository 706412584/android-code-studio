/*
 * This file is part of AndroidIDE and AndroidCodeStudio.
 * Licensed under the GNU General Public License, version 3 or any later version.
 */
package com.tom.rv2ide.uidesigner.views

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Filter
import android.widget.Filterable
import android.widget.LinearLayout
import com.google.android.material.color.MaterialColors
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textview.MaterialTextView
import com.tom.rv2ide.uidesigner.R
import com.tom.rv2ide.uidesigner.utils.ValueSuggestion
import kotlin.math.roundToInt

/** 名称与值的结果均由面板发布，不让平台旧的异步 Filter 回调重开已收起的列表。 */
internal class SuggestionInput(context: Context) : MaterialAutoCompleteTextView(context) {
  init {
    isSingleLine = true
    threshold = 1
  }

  override fun enoughToFilter() = true // 空输入也由提供者决定是否展示候选。
  override fun performFiltering(text: CharSequence?, keyCode: Int) = Unit
  override fun convertSelectionToString(selectedItem: Any?): CharSequence =
      (selectedItem as? ValueSuggestion)?.text ?: super.convertSelectionToString(selectedItem)
}
internal class SuggestionAdapter(private val context: Context, private val items: List<ValueSuggestion>) :
    BaseAdapter(), Filterable {

  override fun getCount() = items.size
  override fun getItem(position: Int) = items[position]
  override fun getItemId(position: Int) = position.toLong()

  override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
    val row = convertView as? Row ?: Row(context)
    row.bind(items[position])
    return row
  }

  // AutoCompleteTextView 要求 Filterable，但本适配器只展示不可变的已筛选结果。
  override fun getFilter() = noFilter

  private companion object {
    val noFilter = object : Filter() {
      override fun performFiltering(constraint: CharSequence?) = FilterResults()
      override fun publishResults(constraint: CharSequence?, results: FilterResults?) = Unit
    }
  }

  private class Row(context: Context) : LinearLayout(context) {
    private val text = MaterialTextView(context).apply {
      setTextAppearance(R.style.TextAppearance_Material3_BodyMedium)
      isSingleLine = true
      ellipsize = TextUtils.TruncateAt.MIDDLE
    }
    private val kind = MaterialTextView(context).apply {
      setTextAppearance(R.style.TextAppearance_Material3_LabelSmall)
      setTextColor(MaterialColors.getColor(this, R.attr.colorOnSurfaceVariant))
      isSingleLine = true
      ellipsize = TextUtils.TruncateAt.END
    }

    init {
      orientation = VERTICAL
      gravity = Gravity.CENTER_VERTICAL or Gravity.START
      val dp = resources.displayMetrics.density
      minimumHeight = (48 * dp).roundToInt()
      setPaddingRelative((12 * dp).roundToInt(), (4 * dp).roundToInt(), (12 * dp).roundToInt(), (4 * dp).roundToInt())
      addView(text, LayoutParams(-1, -2))
      addView(kind, LayoutParams(-1, -2))
    }

    fun bind(item: ValueSuggestion) {
      text.text = item.text
      // 来源包只在不是本应用时显示，避免每行都重复同一个包名。
      val source = item.detail.takeIf { it == "android" }
      kind.text = listOfNotNull(item.kind.takeIf { it.isNotEmpty() }?.lowercase(), source).joinToString(" · ")
      kind.visibility = if (kind.text.isNullOrEmpty()) GONE else VISIBLE
    }
  }
}
