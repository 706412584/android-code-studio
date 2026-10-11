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
import com.tom.rv2ide.ai.tool.IdeLogBuffer
import com.tom.rv2ide.ai.tool.IdeLogSource
import com.tom.rv2ide.ai.tool.api.ErrorLogRedactor

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
    // 先判级别再格式化：TRACE 会被丢弃，而 formattedMessage 与异常堆栈的字符串化都不便宜，
    // 不该为被丢弃的事件先付出这份开销（本 appender 挂在 root logger 上，是全局成本）。
    val level = mapLevel(event.level) ?: return
    val throwable =
        event.throwableProxy?.let { ThrowableProxyUtil.asString(it) } ?: ""

    // 脱敏后再入缓冲：这条内容会经 `ide_log_read` 回灌给模型、并随会话上报服务商，
    // 而 root logger 的原始事件不经过 ErrorLogRedactor。直接用之前这些内容只留在本机 logcat。
    val message =
        try {
          ErrorLogRedactor.redact(event.formattedMessage)
        } catch (t: Throwable) {
          // 脱敏失败必须**失败关闭**：
          //  - 不能把原文写进缓冲（这正是脱敏要防的）
          //  - 也不能让异常冒泡——本 appender 挂在 root logger 上，抛出会破坏正在记日志
          //    的调用方（记日志绝不该反过来让业务代码出错）
          "<redaction-failed>"
        }
    val redactedThrowable =
        try {
          ErrorLogRedactor.redact(throwable)
        } catch (t: Throwable) {
          "<redaction-failed>"
        }
    try {
      IdeLogBufferHolder.get().append(event.timeStamp, level, event.loggerName, message, redactedThrowable)
    } catch (t: Throwable) {
      // 入队失败同样吞掉（root logger 上的 appender 不得影响业务线程）。
    }
  }

  override fun start() {
    super.start()
    IdeLogBufferHolder.get().markStarted()
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
