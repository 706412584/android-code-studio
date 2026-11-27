package com.tom.rv2ide.editor

import com.tom.rv2ide.editor.utils.ContentReadWrite.readContent
import com.tom.rv2ide.editor.utils.ContentReadWrite.writeTo
import io.github.rosemoe.sora.lang.folding.FoldedLineMap
import io.github.rosemoe.sora.lang.folding.FoldingRegion
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FoldingContentTest {
  @get:Rule val temp = TemporaryFolder()

  @Test fun unfoldingOuterKeepsInnerFoldAndRestoresFollowingRowPosition() {
    val map = FoldedLineMap()
    val outer = FoldingRegion(1, 7)
    val inner = FoldingRegion(3, 5)
    map.update(listOf(inner, outer), 10)
    assertTrue(map.isHidden(4))
    assertEquals(2, map.lineToDisplay(8))
    assertEquals(8, map.displayToLine(2))

    map.update(listOf(inner), 10)
    assertFalse(map.isHidden(3))
    assertTrue(map.isHidden(4))
    assertEquals(6, map.lineToDisplay(8))
    map.update(emptyList(), 10)
    assertEquals(8, map.displayToLine(8))
  }

  @Test fun editedAndUndoneContentRoundTripsAllLogicalLinesAndSeparators() {
    val source = "import a.A\r\nimport b.B\r\nclass Outer {\n  fun inner() {\n    val 值 = 1\r\n  }\n}"
    val file = temp.newFile("FoldExample.kt")
    file.writeBytes(source.toByteArray())
    val content = file.readContent()
    content.insert(4, content.getColumnCount(4), "0")
    content.writeTo(file)
    assertArrayEquals(source.replace("= 1", "= 10").toByteArray(), file.readBytes())
    assertTrue(content.canUndo())
    content.undo()
    content.writeTo(file)
    assertArrayEquals(source.toByteArray(), file.readBytes())
    assertEquals(source, file.readContent().toString())
  }
}
