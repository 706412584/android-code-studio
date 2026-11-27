/*
 * This file is part of AndroidIDE and AndroidCodeStudio.
 * Licensed under the GNU General Public License, version 3 or any later version.
 */
package com.tom.rv2ide.uidesigner.utils

import com.tom.rv2ide.lsp.xml.providers.completion.AttrValueFormat

/**
 * 属性定义的值格式（attrs.xml 里的 format 与 enum/flag 符号）。
 * 不直接持有 aapt 类型，单测可以直接构造。
 */
internal data class AttrSchema(
    val reference: Boolean = false,
    val string: Boolean = false,
    val integer: Boolean = false,
    val boolean: Boolean = false,
    val color: Boolean = false,
    val float: Boolean = false,
    val dimension: Boolean = false,
    val fraction: Boolean = false,
    val enum: Boolean = false,
    val flags: Boolean = false,
    val symbols: List<String> = emptyList(),
) {
  /** 定义里没写任何格式时不做限制。 */
  private val unrestricted: Boolean
    get() = !(reference || string || integer || boolean || color || float || dimension || fraction || enum || flags)

  /**
   * 字面量（非 `@`/`?` 引用）是否符合任一允许的格式。
   * 引用由调用方先放行：aapt2 对任何格式的属性都接受资源引用。
   */
  fun acceptsLiteral(value: String): Boolean = unrestricted || string ||
      enum && value in symbols ||
      flags && value.split('|').all { it.trim() in symbols } ||
      boolean && (value == "true" || value == "false") ||
      color && colorLiteral.matches(value) ||
      integer && integerLiteral.matches(value) ||
      float && floatLiteral.matches(value) ||
      dimension && dimensionLiteral.matches(value) ||
      fraction && fractionLiteral.matches(value)

  /** 可直接列出的候选：枚举/flag 符号，以及布尔的 true/false。 */
  val choices: List<String>
    get() = buildList {
      if (enum || flags) addAll(symbols)
      if (boolean) addAll(listOf("true", "false"))
    }

  companion object {
    fun of(format: AttrValueFormat) = AttrSchema(
        reference = format.isReference,
        string = format.isString,
        integer = format.isInteger,
        boolean = format.isBoolean,
        color = format.isColor,
        float = format.isFloat,
        dimension = format.isDimension,
        fraction = format.isFraction,
        enum = format.isEnum,
        flags = format.isFlags,
        symbols = format.symbols,
    )

    private const val NUMBER = "[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?"
    private val colorLiteral = Regex("#(?:[0-9a-fA-F]{3,4}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})")
    private val integerLiteral = Regex("[+-]?(?:0[xX][0-9a-fA-F]+|[0-9]+)")
    private val floatLiteral = Regex(NUMBER)
    private val dimensionLiteral = Regex("$NUMBER(?:dp|dip|sp|px|in|mm|pt)")
    private val fractionLiteral = Regex("$NUMBER%p?")
  }
}
