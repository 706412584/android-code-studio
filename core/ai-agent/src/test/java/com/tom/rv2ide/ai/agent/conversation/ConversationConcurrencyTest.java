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

package com.tom.rv2ide.ai.agent.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 并发 append 的不变量：OS 级文件锁保证「读长度→seek→写入」原子，多线程对同一文件
 * 并发追加时不得出现「同偏移覆盖」导致的消息丢失或行粘连。
 */
final class ConversationConcurrencyTest {

  @Test
  void concurrentAppendsDoNotLoseOrCorruptMessages(@TempDir Path dir) throws Exception {
    ConversationLog log = new ConversationLog(dir.resolve("c.jsonl").toFile());
    final int threads = 8;
    final int perThread = 25;
    final long t0 = 1_700_000_000_000L;

    ExecutorService pool = Executors.newFixedThreadPool(threads);
    List<Future<?>> futures = new ArrayList<>();
    for (int t = 0; t < threads; t++) {
      final int id = t;
      futures.add(
          pool.submit(
              () -> {
                for (int i = 0; i < perThread; i++) {
                  log.append(UserMessageEntry.create(null, t0, "t" + id + "-" + i));
                }
                return null;
              }));
    }
    for (Future<?> f : futures) {
      f.get();
    }
    pool.shutdown();

    List<ConversationLog.EntryLocation> entries = log.readAll();
    assertEquals(threads * perThread, entries.size(), "并发 append 不应丢消息（文件锁防同偏移覆盖）");
    // 每条内容都应完好（无粘连、无被覆盖成别的消息）。
    long wellFormed = entries.stream().filter(e -> e.getEntry() instanceof UserMessageEntry).count();
    assertEquals(threads * perThread, wellFormed, "每条消息都应可独立解析");
  }

  @Test
  void concurrentAppendsAcrossStoreInstances(@TempDir Path dir) throws Exception {
    // 跨 Store 实例（锁不共享）：文件锁在 OS 层互斥，覆盖「锁不跨实例」的场景。
    final java.io.File file = dir.resolve("shared.jsonl").toFile();
    final int threads = 6;
    final int perThread = 20;
    final long t0 = 1_700_000_000_000L;

    ExecutorService pool = Executors.newFixedThreadPool(threads);
    List<Future<?>> futures = new ArrayList<>();
    for (int t = 0; t < threads; t++) {
      final int id = t;
      futures.add(
          pool.submit(
              () -> {
                // 每个线程用**自己独立**的 ConversationLog 实例写同一文件。
                ConversationLog log = new ConversationLog(file);
                for (int i = 0; i < perThread; i++) {
                  log.append(UserMessageEntry.create(null, t0, "i" + id + "-" + i));
                }
                return null;
              }));
    }
    for (Future<?> f : futures) {
      f.get();
    }
    pool.shutdown();

    List<ConversationLog.EntryLocation> entries = new ConversationLog(file).readAll();
    assertEquals(threads * perThread, entries.size(), "跨实例并发写同一文件不应丢消息");
    assertTrue(
        entries.stream().allMatch(e -> e.getEntry() instanceof UserMessageEntry),
        "跨实例写入不应产生粘连/坏行");
  }
}
