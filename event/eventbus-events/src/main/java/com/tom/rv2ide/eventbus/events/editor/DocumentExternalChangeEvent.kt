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

package com.tom.rv2ide.eventbus.events.editor

import java.nio.file.Path

/**
 * 某个文件在**编辑器之外**被改写（AI 工具 / git checkout / archive 解压等），
 * 需要已打开它的编辑器重载。
 *
 * <p>与 [DocumentChangeEvent] 的区别：那个是「编辑器里的内容变了」（用户敲键盘或
 * IDE 自身改文本），编辑器是**变更的发起方**、内容已是最新；这个是「磁盘上的文件
 * 变了」，编辑器是**被动方**、内存里还是旧内容。把两者混用会让订阅方无法区分方向。
 *
 * <p>此前缺失这条通路导致的实测问题：AI 改完一个正开在编辑器里的文件后，编辑器仍显示
 * 旧内容；用户随手改一笔触发 2 秒自动保存，编辑器 buffer（旧的）会把 AI 的修改整段覆盖回磁盘。
 *
 * @param changedFile 被外部改写的文件
 */
data class DocumentExternalChangeEvent(var changedFile: Path) : DocumentEvent(changedFile)
