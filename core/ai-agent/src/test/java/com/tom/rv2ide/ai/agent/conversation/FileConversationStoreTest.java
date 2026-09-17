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

package com.tom.rv2ide.ai.agent.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 会话存储的 CRUD 与边界。 */
final class FileConversationStoreTest {

  private static FileConversationStore storeAt(Path dir) {
    return new FileConversationStore(dir.toFile());
  }

  @Test
  void createsListsAndReadsConversation(@TempDir Path dir) throws IOException {
    FileConversationStore store = storeAt(dir);
    ConversationSummary created = store.create("第一个会话", "/workspace", "agnes-2.5-flash", "confirm");

    assertFalse(created.getId().isEmpty());
    assertEquals("第一个会话", created.getTitle());
    assertTrue(store.exists(created.getId()));

    List<ConversationSummary> list = store.list();
    assertEquals(1, list.size());
    assertEquals(created.getId(), list.get(0).getId());

    // 首条应为 session-meta
    List<ConversationLog.EntryLocation> entries = store.read(created.getId());
    assertEquals(ConversationEntry.Type.SESSION_META, entries.get(0).getEntry().getType());
    SessionMetaEntry meta = assertInstanceOf(SessionMetaEntry.class, entries.get(0).getEntry());
    assertEquals("/workspace", meta.getCwd());
    assertEquals("agnes-2.5-flash", meta.getModel());
    assertEquals("confirm", meta.getPermissionMode());
  }

  @Test
  void appendAddsEntriesReadableAfterReopen(@TempDir Path dir) throws IOException {
    FileConversationStore store = storeAt(dir);
    String id = store.create(null, "/workspace", "m", "confirm").getId();

    store.append(id, UserMessageEntry.create(null, 1L, "你好"));
    store.append(id, AssistantMessageEntry.create(null, 2L, "你好，有什么可以帮你", "", List.of()));

    // 重新打开（模拟进程重启）后消息仍在
    FileConversationStore reopened = storeAt(dir);
    List<ConversationLog.EntryLocation> entries = reopened.read(id);
    assertEquals(3, entries.size());
    assertEquals(
        "你好", assertInstanceOf(UserMessageEntry.class, entries.get(1).getEntry()).getContent());
    assertEquals(
        "你好，有什么可以帮你",
        assertInstanceOf(AssistantMessageEntry.class, entries.get(2).getEntry()).getContent());
  }

  @Test
  void renameAppendsInsteadOfRewriting(@TempDir Path dir) throws IOException {
    FileConversationStore store = storeAt(dir);
    String id = store.create("原标题", "/w", "m", "confirm").getId();
    long sizeBefore = store.read(id).size();

    store.rename(id, "新标题");

    List<ConversationLog.EntryLocation> entries = store.read(id);
    // 只追加一条，历史条目一条不动
    assertEquals(sizeBefore + 1, entries.size());
    ConversationEntry last = entries.get(entries.size() - 1).getEntry();
    assertInstanceOf(TitleEntry.class, last);
    assertEquals("新标题", ((TitleEntry) last).getTitle());

    // 摘要取最后一条标题
    assertEquals("新标题", store.list().get(0).getTitle());
  }

  @Test
  void summaryTitlePrefersUserRenameOverAiTitle(@TempDir Path dir) throws IOException {
    FileConversationStore store = storeAt(dir);
    String id = store.create(null, "/w", "m", "confirm").getId();
    store.append(id, UserMessageEntry.create(null, 1L, "帮我看看这个 bug 怎么修"));
    store.append(id, TitleEntry.aiTitle(null, 2L, "模型标题"));
    store.append(id, TitleEntry.customTitle(null, 3L, "用户标题"));

    assertEquals("用户标题", store.list().get(0).getTitle());
  }

  @Test
  void summaryTitleFallsBackToFirstUserMessage(@TempDir Path dir) throws IOException {
    FileConversationStore store = storeAt(dir);
    String id = store.create(null, "/w", "m", "confirm").getId();
    store.append(id, UserMessageEntry.create(null, 1L, "第一条用户消息"));

    assertEquals("第一条用户消息", store.list().get(0).getTitle());
  }

  @Test
  void summaryTitleTruncatesLongUserMessage(@TempDir Path dir) throws IOException {
    FileConversationStore store = storeAt(dir);
    String id = store.create(null, "/w", "m", "confirm").getId();
    StringBuilder longText = new StringBuilder();
    for (int i = 0; i < 100; i++) {
      longText.append("很长");
    }
    store.append(id, UserMessageEntry.create(null, 1L, longText.toString()));

    String title = store.list().get(0).getTitle();
    assertTrue(title.length() <= 41, "标题应被截断，实际长度 " + title.length());
    assertTrue(title.endsWith("…"));
  }

  @Test
  void summaryTitleFallsBackToPlaceholderWhenEmpty(@TempDir Path dir) throws IOException {
    FileConversationStore store = storeAt(dir);
    store.create(null, "/w", "m", "confirm");
    assertEquals(ConversationSummary.UNTITLED, store.list().get(0).getTitle());
  }

  @Test
  void summaryCountsOnlyVisibleMessages(@TempDir Path dir) throws IOException {
    FileConversationStore store = storeAt(dir);
    String id = store.create(null, "/w", "m", "confirm").getId();
    store.append(id, UserMessageEntry.create(null, 1L, "用户消息"));
    store.append(id, new UserMessageEntry(null, null, 2L, "系统注入", true));
    store.append(id, AssistantMessageEntry.create(null, 3L, "回复", "", List.of()));

    // meta 条目不计入，否则列表页的条数会包含用户看不到的内部消息
    assertEquals(2, store.list().get(0).getMessageCount());
  }

  @Test
  void listSortsByMostRecentlyModified(@TempDir Path dir) throws IOException {
    FileConversationStore store = storeAt(dir);
    String older = store.create("较早", "/w", "m", "confirm").getId();
    String newer = store.create("较晚", "/w", "m", "confirm").getId();
    // 给较早的会话追加一条更晚的消息，它应排到前面
    store.append(older, UserMessageEntry.create(null, System.currentTimeMillis() + 10_000L, "新消息"));

    assertEquals(older, store.list().get(0).getId());
    assertEquals(2, store.list().size());
  }

  @Test
  void deleteRemovesConversation(@TempDir Path dir) throws IOException {
    FileConversationStore store = storeAt(dir);
    String id = store.create("待删", "/w", "m", "confirm").getId();
    assertTrue(store.exists(id));

    store.delete(id);
    assertFalse(store.exists(id));
    assertEquals(0, store.list().size());
  }

  @Test
  void rejectsPathTraversalInConversationId(@TempDir Path dir) throws IOException {
    FileConversationStore store = storeAt(dir);
    store.create("正常", "/w", "m", "confirm");

    // 会话 id 参与文件名，必须拒绝穿越——否则可被诱导删除任意文件
    assertThrows(IllegalArgumentException.class, () -> store.read("../../etc/passwd"));
    assertThrows(IllegalArgumentException.class, () -> store.delete("../other"));
    assertThrows(IllegalArgumentException.class, () -> store.read("a/b"));
    assertThrows(IllegalArgumentException.class, () -> store.read(""));
    assertThrows(IllegalArgumentException.class, () -> store.read(null));
    assertFalse(store.exists("../../etc/passwd"));
  }

  @Test
  void skipsCorruptConversationInList(@TempDir Path dir) throws IOException {
    FileConversationStore store = storeAt(dir);
    store.create("正常会话", "/w", "m", "confirm");

    // 一个完全损坏的文件不应让整个列表不可用
    Path corrupt = dir.resolve("deadbeef-0000-0000-0000-000000000000.jsonl");
    Files.write(corrupt, "完全不是 JSON\n也不是\n".getBytes(StandardCharsets.UTF_8));

    // 损坏会话因无可解析条目，摘要为占位标题；关键是列表仍能返回
    List<ConversationSummary> list = store.list();
    assertEquals(2, list.size());
  }

  @Test
  void ignoresNonJsonlFilesInDirectory(@TempDir Path dir) throws IOException {
    FileConversationStore store = storeAt(dir);
    store.create("会话", "/w", "m", "confirm");
    Files.write(dir.resolve("README.txt"), "无关文件".getBytes(StandardCharsets.UTF_8));
    Files.createDirectory(dir.resolve("subdir.jsonl"));

    assertEquals(1, store.list().size(), "非 jsonl 文件与目录应被忽略");
  }

  @Test
  void listOnMissingDirectoryReturnsEmpty(@TempDir Path dir) throws IOException {
    FileConversationStore store = new FileConversationStore(dir.resolve("不存在").toFile());
    assertEquals(0, store.list().size());
  }

  @Test
  void multipleConversationsAreIsolated(@TempDir Path dir) throws IOException {
    FileConversationStore store = storeAt(dir);
    // 带标题的 create 写 session-meta + custom-title 两条，故基线为 2
    String first = store.create("甲", "/w", "m", "confirm").getId();
    String second = store.create("乙", "/w", "m", "confirm").getId();
    assertEquals(2, store.read(second).size());

    store.append(first, UserMessageEntry.create(null, 1L, "只属于甲"));

    assertEquals(3, store.read(first).size());
    assertEquals(2, store.read(second).size(), "会话之间不应串数据");
    // 隔离性的直接断言：该消息只出现在 first 里
    assertTrue(
        store.read(first).stream()
            .anyMatch(e -> e.getEntry() instanceof UserMessageEntry
                && "只属于甲".equals(((UserMessageEntry) e.getEntry()).getContent())));
    assertFalse(
        store.read(second).stream()
            .anyMatch(e -> e.getEntry() instanceof UserMessageEntry));
  }
}
