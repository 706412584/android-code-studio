/*
 * This file is part of AndroidCodeStudio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.artificial.agent

import com.tom.rv2ide.interfaces.IEditorHandler
import java.lang.ref.WeakReference

/**
 * 编辑器会话的进程级注册表：AI 工具端口从它取当前存活的编辑器 Activity。
 *
 * <p><b>为什么需要</b>：工具在后台线程执行，没有 Activity 上下文；而 [IEditorHandler]
 * 只由 `EditorHandlerActivity` 实现，生命周期与 Activity 绑定。需要一个不依赖调用方的
 * 进程级来源（与 [AssistantOrchestratorProvider] 是同一类问题的同一类解法）。
 *
 * <p><b>为什么是 WeakReference</b>：注册表与进程同寿，强引用 Activity 会泄漏。
 *
 * <p><b>为什么 get() 必须同时判 isDestroyed</b>：见
 * [com.tom.rv2ide.handlers.EditorBuildEventListener.checkActivity] 的教训——
 * WeakReference 仍能取到对象只说明还没被 GC，配置变更/后台回收走「销毁但不 finish」
 * 时对象仍在，而 Activity 的 `_binding` 已置空，随后访问 `content` 会抛
 * `IllegalStateException: Activity has been destroyed`。只判引用非空的写法会直接崩溃。
 */
object EditorSessionRegistry {

  @Volatile private var ref: WeakReference<IEditorHandler>? = null

  /** 编辑器 Activity 创建时登记。 */
  @JvmStatic
  fun bind(handler: IEditorHandler) {
    ref = WeakReference(handler)
  }

  /** 编辑器 Activity 销毁时注销；比对实例防止误删重建后的新实例。 */
  @JvmStatic
  fun unbind(handler: IEditorHandler) {
    if (ref?.get() === handler) {
      ref = null
    }
  }

  /** 取当前可用的编辑器会话；不可用返回 null。任意线程可调（读取本身不要求主线程）。 */
  @JvmStatic
  fun get(): IEditorHandler? {
    val handler = ref?.get() ?: return null
    if (handler is android.app.Activity && handler.isDestroyed) {
      return null
    }
    return handler
  }
}
