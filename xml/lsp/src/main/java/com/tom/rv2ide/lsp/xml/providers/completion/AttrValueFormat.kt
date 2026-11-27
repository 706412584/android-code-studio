/*
 * This file is part of AndroidIDE and AndroidCodeStudio.
 * Licensed under the GNU General Public License, version 3 or any later version.
 */
package com.tom.rv2ide.lsp.xml.providers.completion

import com.android.aapt.Resources.Attribute.FormatFlags

/** 属性定义里的格式掩码与枚举/flag 符号，供 LSP 之外的调用方判断值的类型。 */
data class AttrValueFormat(val typeMask: Int, val symbols: List<String>) {
  private fun has(flag: FormatFlags) = typeMask and flag.number != 0

  val isReferenceOnly: Boolean get() = typeMask == FormatFlags.REFERENCE_VALUE
  val isReference: Boolean get() = has(FormatFlags.REFERENCE)
  val isString: Boolean get() = has(FormatFlags.STRING)
  val isInteger: Boolean get() = has(FormatFlags.INTEGER)
  val isBoolean: Boolean get() = has(FormatFlags.BOOLEAN)
  val isColor: Boolean get() = has(FormatFlags.COLOR)
  val isFloat: Boolean get() = has(FormatFlags.FLOAT)
  val isDimension: Boolean get() = has(FormatFlags.DIMENSION)
  val isFraction: Boolean get() = has(FormatFlags.FRACTION)
  val isEnum: Boolean get() = has(FormatFlags.ENUM)
  val isFlags: Boolean get() = has(FormatFlags.FLAGS)
}
