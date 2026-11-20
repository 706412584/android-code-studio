/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidCodeStudio is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.utils

import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.tom.rv2ide.R
import com.tom.rv2ide.actions.SidebarActionItem

/**
 * Data class representing a navigation item in the sidebar.
 *
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
 */
data class SidebarNavigationItem(
    val id: String,
    val icon: Drawable?,
    val title: String,
    val subtitle: String? = null,
    val isSelected: Boolean = false,
    val action: SidebarActionItem,
)

class SidebarNavigationAdapter(
    private val onItemClick: (SidebarNavigationItem) -> Unit,
    private val onItemLongClick: ((SidebarNavigationItem) -> Boolean)? = null,
) : ListAdapter<SidebarNavigationItem, SidebarNavigationAdapter.ViewHolder>(DiffCallback()) {

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
    val view =
        LayoutInflater.from(parent.context).inflate(R.layout.item_sidebar_navigation, parent, false)
    return ViewHolder(view)
  }

  override fun onBindViewHolder(holder: ViewHolder, position: Int) {
    holder.bind(getItem(position))
  }

  inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
    private val button: MaterialButton = itemView.findViewById(R.id.navigation_button)

    fun bind(item: SidebarNavigationItem) {
      button.apply {
        contentDescription = item.title
        isSelected = item.isSelected
        isChecked = item.isSelected
        backgroundTintList = ColorStateList.valueOf(
            if (item.isSelected) {
              com.google.android.material.color.MaterialColors.getColor(
                  this, com.google.android.material.R.attr.colorPrimaryContainer)
            } else {
              android.graphics.Color.TRANSPARENT
            })
        icon = item.icon?.let { it.constantState?.newDrawable()?.mutate() ?: it.mutate() }
        iconTint = ColorStateList.valueOf(
            com.google.android.material.color.MaterialColors.getColor(
                this,
                if (item.isSelected) com.google.android.material.R.attr.colorOnPrimaryContainer
                else com.google.android.material.R.attr.colorOnSurfaceVariant,
            ))

        setOnClickListener { onItemClick(item) }

        setOnLongClickListener(onItemLongClick?.let { handler ->
          View.OnLongClickListener { handler(item) }
        })
        isLongClickable = onItemLongClick != null
      }
    }
  }

  private class DiffCallback : DiffUtil.ItemCallback<SidebarNavigationItem>() {
    override fun areItemsTheSame(
        oldItem: SidebarNavigationItem,
        newItem: SidebarNavigationItem,
    ): Boolean = oldItem.id == newItem.id

    override fun areContentsTheSame(
        oldItem: SidebarNavigationItem,
        newItem: SidebarNavigationItem,
    ): Boolean = oldItem == newItem
  }
}
