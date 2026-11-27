package com.tom.rv2ide.uidesigner

import com.tom.rv2ide.uidesigner.models.AttrKey
import com.tom.rv2ide.uidesigner.models.LayoutDocument
import com.tom.rv2ide.uidesigner.models.Placement
import com.tom.rv2ide.uidesigner.viewmodel.WorkspaceHistory
import com.tom.rv2ide.uidesigner.views.validPropertyValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutEditingTest {
  private val android = LayoutDocument.ANDROID
  private val text = AttrKey(android, "text", "android")

  private fun document(xml: String) = LayoutDocument(xml, "file:///project/res/layout/example.xml")

  @Test
  fun editsOnlySelectedAttributeWithoutRewritingMergeIncludeOrComments() {
    val before = document("""<?xml version="1.0" encoding="utf-8"?>
      |<!-- before -->
      |<merge xmlns:a="$android">
      |  <include layout="@layout/header" a:visibility="gone" />
      |  <!-- keep the original lexical XML -->
      |  <TextView a:text='A &amp; B' a:id="@+id/label" />
      |</merge>
      |""".trimMargin().replace("\n", "\r\n"))
    val label = requireNotNull(before.at(listOf(1)))
    val decoded = label.attrs.first { it.first.name == "text" }.second
    assertEquals("A & B", decoded)
    assertSame(before, before.attribute(label, text, decoded))
    val after = before.attribute(label, text, "'<&\n😀")
    assertEquals(before.xml.replace("A &amp; B", "&apos;&lt;&amp;&#10;😀"), after.xml)
    assertEquals("merge", after.root?.tag)
    assertEquals("include", after.at(listOf(0))?.tag)
    assertEquals("@layout/header", after.at(listOf(0))?.attrs?.first { it.first.name == "layout" }?.second)
    assertEquals("'<&\n😀", after.node(label.id)?.attrs?.first { it.first.name == "text" }?.second)
  }

  @Test
  fun insertionAndMovePreserveExistingIdsAndInheritedNamespaces() {
    val before = document("""<LinearLayout xmlns:a="$android">
      |  <FrameLayout xmlns:x="urn:original"><TextView a:id="@+id/label" x:test="yes" /></FrameLayout>
      |  <FrameLayout xmlns:x="urn:other" />
      |</LinearLayout>""".trimMargin())
    val label = requireNotNull(before.at(listOf(0, 0)))
    val target = requireNotNull(before.at(listOf(1)))
    val placement = Placement(target.id, 0)
    val inserted = before.insert(target, placement.index, "<Button a:text=\"new\" />")
    val button = requireNotNull(inserted.childAt(placement))
    assertEquals("Button", button.tag)
    assertNotEquals(label.id, button.id)
    val moved = inserted.move(requireNotNull(inserted.node(label.id)), requireNotNull(inserted.node(target.id)), 0)
    assertEquals(listOf(1, 0), moved.node(label.id)?.path)
    assertEquals(listOf(1, 1), moved.node(button.id)?.path)
    assertEquals("urn:original", moved.node(label.id)?.attrs?.first { it.first.name == "test" }?.first?.uri)
    assertEquals("@+id/label", moved.node(label.id)?.xmlId)
    assertNull(moved.remove(requireNotNull(moved.node(label.id))).node(label.id))
  }

  @Test
  fun aSingleCommittedPropertyChangeIsOneUndoStepAndRestoresSelection() {
    val original = document("<LinearLayout xmlns:a=\"$android\" />")
    val history = WorkspaceHistory(original)
    val root = requireNotNull(original.root)
    val placement = Placement(root.id, 0)
    val inserted = original.insert(root, 0, "<TextView a:text=\"Before\" />")
    val node = requireNotNull(inserted.childAt(placement))
    assertTrue(history.commit(inserted, node.id))
    val draft = inserted.attribute(node, text, "Draft")
    assertSame(inserted, history.current.document)
    val committed = draft.attribute(requireNotNull(draft.node(node.id)), text, "After")
    assertTrue(history.commit(committed, node.id))
    assertFalse(history.commit(committed, node.id))
    assertSame(inserted, history.target(redo = false)?.document)
    history.replay(redo = false)
    assertSame(inserted, history.current.document)
    assertEquals(node.id, history.current.selectedId)
    history.replay(redo = false)
    assertSame(original, history.current.document)
    assertEquals(root.id, history.current.selectedId)
    history.replay(redo = true)
    history.replay(redo = true)
    assertSame(committed, history.current.document)
    assertEquals(node.id, history.current.selectedId)
  }

  @Test
  fun rejectsUnsafeSourceAndValidatesTypedValues() {
    assertFalse(document("<TextView").editable)
    assertFalse(document("<!DOCTYPE TextView><TextView />").editable)
    assertFalse(document("<TextView /><Button />").editable)
    val width = AttrKey(android, "layout_width", "a")
    assertTrue(validPropertyValue(width, "0dp"))
    assertTrue(validPropertyValue(width, "@dimen/widget_width"))
    assertFalse(validPropertyValue(width, "-2dp"))
    assertFalse(validPropertyValue(width, "24"))
    assertFalse(validPropertyValue(AttrKey(android, "enabled"), "maybe"))
    assertTrue(validPropertyValue(text, "< & \""))
  }
}
