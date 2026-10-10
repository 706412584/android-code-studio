/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidCodeStudio is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.logging

import com.tom.rv2ide.ai.tool.IdeLogBuffer

/**
 * 全局唯一的日志环形缓冲。
 *
 * 缓冲实现（[IdeLogBuffer]）在纯 Java 的 `ai-tool` 模块，为的是可 JVM 单测；
 * 单例与 logback 的接线留在 app 层。logback appender 是全局的，因此缓冲也必须全局唯一，
 * 否则工具读到的与 appender 写入的是两份。
 */
object IdeLogBufferHolder {

  private val buffer = IdeLogBuffer(IdeLogBuffer.DEFAULT_CAPACITY)

  fun get(): IdeLogBuffer = buffer
}
