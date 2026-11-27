/*
 * This file is part of AndroidCodeStudio, licensed under the GNU General Public License,
 * version 3 or (at your option) any later version.
 */
package com.tom.rv2ide.uidesigner.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.tom.rv2ide.uidesigner.models.LayoutDocument
import com.tom.rv2ide.uidesigner.undo.IUiAction
import com.tom.rv2ide.uidesigner.undo.UndoManager
import java.io.File

internal data class WorkspaceSnapshot(val document: LayoutDocument, val selectedId: Long?)

/** 历史只持有源文本及节点身份，不保留 Activity、真实 View 或预览参数。 */
internal class WorkspaceHistory(initial: LayoutDocument) {
  private val history = UndoManager()
  var current = WorkspaceSnapshot(initial, initial.root?.id)
    private set

  val canUndo: Boolean get() = history.canUndo()
  val canRedo: Boolean get() = history.canRedo()

  fun select(id: Long?) {
    current = current.copy(selectedId = current.document.node(id)?.id)
  }

  fun commit(document: LayoutDocument, selectedId: Long?): Boolean {
    if (document.xml == current.document.xml) return false
    val before = current
    val after = WorkspaceSnapshot(document, document.node(selectedId)?.id)
    history.push(Edit(before, after))
    current = after
    return true
  }

  fun target(redo: Boolean): WorkspaceSnapshot? =
      if (redo) (history.peekRedo() as? Edit)?.after else (history.peekUndo() as? Edit)?.before

  fun replay(redo: Boolean) {
    if (redo) history.redo() else history.undo()
  }

  private inner class Edit(val before: WorkspaceSnapshot, val after: WorkspaceSnapshot) : IUiAction {
    override fun undo() { current = before }
    override fun redo() { current = after }
  }
}

internal class WorkspaceViewModel : ViewModel() {
  private val snapshot = MutableLiveData<WorkspaceSnapshot>()
  val state: LiveData<WorkspaceSnapshot> get() = snapshot
  lateinit var file: File
    private set
  lateinit var originalXml: String
    private set
  lateinit var diskFingerprint: String
    private set
  private lateinit var history: WorkspaceHistory

  val isOpen: Boolean get() = ::history.isInitialized
  val current: WorkspaceSnapshot get() = history.current
  val canUndo: Boolean get() = history.canUndo
  val canRedo: Boolean get() = history.canRedo
  val dirty: Boolean get() = current.document.xml != originalXml
  val collapsed = mutableSetOf<Long>()
  var copiedNode: CopiedNode? = null

  data class CopiedNode(val name: String, val xml: String)

  fun open(file: File, xml: String, diskFingerprint: String) {
    if (::history.isInitialized) return
    this.file = file
    this.originalXml = xml
    this.diskFingerprint = diskFingerprint
    history = WorkspaceHistory(LayoutDocument(xml, file.toURI().toString()))
    snapshot.value = current
  }

  fun select(id: Long?) {
    if (current.selectedId == id) return
    history.select(id)
    snapshot.value = current
  }

  fun commit(document: LayoutDocument, selectedId: Long?) {
    if (history.commit(document, selectedId)) snapshot.value = current
  }

  fun historyTarget(redo: Boolean): WorkspaceSnapshot? = history.target(redo)

  fun replay(redo: Boolean) {
    history.replay(redo)
    snapshot.value = current
  }
}
