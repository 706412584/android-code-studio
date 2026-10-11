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

package com.tom.rv2ide.ai.tool;

import java.io.File;

/**
 * 写盘后通知编辑器的统一入口。
 *
 * <p><b>为什么单独包一层而不是各工具直接调端口</b>：与 {@link FileDiagnostics#describe}
 * 同理——调用点在「写盘已成功之后、返回 ok 之前」。同步里任何未预期异常若冒泡，
 * 会把一次**已经成功的写盘**翻成 error，而 DiffRecorder 见到 error 就不记录 diff，
 * 用户会既看到「编辑失败」、又拿不到回滚入口，文件却已经改了。因此这里兜住
 * {@code Throwable}：通知失败只意味着编辑器不刷新，绝不能影响写盘结论。
 */
final class DocumentSync {

  static void notifyChanged(ToolContext context, File file) {
    if (context == null || file == null) {
      return;
    }
    try {
      DocumentSyncPort port = context.getDocumentSync();
      if (port != null) {
        port.notifyChanged(file);
      }
    } catch (Throwable ignored) {
      // 通知编辑器是尽力而为：失败不影响写盘结果。
    }
  }

  private DocumentSync() {}
}
