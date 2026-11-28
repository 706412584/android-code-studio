package com.tom.rv2ide.inflater.drawable

import android.content.Context
import android.graphics.drawable.RotateDrawable
import org.xmlpull.v1.XmlPullParser

class RotateDrawableParser(parser: XmlPullParser, minDepth: Int) : IDrawableParser(parser, minDepth) {
  override fun parseDrawable(context: Context) = RotateDrawable().apply {
    androidAttribute("fromDegrees")?.let { fromDegrees = it.toFloat() }
    androidAttribute("toDegrees")?.let { toDegrees = it.toFloat() }
    androidAttribute("pivotX")?.let {
      isPivotXRelative = it.endsWith('%')
      pivotX = if (isPivotXRelative) it.dropLast(1).toFloat() / 100 else it.toFloat()
    }
    androidAttribute("pivotY")?.let {
      isPivotYRelative = it.endsWith('%')
      pivotY = if (isPivotYRelative) it.dropLast(1).toFloat() / 100 else it.toFloat()
    }
    drawable = childDrawable(context)
  }
}
