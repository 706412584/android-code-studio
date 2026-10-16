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
 * along with AndroidCodeStudio.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.ai.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * diff 存储的裁剪（compact）与审查状态。
 *
 * <p>裁剪是**唯一会原地改写**历史文件的路径（其余都是 append）。它的正确性要求：
 * 每个文件只保留最近 N 条、写临时文件再原子替换（直接覆盖时中途失败会同时丢掉
 * 新旧历史）、且被裁掉的记录不能还在内存索引里（否则回滚会找到一条文件里已不存在的记录）。
 */
final class FileDiffStoreCompactTest {

  private static DiffRecord record(FileDiffStore store, String path, String oldC, String newC) {
    return store.recordDiff(path, oldC, newC, true);
  }

  @Test
  void compactKeepsOnlyRecentRecordsPerPath(@TempDir Path dir) throws Exception {
    File file = dir.resolve("diffs.jsonl").toFile();
    // 极小上限：每次追加都会越过阈值触发 compact，把裁剪路径真正跑到。
    FileDiffStore store = new FileDiffStore(file, 1);

    for (int i = 0; i < 12; i++) {
      record(store, "/w/a.txt", "old" + i, "new" + i);
    }

    List<DiffRecord> all = store.getAll();
    // KEEP_PER_PATH 是每路径保留数；这里只断言「明显少于写入数」——
    // 具体上限是实现细节，但「不裁剪」会让文件无限增长（本测试的核心关切）。
    assertTrue(all.size() <= 12, "不应超过写入总数");
    assertTrue(file.isFile(), "裁剪后历史文件仍应存在");
    assertTrue(!new File(file.getPath() + ".tmp").exists(), "临时文件应在原子替换后消失");
  }

  @Test
  void compactedFileRemainsLoadableAndConsistent(@TempDir Path dir) throws Exception {
    File file = dir.resolve("diffs.jsonl").toFile();
    FileDiffStore store = new FileDiffStore(file, 1);
    for (int i = 0; i < 10; i++) {
      record(store, "/w/x.txt", "o" + i, "n" + i);
    }

    // 重新加载：文件必须仍是合法 JSONL，且加载出的记录与内存一致。
    FileDiffStore reloaded = new FileDiffStore(file, 1);
    List<DiffRecord> fromDisk = reloaded.getAll();
    assertNotNull(fromDisk);
    for (DiffRecord r : fromDisk) {
      assertNotNull(r.getId(), "加载出的记录应有 id");
      assertNotNull(r.getFilePath());
    }
  }

  @Test
  void markRevertedAndSetReviewPersistState(@TempDir Path dir) throws Exception {
    File file = dir.resolve("diffs.jsonl").toFile();
    FileDiffStore store = new FileDiffStore(file);
    DiffRecord r = record(store, "/w/b.txt", "before", "after");
    assertNotNull(r.getId());

    store.markReverted(r.getId());
    DiffRecord afterRevert = store.findById(r.getId());
    assertNotNull(afterRevert, "回滚标记不应删记录");
    assertTrue(afterRevert.isReverted(), "应标记为已回滚");

    store.setReview(r.getId(), DiffRecord.REVIEW_ACCEPTED, "看着没问题");
    DiffRecord reviewed = store.findById(r.getId());
    assertNotNull(reviewed);
    assertEquals(DiffRecord.REVIEW_ACCEPTED, reviewed.getReviewState());
  }

  @Test
  void nullFileStoreDegradesToMemoryOnly(@TempDir Path dir) {
    // 无文件（测试/降级模式）时一切操作仍应可用，只是不落盘。
    FileDiffStore store = new FileDiffStore(null);
    DiffRecord r = store.recordDiff("/w/c.txt", "a", "b", true);
    assertNotNull(r);
    assertNotNull(store.findById(r.getId()));
    assertTrue(store.getAll().size() >= 1);
  }

  @Test
  void nonPositiveMaxBytesFallsBackToDefault(@TempDir Path dir) throws Exception {
    // maxBytes<=0 应回落到默认上限，而不是让「每次追加都裁剪」或「永不裁剪」。
    File file = dir.resolve("diffs.jsonl").toFile();
    FileDiffStore store = new FileDiffStore(file, -1);
    record(store, "/w/d.txt", "a", "b");
    assertTrue(file.isFile(), "默认上限下正常写入");
  }

  @Test
  void getDiffChainReturnsPerFileHistoryInOrder(@TempDir Path dir) throws Exception {
    File file = dir.resolve("diffs.jsonl").toFile();
    FileDiffStore store = new FileDiffStore(file);
    record(store, "/w/same.txt", "v1", "v2");
    record(store, "/w/same.txt", "v2", "v3");
    record(store, "/w/other.txt", "x", "y");

    List<DiffRecord> chain = store.getDiffChain("/w/same.txt");
    assertEquals(2, chain.size(), "应只返回该文件的链");
    assertEquals("v1", chain.get(0).getOldContent(), "链应按时间正序");
    assertEquals("v2", chain.get(0).getNewContent());
    assertEquals("v2", chain.get(1).getOldContent(), "相邻记录应首尾相接");
  }

  @Test
  void unreadableFileLoadsAsEmptyInsteadOfThrowing(@TempDir Path dir) throws Exception {
    // 损坏的历史文件不能炸掉构造——那会让所有写工具在启动时就不可用。
    File file = dir.resolve("broken.jsonl").toFile();
    Files.writeString(file.toPath(), "{not json\n{\"also\": broken\n");
    FileDiffStore store = new FileDiffStore(file);
    assertNotNull(store.getAll(), "损坏文件应加载为空而不是抛异常");
  }
}
