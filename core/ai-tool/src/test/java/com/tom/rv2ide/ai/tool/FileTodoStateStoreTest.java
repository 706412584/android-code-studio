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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link FileTodoStateStore} 的持久化边界：原子写、降级、条目过滤。
 *
 * <p>待办是模型「记住做到哪一步」的依据——写失败必须静默降级为仅内存（不能弄挂
 * 主任务），读失败必须当空列表（不能让损坏文件让工具不可用）。同时写路径用
 * 临时文件 + 原子替换，中途失败不能把旧列表弄丢。
 */
final class FileTodoStateStoreTest {

  @Test
  void clearEmptiesMemoryAndFile(@TempDir Path dir) throws Exception {
    File file = dir.resolve("todos.json").toFile();
    FileTodoStateStore store = new FileTodoStateStore(file);
    store.setItems(Arrays.asList(new TodoItem("a", TodoItem.STATUS_PENDING)));
    assertEquals(1, store.getItems().size());

    store.clear();

    assertTrue(store.getItems().isEmpty());
    // 文件里也应是空数组——重开实例不应看到旧条目。
    assertTrue(new FileTodoStateStore(file).getItems().isEmpty());
  }

  @Test
  void getFileExposesTarget(@TempDir Path dir) {
    File file = dir.resolve("todos.json").toFile();
    assertEquals(file, new FileTodoStateStore(file).getFile());
  }

  @Test
  void nullFileStoreDegradesToMemoryOnly() {
    // 测试/降级模式：null 文件时一切操作仍可用，只是不落盘。
    FileTodoStateStore store = new FileTodoStateStore(null);
    store.setItems(Arrays.asList(new TodoItem("x", TodoItem.STATUS_PENDING)));
    assertEquals(1, store.getItems().size());
    store.clear();
    assertTrue(store.getItems().isEmpty());
  }

  @Test
  void setNullItemsBecomesEmptyList(@TempDir Path dir) {
    FileTodoStateStore store = new FileTodoStateStore(dir.resolve("t.json").toFile());
    store.setItems(Arrays.asList(new TodoItem("x", TodoItem.STATUS_PENDING)));
    store.setItems(null);
    assertTrue(store.getItems().isEmpty());
  }

  @Test
  void saveCreatesMissingParentDirectories(@TempDir Path dir) {
    // 父目录不存在时必须自动创建（filesDir 子路径首写场景）。
    File file = dir.resolve("nested/deeper/todos.json").toFile();
    FileTodoStateStore store = new FileTodoStateStore(file);
    store.setItems(Arrays.asList(new TodoItem("step", TodoItem.STATUS_PENDING)));

    assertTrue(file.isFile(), "应自动创建父目录并写入");
    assertEquals(1, new FileTodoStateStore(file).getItems().size());
  }

  @Test
  void atomicWriteLeavesNoTempFile(@TempDir Path dir) {
    File file = dir.resolve("todos.json").toFile();
    FileTodoStateStore store = new FileTodoStateStore(file);
    store.setItems(Arrays.asList(new TodoItem("a", TodoItem.STATUS_PENDING)));
    store.setItems(Arrays.asList(new TodoItem("b", TodoItem.STATUS_IN_PROGRESS)));

    assertFalse(new File(file.getPath() + ".tmp").exists(), "临时文件应已改名消失");
  }

  @Test
  void loadSkipsEntriesWithoutContent(@TempDir Path dir) throws Exception {
    // 手工编辑过的文件里可能有空内容条目——读入时跳过，保留其余。
    File file = dir.resolve("todos.json").toFile();
    Files.write(file.toPath(),
        "[{\"c\":\"\",\"s\":\"pending\"},{\"c\":\"real\",\"s\":\"completed\"},{\"s\":\"pending\"}]"
            .getBytes(StandardCharsets.UTF_8));

    List<TodoItem> items = new FileTodoStateStore(file).getItems();
    assertEquals(1, items.size());
    assertEquals("real", items.get(0).getContent());
    assertEquals(TodoItem.STATUS_COMPLETED, items.get(0).getStatus());
  }

  @Test
  void loadSkipsNonObjectArrayElements(@TempDir Path dir) throws Exception {
    File file = dir.resolve("todos.json").toFile();
    Files.write(file.toPath(), "[1, \"x\", {\"c\":\"ok\"}]".getBytes(StandardCharsets.UTF_8));
    List<TodoItem> items = new FileTodoStateStore(file).getItems();
    assertEquals(1, items.size());
    assertEquals("ok", items.get(0).getContent());
  }

  @Test
  void loadUsesPendingAsDefaultStatus(@TempDir Path dir) throws Exception {
    File file = dir.resolve("todos.json").toFile();
    Files.write(file.toPath(), "[{\"c\":\"no-status\"}]".getBytes(StandardCharsets.UTF_8));
    List<TodoItem> items = new FileTodoStateStore(file).getItems();
    assertEquals(TodoItem.STATUS_PENDING, items.get(0).getStatus());
  }

  @Test
  void getItemsReturnsSnapshotNotLiveList(@TempDir Path dir) {
    // 返回防御性拷贝：调用方改返回值不得影响存储内部状态。
    FileTodoStateStore store = new FileTodoStateStore(dir.resolve("t.json").toFile());
    store.setItems(Arrays.asList(new TodoItem("a", TodoItem.STATUS_PENDING)));
    List<TodoItem> snapshot = store.getItems();
    snapshot.clear();
    assertEquals(1, store.getItems().size(), "外部修改不得影响内部列表");
  }

  @Test
  void missingFileLoadsAsEmpty(@TempDir Path dir) {
    FileTodoStateStore store = new FileTodoStateStore(dir.resolve("nope.json").toFile());
    assertNotNull(store.getItems());
    assertTrue(store.getItems().isEmpty());
  }
}
