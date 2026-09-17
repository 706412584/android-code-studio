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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tom.rv2ide.ai.protocol.ModelConfig;
import com.tom.rv2ide.ai.protocol.ModelContextParser;
import com.tom.rv2ide.ai.protocol.ModelMessage;
import com.tom.rv2ide.ai.protocol.ModelProtocolType;
import com.tom.rv2ide.ai.protocol.ToolModelMessage;
import com.tom.rv2ide.ai.protocol.UserModelMessage;
import com.tom.rv2ide.ai.tool.api.ToolCall;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 压缩边界的选择：只压最旧前缀、不拆散工具调用、不重复摘要既有区间。
 *
 * <p>token 数按 {@link com.tom.rv2ide.ai.agent.context.TokenEstimator} 手算：
 * 4 字符 ASCII 用户消息 = 5 tokens（4 固定开销 + 1）。
 */
final class ConversationCompactionTest {

  private static final long T0 = 1_700_000_000_000L;

  private static ConversationLog logAt(Path dir) {
    return new ConversationLog(dir.resolve("c1.jsonl").toFile());
  }

  private static UserModelMessage user(String text) {
    return new UserModelMessage(text);
  }

  /** 请求一次工具调用的助手条目：4 + name(1) + args "{}"(1) = 6 tokens。 */
  private static AssistantMessageEntry callingEntry(long timestamp) {
    return AssistantMessageEntry.create(
        null, timestamp, "", "", List.of(new ToolCall("c1", "t", "{}")));
  }

  /** 工具结果条目：4 + "aaaa"(1) + id(1) + name(1) = 7 tokens。 */
  private static ToolResultEntry resultEntry(long timestamp) {
    return ToolResultEntry.create(
        null, timestamp, ToolResult.of("c1", "t", "aaaa", false));
  }

  private static ModelConfig configWithContext(int contextSize) {
    return ModelConfig.builder(
            "test", "test", ModelProtocolType.OPENAI_COMPATIBLE,
            "p", "http://localhost", "k", "m")
        .contextSize(contextSize)
        .build();
  }

  @Test
  void nullAndEmptyInputSelectNothing() {
    assertTrue(ConversationCompaction.select(null, 100).isEmpty());
    assertTrue(ConversationCompaction.select(List.of(), 100).isEmpty());
  }

  @Test
  void everythingWithinBudgetSelectsNothing() throws IOException {
    ConversationLog log = logAt(java.nio.file.Files.createTempDirectory("cc"));
    log.append(UserMessageEntry.create(null, T0, "aaaa"));
    log.append(UserMessageEntry.create(null, T0 + 1, "bbbb"));

    // 两条共 10 tokens，预算 100
    assertTrue(ConversationCompaction.select(log.readAll(), 100).isEmpty());
  }

  @Test
  void selectsOldestPrefixWhenOverBudget() throws IOException {
    Path dir = java.nio.file.Files.createTempDirectory("cc");
    ConversationLog log = logAt(dir);
    log.append(UserMessageEntry.create(null, T0, "aaaa"));
    log.append(UserMessageEntry.create(null, T0 + 1, "bbbb"));
    log.append(UserMessageEntry.create(null, T0 + 2, "cccc"));
    log.append(UserMessageEntry.create(null, T0 + 3, "dddd"));

    // 预算 12 只装得下最近两条 → 被压区间是前两条（序号 0、1）
    ConversationCompaction.Selection selection = ConversationCompaction.select(log.readAll(), 12);
    assertFalse(selection.isEmpty());
    assertEquals(1, selection.getUpToOrdinal());
    assertEquals(2, selection.getMessages().size());
    assertEquals("aaaa", selection.getMessages().get(0).getContent());
    assertEquals("bbbb", selection.getMessages().get(1).getContent());
  }

  @Test
  void keepsAtLeastMinKeepMessages() throws IOException {
    Path dir = java.nio.file.Files.createTempDirectory("cc");
    ConversationLog log = logAt(dir);
    log.append(UserMessageEntry.create(null, T0, "aaaa"));
    log.append(UserMessageEntry.create(null, T0 + 1, "bbbb"));
    log.append(UserMessageEntry.create(null, T0 + 2, "cccc"));

    // 预算 1：连两条都装不下，但最近两条必须留下 → 只压第 0 条。
    // 即便新覆盖区间只有一条消息也必须压缩，否则历史会永远停在阈值之上，
    // 只能靠 ContextTrimmer 默默丢消息——那比压缩更糟。
    ConversationCompaction.Selection selection = ConversationCompaction.select(log.readAll(), 1);
    assertFalse(selection.isEmpty());
    assertEquals(0, selection.getUpToOrdinal());
    assertEquals(1, selection.getMessages().size());
    assertEquals("aaaa", selection.getMessages().get(0).getContent());
  }

  @Test
  void boundaryExtendsPastToolResults() throws IOException {
    Path dir = java.nio.file.Files.createTempDirectory("cc");
    ConversationLog log = logAt(dir);
    log.append(UserMessageEntry.create(null, T0, "aaaa"));
    log.append(callingEntry(T0 + 1));
    log.append(resultEntry(T0 + 2));
    log.append(UserMessageEntry.create(null, T0 + 3, "bbbb"));
    log.append(UserMessageEntry.create(null, T0 + 4, "cccc"));

    // 预算 12 → 从最新累计：cccc(5)+bbbb(5)=10，再加 aaaa(5) 超预算 → 边界在序号 0。
    // 但序号 0 之后是助手调用消息，其结果是序号 2——必须一起压掉。
    ConversationCompaction.Selection selection = ConversationCompaction.select(log.readAll(), 12);
    assertFalse(selection.isEmpty());
    assertEquals(2, selection.getUpToOrdinal(), "边界必须越过被压助手消息的工具结果");
  }

  @Test
  void compactedSelectionNeverLeavesOrphanToolResult() throws IOException {
    Path dir = java.nio.file.Files.createTempDirectory("cc");
    ConversationLog log = logAt(dir);
    log.append(UserMessageEntry.create(null, T0, "aaaa"));
    log.append(callingEntry(T0 + 1));
    log.append(resultEntry(T0 + 2));
    log.append(UserMessageEntry.create(null, T0 + 3, "bbbb"));

    ConversationCompaction.Selection selection = ConversationCompaction.select(log.readAll(), 6);
    assertFalse(selection.isEmpty());

    // 关键不变量：被压区间内不得出现"有调用无结果"
    boolean sawCall = false;
    boolean sawResult = false;
    for (ModelMessage message : selection.getMessages()) {
      if (message instanceof com.tom.rv2ide.ai.protocol.AssistantModelMessage
          && !message.getToolCalls().isEmpty()) {
        sawCall = true;
      }
      if (message instanceof ToolModelMessage) {
        sawResult = true;
      }
    }
    assertEquals(sawCall, sawResult, "工具调用与结果必须同在被压区间或同在被保留区间");
  }

  @Test
  void skipsEntriesAlreadyCoveredByPriorCompaction() throws IOException {
    Path dir = java.nio.file.Files.createTempDirectory("cc");
    ConversationLog log = logAt(dir);
    log.append(UserMessageEntry.create(null, T0, "旧一"));
    log.append(UserMessageEntry.create(null, T0 + 1, "旧二"));
    log.append(CompactionEntry.create(null, T0 + 2, "旧摘要", 1));
    log.append(UserMessageEntry.create(null, T0 + 3, "新一"));
    log.append(UserMessageEntry.create(null, T0 + 4, "新二"));
    log.append(UserMessageEntry.create(null, T0 + 5, "新三"));

    // 预算 12 → 只装得下最近两条（新二、新三）；边界落在"新一"（序号 3）。
    // 必须跳过已压缩的序号 0..1，否则摘要会把旧内容重述一遍。
    ConversationCompaction.Selection selection = ConversationCompaction.select(log.readAll(), 12);
    assertFalse(selection.isEmpty());
    assertEquals(3, selection.getUpToOrdinal());
    assertEquals(1, selection.getMessages().size());
    assertEquals("新一", selection.getMessages().get(0).getContent());
  }

  @Test
  void selectsNothingWhenBoundaryDoesNotAdvancePastPriorCompaction() throws IOException {
    Path dir = java.nio.file.Files.createTempDirectory("cc");
    ConversationLog log = logAt(dir);
    log.append(UserMessageEntry.create(null, T0, "旧一"));
    log.append(UserMessageEntry.create(null, T0 + 1, "旧二"));
    log.append(CompactionEntry.create(null, T0 + 2, "旧摘要", 1));
    log.append(UserMessageEntry.create(null, T0 + 3, "新一"));
    log.append(UserMessageEntry.create(null, T0 + 4, "新二"));

    // 边界算在序号 1（已在压缩区间内）→ 没有新内容可压，不应产生第二个压缩条目
    ConversationCompaction.Selection selection = ConversationCompaction.select(log.readAll(), 100);
    assertTrue(selection.isEmpty());
  }

  @Test
  void priorSummaryConsumesBudget() throws IOException {
    Path dir = java.nio.file.Files.createTempDirectory("cc");
    ConversationLog log = logAt(dir);
    log.append(UserMessageEntry.create(null, T0, "旧一"));
    log.append(UserMessageEntry.create(null, T0 + 1, "旧二"));
    // 摘要足够长，折算出的 token 会吃掉大部分预算
    log.append(CompactionEntry.create(null, T0 + 2, "摘要".repeat(40), 1));
    log.append(UserMessageEntry.create(null, T0 + 3, "新一"));
    log.append(UserMessageEntry.create(null, T0 + 4, "新二"));
    log.append(UserMessageEntry.create(null, T0 + 5, "新三"));

    List<ConversationLog.EntryLocation> entries = log.readAll();
    // 忽略摘要占用 → 边界落在"新一"；计入摘要占用后预算不够，边界必须前移或直接不压
    ConversationCompaction.Selection generous = ConversationCompaction.select(entries, 12);
    ConversationCompaction.Selection tight = ConversationCompaction.select(entries, 40);

    assertFalse(tight.isEmpty());
    assertTrue(
        generous.isEmpty() || tight.getUpToOrdinal() >= generous.getUpToOrdinal(),
        "预算收紧后边界不应前移");
  }

  @Test
  void metaAndTitleEntriesDoNotConsumeBudget() throws IOException {
    Path dir = java.nio.file.Files.createTempDirectory("cc");
    ConversationLog log = logAt(dir);
    log.append(SessionMetaEntry.create(T0, "/w", "m", "confirm"));
    log.append(TitleEntry.customTitle(null, T0 + 1, "标题"));
    log.append(UserMessageEntry.create(null, T0 + 2, "aaaa"));
    log.append(UserMessageEntry.create(null, T0 + 3, "bbbb"));
    log.append(UserMessageEntry.create(null, T0 + 4, "cccc"));

    // 预算 12 只装得下最近两条 → 边界落在"aaaa"（序号 2）
    ConversationCompaction.Selection selection = ConversationCompaction.select(log.readAll(), 12);
    assertEquals(2, selection.getUpToOrdinal());
    // meta 与标题不参与模型对话，因此被压区间里只剩"aaaa"一条
    assertEquals(1, selection.getMessages().size());
    assertEquals("aaaa", selection.getMessages().get(0).getContent());
  }

  @Test
  void metaUserMessagesAreExcludedFromCompactedRange() throws IOException {
    Path dir = java.nio.file.Files.createTempDirectory("cc");
    ConversationLog log = logAt(dir);
    log.append(new UserMessageEntry(null, null, T0, "系统注入", true));
    log.append(UserMessageEntry.create(null, T0 + 1, "aaaa"));
    log.append(UserMessageEntry.create(null, T0 + 2, "bbbb"));
    log.append(UserMessageEntry.create(null, T0 + 3, "cccc"));

    ConversationCompaction.Selection selection = ConversationCompaction.select(log.readAll(), 12);
    assertFalse(selection.isEmpty());
    for (ModelMessage message : selection.getMessages()) {
      assertFalse(
          "系统注入".equals(message.getContent()), "meta 条目不进入模型对话，不该出现在待摘要消息里");
    }
  }

  @Test
  void selectionMessagesAreAllUserOrAssistantOrTool() throws IOException {
    Path dir = java.nio.file.Files.createTempDirectory("cc");
    ConversationLog log = logAt(dir);
    log.append(SessionMetaEntry.create(T0, "/w", "m", "confirm"));
    log.append(UserMessageEntry.create(null, T0 + 1, "aaaa"));
    log.append(UserMessageEntry.create(null, T0 + 2, "bbbb"));
    log.append(UserMessageEntry.create(null, T0 + 3, "cccc"));
    log.append(UserMessageEntry.create(null, T0 + 4, "dddd"));

    ConversationCompaction.Selection selection = ConversationCompaction.select(log.readAll(), 12);
    assertFalse(selection.isEmpty());
    for (ModelMessage message : selection.getMessages()) {
      assertInstanceOf(UserModelMessage.class, message);
    }
  }

  @Test
  void historyBudgetSubtractsOverheadAndOutputReserve() {
    // 100000 * 0.8 = 80000；再扣 overhead 1000 与输出预留 8192
    assertEquals(
        80_000 - 1_000 - ConversationCompaction.RESERVE_OUTPUT_TOKENS,
        ConversationCompaction.historyBudget(configWithContext(100_000), 1_000));
  }

  @Test
  void historyBudgetClampsToZeroWhenOverheadExceedsTriggerPoint() {
    assertEquals(0, ConversationCompaction.historyBudget(configWithContext(10_000), 100_000));
  }

  @Test
  void historyBudgetIsUnlimitedWithoutConfig() {
    assertEquals(Integer.MAX_VALUE, ConversationCompaction.historyBudget(null, 1_000));
  }

  @Test
  void negativeOverheadIsTreatedAsZero() {
    assertEquals(
        80_000 - ConversationCompaction.RESERVE_OUTPUT_TOKENS,
        ConversationCompaction.historyBudget(configWithContext(100_000), -5_000));
  }

  @Test
  void contextSizeFallsBackToParserDefault() {
    ModelConfig noContext =
        ModelConfig.builder(
                "test", "test", ModelProtocolType.OPENAI_COMPATIBLE,
                "p", "http://localhost", "k", "m")
            .build();
    // 未配置时应与解析器一致（解析器会回退到默认窗口），而不是 0
    assertEquals(
        ModelContextParser.parse(noContext).getContextTokens(),
        ConversationCompaction.contextSizeOf(noContext));
    assertTrue(ConversationCompaction.contextSizeOf(noContext) > 0);
  }

  @Test
  void contextSizeIsZeroWithoutConfig() {
    assertEquals(0, ConversationCompaction.contextSizeOf(null));
  }

  @Test
  void explicitContextSizeWinsOverModelIdSuffix() {
    ModelConfig config =
        ModelConfig.builder(
                "test", "test", ModelProtocolType.OPENAI_COMPATIBLE,
                "p", "http://localhost", "k", "m[8k]")
            .contextSize(200_000)
            .build();
    assertEquals(200_000, ConversationCompaction.contextSizeOf(config));
  }

  @Test
  void upToOrdinalMatchesSelectedEntryOrdinal() throws IOException {
    Path dir = java.nio.file.Files.createTempDirectory("cc");
    ConversationLog log = logAt(dir);
    for (int i = 0; i < 6; i++) {
      log.append(UserMessageEntry.create(null, T0 + i, "aaaa"));
    }
    List<ConversationLog.EntryLocation> entries = log.readAll();

    ConversationCompaction.Selection selection = ConversationCompaction.select(entries, 12);
    assertFalse(selection.isEmpty());
    // 序号必须指向真实条目，否则 fold 的边界判断会错位
    assertEquals(entries.get(selection.getUpToOrdinal()).getOrdinal(), selection.getUpToOrdinal());
  }
}
