/*
 * This file is part of AndroidCodeStudio.
 *
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

package com.tom.rv2ide.ai.tool;

import java.util.List;

/**
 * IDE 自身日志的读取端口。
 *
 * <p><b>解决什么问题</b>：AI 工具层与协议层被设计成「不抛异常、把失败回写成文本」，
 * 但模块边界之外（如 `ErrorLog` 的 sink、协议层的流式回调）仍有异常只能落进 IDE 日志。
 * 读不到这些日志，AI 就无法自查「我为什么没按预期工作」。设备 logcat 里虽然有这些行，
 * 但应用只能读到自身进程的日志，而 {@code logcat_read} 默认读的是**被测应用**的，
 * 因此需要一条专门读取 IDE 自身日志的通路。
 *
 * <p><b>为什么是端口而不是文件路径</b>：日志既可能来自内存环形缓冲，也可能来自日志文件
 * 或 logcat；把「怎么读」放在 app 层（能访问 Android 与 logback 的地方），
 * 工具层只依赖下面这个纯数据契约。
 */
public interface IdeLogSource {

  /** 严重级别数值，与 logback 对齐：越小越严重。 */
  int LEVEL_ERROR = 1;
  int LEVEL_WARN = 2;
  int LEVEL_INFO = 3;
  int LEVEL_DEBUG = 4;
  int LEVEL_TRACE = 5;

  /**
   * 读取日志条目（按时间正序，即最早的在前）。
   *
   * @param limit 最多返回条数
   * @param minLevel 只保留该级别及更严重的条目（数值 ≤ minLevel）
   * @param loggerKeyword 按 logger 名过滤（子串，忽略大小写）；空串表示不过滤
   * @param textKeyword 按消息文本过滤（子串，忽略大小写）；空串表示不过滤
   */
  List<Entry> read(int limit, int minLevel, String loggerKeyword, String textKeyword);

  /** 端口是否可用（日志缓冲是否已就绪）。 */
  boolean isAvailable();

  /** 不可用的原因；可用时返回空串。 */
  String unavailableReason();

  /** 一条日志。 */
  final class Entry {
    private final long timestampMs;
    private final int level;
    private final String logger;
    private final String message;
    private final String throwable;

    public Entry(long timestampMs, int level, String logger, String message, String throwable) {
      this.timestampMs = timestampMs;
      this.level = level;
      this.logger = logger == null ? "" : logger;
      this.message = message == null ? "" : message;
      this.throwable = throwable == null ? "" : throwable;
    }

    public long getTimestampMs() {
      return timestampMs;
    }

    public int getLevel() {
      return level;
    }

    public String getLogger() {
      return logger;
    }

    public String getMessage() {
      return message;
    }

    /** 异常堆栈文本（多行）；无异常时为空串。 */
    public String getThrowable() {
      return throwable;
    }

    public String levelName() {
      switch (level) {
        case LEVEL_ERROR:
          return "ERROR";
        case LEVEL_WARN:
          return "WARN";
        case LEVEL_INFO:
          return "INFO";
        case LEVEL_DEBUG:
          return "DEBUG";
        case LEVEL_TRACE:
          return "TRACE";
        default:
          return "UNKNOWN";
      }
    }

    public boolean hasThrowable() {
      return !throwable.isEmpty();
    }
  }
}
