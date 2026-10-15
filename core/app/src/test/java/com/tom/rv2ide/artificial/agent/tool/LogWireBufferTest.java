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

package com.tom.rv2ide.artificial.agent.tool;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import io.github.mohammedbaqernull.logger.model.LogEntry;
import java.util.List;
import org.junit.Test;

/**
 * LogWire 环形缓冲的回归测试（Kotlin 类，从 Java 调用）。
 *
 * <p><b>为什么需要它</b>：缓冲最容易出的错是「多应用互相挤占」——单一缓冲下，
 * 话多的应用会把安静应用的日志挤掉，查询时明明只关心 A 却看不到 A。这类 bug
 * 只在多应用同时上报时出现，手工验证极易漏掉，必须有测试钉住。
 */
public final class LogWireBufferTest {

  /** 造一条日志。 */
  private static LogEntry entry(String pkg, String tag, String msg, long ts) {
    return new LogEntry(pkg, tag, msg, LogEntry.LogLevel.INFO, ts);
  }

  @Test
  public void perPackageLimitDropsOldest() {
    // 单包超限：保留最新的 N 条，最旧的被丢弃。
    LogWireBuffer buffer = new LogWireBuffer(3);
    for (int i = 0; i < 5; i++) {
      buffer.add(entry("com.a", "T", "msg" + i, 1000 + i));
    }

    List<LogEntry> out = buffer.snapshot("com.a", 0);
    assertEquals("超限后应只剩 3 条", 3, out.size());
    assertEquals("应保留最新的三条", "msg2", out.get(0).getMessage());
    assertEquals("末尾应是最新一条", "msg4", out.get(2).getMessage());
  }

  @Test
  public void packagesDoNotEvictEachOther() {
    // 核心场景：A 话多、B 话少，B 的日志不能被 A 挤掉。
    LogWireBuffer buffer = new LogWireBuffer(3);
    for (int i = 0; i < 10; i++) {
      buffer.add(entry("com.talkative", "T", "spam" + i, 1000 + i));
    }
    buffer.add(entry("com.quiet", "T", "only one", 2000));

    List<LogEntry> quiet = buffer.snapshot("com.quiet", 0);
    assertEquals("安静应用的日志必须保留", 1, quiet.size());
    assertEquals("only one", quiet.get(0).getMessage());
  }

  @Test
  public void snapshotTakesLatestNotEarliest() {
    // limit 应取**最新**的 N 条（日志调试看的是最近发生了什么，不是最早）。
    LogWireBuffer buffer = new LogWireBuffer(100);
    for (int i = 0; i < 10; i++) {
      buffer.add(entry("com.a", "T", "msg" + i, 1000 + i));
    }

    List<LogEntry> out = buffer.snapshot("com.a", 3);
    assertEquals(3, out.size());
    assertEquals("应取最新的三条", "msg7", out.get(0).getMessage());
    assertEquals("msg9", out.get(2).getMessage());
  }

  @Test
  public void allPackagesMergedInTimeOrder() {
    // 不指定包名：合并全部包，且按时间戳排序（保持「末尾最新」语义）。
    LogWireBuffer buffer = new LogWireBuffer(100);
    buffer.add(entry("com.b", "T", "b-late", 3000));
    buffer.add(entry("com.a", "T", "a-early", 1000));
    buffer.add(entry("com.b", "T", "b-mid", 2000));

    List<LogEntry> out = buffer.snapshot(null, 0);
    assertEquals(3, out.size());
    assertEquals("应按时间升序", "a-early", out.get(0).getMessage());
    assertEquals("b-mid", out.get(1).getMessage());
    assertEquals("b-late", out.get(2).getMessage());
  }

  @Test
  public void unknownPackageReturnsEmpty() {
    LogWireBuffer buffer = new LogWireBuffer(10);
    buffer.add(entry("com.a", "T", "x", 1000));

    assertTrue("未知包名应返回空列表而不是抛异常", buffer.snapshot("com.unknown", 0).isEmpty());
  }

  @Test
  public void packagesListedInFirstSeenOrder() {
    LogWireBuffer buffer = new LogWireBuffer(10);
    buffer.add(entry("com.b", "T", "x", 1000));
    buffer.add(entry("com.a", "T", "y", 2000));

    List<String> pkgs = buffer.packages();
    assertEquals(2, pkgs.size());
    assertEquals("com.b", pkgs.get(0));
    assertEquals("com.a", pkgs.get(1));
  }

  @Test
  public void countReflectsPerPackageSize() {
    LogWireBuffer buffer = new LogWireBuffer(10);
    buffer.add(entry("com.a", "T", "x", 1000));
    buffer.add(entry("com.a", "T", "y", 2000));

    assertEquals(2, buffer.count("com.a"));
    assertEquals("未上报过的包应为 0", 0, buffer.count("com.b"));
  }
}
