package com.tom.rv2ide.inflater

import com.android.aaptcompiler.AaptResourceType.*
import com.android.aaptcompiler.Reference
import com.android.aaptcompiler.ResourceName
import com.android.aaptcompiler.Style
import com.tom.rv2ide.inflater.internal.utils.parseString
import com.tom.rv2ide.inflater.internal.utils.resolvePreviewStyle
import org.junit.Assert.*
import org.junit.Test

class PreviewValuesTest {
  private fun ref(type: com.android.aaptcompiler.AaptResourceType, name: String, pkg: String = "") =
      Reference(ResourceName(pkg, type, name))

  @Test fun layoutLiteralsDecodeEscapesOnlyOnce() {
    assertEquals("纪\n元\n修\n仙", parseString("纪\\n元\\n修\\n仙"))
    assertEquals("前\\n后", parseString("前\\\\n后"))
    assertEquals(" A\tB ", parseString(" A\\tB "))
    assertEquals("没有转义", parseString("没有转义"))
  }

  @Test fun colorAliasesPreserveTransparentAndStopAtCycles() {
    val table = com.android.aaptcompiler.ResourceTable()
    val config = com.android.aaptcompiler.ConfigDescription()
    fun add(name: String, value: com.android.aaptcompiler.Value) =
        assertTrue(table.addResource(ResourceName("preview", COLOR, name), config, "", value))
    fun resolve(name: String): Int? = com.tom.rv2ide.inflater.internal.utils.resolveResourceReference(
        table, COLOR, "preview", name, null, com.tom.rv2ide.inflater.internal.utils.colorResolver)
    add("ink", checkNotNull(com.android.aaptcompiler.tryParseColor("#ffe7e7e7")))
    add("title", ref(COLOR, "ink"))
    add("clear", checkNotNull(com.android.aaptcompiler.tryParseColor("#00000000")))
    add("clear_alias", ref(COLOR, "clear", "preview"))
    add("cycle_a", ref(COLOR, "cycle_b"))
    add("cycle_b", ref(COLOR, "cycle_a"))
    assertEquals(0xffe7e7e7.toInt(), resolve("title"))
    assertEquals(0, resolve("clear_alias"))
    assertNull(resolve("cycle_a"))
  }

  @Test fun projectStylesKeepParentEntriesAndOverrideByAttribute() {
    val base = Style().apply {
      parent = ref(STYLE, "Widget.ProgressBar.Horizontal", "android")
      entries.add(Style.Entry(ref(ATTR, "background", "android"), ref(DRAWABLE, "parent")))
      entries.add(Style.Entry(ref(ATTR, "textColor", "android"), ref(COLOR, "ink")))
    }
    val child = Style().apply {
      parent = ref(STYLE, "Base")
      entries.add(Style.Entry(ref(ATTR, "background", "android"), ref(DRAWABLE, "child")))
    }
    val all = mapOf("@style/Base" to base, "@style/Child" to child)
    val resolved = resolvePreviewStyle("@style/Child", all::get)
    assertTrue(resolved.horizontalProgress)
    assertEquals(mapOf("background" to "@drawable/child", "textColor" to "@color/ink"), resolved.attributes)
    assertEquals("@drawable/parent", resolvePreviewStyle("@style/Base", all::get).attributes["background"])
    assertFalse(resolvePreviewStyle(null, all::get).horizontalProgress)
    assertTrue(resolvePreviewStyle("?android:attr/progressBarStyleHorizontal", all::get).horizontalProgress)
  }

  @Test fun styleCyclesStopWithAnExplanation() {
    val first = Style().apply { parent = ref(STYLE, "Second") }
    val second = Style().apply { parent = ref(STYLE, "First") }
    val error = assertThrows(InflateException::class.java) {
      resolvePreviewStyle("@style/First", mapOf("@style/First" to first, "@style/Second" to second)::get)
    }
    assertTrue(error.message.orEmpty().contains("@style/First → @style/Second → @style/First"))
  }
}
