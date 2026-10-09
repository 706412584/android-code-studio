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
  void preservesToolResultImageAcrossPersistence(@TempDir Path dir) throws IOException {
    ConversationLog log = logAt(dir);
    // 带图结果必须有配对调用：孤儿结果会被清洗掉（见 dropsOrphanResultWithoutCall），
    // 本测试验证的是图片负载的往返，不是孤儿语义。
    log.append(
        AssistantMessageEntry.create(
            null, T0 - 1, "", "", List.of(new ToolCall("c1", "file_read", "{}"))));
    // withCall 是执行器在真实路径上的动作（ToolExecutor 返回前统一附加 callId），
    // 少了它结果条目没有配对身份，会被清洗规则当成孤儿。
    log.append(
        ToolResultEntry.create(
            null,
            T0,
            ToolResult.withImage("file_read", "图片已附加", "image/png", "QUJD")
                .withCall("c1", "file_read")));

    // 先验证 JSONL 往返（写盘再读回）——图片负载是 base64，容易被截断或转义出错。
    ConversationEntry reloaded =
        ConversationCodec.parse(ConversationCodec.toLine(log.readAll().get(1).getEntry()));
    ToolResultEntry entry = assertInstanceOf(ToolResultEntry.class, reloaded);
    assertEquals("QUJD", entry.getImageBase64());
    assertEquals("image/png", entry.getImageMimeType());

    // 再验证折叠成模型消息时图片被重新编码成工具结果图片 payload，
    // 否则续接会话时历史里的图会退化成纯文字，模型看不到像素。
    ToolModelMessage tool =
        assertInstanceOf(ToolModelMessage.class, ConversationHistory.fold(log.readAll()).get(1));
    assertEquals("图片已附加", tool.getContent());
    assertEquals(
        "QUJD",
        com.tom.rv2ide.ai.protocol.ImageInputPayload.fromImageResult(tool.getRawInputJson())
            .getDataBase64());
  }

  @Test
  void marksToolErrorOnFoldedMessage(@TempDir Path dir) throws IOException {
    ConversationLog log = logAt(dir);
    // 同 preservesToolResultImageAcrossPersistence：错误标记的验证需要配对调用。
    log.append(
        AssistantMessageEntry.create(
            null, T0 - 1, "", "", List.of(new ToolCall("c1", "shell_execute", "{}"))));
    log.append(
        ToolResultEntry.create(null, T0, ToolResult.of("c1", "shell_execute", "命令失败", true)));

    ToolModelMessage tool =
        assertInstanceOf(ToolModelMessage.class, ConversationHistory.fold(log.readAll()).get(1));
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

  // ── 工具调用与结果的成对清洗（真实会话的 400 事故回归）──

  @Test
  void dropsCallWithUnrecoverableArguments(@TempDir Path dir) throws IOException {
    // 事故原型：流在参数中途断开，调用以残缺 JSON 落盘。原样重发时服务端
    // 报 400 "Assistant tool call arguments must be valid JSON"，此后该会话
    // 每一次请求都失败（与切换服务商无关）。折叠时必须把它清洗掉。
    ConversationLog log = logAt(dir);
    log.append(UserMessageEntry.create(null, T0, "改文件"));
    log.append(
        AssistantMessageEntry.create(
            null,
            T0 + 1,
            "好的",
            "",
            List.of(
                new ToolCall("c1", "file_write", "{\"file_path\": \"a.cpp\""),
                new ToolCall("c2", "file_write", "{\"file_path\": \"b.cpp\", }"))));
    log.append(ToolResultEntry.create(null, T0 + 2, ToolResult.of("c1", "file_write", "ok", false)));
    log.append(ToolResultEntry.create(null, T0 + 3, ToolResult.of("c2", "file_write", "ok", false)));

    List<ModelMessage> messages = ConversationHistory.fold(log.readAll());
    AssistantModelMessage assistant =
        assertInstanceOf(AssistantModelMessage.class, messages.get(1));
    // 两条都可由 ToolArgsCleaner 修复（补闭合括号 / 去尾逗号，与执行时同一套算法）——
    // 关键是**发出的参数必须是合法 JSON**，否则整条会话被 400 拒绝。
    assertEquals(2, assistant.getToolCalls().size());
    for (ToolCall call : assistant.getToolCalls()) {
      assertTrue(
          parsesAsJson(call.getArguments()),
          "发往服务端的参数必须是合法 JSON: " + call.getArguments());
    }
  }

  @Test
  void dropsCallWhoseArgumentsCannotBeRepaired(@TempDir Path dir) throws IOException {
    ConversationLog log = logAt(dir);
    log.append(
        AssistantMessageEntry.create(
            null, T0, "", "", List.of(new ToolCall("c1", "file_write", "not json at all {{{"))));
    log.append(ToolResultEntry.create(null, T0 + 1, ToolResult.of("c1", "file_write", "ok", false)));

    List<ModelMessage> messages = ConversationHistory.fold(log.readAll());
    // 调用被丢弃 + 消息本身无正文无推理 → 整条跳过；它的结果随之消失（否则又变成
    // 孤儿结果，换一种 400）。
    assertTrue(messages.isEmpty(), "坏调用及其结果不得留在历史里: " + messages);
  }

  @Test
  void dropsOrphanResultWithoutCall(@TempDir Path dir) throws IOException {
    // 事故原型：结果条目落盘而前导调用不存在（历史被裁剪 / 旧日志不完整）。
    // 以工具结果开头发请求时多数服务商报 400（找不到对应 tool_call）。
    ConversationLog log = logAt(dir);
    log.append(UserMessageEntry.create(null, T0, "问题"));
    log.append(
        ToolResultEntry.create(null, T0 + 1, ToolResult.of("gone", "file_read", "内容", false)));

    List<ModelMessage> messages = ConversationHistory.fold(log.readAll());
    assertEquals(1, messages.size());
    assertInstanceOf(UserModelMessage.class, messages.get(0));
  }

  @Test
  void dropsResultWhoseCallWasSanitizedAway(@TempDir Path dir) throws IOException {
    // 调用因参数不可修复被丢弃时，它的结果不能以「孤儿」形态留下。
    ConversationLog log = logAt(dir);
    log.append(
        AssistantMessageEntry.create(
            null, T0, "", "", List.of(new ToolCall("bad", "shell_execute", "%%% not json"))));
    log.append(
        ToolResultEntry.create(null, T0 + 1, ToolResult.of("bad", "shell_execute", "out", false)));
    log.append(
        AssistantMessageEntry.create(
            null, T0 + 2, "", "", List.of(new ToolCall("good", "file_read", "{}"))));
    log.append(ToolResultEntry.create(null, T0 + 3, ToolResult.of("good", "file_read", "ok", false)));

    List<ModelMessage> messages = ConversationHistory.fold(log.readAll());
    // 坏块整块消失（调用被清空且无正文 → 整条跳过），只留下 good 的调用与结果。
    assertEquals(2, messages.size());
    AssistantModelMessage second = assertInstanceOf(AssistantModelMessage.class, messages.get(0));
    assertEquals("good", second.getToolCalls().get(0).getId());
    assertEquals("good", ((ToolModelMessage) messages.get(1)).getToolCallId());
  }

  @Test
  void keepsEveryCallInMultiCallMessage(@TempDir Path dir) throws IOException {
    // 一条助手消息带多个调用时，配对是按「消息」而不是「最近一条」——少保留一个
    // 就会让它对应的结果变成孤儿。
    ConversationLog log = logAt(dir);
    log.append(
        AssistantMessageEntry.create(
            null,
            T0,
            "",
            "",
            List.of(
                new ToolCall("c1", "file_read", "{}"),
                new ToolCall("c2", "file_read", "{}"),
                new ToolCall("c3", "file_read", "{}"))));
    log.append(ToolResultEntry.create(null, T0 + 1, ToolResult.of("c1", "file_read", "a", false)));
    log.append(ToolResultEntry.create(null, T0 + 2, ToolResult.of("c2", "file_read", "b", false)));
    log.append(ToolResultEntry.create(null, T0 + 3, ToolResult.of("c3", "file_read", "c", false)));

    List<ModelMessage> messages = ConversationHistory.fold(log.readAll());
    assertEquals(4, messages.size());
    AssistantModelMessage assistant =
        assertInstanceOf(AssistantModelMessage.class, messages.get(0));
    assertEquals(3, assistant.getToolCalls().size());
    for (int i = 1; i <= 3; i++) {
      assertInstanceOf(ToolModelMessage.class, messages.get(i));
    }
  }

  @Test
  void normalHistoryIsUnchangedBySanitization(@TempDir Path dir) throws IOException {
    // 清洗必须是「无坏数据时零副作用」：合法调用 + 配对结果原样通过。
    ConversationLog log = logAt(dir);
    log.append(UserMessageEntry.create(null, T0, "读文件"));
    log.append(
        AssistantMessageEntry.create(
            null, T0 + 1, "好的", "思考", List.of(new ToolCall("c1", "file_read", "{\"path\":\"a\"}"))));
    log.append(ToolResultEntry.create(null, T0 + 2, ToolResult.of("c1", "file_read", "内容", false)));

    List<ModelMessage> messages = ConversationHistory.fold(log.readAll());
    assertEquals(3, messages.size());
    AssistantModelMessage assistant =
        assertInstanceOf(AssistantModelMessage.class, messages.get(1));
    assertEquals("{\"path\":\"a\"}", assistant.getToolCalls().get(0).getArguments());
    assertEquals("c1", ((ToolModelMessage) messages.get(2)).getToolCallId());
  }

  /** 参数能否被解析为 JSON 对象——测试侧与服务端同一判据。 */
  private static boolean parsesAsJson(String value) {
    try {
      new org.json.JSONObject(value);
      return true;
    } catch (org.json.JSONException e) {
      return false;
    }
  }
}
