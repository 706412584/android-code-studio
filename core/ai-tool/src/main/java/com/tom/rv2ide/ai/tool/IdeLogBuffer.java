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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * IDE 日志的进程内环形缓冲。
 *
 * <p><b>为什么用内存缓冲而不是读文件/logcat</b>：
 * <ul>
 *   <li>文件：logback 只接了 {@code LogcatAppender}，没有写日志文件（{@code IDELogcatReader}
 *       那个 {@code AndroidIDE-LOG-*.txt} 是 UI 手动导出的 logcat 转储，非常驻）
 *   <li>logcat：应用只能可靠读取**自身进程**的日志，而工具经 Shizuku/Termux 后端跑的
 *       {@code logcat} 是另一个进程，读不到 ACS 自己；且要依赖 shell 后端可用
 * </ul>
 *
 * <p><b>为什么是环形</b>：日志在应用整个生命周期内持续产生，无上限会持续吃内存。
 * 默认保留最近 {@link #DEFAULT_CAPACITY} 条，够排查「刚刚发生了什么」，又不失控。
 *
 * <p>放在 {@code ai-tool}（纯 Java、零 Android）而非 app 层，是为了**可 JVM 单测**——
 * 环形淘汰与并发写入是这里最容易出错、也最该被钉住的部分。
 */
public final class IdeLogBuffer implements IdeLogSource {

  /** 默认保留条数。 */
  public static final int DEFAULT_CAPACITY = 1000;

  /** 单条消息最大字符数，防止超长消息把缓冲撑爆。 */
  private static final int MAX_MESSAGE_CHARS = 2000;

  /**
   * 单条异常堆栈最大字符数。
   *
   * <p>取 4000（而非更大）：缓冲是 1000 条，最坏情况约 1000×(2000+4000) 字符 ≈ 20MB，
   * 已是偏高的常驻内存。堆栈前几帧通常足以定位，剪掉尾部对排查影响很小。
   */
  private static final int MAX_THROWABLE_CHARS = 4000;

  private final int capacity;
  private final Deque<Entry> buffer = new ArrayDeque<>();
  private volatile boolean started;

  public IdeLogBuffer(int capacity) {
    this.capacity = Math.max(1, capacity);
  }

  /** 标记缓冲已就绪（由 logback appender 挂载后调用）。 */
  public void markStarted() {
    this.started = true;
  }

  /** 追加一条。供 appender 调用；线程安全。 */
  public void append(long timestampMs, int level, String logger, String message, String throwable) {
    Entry entry =
        new Entry(
            timestampMs,
            level,
            logger,
            clip(message, MAX_MESSAGE_CHARS),
            clip(throwable, MAX_THROWABLE_CHARS));
    synchronized (buffer) {
      while (buffer.size() >= capacity) {
        buffer.pollFirst();
      }
      buffer.addLast(entry);
    }
  }

  @Override
  public List<Entry> read(int limit, int minLevel, String loggerKeyword, String textKeyword) {
    if (limit <= 0) {
      return Collections.emptyList();
    }
    String loggerFilter = loggerKeyword == null ? "" : loggerKeyword.trim().toLowerCase(Locale.US);
    String textFilter = textKeyword == null ? "" : textKeyword.trim().toLowerCase(Locale.US);

    List<Entry> snapshot;
    synchronized (buffer) {
      snapshot = new ArrayList<>(buffer);
    }

    // 从最新往回取，命中后插入到头部，保证结果按时间正序且保留「最近 N 条」语义。
    List<Entry> result = new ArrayList<>();
    for (int i = snapshot.size() - 1; i >= 0 && result.size() < limit; i--) {
      Entry entry = snapshot.get(i);
      if (entry.getLevel() > minLevel) {
        continue;
      }
      if (!loggerFilter.isEmpty()
          && !entry.getLogger().toLowerCase(Locale.US).contains(loggerFilter)) {
        continue;
      }
      if (!textFilter.isEmpty()) {
        String haystack =
            (entry.getMessage() + "\n" + entry.getThrowable()).toLowerCase(Locale.US);
        if (!haystack.contains(textFilter)) {
          continue;
        }
      }
      result.add(entry);
    }
    Collections.reverse(result);
    return result;
  }

  @Override
  public boolean isAvailable() {
    return started;
  }

  @Override
  public String unavailableReason() {
    return started ? "" : "日志缓冲尚未初始化（日志配置器未挂载）";
  }

  private static String clip(String text, int max) {
    if (text == null) {
      return "";
    }
    return text.length() <= max ? text : text.substring(0, max) + "…(截断)";
  }
}
