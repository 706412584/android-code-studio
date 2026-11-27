package com.tom.rv2ide.uidesigner.undo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UndoManagerTest {
  private val history = UndoManager()
  private var value = ""

  private fun setValue(next: String) {
    val previous = value
    val action = object : IUiAction {
      override fun undo() { value = previous }
      override fun redo() { value = next }
    }
    action.redo()
    history.push(action)
  }

  @Test
  fun undoAndRedoReplayInOrder() {
    setValue("LinearLayout")
    setValue("LinearLayout/TextView")
    history.undo()
    assertEquals("LinearLayout", value)
    assertTrue(history.canRedo())
    history.redo()
    assertEquals("LinearLayout/TextView", value)
    assertFalse(history.canRedo())
  }

  @Test
  fun committingAfterUndoClearsRedo() {
    setValue("TextView")
    history.undo()
    setValue("Button")
    assertEquals("Button", value)
    assertFalse(history.canRedo())
    history.undo()
    assertEquals("", value)
  }
}
