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
import android.graphics.drawable.LayerDrawable
import org.xmlpull.v1.XmlPullParser

/**
 * Parser for parsing &lt;layer-list&gt; drawables.
 *
 * @author Akash Yadav
 */
open class LayerListParser protected constructor(parser: XmlPullParser?, minDepth: Int) :
    IDrawableParser(parser, minDepth) {
  @Throws(Exception::class)
  public override fun parseDrawable(context: Context): Drawable {
    val layer = LayerDrawable(emptyArray())
    fun dimension(name: String) = androidAttribute(name)?.let { parseDimension(context, it) }
    children { tag ->
      if (tag == "item") {
        val id = androidAttribute("id")?.takeIf { it.startsWith("@android:id/") }
            ?.let { context.resources.getIdentifier(it.substringAfter('/'), "id", "android") }
        val left = dimension("left") ?: 0
        val top = dimension("top") ?: 0
        val right = dimension("right") ?: 0
        val bottom = dimension("bottom") ?: 0
        val width = dimension("width")
        val height = dimension("height")
        val gravity = androidAttribute("gravity")?.let { parseGravity(it) }
        val index = layer.addLayer(childDrawable(context))
        if (id != null && id != 0) layer.setId(index, id)
        layer.setLayerInset(index, left, top, right, bottom)
        width?.let { layer.setLayerWidth(index, it) }
        height?.let { layer.setLayerHeight(index, it) }
        gravity?.let { layer.setLayerGravity(index, it) }
      }
    }
    return layer
  }
}
