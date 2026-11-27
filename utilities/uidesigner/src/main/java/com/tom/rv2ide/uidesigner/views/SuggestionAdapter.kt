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
import com.google.android.material.textview.MaterialTextView
import com.tom.rv2ide.uidesigner.R
import com.tom.rv2ide.uidesigner.utils.ValueSuggestion
import kotlin.math.roundToInt

/**
 * 补全下拉的适配器。候选已由补全提供者过滤排序，这里的 [Filter] 原样返回全部条目，
 * 避免 AutoCompleteTextView 再按前缀筛一遍，把模糊匹配和 flag 拼接结果筛掉。
 */
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

  override fun getFilter(): Filter = object : Filter() {
    override fun performFiltering(constraint: CharSequence?) = FilterResults().apply {
      values = items
      count = items.size
    }

    override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
      if (items.isEmpty()) notifyDataSetInvalidated() else notifyDataSetChanged()
    }

    override fun convertResultToString(resultValue: Any?): CharSequence =
        (resultValue as? ValueSuggestion)?.text ?: resultValue?.toString().orEmpty()
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
      gravity = Gravity.END
    }

    init {
      gravity = Gravity.CENTER_VERTICAL
      val dp = resources.displayMetrics.density
      minimumHeight = (48 * dp).roundToInt()
      setPadding((16 * dp).roundToInt(), 0, (16 * dp).roundToInt(), 0)
      addView(text, LayoutParams(0, -2, 1f))
      addView(kind, LayoutParams(-2, -2).apply { marginStart = (12 * dp).roundToInt() })
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
