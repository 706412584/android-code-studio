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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.ai.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 回滚与审查的回归测试。
 *
 * <p><b>为什么需要它</b>：回滚会直接改用户的源码文件。两类错误代价最高——
 * 恢复到错误的内容（用户的工作被覆盖成别的版本）、以及状态与文件不一致
 * （界面说已撤销、文件其实没变，用户便不会再去检查）。这两类都必须有测试钉住。
 */
final class DiffRevertTest {

  private static ToolContext contextFor(Path root) {
    return ToolContext.builder().homePath(root.toString()).build();
  }

  private static void write(Path path, String content) throws Exception {
    Files.write(path, content.getBytes(StandardCharsets.UTF_8));
  }

  private static String read(Path path) throws Exception {
    return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
  }

  @Test
  void revertRestoresPreviousContent(@TempDir Path root) throws Exception {
    Path file = root.resolve("A.kt");
    write(file, "old");

    DiffStore store = new InMemoryDiffStore();
    DiffRecord record = store.recordDiff(file.toString(), "old", "new", true);
    write(file, "new");

    DiffReverter reverter = new DiffReverter(store);
    DiffReverter.RevertResult result = reverter.revert(record.getId(), contextFor(root));

    assertTrue(result.isSuccess(), result.getMessage());
    assertEquals("old", read(file));
    assertTrue(store.findById(record.getId()).isReverted());
  }

  @Test
  void revertOfCreatedFileDeletesItInsteadOfLeavingEmptyFile(@TempDir Path root) throws Exception {
    // 写入前文件不存在 → 回滚应删掉它。写空内容会留下一个空文件，与「改动前不存在」
    // 并不等价（例如构建脚本会因此认为该资源存在）。
    Path file = root.resolve("Created.kt");

    DiffStore store = new InMemoryDiffStore();
    DiffRecord record = store.recordDiff(file.toString(), "", "content", false);
    write(file, "content");
    assertTrue(Files.exists(file));

    DiffReverter.RevertResult result =
        new DiffReverter(store).revert(record.getId(), contextFor(root));

    assertTrue(result.isSuccess(), result.getMessage());
    assertFalse(Files.exists(file), "回滚新建文件应当删除它，而不是留下空文件");
  }

  @Test
  void revertIsIdempotentAndRefusesSecondRun(@TempDir Path root) throws Exception {
    Path file = root.resolve("A.kt");
    write(file, "old");

    DiffStore store = new InMemoryDiffStore();
    DiffRecord record = store.recordDiff(file.toString(), "old", "new", true);
    write(file, "new");
    DiffReverter reverter = new DiffReverter(store);

    assertTrue(reverter.revert(record.getId(), contextFor(root)).isSuccess());

    // 用户又改了文件，然后误点第二次撤销
    write(file, "user edit");
    DiffReverter.RevertResult second = reverter.revert(record.getId(), contextFor(root));

    assertFalse(second.isSuccess(), "已回滚的记录不应被再次回滚");
    assertEquals("user edit", read(file), "第二次回滚不得覆盖用户之后的编辑");
  }

  @Test
  void revertOutsideWorkspaceIsRefused(@TempDir Path root) throws Exception {
    // 记录里的路径可能来自另一个工作区（用户切过项目）。恢复前必须重新校验，
    // 否则回滚就成了绕过工作区边界的写入通道。
    Path outside = root.resolveSibling("outside-" + root.getFileName() + ".kt");
    write(outside, "outside");

    DiffStore store = new InMemoryDiffStore();
    DiffRecord record = store.recordDiff(outside.toString(), "outside", "changed", true);

    Path workspace = root.resolve("ws");
    Files.createDirectories(workspace);

    DiffReverter.RevertResult result =
        new DiffReverter(store).revert(record.getId(), contextFor(workspace));

    assertFalse(result.isSuccess(), "工作区之外的路径必须拒绝回滚");
    assertEquals("outside", read(outside), "拒绝时不得改动目标文件");
    Files.deleteIfExists(outside);
  }

  @Test
  void revertWithoutContextIsRefused(@TempDir Path root) throws Exception {
    Path file = root.resolve("A.kt");
    write(file, "new");

    DiffStore store = new InMemoryDiffStore();
    DiffRecord record = store.recordDiff(file.toString(), "old", "new", true);

    DiffReverter.RevertResult result = new DiffReverter(store).revert(record.getId(), null);

    assertFalse(result.isSuccess());
    assertEquals("new", read(file));
  }

  @Test
  void revertUnknownIdReportsSpecificReason(@TempDir Path root) {
    // 提示要具体到「存储未持久化」，否则用户只看到「失败」而不知为何。
    DiffReverter.RevertResult result =
        new DiffReverter(new InMemoryDiffStore()).revert("nonexistent", contextFor(root));

    assertFalse(result.isSuccess());
    assertTrue(result.getMessage().contains("找不到"), result.getMessage());
  }

  @Test
  void revertLatestForUsesMostRecentRecord(@TempDir Path root) throws Exception {
    Path file = root.resolve("A.kt");
    write(file, "v1");

    DiffStore store = new InMemoryDiffStore();
    store.recordDiff(file.toString(), "v1", "v2", true);
    DiffRecord second = store.recordDiff(file.toString(), "v2", "v3", true);
    write(file, "v3");

    DiffReverter.RevertResult result =
        new DiffReverter(store).revertLatestFor(file.toString(), contextFor(root));

    assertTrue(result.isSuccess(), result.getMessage());
    // 最近一条是 v2→v3，回滚应恢复到 v2，而不是最旧的 v1
    assertEquals("v2", read(file));
    assertNotNull(second);
  }

  @Test
  void revertSetsReviewStateToRejected(@TempDir Path root) throws Exception {
    // 回滚的语义就是用户拒绝了这次改动。两处状态若不一致，UI 会显示
    // 「已接受但内容已还原」这种自相矛盾的结果。
    Path file = root.resolve("A.kt");
    write(file, "old");

    DiffStore store = new InMemoryDiffStore();
    DiffRecord record = store.recordDiff(file.toString(), "old", "new", true);
    write(file, "new");

    new DiffReverter(store).revert(record.getId(), contextFor(root));

    DiffRecord updated = store.findById(record.getId());
    assertTrue(updated.isReverted());
    assertEquals(DiffRecord.REVIEW_REJECTED, updated.getReviewState());
  }

  @Test
  void setReviewUpdatesStateWithoutTouchingFile(@TempDir Path root) throws Exception {
    Path file = root.resolve("A.kt");
    write(file, "old");

    DiffStore store = new InMemoryDiffStore();
    DiffRecord record = store.recordDiff(file.toString(), "old", "new", true);
    write(file, "new");

    DiffRecord updated = store.setReview(record.getId(), DiffRecord.REVIEW_ACCEPTED, "看起来没问题");

    assertEquals(DiffRecord.REVIEW_ACCEPTED, updated.getReviewState());
    assertEquals("看起来没问题", updated.getReviewMessage());
    assertFalse(updated.isReverted());
    // 接受审查不应改动文件
    assertEquals("new", read(file));
  }

  @Test
  void findByPathAndByIdStayConsistentAfterStatusChange(@TempDir Path root) throws Exception {
    // 记录不可变，状态变化产生新实例。若只更新 id 索引而不更新路径链，
    // 会出现「按 id 查到已回滚、按路径查到未回滚」的不一致。
    Path file = root.resolve("A.kt");

    DiffStore store = new InMemoryDiffStore();
    DiffRecord record = store.recordDiff(file.toString(), "old", "new", true);

    store.markReverted(record.getId());

    assertTrue(store.findById(record.getId()).isReverted());
    assertTrue(store.getDiffChain(file.toString()).get(0).isReverted());
    assertTrue(store.latestFor(file.toString()).isReverted());
    assertTrue(store.getAll().get(0).isReverted());
  }

  @Test
  void statusChangeOnUnknownIdReturnsNullInsteadOfThrowing() {
    DiffStore store = new InMemoryDiffStore();
    assertNull(store.markReverted("nope"));
    assertNull(store.setReview("nope", DiffRecord.REVIEW_ACCEPTED, ""));
    assertNull(store.findById("nope"));
  }

  @Test
  void reviewStateDefaultsToPending() {
    DiffRecord record = new DiffRecord("id", "/p", "old", "new", true, 1L);
    assertEquals(DiffRecord.REVIEW_PENDING, record.getReviewState());
    assertFalse(record.isReverted());
    assertEquals("", record.getReviewMessage());
  }

  @Test
  void revertingTwiceProducesSameContent(@TempDir Path root) throws Exception {
    // asReverted 必须幂等：重复派生不能把 oldContent 改掉，
    // 否则第二次回滚会写到错误的内容。
    Path file = root.resolve("A.kt");
    write(file, "old");

    DiffStore store = new InMemoryDiffStore();
    DiffRecord record = store.recordDiff(file.toString(), "old", "new", true);
    write(file, "new");

    new DiffReverter(store).revert(record.getId(), contextFor(root));
    assertEquals("old", read(file));

    // 手动把文件改回去，再直接调一次 markReverted（绕过「已回滚」检查）
    write(file, "new");
    DiffRecord again = store.markReverted(record.getId());
    assertNotNull(again);
    assertEquals("old", again.getOldContent());
  }

  @Test
  void fileStorePersistsRecordsAcrossInstances(@TempDir Path root) throws Exception {
    // 「重启应用后仍可撤销」这条验收要求只有持久化实现能满足。
    File log = root.resolve("diffs.jsonl").toFile();
    Path file = root.resolve("A.kt");
    write(file, "old");

    DiffStore first = new FileDiffStore(log);
    DiffRecord record = first.recordDiff(file.toString(), "old", "new", true);
    write(file, "new");

    // 模拟进程重启：新建实例读同一个文件
    DiffStore second = new FileDiffStore(log);
    DiffRecord reloaded = second.findById(record.getId());

    assertNotNull(reloaded, "重启后应能按 id 查到记录");
    assertEquals("old", reloaded.getOldContent());
    assertEquals(file.toString(), reloaded.getFilePath());

    DiffReverter.RevertResult result =
        new DiffReverter(second).revert(record.getId(), contextFor(root));
    assertTrue(result.isSuccess(), result.getMessage());
    assertEquals("old", read(file));
  }

  @Test
  void fileStorePersistsRevertedFlagAcrossInstances(@TempDir Path root) throws Exception {
    File log = root.resolve("diffs.jsonl").toFile();
    Path file = root.resolve("A.kt");
    write(file, "old");

    DiffStore first = new FileDiffStore(log);
    DiffRecord record = first.recordDiff(file.toString(), "old", "new", true);
    write(file, "new");
    new DiffReverter(first).revert(record.getId(), contextFor(root));

    DiffStore second = new FileDiffStore(log);
    assertTrue(second.findById(record.getId()).isReverted(), "回滚标记必须跨重启保留");
  }

  @Test
  void fileStorePersistsReviewStateAcrossInstances(@TempDir Path root) throws Exception {
    File log = root.resolve("diffs.jsonl").toFile();

    DiffStore first = new FileDiffStore(log);
    DiffRecord record = first.recordDiff("/p/A.kt", "old", "new", true);
    first.setReview(record.getId(), DiffRecord.REVIEW_ACCEPTED, "ok");

    DiffStore second = new FileDiffStore(log);
    assertEquals(DiffRecord.REVIEW_ACCEPTED, second.findById(record.getId()).getReviewState());
    assertEquals("ok", second.findById(record.getId()).getReviewMessage());
  }

  @Test
  void fileStoreSkipsCorruptLinesAndKeepsRest(@TempDir Path root) throws Exception {
    // 文件是 append-only 且用户可能手工编辑（ACS 是 IDE）。一行损坏不该让
    // 全部历史不可读。
    File log = root.resolve("diffs.jsonl").toFile();
    FileDiffStore store = new FileDiffStore(log);
    DiffRecord a = store.recordDiff("/p/A.kt", "old", "new", true);

    String good = new String(Files.readAllBytes(log.toPath()), StandardCharsets.UTF_8);
    String corrupted = "this is not json\n" + good + "{\"t\":\"d\",\"id\":\"trunc\n";
    Files.write(log.toPath(), corrupted.getBytes(StandardCharsets.UTF_8));

    FileDiffStore reloaded = new FileDiffStore(log);
    assertNotNull(reloaded.findById(a.getId()), "坏行之后的合法记录仍应可读");
    assertEquals(1, reloaded.size());
  }

  @Test
  void fileStoreAppendsWithoutRewritingExistingHistory(@TempDir Path root) throws Exception {
    // append-only 是崩溃安全的前提：重写整个文件时崩溃会同时丢掉新旧历史。
    File log = root.resolve("diffs.jsonl").toFile();
    DiffStore store = new FileDiffStore(log);

    store.recordDiff("/p/A.kt", "o1", "n1", true);
    long afterFirst = log.length();
    store.recordDiff("/p/B.kt", "o2", "n2", true);

    assertTrue(log.length() > afterFirst, "新增记录应当追加而非重写");
    String text = new String(Files.readAllBytes(log.toPath()), StandardCharsets.UTF_8);
    assertTrue(text.contains("/p/A.kt") && text.contains("/p/B.kt"));
  }

  @Test
  void fileStoreCompactsWhenExceedingLimitAndStaysConsistent(@TempDir Path root) throws Exception {
    // 裁剪会原地重写文件（唯一这样的路径）。裁剪后内存索引必须与文件一致，
    // 否则出现「本次进程能查到、重启后就消失」。
    File log = root.resolve("diffs.jsonl").toFile();
    // 极小的上限，强制写入后立即触发裁剪
    FileDiffStore store = new FileDiffStore(log, 1);

    for (int i = 0; i < 40; i++) {
      store.recordDiff("/p/A.kt", "o" + i, "n" + i, true);
    }

    FileDiffStore reloaded = new FileDiffStore(log);
    assertEquals(
        store.size(),
        reloaded.size(),
        "裁剪后内存记录数必须与重载后一致（否则内存里留着文件里已无的记录）");
    assertTrue(store.size() <= 20, "裁剪后每个文件的记录数应受限，实际 " + store.size());
    // 保留的应当是最新的若干条，而不是最旧的
    assertEquals("n39", store.latestFor("/p/A.kt").getNewContent());
  }

  @Test
  void fileStoreHandlesMissingParentDirectory(@TempDir Path root) {
    File log = root.resolve("nested/deeper/diffs.jsonl").toFile();
    DiffStore store = new FileDiffStore(log);
    DiffRecord record = store.recordDiff("/p/A.kt", "old", "new", true);

    assertNotNull(record);
    assertTrue(log.exists(), "父目录应被自动创建");
  }

  @Test
  void fileStoreSurvivesUnwritableLocation() {
    // 写不进去时静默降级为仅内存记录：回滚是补救手段，不能因为磁盘问题
    // 让 agent 的文件写入操作看起来失败了。
    File log = new File("/proc/nonexistent/diffs.jsonl");
    DiffStore store = new FileDiffStore(log);
    DiffRecord record = store.recordDiff("/p/A.kt", "old", "new", true);

    assertNotNull(record);
    assertNotNull(store.findById(record.getId()));
  }

  @Test
  void fileStoreGetDiffChainIsOrderedByTime(@TempDir Path root) throws Exception {
    File log = root.resolve("diffs.jsonl").toFile();
    DiffStore store = new FileDiffStore(log);
    DiffRecord first = store.recordDiff("/p/A.kt", "v1", "v2", true);
    DiffRecord second = store.recordDiff("/p/A.kt", "v2", "v3", true);

    // 重载后链的顺序必须仍是改动顺序，否则 latestFor 会返回错误的「最近一次改动」
    DiffStore reloaded = new FileDiffStore(log);
    java.util.List<DiffRecord> chain = reloaded.getDiffChain("/p/A.kt");

    assertEquals(2, chain.size());
    assertEquals(first.getId(), chain.get(0).getId());
    assertEquals(second.getId(), chain.get(1).getId());
    assertEquals(second.getId(), reloaded.latestFor("/p/A.kt").getId());
  }
}
