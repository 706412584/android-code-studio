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

package com.tom.rv2ide.xml.versions

/**
 * A model class to hold the API version information.
 *
 * **Dev Note**: This class must be immutable as the instances of this class are reused for multiple
 * symbols.
 *
 * Recent Android SDKs express API levels as `major.minor` (for example `36.1` or `37.2`) while
 * older ones use a plain integer. The `*Minor` properties carry the fractional part; they are
 * [NONE] when the SDK only specified an integer, or when the minor component is zero.
 *
 * @property since The API in which the symbol was added.
 * @property deprecatedIn The API in which the symbol was deprecated.
 * @property removedIn The API in which the symbol was removed.
 * @property sinceMinor The minor component of [since], e.g. `1` for API `36.1`.
 * @property deprecatedMinor The minor component of [deprecatedIn].
 * @property removedMinor The minor component of [removedIn].
 * @author Akash Yadav
 */
data class ApiVersion(
    val since: Int,
    val deprecatedIn: Int = NONE,
    val removedIn: Int = NONE,
    val sinceMinor: Int = NONE,
    val deprecatedMinor: Int = NONE,
    val removedMinor: Int = NONE,
) {

  /** Returns `true` if [since] is 1 and [deprecatedIn] and [removedIn] is [ApiVersion.NONE]. */
  fun isSinceInception(): Boolean = since == 1 && deprecatedIn == NONE && removedIn == NONE

  /** Formats [since] for display, e.g. `36` or `36.1`. */
  fun formatSince(): String = formatLevel(since, sinceMinor)

  /** Formats [deprecatedIn] for display, e.g. `36` or `36.1`. */
  fun formatDeprecatedIn(): String = formatLevel(deprecatedIn, deprecatedMinor)

  /** Formats [removedIn] for display, e.g. `36` or `36.1`. */
  fun formatRemovedIn(): String = formatLevel(removedIn, removedMinor)

  private fun formatLevel(major: Int, minor: Int): String =
      if (minor > NONE) "$major.$minor" else major.toString()

  companion object {
    internal const val NONE = 0
  }
}
