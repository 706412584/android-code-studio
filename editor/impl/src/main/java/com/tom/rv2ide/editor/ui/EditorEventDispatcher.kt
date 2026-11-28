/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.editor.ui

import com.tom.rv2ide.eventbus.events.editor.DocumentChangeEvent
import com.tom.rv2ide.eventbus.events.editor.DocumentCloseEvent
import com.tom.rv2ide.eventbus.events.editor.DocumentEvent
import com.tom.rv2ide.eventbus.events.editor.DocumentOpenEvent
import com.tom.rv2ide.eventbus.events.editor.DocumentSaveEvent
import com.tom.rv2ide.eventbus.events.editor.DocumentSelectedEvent
import com.tom.rv2ide.projects.FileManager.onDocumentClose
import com.tom.rv2ide.projects.FileManager.onDocumentContentChange
import com.tom.rv2ide.projects.FileManager.onDocumentOpen
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.greenrobot.eventbus.EventBus
import org.slf4j.LoggerFactory

/**
 * Dispatches events for the editor.
 *
 * @author Akash Yadav
 */
class EditorEventDispatcher(var editor: IDEEditor? = null) {

  // 空标签只挂起协程，不占用设计器回写等操作需要的共享 IO 线程。
  private val eventQueue = Channel<DocumentEvent>(Channel.UNLIMITED)
  private var eventDispatcherJob: Job? = null

  companion object {

    private val log = LoggerFactory.getLogger(EditorEventDispatcher::class.java)
  }

  fun init(scope: CoroutineScope) {
    eventDispatcherJob =
        scope
            .launch(Dispatchers.Default) {
              for (event in eventQueue) dispatchEvent(event)
            }
            .also {
              it.invokeOnCompletion { error ->
                if (error != null && error !is CancellationException) {
                  log.error("Failed to dispatch editor events", error)
                }
              }
            }
  }

  fun dispatch(event: DocumentEvent) {
    eventQueue.trySend(event) // 已释放的编辑器不再接收事件。
  }

  private fun dispatchEvent(event: DocumentEvent) {
    if (editor?.isReleased != false) {
      return
    }

    when (event) {
      is DocumentOpenEvent -> dispatchOpen(event)
      is DocumentChangeEvent -> dispatchChange(event)
      is DocumentSaveEvent -> dispatchSave(event)
      is DocumentCloseEvent -> dispatchClose(event)
      is DocumentSelectedEvent -> dispatchSelected(event)
      else -> throw IllegalArgumentException("Unknown document event: $event")
    }
  }

  private fun dispatchOpen(event: DocumentOpenEvent) {
    onDocumentOpen(event)
    post(event)
  }

  private fun dispatchChange(event: DocumentChangeEvent) {
    onDocumentContentChange(event)
    post(event)
  }

  private fun dispatchSave(event: DocumentSaveEvent) {
    post(event)
  }

  private fun dispatchClose(event: DocumentCloseEvent) {
    onDocumentClose(event)
    post(event)
  }

  private fun dispatchSelected(event: DocumentSelectedEvent) {
    post(event)
  }

  private fun post(event: DocumentEvent) {
    EventBus.getDefault().post(event)
  }

  fun destroy() {
    editor = null
    eventQueue.cancel()
    eventDispatcherJob?.cancel()
  }
}
