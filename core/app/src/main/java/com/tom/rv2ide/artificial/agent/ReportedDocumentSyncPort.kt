/*
 * AndroidCodeStudio is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AndroidCodeStudio is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.artificial.agent

import com.tom.rv2ide.ai.tool.DocumentSyncPort
import com.tom.rv2ide.eventbus.events.editor.DocumentExternalChangeEvent
import java.io.File
import org.greenrobot.eventbus.EventBus

/**
 * [DocumentSyncPort] 的 app 层实现：把「文件被工具写盘」转成一条
 * [DocumentExternalChangeEvent]，由已打开该文件的 [com.tom.rv2ide.ui.CodeEditorView]
 * 自行重载。
 *
 * <p><b>为什么走 EventBus 而不是直接找编辑器</b>：工具在后台线程运行，既不知道哪个
 * Activity 在前台，也不该持有 View 引用。EventBus 是本项目里编辑器层既有的解耦通道
 * （编辑器已订阅 [com.tom.rv2ide.eventbus.events.editor.PreferenceChangeEvent] 等），
 * 复用它与既有模式一致。
 */
class ReportedDocumentSyncPort : DocumentSyncPort {

  override fun notifyChanged(file: File?) {
    if (file == null) {
      return
    }
    // 用 file.toPath() 与事件基类 DocumentEvent 的 Path 契约保持一致。
    EventBus.getDefault().post(DocumentExternalChangeEvent(file.toPath()))
  }
}
