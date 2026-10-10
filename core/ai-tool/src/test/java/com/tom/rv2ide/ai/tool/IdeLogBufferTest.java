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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * {@link IdeLogBuffer} 的环形淘汰、最近 N 条语义、过滤与并发写入。
 *
 * <p>这些是日志工具里最容易悄悄出错的部分（淘汰算错 → 读到旧日志；顺序搞反 → 读到最老而非最新），
 * 因此单独钉住。
 */
class IdeLogBufferTest {

  private static void append(IdeLogBuffer buffer, int level, String logger, String message) {
    buffer.append(1_700_000_000_000L, level, logger, message, "");
  }

  @Test
  void evictsOldestWhenOverCapacity() {
    IdeLogBuffer buffer = new IdeLogBuffer(3);
    for (int i = 1; i <= 5; i++) {
      append(buffer, IdeLogSource.LEVEL_ERROR, "ai", "m" + i);
    }
    List<IdeLogSource.Entry> all = buffer.read(10, IdeLogSource.LEVEL_TRACE, "", "");
    assertEquals(3, all.size());
    // 保留的是最新的 3 条：m3, m4, m5
    assertEquals("m3", all.get(0).getMessage());
    assertEquals("m5", all.get(2).getMessage());
  }

  @Test
  void readReturnsMostRecentInChronologicalOrder() {
    IdeLogBuffer buffer = new IdeLogBuffer(100);
    for (int i = 1; i <= 10; i++) {
      append(buffer, IdeLogSource.LEVEL_ERROR, "ai", "m" + i);
    }
    List<IdeLogSource.Entry> recent = buffer.read(3, IdeLogSource.LEVEL_TRACE, "", "");
    assertEquals(3, recent.size());
    // 「最近 3 条」= m8, m9, m10，且按时间正序（m8 在前）
    assertEquals("m8", recent.get(0).getMessage());
    assertEquals("m10", recent.get(2).getMessage());
  }

  @Test
  void capacityOneKeepsOnlyLatest() {
    IdeLogBuffer buffer = new IdeLogBuffer(1);
    append(buffer, IdeLogSource.LEVEL_ERROR, "a", "first");
    append(buffer, IdeLogSource.LEVEL_ERROR, "a", "second");
    List<IdeLogSource.Entry> all = buffer.read(10, IdeLogSource.LEVEL_TRACE, "", "");
    assertEquals(1, all.size());
    assertEquals("second", all.get(0).getMessage());
  }

  @Test
  void zeroOrNegativeLimitReturnsEmpty() {
    IdeLogBuffer buffer = new IdeLogBuffer(10);
    append(buffer, IdeLogSource.LEVEL_ERROR, "a", "x");
    assertTrue(buffer.read(0, IdeLogSource.LEVEL_TRACE, "", "").isEmpty());
    assertTrue(buffer.read(-5, IdeLogSource.LEVEL_TRACE, "", "").isEmpty());
  }

  @Test
  void filtersByLevelLoggerAndText() {
    IdeLogBuffer buffer = new IdeLogBuffer(50);
    append(buffer, IdeLogSource.LEVEL_ERROR, "com.tom.rv2ide.ai.Protocol", "bad request");
    append(buffer, IdeLogSource.LEVEL_INFO, "com.tom.rv2ide.ai.Protocol", "noise");
    append(buffer, IdeLogSource.LEVEL_WARN, "com.tom.rv2ide.ui.Editor", "also bad");

    // 默认门槛 warn：INFO 被滤掉
    assertEquals(2, buffer.read(10, IdeLogSource.LEVEL_WARN, "", "").size());
    // logger 过滤
    assertEquals(1, buffer.read(10, IdeLogSource.LEVEL_WARN, "editor", "").size());
    // 文本过滤（大小写不敏感）
    assertEquals(2, buffer.read(10, IdeLogSource.LEVEL_WARN, "", "BAD").size());
    // level + logger + text 组合
    assertEquals(1, buffer.read(10, IdeLogSource.LEVEL_ERROR, "protocol", "bad").size());
  }

  @Test
  void matchesTextInsideThrowable() {
    IdeLogBuffer buffer = new IdeLogBuffer(10);
    buffer.append(
        1L, IdeLogSource.LEVEL_ERROR, "ai", "msg", "java.lang.IllegalStateException: kaput");
    assertEquals(1, buffer.read(10, IdeLogSource.LEVEL_ERROR, "", "illegalstate").size());
  }

  @Test
  void clipsOverlongMessageAndThrowable() {
    IdeLogBuffer buffer = new IdeLogBuffer(10);
    StringBuilder huge = new StringBuilder();
    for (int i = 0; i < 5000; i++) {
      huge.append('x');
    }
    buffer.append(1L, IdeLogSource.LEVEL_ERROR, "ai", huge.toString(), huge.toString());
    IdeLogSource.Entry entry = buffer.read(1, IdeLogSource.LEVEL_ERROR, "", "").get(0);
    assertTrue(entry.getMessage().endsWith("(截断)"));
    assertTrue(entry.getThrowable().endsWith("(截断)"));
    assertTrue(entry.getMessage().length() < 2100, "消息应被裁到 2000 附近");
  }

  @Test
  void unavailableUntilMarkedStarted() {
    IdeLogBuffer buffer = new IdeLogBuffer(10);
    assertFalse(buffer.isAvailable());
    assertFalse(buffer.unavailableReason().isEmpty());
    buffer.markStarted();
    assertTrue(buffer.isAvailable());
    assertTrue(buffer.unavailableReason().isEmpty());
  }

  @Test
  void concurrentAppendsDoNotCorruptOrExceedCapacity() throws Exception {
    IdeLogBuffer buffer = new IdeLogBuffer(500);
    int threads = 8;
    int perThread = 500;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
    for (int t = 0; t < threads; t++) {
      final int threadId = t;
      futures.add(
          pool.submit(
              () -> {
                start.await();
                for (int i = 0; i < perThread; i++) {
                  append(buffer, IdeLogSource.LEVEL_ERROR, "t" + threadId, "m" + i);
                }
                return null;
              }));
    }
    start.countDown();
    for (java.util.concurrent.Future<?> f : futures) {
      f.get(30, TimeUnit.SECONDS);
    }
    pool.shutdown();

    List<IdeLogSource.Entry> all = buffer.read(100000, IdeLogSource.LEVEL_TRACE, "", "");
    // 不超容量；且读到的条数正好等于容量（因为写入远多于容量）
    assertEquals(500, all.size());
    // 全部条目非空、无「半写」的破损项
    for (IdeLogSource.Entry e : all) {
      assertFalse(e.getMessage().isEmpty());
      assertFalse(e.getLogger().isEmpty());
    }
  }
}
