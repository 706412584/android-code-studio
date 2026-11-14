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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link FileConversationStore#deleteEntries} 的删除语义与原子性。 */
final class FileConversationStoreDeleteTest {

  private static FileConversationStore storeAt(Path dir) {
    return new FileConversationStore(dir.toFile());
  }

  /** 造一个带 meta + 用户 + 助手 + 工具结果 + 用户 + 助手 的会话。 */
  private static String seedConversation(FileConversationStore store) throws IOException {
    ConversationSummary created = store.create("删除测试", "/workspace", "model", "confirm");
    String id = created.getId();
    store.append(id, UserMessageEntry.create(null, 1000L, "第一个问题"));
    store.append(
        id,
        AssistantMessageEntry.create(
            null, 1001L, "回答一", "思考一",
            Arrays.asList(new com.tom.rv2ide.ai.tool.api.ToolCall("call-1", "read_file", "{}"))));
    store.append(
        id,
        ToolResultEntry.create(
            null, 1002L,
            com.tom.rv2ide.ai.tool.api.ToolResult.of("call-1", "read_file", "文件内容", false),
            Collections.emptyList()));
    store.append(id, UserMessageEntry.create(null, 1003L, "第二个问题"));
    store.append(id, AssistantMessageEntry.create(null, 1004L, "回答二", "", Collections.emptyList()));
    return id;
  }

  @Test
  void deleteMiddleAssistantRemovesEntryAndFollowingToolResult(@TempDir Path dir)
      throws IOException {
    FileConversationStore store = storeAt(dir);
    String id = seedConversation(store);
    List<ConversationLog.EntryLocation> before = store.read(id);
    // ordinal: 0=meta, 1=title(可能), ... 定位「回答一」与其后的工具结果。
    int assistantOrdinal = -1;
    int toolResultOrdinal = -1;
    for (ConversationLog.EntryLocation loc : before) {
      if (loc.getEntry() instanceof AssistantMessageEntry
          && "回答一".equals(((AssistantMessageEntry) loc.getEntry()).getContent())) {
        assistantOrdinal = loc.getOrdinal();
      }
      if (loc.getEntry() instanceof ToolResultEntry) {
        toolResultOrdinal = loc.getOrdinal();
      }
    }
    assertTrue(assistantOrdinal > 0, "应找到回答一");
    assertTrue(toolResultOrdinal > assistantOrdinal, "工具结果应在回答一之后");

    int removed = store.deleteEntries(id, Arrays.asList(assistantOrdinal, toolResultOrdinal));
    assertEquals(2, removed);

    List<ConversationLog.EntryLocation> after = store.read(id);
    // 删除后不应再有「回答一」与工具结果。
    for (ConversationLog.EntryLocation loc : after) {
      if (loc.getEntry() instanceof AssistantMessageEntry) {
        assertFalse(
            "回答一".equals(((AssistantMessageEntry) loc.getEntry()).getContent()),
            "回答一应已被删除");
      }
      assertFalse(loc.getEntry() instanceof ToolResultEntry, "工具结果应已被删除");
    }
    // 其余条目保留。
    assertEquals(before.size() - 2, after.size());
  }

  @Test
  void deleteLeavesFoldableHistory(@TempDir Path dir) throws IOException {
    FileConversationStore store = storeAt(dir);
    String id = seedConversation(store);
    List<ConversationLog.EntryLocation> before = store.read(id);
    int assistantOrdinal = -1;
    int toolResultOrdinal = -1;
    for (ConversationLog.EntryLocation loc : before) {
      if (loc.getEntry() instanceof AssistantMessageEntry
          && "回答一".equals(((AssistantMessageEntry) loc.getEntry()).getContent())) {
        assistantOrdinal = loc.getOrdinal();
      }
      if (loc.getEntry() instanceof ToolResultEntry) {
        toolResultOrdinal = loc.getOrdinal();
      }
    }
    store.deleteEntries(id, Arrays.asList(assistantOrdinal, toolResultOrdinal));

    // fold 应正常产出：两个用户消息 + 回答二（无工具调用残留）。
    List<com.tom.rv2ide.ai.protocol.ModelMessage> folded =
        ConversationHistory.fold(store.read(id));
    long userCount =
        folded.stream().filter(m -> m instanceof com.tom.rv2ide.ai.protocol.UserModelMessage).count();
    long assistantCount =
        folded.stream()
            .filter(m -> m instanceof com.tom.rv2ide.ai.protocol.AssistantModelMessage)
            .count();
    assertEquals(2, userCount, "两个用户问题都保留");
    assertEquals(1, assistantCount, "只剩回答二");
  }

  @Test
  void deleteCreatesBackupFile(@TempDir Path dir) throws IOException {
    FileConversationStore store = storeAt(dir);
    String id = seedConversation(store);
    List<ConversationLog.EntryLocation> before = store.read(id);
    store.deleteEntries(id, Collections.singletonList(before.get(before.size() - 1).getOrdinal()));

    File backup = new File(dir.toFile(), id + ".jsonl.bak");
    assertTrue(backup.exists(), "删除前应留 .bak 备份");
    assertTrue(backup.length() > 0, "备份非空");
  }

  @Test
  void deleteNonexistentOrdinalIsNoOp(@TempDir Path dir) throws IOException {
    FileConversationStore store = storeAt(dir);
    String id = seedConversation(store);
    int beforeCount = store.read(id).size();
    int removed = store.deleteEntries(id, Collections.singletonList(9999));
    assertEquals(0, removed, "不存在的序号应被忽略");
    // 文件内容不变。
    assertEquals(beforeCount, store.read(id).size(), "条目数不变");
  }

  @Test
  void deleteEmptyListIsNoOp(@TempDir Path dir) throws IOException {
    FileConversationStore store = storeAt(dir);
    String id = seedConversation(store);
    assertEquals(0, store.deleteEntries(id, Collections.emptyList()));
    assertEquals(0, store.deleteEntries(id, null));
  }

  @Test
  void deleteOnMissingConversationIsNoOp(@TempDir Path dir) throws IOException {
    FileConversationStore store = storeAt(dir);
    assertEquals(0, store.deleteEntries("01234567-0000-0000-0000-000000000000",
        Collections.singletonList(0)));
  }

  @Test
  void appendStillWorksAfterDelete(@TempDir Path dir) throws IOException {
    FileConversationStore store = storeAt(dir);
    String id = seedConversation(store);
    List<ConversationLog.EntryLocation> before = store.read(id);
    store.deleteEntries(id, Collections.singletonList(before.get(before.size() - 1).getOrdinal()));

    // 删除后继续 append：文件应仍是合法 JSONL。
    store.append(id, UserMessageEntry.create(null, 2000L, "删除后的新消息"));
    List<ConversationLog.EntryLocation> after = store.read(id);
    assertEquals(before.size(), after.size(), "删一条 + 加一条 = 总数不变");
    ConversationEntry last = after.get(after.size() - 1).getEntry();
    assertInstanceOf(UserMessageEntry.class, last);
    assertEquals("删除后的新消息", ((UserMessageEntry) last).getContent());
  }

  @Test
  void deleteBeforeCompactionAdjustsUpToOrdinal(@TempDir Path dir) throws IOException {
    FileConversationStore store = storeAt(dir);
    String id = seedConversation(store);
    // 追加压缩条目：压缩前 4 条（meta/title/user/assistant）。
    store.append(id, CompactionEntry.create(null, 1005L, "前四条摘要", 3));
    // 再追加一条用户消息（压缩边界之后）。
    store.append(id, UserMessageEntry.create(null, 1006L, "压缩后的消息"));

    // 删除「回答一」（ordinal 2，在压缩边界之前）。
    List<ConversationLog.EntryLocation> before = store.read(id);
    int assistantOrdinal = -1;
    for (ConversationLog.EntryLocation loc : before) {
      if (loc.getEntry() instanceof AssistantMessageEntry
          && "回答一".equals(((AssistantMessageEntry) loc.getEntry()).getContent())) {
        assistantOrdinal = loc.getOrdinal();
      }
    }
    assertTrue(assistantOrdinal > 0);
    // 连带工具结果（ordinal 3）。
    store.deleteEntries(id, Arrays.asList(assistantOrdinal, assistantOrdinal + 1));

    // 压缩条目的 upToOrdinal 应从 3 调整为 2（只减 1：assistant ordinal 3 在边界内，
    // toolResult ordinal 4 不在边界内——upToOrdinal=3 表示 ordinal 0-3 被压缩）。
    List<ConversationLog.EntryLocation> after = store.read(id);
    boolean foundCompaction = false;
    for (ConversationLog.EntryLocation loc : after) {
      if (loc.getEntry() instanceof CompactionEntry) {
        CompactionEntry c = (CompactionEntry) loc.getEntry();
        assertEquals(2, c.getUpToOrdinal(),
            "assistant ordinal 3 在边界内（3<=3），shift=1，upToOrdinal 应从 3 前移为 2");
        foundCompaction = true;
      }
    }
    assertTrue(foundCompaction, "压缩条目应保留");

    // fold 不应跳过「压缩后的消息」（它在边界之后）。
    List<com.tom.rv2ide.ai.protocol.ModelMessage> folded =
        ConversationHistory.fold(after);
    boolean hasPostCompaction =
        folded.stream()
            .anyMatch(m -> m instanceof com.tom.rv2ide.ai.protocol.UserModelMessage
                && "压缩后的消息".equals(((com.tom.rv2ide.ai.protocol.UserModelMessage) m).getContent()));
    assertTrue(hasPostCompaction, "压缩边界之后的消息不应被错误跳过");
  }

  @Test
  void concurrentAppendDuringDeleteIsBlocked(@TempDir Path dir) throws Exception {
    FileConversationStore store = storeAt(dir);
    String id = seedConversation(store);

    // 在一个线程里删除（持写锁），另一个线程尝试 append（读锁应阻塞）。
    List<ConversationLog.EntryLocation> before = store.read(id);
    int targetOrdinal = before.get(before.size() - 1).getOrdinal();

    Thread deleteThread =
        new Thread(
            () -> {
              try {
                store.deleteEntries(id, Collections.singletonList(targetOrdinal));
              } catch (IOException e) {
                throw new RuntimeException(e);
              }
            });
    Thread appendThread =
        new Thread(
            () -> {
              try {
                // 这个 append 应该在 deleteEntries 完成后才执行（读锁被写锁阻塞）。
                Thread.sleep(50); // 让 delete 先拿到写锁
                store.append(id, UserMessageEntry.create(null, 3000L, "并发追加"));
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            });

    deleteThread.start();
    appendThread.start();
    deleteThread.join(5000);
    appendThread.join(5000);

    // 两个操作都完成后：被删条目不存在，新追加的条目存在。
    List<ConversationLog.EntryLocation> after = store.read(id);
    ConversationEntry last = after.get(after.size() - 1).getEntry();
    assertInstanceOf(UserMessageEntry.class, last);
    assertEquals("并发追加", ((UserMessageEntry) last).getContent());
    // 总条数 = 原条数 - 1（删除） + 1（追加）。
    assertEquals(before.size(), after.size());
  }

  @Test
  void duplicateContentMessagesDeleteCorrectOne(@TempDir Path dir) throws IOException {
    FileConversationStore store = storeAt(dir);
    ConversationSummary created = store.create("重复内容", "/workspace", "model", "confirm");
    String id = created.getId();
    // 两条同内容的用户消息。
    store.append(id, UserMessageEntry.create(null, 1000L, "相同内容"));
    store.append(id, UserMessageEntry.create(null, 1001L, "相同内容"));
    // 一条不同内容的助手消息。
    store.append(id, AssistantMessageEntry.create(null, 1002L, "不同回复", "", Collections.emptyList()));

    List<ConversationLog.EntryLocation> before = store.read(id);
    // 删除第一条「相同内容」（ordinal 2，meta=0, title=1, user1=2, user2=3, assistant=4）。
    store.deleteEntries(id, Collections.singletonList(2));

    List<ConversationLog.EntryLocation> after = store.read(id);
    assertEquals(before.size() - 1, after.size());
    // 剩余的「相同内容」应该是第二条（timestamp=1001）。
    boolean foundSecond =
        after.stream()
            .anyMatch(loc -> loc.getEntry() instanceof UserMessageEntry
                && ((UserMessageEntry) loc.getEntry()).getTimestamp() == 1001L
                && "相同内容".equals(((UserMessageEntry) loc.getEntry()).getContent()));
    assertTrue(foundSecond, "第二条同内容消息应保留");
  }
}

