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

package com.tom.rv2ide.preferences

import android.content.Context
import androidx.preference.Preference
import androidx.preference.PreferenceCategory

/**
 * A group of preferences.
 *
 * @author Akash Yadav
 */
abstract class IPreferenceGroup : BasePreference() {

  /** The preferences. */
  abstract val children: List<IPreference>

  /**
   * Adds the given preference to the preferences list.
   *
   * This is **idempotent by [IPreference.key]**: adding a preference whose key already exists in
   * [children] is a no-op.
   *
   * Why: the setting screens are `@Parcelize` classes that append their entries from an `init`
   * block, while their `children` list is serialized across the Activity↔Fragment boundary (see
   * `PreferencesActivity.EXTRA_DIRECT_CHILDREN` and `IDEPreferencesFragment.EXTRA_CHILDREN`).
   * `@Parcelize` reconstructs a screen through its primary constructor, so `init` runs again on
   * every parcel round-trip and would append a second copy onto the already-restored children.
   * Deduplicating here keeps the append-from-`init` shape working while making repeated
   * construction harmless.
   *
   * Note: keys are expected to be unique among the siblings of one group. There is currently no
   * call site that intentionally registers two children with the same key (the only deliberate
   * duplicate in the tree — `PreviewDataPreferences` and `StatPreferencesScreen` both using
   * `"idepref_privacy"` — are not siblings).
   */
  fun addPreference(preference: IPreference) {
    if (children.any { it.key == preference.key }) {
      return
    }
    (children as MutableList).add(preference)
  }

  /** Removes the given preference. */
  fun removePreference(preference: IPreference) {
    (children as MutableList).remove(preference)
  }

  /** Removes the preference at the given index. */
  fun removePreference(index: Int) {
    (children as MutableList).removeAt(index)
  }

  override fun onCreatePreference(context: Context): Preference {
    return PreferenceCategory(context)
  }
}
