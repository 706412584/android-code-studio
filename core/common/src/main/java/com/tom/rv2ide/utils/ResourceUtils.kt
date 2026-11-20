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

package com.tom.rv2ide.utils

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources.Theme
import android.util.TypedValue
import androidx.annotation.AttrRes
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat

fun Context.isSystemInDarkMode(): Boolean {
  return this.resources.configuration.isSystemInDarkMode()
}

fun Configuration.isSystemInDarkMode(): Boolean {
  return (uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
}

/** Resolve a themed color without treating a missing/non-color attribute as transparent black. */
@ColorInt
fun Context.resolveColorAttrOrNull(@AttrRes id: Int): Int? {
  val value = TypedValue()
  if (!theme.resolveAttribute(id, value, true)) return null
  if (value.type in TypedValue.TYPE_FIRST_COLOR_INT..TypedValue.TYPE_LAST_COLOR_INT) {
    return value.data
  }
  return if (value.resourceId != 0) {
    runCatching { ContextCompat.getColor(this, value.resourceId) }.getOrNull()
  } else {
    null
  }
}

@JvmOverloads
fun Context.resolveAttr(id: Int, resolveRefs: Boolean = true): Int {
  return theme.resolveAttr(id, resolveRefs)
}

@JvmOverloads
fun Theme.resolveAttr(id: Int, resolveRefs: Boolean = true): Int =
    TypedValue().let {
      resolveAttribute(id, it, resolveRefs)
      it.data
    }
