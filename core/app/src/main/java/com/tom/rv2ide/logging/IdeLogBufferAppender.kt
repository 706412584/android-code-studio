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

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.UnsynchronizedAppenderBase
import com.tom.rv2ide.ai.tool.IdeLogSource
import com.tom.rv2ide.artificial.agent.IdeLogBufferSource

/**
 * 把 logback 事件喂给 AI 可读的进程内日志缓冲。
 *
 * 与 [LogcatAppender] 并列挂在 root logger 上：后者给人看（logcat），本 appender 给 AI 看
 * （结构化级别/logger/异常，可被 `ide_log_read` 工具查询）。
 *
 * 只保留 [Level.DEBUG] 及以上，TRACE 通常无人关心且量极大。
 */
class IdeLogBufferAppender : UnsynchronizedAppenderBase<ILoggingEvent>() {

  override fun append(event: ILoggingEvent) {
    val level = mapLevel(event.level) ?: return
    val throwable =
        event.throwableProxy?.let { ThrowableProxyUtil.asString(it) } ?: ""

    IdeLogBufferSource.get().append(
        event.timeStamp,
        level,
        event.loggerName,
        event.formattedMessage,
        throwable)
  }

  override fun start() {
    super.start()
    IdeLogBufferSource.get().markStarted()
  }

  private fun mapLevel(level: Level?): Int? {
    if (level == null) {
      return null
    }
    return when {
      level.isGreaterOrEqual(Level.ERROR) -> IdeLogSource.LEVEL_ERROR
      level.isGreaterOrEqual(Level.WARN) -> IdeLogSource.LEVEL_WARN
      level.isGreaterOrEqual(Level.INFO) -> IdeLogSource.LEVEL_INFO
      level.isGreaterOrEqual(Level.DEBUG) -> IdeLogSource.LEVEL_DEBUG
      else -> null
    }
  }
}
