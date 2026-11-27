/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.uidesigner.utils

import android.content.Context
import com.tom.rv2ide.annotations.uidesigner.IncludeInDesigner.Group.GOOGLE
import com.tom.rv2ide.annotations.uidesigner.IncludeInDesigner.Group.LAYOUTS
import com.tom.rv2ide.annotations.uidesigner.IncludeInDesigner.Group.WIDGETS
import com.tom.rv2ide.inflater.IViewAdapterIndex
import com.tom.rv2ide.inflater.internal.utils.simpleName
import com.tom.rv2ide.uidesigner.R.string
import com.tom.rv2ide.uidesigner.models.LayoutDocument
import com.tom.rv2ide.uidesigner.models.UiWidgetCategory

internal object Widgets {

  @JvmField val CATEGORY_WIDGETS = UiWidgetCategory(string.ui_category_widgets)
  @JvmField val CATEGORY_LAYOUTS = UiWidgetCategory(string.ui_category_layouts)
  @JvmField val CATEGORY_GOOGLE = UiWidgetCategory(string.ui_category_google)

  private val internalCategories = mutableListOf<UiWidgetCategory>()

  val categories: List<UiWidgetCategory>
    get() = this.internalCategories

  init {
    internalCategories.add(getLayouts())
    internalCategories.add(getWidgets())
    internalCategories.add(getGoogle())
  }

  private val labels by lazy {
    categories.flatMap { it.widgets }.groupBy { it.name }.mapValues { it.value.first().label }
  }

  /** 复用控件库的本地化名称；未登记的标签保留原名。 */
  fun displayName(context: Context, node: LayoutDocument.Node): String {
    val simple = node.tag.substringAfterLast('.')
    when (node.tag) {
      "include" -> return context.getString(string.designer_tag_include)
      "merge" -> return context.getString(string.designer_tag_merge)
    }
    if (simple == "LinearLayout") {
      val vertical = node.attrs.any { (key, value) ->
        key.uri == LayoutDocument.ANDROID && key.name == "orientation" && value.trim() == "vertical"
      }
      return context.getString(if (vertical) string.widget_linear_layout_vert else string.widget_linear_layout_horz)
    }
    val label = labels[node.tag]
        ?: if ('.' in node.tag) null else labels.entries.firstOrNull { it.key.substringAfterLast('.') == simple }?.value
    return label?.let { context.getString(it).removePrefix("\uD83C\uDD95").trim() } ?: simple
  }

  fun displayLabel(context: Context, node: LayoutDocument.Node): String =
      displayName(context, node) + (node.xmlId?.let { " · ${it.substringAfter('/')}" } ?: "")

  private fun getWidgets(): UiWidgetCategory {
    return CATEGORY_WIDGETS.apply {
      if (widgets.isNotEmpty()) {
        return@apply
      }
      this.widgets =
          IViewAdapterIndex.instance
              .getWidgetProviders(WIDGETS)
              ?.flatMap { it.getUiWidgets() }
              ?.sortedBy { it.name.simpleName() } ?: emptyList()
    }
  }

  private fun getGoogle(): UiWidgetCategory {
    return CATEGORY_GOOGLE.apply {
      if (widgets.isNotEmpty()) {
        return@apply
      }
      this.widgets =
          IViewAdapterIndex.instance
              .getWidgetProviders(GOOGLE)
              ?.flatMap { it.getUiWidgets() }
              ?.sortedBy { it.name.simpleName() } ?: emptyList()
    }
  }

  private fun getLayouts(): UiWidgetCategory {
    return CATEGORY_LAYOUTS.apply {
      if (widgets.isNotEmpty()) {
        return@apply
      }

      this.widgets =
          IViewAdapterIndex.instance
              .getWidgetProviders(LAYOUTS)
              ?.flatMap { it.getUiWidgets() }
              ?.sortedBy { it.name.simpleName() } ?: emptyList()
    }
  }
}
