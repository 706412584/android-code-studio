package com.tom.rv2ide.inflater.internal.utils

import android.util.TypedValue
import com.android.aaptcompiler.AaptResourceType.STYLE
import com.android.aaptcompiler.BasicString
import com.android.aaptcompiler.BinaryPrimitive
import com.android.aaptcompiler.Item
import com.android.aaptcompiler.RawString
import com.android.aaptcompiler.Reference
import com.android.aaptcompiler.Style
import com.android.aaptcompiler.StyledString
import com.android.aaptcompiler.android.ResValue.DataType
import com.tom.rv2ide.inflater.InflateException

/** 样式值仅用于创建和设置预览 View，不加入可序列化的显式属性。 */
internal data class PreviewStyle(val attributes: Map<String, String>, val horizontalProgress: Boolean) {
  companion object {
    val EMPTY = PreviewStyle(emptyMap(), false)
  }
}

internal fun resolvePreviewStyle(
    reference: String?,
    lookup: (String) -> Style? = { parseReference(it, STYLE, null) { value -> value as? Style } },
): PreviewStyle {
  var current = reference ?: return PreviewStyle.EMPTY
  val chain = mutableListOf<Style>()
  val visited = mutableSetOf<String>()
  while (current.startsWith('@') && !current.startsWith("@android:")) {
    if (!visited.add(current)) throw InflateException("循环样式引用：${(visited.toList() + current).joinToString(" → ")}")
    val style = lookup(current) ?: break
    chain += style
    val parent = style.parent?.name ?: break
    current = "@$parent"
  }
  val horizontal = current == "?android:attr/progressBarStyleHorizontal" ||
      current == "?android:progressBarStyleHorizontal" ||
      current == "@android:style/Widget.ProgressBar.Horizontal" ||
      current == "@android:style/Widget.Material.ProgressBar.Horizontal"
  val attributes = linkedMapOf<String, String>()
  for (style in chain.asReversed()) {
    for ((key, value) in style.entries) {
      val name = key.name.entry ?: continue
      if (key.name.pck == "android") value?.previewText()?.let { attributes[name] = it }
    }
  }
  return PreviewStyle(attributes, horizontal)
}

private fun Item.previewText(): String? = when (this) {
  is Reference -> (if (referenceType == Reference.Type.ATTRIBUTE) "?" else "@") + name
  is RawString -> value.value()
  is BasicString -> ref.value()
  is StyledString -> ref.value()
  is BinaryPrimitive -> when (resValue.dataType) {
    in DataType.FIRST_COLOR_INT..DataType.LAST_COLOR_INT -> "#" + resValue.data.toUInt().toString(16).padStart(8, '0')
    else -> TypedValue.coerceToString(resValue.dataType.byteValue.toInt(), resValue.data)
  }
  else -> null
}
