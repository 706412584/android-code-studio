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

package com.tom.rv2ide.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceGroupAdapter
import androidx.preference.PreferenceScreen
import androidx.recyclerview.widget.RecyclerView
import androidx.preference.PreferenceViewHolder
import com.tom.rv2ide.R as AppR
import com.google.android.material.transition.MaterialSharedAxis
import com.tom.rv2ide.preferences.IPreference
import com.tom.rv2ide.preferences.IPreferenceGroup
import com.tom.rv2ide.preferences.IPreferenceScreen
import com.tom.rv2ide.preferences.observers.LSPStateObserver

class IDEPreferencesFragment : BasePreferenceFragment() {

  private var children: List<IPreference> = emptyList()

  private val serverStateListener = {
    refreshPreferences()
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    
    enterTransition = MaterialSharedAxis(MaterialSharedAxis.Z, true).apply {
        duration = 600
    }
    returnTransition = MaterialSharedAxis(MaterialSharedAxis.Z, false).apply {
        duration = 600
    }
    exitTransition = MaterialSharedAxis(MaterialSharedAxis.Z, true).apply {
        duration = 600
    }
    reenterTransition = MaterialSharedAxis(MaterialSharedAxis.Z, false).apply {
        duration = 600
    }
  }

  override fun onCreateView(
      inflater: LayoutInflater,
      container: ViewGroup?,
      savedInstanceState: Bundle?,
  ): View {
    return super.onCreateView(inflater, container, savedInstanceState).apply {
      setPadding(0, resources.getDimensionPixelSize(AppR.dimen.acs_ui_space_sm), 0, 0)
      listView?.apply {
        clipToPadding = false
        setPadding(0, 0, 0, resources.getDimensionPixelSize(AppR.dimen.acs_ui_space_lg))
      }
      setDivider(null)
    }
  }

  override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
    super.onCreatePreferences(savedInstanceState, rootKey)

    if (context == null) {
      return
    }

    @Suppress("DEPRECATION")
    this.children = arguments?.getParcelableArrayList(EXTRA_CHILDREN) ?: emptyList()

    preferenceScreen.removeAll()
    addChildren(this.children, preferenceScreen)
  }

  override fun onResume() {
    super.onResume()
    LSPStateObserver.addListener(serverStateListener)
    // 从服务商/MCP 管理页返回：那边改过数据就重建一次，让摘要里的计数跟上。
    // 只在真的改过时重建，避免每次返回都重置滚动位置。
    if (com.tom.rv2ide.artificial.agent.manage.ManageChangeSignal.consume()) {
      refreshPreferences()
    }
    refreshAppearanceSummaries()
  }

  private fun refreshAppearanceSummaries() {
    val theme = getString(com.tom.rv2ide.ui.themes.IThemeManager.getInstance().getCurrentTheme().title)
    val mode = getString(when (com.tom.rv2ide.preferences.internal.GeneralPreferences.uiMode) {
      androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO -> AppR.string.uiMode_light
      androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES -> AppR.string.uiMode_dark
      else -> AppR.string.uiMode_system
    })
    findPreference<androidx.preference.Preference>("idepref_general")?.summary =
        getString(AppR.string.acs_ui_settings_appearance, theme, mode)
    findPreference<androidx.preference.Preference>(
        com.tom.rv2ide.preferences.internal.GeneralPreferences.SELECTED_THEME)?.summary = theme
    findPreference<androidx.preference.Preference>(
        com.tom.rv2ide.preferences.internal.GeneralPreferences.UI_MODE)?.summary = mode
  }

  override fun onPause() {
    super.onPause()
    LSPStateObserver.removeListener(serverStateListener)
  }

  private fun refreshPreferences() {
    if (context == null) {
      return
    }

    preferenceScreen.removeAll()
    addChildren(this.children, preferenceScreen)
  }

  override fun onCreateAdapter(preferenceScreen: PreferenceScreen): RecyclerView.Adapter<*> {
    return object : PreferenceGroupAdapter(preferenceScreen) {
      override fun onBindViewHolder(holder: PreferenceViewHolder, position: Int) {
        super.onBindViewHolder(holder, position)
        val preference = getItem(position)
        val isCategory = preference is PreferenceCategory
        holder.isDividerAllowedAbove = false
        holder.isDividerAllowedBelow = false
        holder.itemView.apply {
          if (isCategory) {
            background = null
            minimumHeight = resources.getDimensionPixelSize(AppR.dimen.acs_ui_touch_target)
            setPaddingRelative(
                resources.getDimensionPixelSize(AppR.dimen.acs_ui_space_xl),
                resources.getDimensionPixelSize(AppR.dimen.acs_ui_space_lg),
                resources.getDimensionPixelSize(AppR.dimen.acs_ui_space_xl),
                resources.getDimensionPixelSize(AppR.dimen.acs_ui_space_sm),
            )
          } else {
            if (children.any { child ->
                  child is IPreferenceScreen ||
                      (child is IPreferenceGroup && child.children.any { it is IPreferenceScreen })
                }) {
              setBackgroundResource(AppR.drawable.acs_ui_preference_background)
            } else {
              val backgroundValue = android.util.TypedValue()
              context.theme.resolveAttribute(android.R.attr.selectableItemBackground, backgroundValue, true)
              setBackgroundResource(backgroundValue.resourceId)
            }
            minimumHeight = resources.getDimensionPixelSize(AppR.dimen.acs_ui_preference_min_height)
            setPaddingRelative(
                resources.getDimensionPixelSize(AppR.dimen.acs_ui_space_xl),
                resources.getDimensionPixelSize(AppR.dimen.acs_ui_space_md),
                resources.getDimensionPixelSize(AppR.dimen.acs_ui_space_xl),
                resources.getDimensionPixelSize(AppR.dimen.acs_ui_space_md),
            )
          }
        }
        (holder.findViewById(android.R.id.title) as? android.widget.TextView)?.apply {
          setTextAppearance(
              if (isCategory) AppR.style.acs_ui_text_caption else AppR.style.acs_ui_text_body)
          setTypeface(
              typeface,
              if (isCategory) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL,
          )
          isSingleLine = false
          maxLines = Int.MAX_VALUE
        }
        (holder.findViewById(android.R.id.summary) as? android.widget.TextView)
            ?.setTextAppearance(AppR.style.acs_ui_text_caption)
      }
    }
  }

  private fun addChildren(children: List<IPreference>, pref: PreferenceGroup) {
    for (child in children) {
      val preference = child.onCreateView(requireContext())
      if (child is IPreferenceScreen) {
        preference.fragment = IDEPreferencesFragment::class.java.name
        preference.extras.putParcelableArrayList(EXTRA_CHILDREN, ArrayList(child.children))
        pref.addPreference(preference)
        continue
      }

      if (child is IPreferenceGroup) {
        pref.addPreference(preference as PreferenceCategory)
        addChildren(child.children, preference)
        continue
      }

      pref.addPreference(preference)
    }
  }

  companion object {
    const val EXTRA_CHILDREN = "ide.preferences.fragment.children"
  }
}