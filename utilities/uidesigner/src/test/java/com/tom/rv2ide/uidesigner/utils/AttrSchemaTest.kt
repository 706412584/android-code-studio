package com.tom.rv2ide.uidesigner.utils

import com.tom.rv2ide.uidesigner.models.AttrKey
import com.tom.rv2ide.uidesigner.models.LayoutDocument
import com.tom.rv2ide.uidesigner.views.validPropertyValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttrSchemaTest {
  private val android = LayoutDocument.ANDROID
  private val app = LayoutDocument.APP

  private val gravity = AttrSchema(flags = true, symbols = listOf("top", "bottom", "center", "start"))
  private val visibility = AttrSchema(enum = true, symbols = listOf("visible", "invisible", "gone"))
  private val size = AttrSchema(dimension = true, enum = true, symbols = listOf("fill_parent", "match_parent", "wrap_content"))
  private val bool = AttrSchema(boolean = true)
  private val color = AttrSchema(reference = true, color = true)

  @Test
  fun enumAndFlagsAcceptOnlyKnownSymbols() {
    assertTrue(visibility.acceptsLiteral("gone"))
    assertFalse(visibility.acceptsLiteral("hidden"))
    assertTrue(gravity.acceptsLiteral("center"))
    assertTrue(gravity.acceptsLiteral("top|start"))
    assertTrue(gravity.acceptsLiteral("top | start"))
    assertFalse(gravity.acceptsLiteral("top|middle"))
    assertFalse(gravity.acceptsLiteral("top|"))
  }

  @Test
  fun literalFormats() {
    assertTrue(bool.acceptsLiteral("true"))
    assertFalse(bool.acceptsLiteral("yes"))
    assertTrue(color.acceptsLiteral("#fff"))
    assertTrue(color.acceptsLiteral("#80FF0000"))
    assertFalse(color.acceptsLiteral("#12345"))
    assertFalse(color.acceptsLiteral("red"))
    assertTrue(size.acceptsLiteral("12.5dp"))
    assertTrue(size.acceptsLiteral("wrap_content"))
    assertFalse(size.acceptsLiteral("12"))
    assertTrue(AttrSchema(integer = true).acceptsLiteral("0x1F"))
    assertTrue(AttrSchema(float = true).acceptsLiteral("0.5"))
    assertTrue(AttrSchema(fraction = true).acceptsLiteral("50%p"))
    assertTrue(AttrSchema(string = true, boolean = true).acceptsLiteral("anything"))
    // 定义里没写格式就不限制。
    assertTrue(AttrSchema().acceptsLiteral("anything"))
  }

  @Test
  fun choicesListSymbolsAndBooleans() {
    assertEquals(listOf("visible", "invisible", "gone"), visibility.choices)
    assertEquals(listOf("true", "false"), bool.choices)
    assertTrue(color.choices.isEmpty())
  }

  @Test
  fun validationUsesSchemaWhenKnown() {
    val gravityKey = AttrKey(android, "gravity", "android")
    // 没有格式时 gravity 不在内置名单里，什么都放行；有格式后按 flag 校验。
    assertTrue(validPropertyValue(gravityKey, "middle"))
    assertFalse(validPropertyValue(gravityKey, "middle", gravity))
    assertTrue(validPropertyValue(gravityKey, "center|top", gravity))
    // 引用对任何格式都放行。
    assertTrue(validPropertyValue(gravityKey, "@integer/g", gravity))
    assertTrue(validPropertyValue(gravityKey, "?attr/g", gravity))
    // app 命名空间原来一律放行，有格式后也校验。
    val appBool = AttrKey(app, "useCompatPadding", "app")
    assertTrue(validPropertyValue(appBool, "maybe"))
    assertFalse(validPropertyValue(appBool, "maybe", bool))
    // 宽高：0 放行，负值拒绝，与内置规则一致。
    val width = AttrKey(android, "layout_width", "android")
    assertTrue(validPropertyValue(width, "0", size))
    assertFalse(validPropertyValue(width, "-2dp", size))
    assertTrue(validPropertyValue(width, "match_parent", size))
    // id 不走格式。
    val id = AttrKey(android, "id", "android")
    assertFalse(validPropertyValue(id, "title", AttrSchema(reference = true)))
    assertTrue(validPropertyValue(id, "@+id/title", AttrSchema(reference = true)))
    // 数据绑定表达式照旧。
    assertTrue(validPropertyValue(gravityKey, "@{vm.gravity}", gravity))
  }
}
