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
package com.tom.rv2ide.inflater.drawable

import android.content.Context
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.Drawable
import android.view.Gravity
import org.xmlpull.v1.XmlPullParser

/**
 * Parser for parsing &lt;clip&gt; drawables;
 *
 * @author Akash Yadav
 */
open class ClipDrawableParser protected constructor(parser: XmlPullParser, minDepth: Int) :
    IDrawableParser(parser, minDepth) {
  @Throws(Exception::class)
  public override fun parseDrawable(context: Context): Drawable {
    val orientation = androidAttribute("clipOrientation")?.let(::parseClipOrientation) ?: ClipDrawable.HORIZONTAL
    val gravity = androidAttribute("gravity")?.let { parseGravity(it) } ?: Gravity.LEFT
    return ClipDrawable(childDrawable(context), gravity, orientation)
  }

  protected open fun parseClipOrientation(value: String): Int {
    return when (value) {
      "vertical" -> ClipDrawable.VERTICAL
      "horizontal" -> ClipDrawable.HORIZONTAL
      else -> ClipDrawable.HORIZONTAL
    }
  }
}
