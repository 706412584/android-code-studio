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
 * 文档同步端口：文件被工具写盘后，通知编辑器刷新（或标脏）。
 *
 * <p><b>为什么需要它</b>：{@code file_write} / {@code file_edit} 直接写磁盘，但**打开着
 * 该文件的编辑器不知道**。后果有两层：
 * <ul>
 *   <li>编辑器仍显示旧内容，用户以为改动没生效，需要手动关掉重开；
 *   <li>更危险的是——用户随后在编辑器里随手改一笔，2 秒自动保存会把编辑器 buffer
 *       （写盘前的旧内容 + 用户那一笔）整段写回磁盘，**静默覆盖 AI 的修改**。
 * </ul>
 * 本端口把「磁盘变了」这件事告诉 app 层，由编辑器决定重载还是标记冲突。
 *
 * <p><b>为什么用端口</b>：本模块是纯 JVM、零 Android 依赖的（见
 * {@code core/ai-tool/build.gradle.kts}），而通知编辑器需要 EventBus 与 Activity/View。
 * 因此这里只定义中性契约，由 app 层适配器实现。
 *
 * <p><b>实现必须容忍未被订阅</b>（没有编辑器打开该文件时静默无事）与**不得抛异常**
 * （同步失败不能把「已经成功的写盘」翻成错误）。
 */
public interface DocumentSyncPort {

  /**
   * 通知编辑器：该文件已被外部改写。
   *
   * <p>实现方应保证：文件未打开时是 no-op；实现不得抛异常。
   *
   * @param file 被写盘的文件
   */
  void notifyChanged(File file);
}
