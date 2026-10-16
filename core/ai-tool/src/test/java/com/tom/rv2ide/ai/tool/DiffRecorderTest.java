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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 写工具执行前后的 diff 记录。
 *
 * <p>记录 diff 是「AI 改了什么」可回滚的前提。这里钉住的是记录**时机**与**边界**：
 * 只在内容真的变了才记（否则每次覆盖相同内容都产生一条空 diff，回滚列表被噪声淹没）、
 * 目标是目录时拒绝写入（不是让 FileOutputStream 抛 IOException 再兜）、
 * 工具自身失败时不记录（失败没改文件，记了会把「失败」混进可回滚历史）。
 */
final class DiffRecorderTest {

  /** 记录调用的假 DiffStore：只实现本测试关心的 recordDiff。 */
  private static final class FakeStore implements DiffStore {
    final List<String> recorded = new ArrayList<>();
    final List<Boolean> oldExistsFlags = new ArrayList<>();

    @Override
    public DiffRecord recordDiff(
        String filePath, String oldContent, String newContent, boolean oldExists) {
      recorded.add(filePath + "|" + oldContent + "=>" + newContent);
      oldExistsFlags.add(oldExists);
      return new DiffRecord("diff-1", filePath, oldContent, newContent, oldExists, 0L);
    }
  }

  private static ToolContext ctx(Path root) {
    return ToolContext.builder().homePath(root.toString()).build();
  }

  private static JSONObject writeArgs(String path, String content) {
    JSONObject json = new JSONObject();
    json.put("file_path", path);
    json.put("content", content);
    return json;
  }

  @Test
  void recordsDiffWhenContentChanges(@TempDir Path root) throws Exception {
    Files.writeString(root.resolve("a.txt"), "old\n");
    FakeStore store = new FakeStore();
    DiffRecorder recorder = new DiffRecorder(store);

    ToolResult result =
        recorder.executeWithDiff(
            new FileWriteTool(), writeArgs("a.txt", "new\n"), ctx(root));

    assertFalse(result.isError(), result.getContent());
    assertEquals(1, store.recorded.size(), "内容变化应产生一条 diff");
    assertEquals("diff-1", result.getDiffId(), "结果应带上 diff id 供回滚引用");
    assertEquals(Boolean.TRUE, store.oldExistsFlags.get(0), "已存在文件应标记 oldExists=true");
  }

  @Test
  void doesNotRecordWhenContentUnchanged(@TempDir Path root) throws Exception {
    Files.writeString(root.resolve("same.txt"), "identical\n");
    FakeStore store = new FakeStore();
    DiffRecorder recorder = new DiffRecorder(store);

    ToolResult result =
        recorder.executeWithDiff(
            new FileWriteTool(), writeArgs("same.txt", "identical\n"), ctx(root));

    assertFalse(result.isError());
    assertTrue(store.recorded.isEmpty(), "内容未变不应记录 diff（避免回滚列表被噪声淹没）");
    // getDiffId 用 nullToEmpty 规整，无 diff 时是空串而非 null（调用方判 isEmpty 即可）。
    assertTrue(result.getDiffId() == null || result.getDiffId().isEmpty(),
        "无变更时不应带 diff id，实际：" + result.getDiffId());
  }

  @Test
  void recordsCreationWithOldExistsFalse(@TempDir Path root) {
    FakeStore store = new FakeStore();
    DiffRecorder recorder = new DiffRecorder(store);

    ToolResult result =
        recorder.executeWithDiff(new FileWriteTool(), writeArgs("fresh.txt", "hello\n"), ctx(root));

    assertFalse(result.isError(), result.getContent());
    assertEquals(1, store.recorded.size(), "新建文件应记录 diff（回滚时删除它）");
    assertEquals(Boolean.FALSE, store.oldExistsFlags.get(0), "新建时 oldExists 应为 false");
  }

  @Test
  void refusesDirectoryTarget(@TempDir Path root) throws Exception {
    Files.createDirectories(root.resolve("adir"));
    FakeStore store = new FakeStore();
    DiffRecorder recorder = new DiffRecorder(store);

    ToolResult result =
        recorder.executeWithDiff(new FileWriteTool(), writeArgs("adir", "x"), ctx(root));

    assertTrue(result.isError(), "目标是目录应明确拒绝");
    assertTrue(result.getContent().contains("目录"), result.getContent());
    assertTrue(store.recorded.isEmpty(), "被拒绝的写入不应产生 diff");
  }

  @Test
  void toolFailureIsNotRecorded(@TempDir Path root) throws Exception {
    FakeStore store = new FakeStore();
    DiffRecorder recorder = new DiffRecorder(store);

    // 目标路径的父级是文件（不是目录）→ 无法创建父目录 → FileWriteTool 自身失败。
    // 此时不应记录 diff：失败没改文件，记了会把失败混进可回滚历史。
    Files.writeString(root.resolve("notadir"), "i am a file\n");
    JSONObject args = writeArgs("notadir/child.txt", "x");
    ToolResult result = recorder.executeWithDiff(new FileWriteTool(), args, ctx(root));

    assertTrue(result.isError(), "父级是文件时写入应失败：" + result.getContent());
    assertTrue(store.recorded.isEmpty(), "失败的写入没改文件，不应记入可回滚历史");
  }

  @Test
  void shouldRecordDiffRequiresStoreAndFlag() {
    FakeStore store = new FakeStore();
    DiffRecorder withStore = new DiffRecorder(store);
    DiffRecorder withoutStore = new DiffRecorder(null);

    assertTrue(withStore.shouldRecordDiff(new FileWriteTool()), "有 store + 工具声明需要 → true");
    assertFalse(withStore.shouldRecordDiff(new FileReadTool()), "只读工具不应记录 diff");
    assertFalse(withoutStore.shouldRecordDiff(new FileWriteTool()), "无 store 时不应记录");
  }
}
