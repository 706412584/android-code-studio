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
import android.os.Build.VERSION
import android.os.Build.VERSION_CODES
import androidx.annotation.ChecksSdkIntAtLeast
import com.tom.rv2ide.inflater.AbstractParser
import org.xmlpull.v1.XmlPullParser

/** 资源是可绘制 XML，但预览器没有相应实现；不要将整个布局判为无效。 */
internal class UnsupportedPreviewDrawable(value: String) : UnsupportedOperationException(value)

/**
 * Base class for drawable parsers.
 *
 * @author Akash Yadav
 */
abstract class IDrawableParser
protected constructor(protected open val parser: XmlPullParser?, open var minDepth: Int) :
    AbstractParser() {
  /**
   * Parse the drawable using the already provided parser and data.
   *
   * @return The parsed [Drawable] or `null` if the parse was unsuccessful.
   * @throws Exception If any fatal error occurred while parsing the drawable.
   */
  @Throws(Exception::class)
  open fun parse(context: Context): Drawable? {
    var index = attrIndex("visible")
    var visible = true
    if (index != -1) {
      visible = parseBoolean(value(index))
    }
    var autoMirrored = false
    index = attrIndex("autoMirrored")
    if (index != -1) {
      autoMirrored = parseBoolean(value(index))
    }
    var level = 0
    index = attrIndex("level")
    if (index != -1) {
      level = parseInteger(value(index), 0)
    }
    val drawable = parseDrawable(context)
    if (drawable != null) {
      drawable.setVisible(visible, false)
      drawable.isAutoMirrored = autoMirrored
      drawable.level = level
    }
    return drawable
  }

  /**
   * Actual implementation of the parse logic.
   *
   * @return The parsed drawable. Maybe `null`.
   * @throws Exception If any fatal error occurs while parsing the drawable.
   */
  @Throws(Exception::class) protected abstract fun parseDrawable(context: Context): Drawable?

  /**
   * Find the index of the attribute with the given name.
   *
   * @param name The name of the attribute to look for.
   * @return The index of the attribute or `-1`.
   */
  protected fun attrIndex(name: String): Int {
    for (i in 0 until parser!!.attributeCount) {
      if (parser!!.getAttributeName(i) == name) {
        return i
      }
    }
    return -1
  }

  /**
   * Get the value of the attribute at the given index.
   *
   * @param index The index of the attribute.
   * @return The value of the attribute.
   */
  protected fun value(index: Int): String {
    return parser!!.getAttributeValue(index)
  }

  /** 从当前开始标签遍历直接子元素，返回时游标仍在当前元素的结束标签。 */
  protected fun children(visit: (String) -> Unit) {
    val xml = checkNotNull(parser)
    val parentDepth = xml.depth
    while (true) {
      when (xml.next()) {
        XmlPullParser.END_DOCUMENT -> throw org.xmlpull.v1.XmlPullParserException("Unexpected end of drawable")
        XmlPullParser.END_TAG -> if (xml.depth == parentDepth) return
        XmlPullParser.START_TAG -> {
          val depth = xml.depth
          visit(xml.name)
          // 有的叶子解析器只读取属性；未处理的扩展元素也要整棵跳过。
          while (xml.eventType != XmlPullParser.END_TAG || xml.depth != depth) {
            if (xml.next() == XmlPullParser.END_DOCUMENT) {
              throw org.xmlpull.v1.XmlPullParserException("Unclosed drawable element")
            }
          }
        }
      }
    }
  }

  /** item、clip 和 rotate 都允许使用引用或一个内联子图。先读属性，再移动游标。 */
  protected fun childDrawable(context: Context): Drawable {
    val xml = checkNotNull(parser)
    val element = xml.name
    val ref = androidAttribute("drawable")
    if (ref != null) return parseDrawable(context, ref)
    var child: Drawable? = null
    children { tag ->
      if (child != null) throw com.tom.rv2ide.inflater.InflateException("<$element> has multiple drawables")
      child = DrawableParserFactory.parserForTag(null, xml, tag)?.parse(context)
          ?: throw UnsupportedPreviewDrawable("<$element>/<$tag>")
    }
    return child ?: throw com.tom.rv2ide.inflater.InflateException("<$element> requires a drawable")
  }

  protected fun androidAttribute(name: String): String? = parser?.getAttributeValue(ANDROID_NS, name)

  @get:ChecksSdkIntAtLeast(api = VERSION_CODES.Q)
  protected val isApi29: Boolean
    get() = VERSION.SDK_INT >= 29

  companion object {
    const val ANY_DEPTH = -1
    internal const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
  }
}
