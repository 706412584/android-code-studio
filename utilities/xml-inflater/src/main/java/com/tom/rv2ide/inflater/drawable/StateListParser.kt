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
import android.graphics.drawable.Drawable
import android.graphics.drawable.StateListDrawable
import com.tom.rv2ide.inflater.InflateException
import org.xmlpull.v1.XmlPullParser

/**
 * Parser for parsing &lt;selector&gt; drawables.
 *
 * @author Akash Yadav
 */
class StateListParser(parser: XmlPullParser?, minDepth: Int) : IDrawableParser(parser, minDepth) {

  @Throws(Exception::class)
  public override fun parseDrawable(context: Context): Drawable {
    val states = StateListDrawable()

    // --------------------------- NOTE -------------------------
    // Unsupported attributes :
    //  1. android:constantSize
    //  2. android:variablePadding
    var index = attrIndex("dither")
    if (index != -1) {
      states.setDither(parseBoolean(value(index)))
    }
    val xml = checkNotNull(parser)
    children { tag ->
      if (tag == "item") {
        val spec = (0 until xml.attributeCount).mapNotNull { i ->
          val name = xml.getAttributeName(i)
          if (!name.startsWith("state_") || xml.getAttributeNamespace(i) != ANDROID_NS) return@mapNotNull null
          val id = context.resources.getIdentifier(name, "attr", "android")
          if (id == 0) throw InflateException("Unknown Android drawable state: $name")
          if (parseBoolean(xml.getAttributeValue(i))) id else -id
        }.toIntArray()
        states.addState(spec, childDrawable(context))
      }
    }
    return states
  }
}
