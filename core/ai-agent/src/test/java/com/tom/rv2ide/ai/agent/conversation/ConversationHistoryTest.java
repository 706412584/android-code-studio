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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tom.rv2ide.ai.protocol.AssistantModelMessage;
import com.tom.rv2ide.ai.protocol.ModelMessage;
import com.tom.rv2ide.ai.protocol.ToolModelMessage;
import com.tom.rv2ide.ai.protocol.UserModelMessage;
import com.tom.rv2ide.ai.tool.api.ToolCall;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 条目折叠为模型消息的语义，含压缩跳过区间。 */
final class ConversationHistoryTest {

  private static final long T0 = 1_700_000_000_000L;

  private static ConversationLog logAt(Path dir) {
    return new ConversationLog(dir.resolve("c1.jsonl").toFile());
  }

  @Test
  void foldsUserAndAssistantMessages(@TempDir Path dir) throws IOException {
    ConversationLog log = logAt(dir);
    log.append(UserMessageEntry.create(null, T0, "问题"));
    log.append(AssistantMessageEntry.create(null, T0 + 1, "回答", "", List.of()));

    List<ModelMessage> messages = ConversationHistory.fold(log.readAll());
    assertEquals(2, messages.size());
    assertEquals("user", messages.get(0).getRole());
    assertEquals("问题", messages.get(0).getContent());
    assertEquals("assistant", messages.get(1).getRole());
    assertEquals("回答", messages.get(1).getContent());
  }

  @Test
  void skipsSessionMetaAndTitleEntries(@TempDir Path dir) throws IOException {
    ConversationLog log = logAt(dir);
    log.append(SessionMetaEntry.create(T0, "/w", "m", "confirm"));
    log.append(TitleEntry.customTitle(null, T0 + 1, "标题"));
    log.append(UserMessageEntry.create(null, T0 + 2, "用户消息"));

    // 元信息与标题不参与模型对话，喂给模型会浪费 token
    List<ModelMessage> messages = ConversationHistory.fold(log.readAll());
    assertEquals(1, messages.size());
    assertEquals("用户消息", messages.get(0).getContent());
  }

  @Test
  void skipsMetaUserMessages(@TempDir Path dir) throws IOException {
    ConversationLog log = logAt(dir);
    log.append(new UserMessageEntry(null, null, T0, "系统注入", true));
    log.append(UserMessageEntry.create(null, T0 + 1, "真实消息"));

    List<ModelMessage> messages = ConversationHistory.fold(log.readAll());
    assertEquals(1, messages.size());
    assertEquals("真实消息", messages.get(0).getContent());
  }

  @Test
  void preservesToolCallsAndResults(@TempDir Path dir) throws IOException {
    ConversationLog log = logAt(dir);
    log.append(UserMessageEntry.create(null, T0, "读文件"));
    log.append(
        AssistantMessageEntry.create(
            null, T0 + 1, "好的", "思考", List.of(new ToolCall("c1", "file_read", "{}"))));
    log.append(ToolResultEntry.create(null, T0 + 2, ToolResult.of("c1", "file_read", "内容", false)));

    List<ModelMessage> messages = ConversationHistory.fold(log.readAll());
    assertEquals(3, messages.size());

    AssistantModelMessage assistant =
        assertInstanceOf(AssistantModelMessage.class, messages.get(1));
    assertEquals(1, assistant.getToolCalls().size());
    assertEquals("file_read", assistant.getToolCalls().get(0).getName());
    assertEquals("思考", assistant.getReasoningContent());

    ToolModelMessage tool = assertInstanceOf(ToolModelMessage.class, messages.get(2));
    assertEquals("c1", tool.getToolCallId());
    assertEquals("file_read", tool.getToolName());
    assertEquals("内容", tool.getContent());
  }

  @Test
  void marksToolErrorOnFoldedMessage(@TempDir Path dir) throws IOException {
    ConversationLog log = logAt(dir);
    log.append(
        ToolResultEntry.create(null, T0, ToolResult.of("c1", "shell_execute", "命令失败", true)));

    ToolModelMessage tool =
        assertInstanceOf(ToolModelMessage.class, ConversationHistory.fold(log.readAll()).get(0));
    assertTrue(tool.isToolError(), "错误标记必须保留，否则模型会以为工具成功");
  }

  @Test
  void compactionHidesCoveredEntries(@TempDir Path dir) throws IOException {
    ConversationLog log = logAt(dir);
    log.append(UserMessageEntry.create(null, T0, "很久以前的问题"));
    log.append(AssistantMessageEntry.create(null, T0 + 1, "很久以前的回答", "", List.of()));
    // ordinal 0..1 被压缩
    log.append(CompactionEntry.create(null, T0 + 2, "早期对话摘要", 1));
    log.append(UserMessageEntry.create(null, T0 + 3, "压缩后的新问题"));

    List<ModelMessage> messages = ConversationHistory.fold(log.readAll());
    // 摘要 + 新问题：被压缩的两条不再单独出现
    assertEquals(2, messages.size());
    assertEquals("早期对话摘要", messages.get(0).getContent());
    assertEquals("压缩后的新问题", messages.get(1).getContent());
    assertTrue(
        messages.stream().noneMatch(m -> "很久以前的问题".equals(m.getContent())),
        "被压缩覆盖的消息不应再进入历史");
  }

  @Test
  void summaryIsPlacedBeforeSubsequentMessages(@TempDir Path dir) throws IOException {
    ConversationLog log = logAt(dir);
    log.append(UserMessageEntry.create(null, T0, "旧问题"));
    log.append(CompactionEntry.create(null, T0 + 1, "摘要内容", 0));
    log.append(UserMessageEntry.create(null, T0 + 2, "新问题"));

    List<ModelMessage> messages = ConversationHistory.fold(log.readAll());
    // 摘要必须出现在后续对话之前，否则模型看到的是"先有新问题、后有摘要"
    assertEquals("摘要内容", messages.get(0).getContent());
    assertEquals("新问题", messages.get(1).getContent());
    assertEquals("user", messages.get(0).getRole());
  }

  @Test
  void multipleCompactionsUseLatestBoundary(@TempDir Path dir) throws IOException {
    ConversationLog log = logAt(dir);
    log.append(UserMessageEntry.create(null, T0, "消息0"));
    log.append(CompactionEntry.create(null, T0 + 1, "第一次摘要", 0));
    log.append(UserMessageEntry.create(null, T0 + 2, "消息2"));
    log.append(CompactionEntry.create(null, T0 + 3, "第二次摘要", 2));
    log.append(UserMessageEntry.create(null, T0 + 4, "消息4"));

    List<ModelMessage> messages = ConversationHistory.fold(log.readAll());
    // 第二次压缩覆盖 ordinal 0..2（含第一次压缩条目与"消息2"），故只剩摘要与"消息4"。
    // 两次摘要都保留：后一次可能建立在前一次之上，丢弃会丢失信息；重复的代价
    // 远小于丢信息。
    assertEquals(2, messages.size());
    assertEquals("第一次摘要\n\n第二次摘要", messages.get(0).getContent());
    assertEquals("消息4", messages.get(1).getContent());
  }

  @Test
  void laterCompactionDoesNotLoseEarlierSummary(@TempDir Path dir) throws IOException {
    // 第二次压缩只覆盖较新的区间，第一次压缩覆盖的区间不受影响。
    // 实现取所有压缩条目 upToOrdinal 的最大值作为隐藏上界——若第一次的摘要
    // 被丢弃，早期上下文就彻底消失了。
    ConversationLog log = logAt(dir);
    log.append(UserMessageEntry.create(null, T0, "早期消息"));
    log.append(CompactionEntry.create(null, T0 + 1, "早期摘要", 0));
    log.append(UserMessageEntry.create(null, T0 + 2, "中期消息"));
    log.append(CompactionEntry.create(null, T0 + 3, "中期摘要", 2));

    List<ModelMessage> messages = ConversationHistory.fold(log.readAll());
    assertEquals(1, messages.size());
    String summary = messages.get(0).getContent();
    assertTrue(summary.contains("早期摘要"), "早期摘要不应因后续压缩而丢失");
    assertTrue(summary.contains("中期摘要"));
  }

  @Test
  void foldsEmptyAndNullInputSafely() {
    assertTrue(ConversationHistory.fold(null).isEmpty());
    assertTrue(ConversationHistory.fold(List.of()).isEmpty());
  }

  @Test
  void emptySummaryDoesNotProduceBlankMessage(@TempDir Path dir) throws IOException {
    ConversationLog log = logAt(dir);
    log.append(UserMessageEntry.create(null, T0, "消息"));
    log.append(CompactionEntry.create(null, T0 + 1, "", 0));

    List<ModelMessage> messages = ConversationHistory.fold(log.readAll());
    // 空摘要不该产生一条空消息污染上下文
    assertEquals(0, messages.size());
  }

  @Test
  void roundTripThroughStoreProducesUsableHistory(@TempDir Path dir) throws IOException {
    // 端到端：写入存储 → 重新打开 → 折叠 → 得到可续接的历史
    FileConversationStore store = new FileConversationStore(dir.toFile());
    String id = store.create(null, "/workspace", "agnes-2.5-flash", "confirm").getId();
    store.append(id, UserMessageEntry.create(null, T0, "第一问"));
    store.append(id, AssistantModelEntryHelper.reply(T0 + 1, "第一答"));

    FileConversationStore reopened = new FileConversationStore(dir.toFile());
    List<ModelMessage> history = ConversationHistory.fold(reopened.read(id));

    assertEquals(2, history.size());
    assertInstanceOf(UserModelMessage.class, history.get(0));
    assertInstanceOf(AssistantModelMessage.class, history.get(1));
  }

  /** 小工具：避免在测试里重复写长构造。 */
  private static final class AssistantModelEntryHelper {
    static AssistantMessageEntry reply(long timestamp, String content) {
      return AssistantMessageEntry.create(null, timestamp, content, "", List.of());
    }
  }
}
