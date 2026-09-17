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

package com.tom.rv2ide.ai.agent.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tom.rv2ide.ai.agent.conversation.AssistantMessageEntry;
import com.tom.rv2ide.ai.agent.conversation.CompactionEntry;
import com.tom.rv2ide.ai.agent.conversation.ConversationEntry;
import com.tom.rv2ide.ai.agent.conversation.ConversationLog;
import com.tom.rv2ide.ai.agent.conversation.ToolResultEntry;
import com.tom.rv2ide.ai.agent.conversation.UserMessageEntry;
import com.tom.rv2ide.ai.tool.api.ToolCall;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 会话导出的回归测试。
 *
 * <p><b>为什么需要它</b>：导出是「只读」功能，出错不会破坏数据，但会产出**看起来没问题
 * 实则丢内容**的文件——工具输出被静默截断、助手回复缺失、时间戳算错。用户把文件发出去
 * 之后才发现问题，代价比在应用内看到错误高得多。
 */
final class ConversationExporterTest {

  private static List<ConversationEntry> entries(ConversationEntry... items) {
    List<ConversationEntry> list = new ArrayList<>();
    for (ConversationEntry item : items) {
      list.add(item);
    }
    return list;
  }

  private static UserMessageEntry user(String text) {
    return UserMessageEntry.create(null, 1000L, text);
  }

  private static AssistantMessageEntry assistant(String text) {
    return AssistantMessageEntry.create(null, 2000L, text, "", Collections.<ToolCall>emptyList());
  }

  private static AssistantMessageEntry assistantWithTools(String text, String... toolNames) {
    List<ToolCall> calls = new ArrayList<>();
    for (String name : toolNames) {
      calls.add(new ToolCall("id-" + name, name, "{}"));
    }
    return AssistantMessageEntry.create(null, 2000L, text, "", calls);
  }

  private static ToolResultEntry toolResult(String name, String content, boolean error) {
    ToolResult result = ToolResult.of("id-" + name, name, content, error);
    return ToolResultEntry.create(null, 3000L, result);
  }

  // ---- 基本结构 ----

  @Test
  void exportsTitleAndUserAssistantTurns() {
    String markdown =
        ConversationExporter.toMarkdown(
            "构建排障", entries(user("为什么构建失败"), assistant("因为缺少依赖")));

    assertTrue(markdown.startsWith("# 构建排障"));
    assertTrue(markdown.contains("**用户**"));
    assertTrue(markdown.contains("为什么构建失败"));
    assertTrue(markdown.contains("**助手**"));
    assertTrue(markdown.contains("因为缺少依赖"));
  }

  @Test
  void usesDefaultTitleWhenBlank() {
    assertTrue(ConversationExporter.toMarkdown(null, entries(user("x"))).contains("# AI 对话"));
    assertTrue(ConversationExporter.toMarkdown("   ", entries(user("x"))).contains("# AI 对话"));
  }

  @Test
  void handlesEmptyEntryList() {
    String markdown = ConversationExporter.toMarkdown("标题", Collections.<ConversationEntry>emptyList());

    assertTrue(markdown.contains("# 标题"));
    assertTrue(markdown.contains("没有可导出的内容"));
  }

  @Test
  void handlesNullEntryList() {
    assertTrue(ConversationExporter.toMarkdown("标题", null).contains("没有可导出的内容"));
  }

  @Test
  void skipsNullEntriesWithoutCrashing() {
    List<ConversationEntry> list = new ArrayList<>();
    list.add(null);
    list.add(user("有效内容"));

    String markdown = ConversationExporter.toMarkdown("t", list);

    assertTrue(markdown.contains("有效内容"));
  }

  @Test
  void omitsAssistantHeadingWhenContentBlank() {
    // 只请求了工具、没说话的轮次不应产出一个空的「助手」标题。
    String markdown =
        ConversationExporter.toMarkdown(
            "t", entries(assistantWithTools("", "file_read")));

    assertFalse(markdown.contains("**助手**"));
    // 但工具名仍应列出
    assertTrue(markdown.contains("file_read"));
  }

  // ---- 工具调用与输出 ----

  @Test
  void listsRequestedToolNames() {
    String markdown =
        ConversationExporter.toMarkdown(
            "t", entries(assistantWithTools("开始处理", "file_read", "file_write")));

    assertTrue(markdown.contains("请求工具：file_read, file_write"), markdown);
  }

  @Test
  void exportsToolResultsWithStatusMarker() {
    String markdown =
        ConversationExporter.toMarkdown(
            "t", entries(toolResult("file_read", "内容", false), toolResult("file_write", "失败原因", true)));

    assertTrue(markdown.contains("✓ file_read"), markdown);
    assertTrue(markdown.contains("✗ file_write"), markdown);
    assertTrue(markdown.contains("内容"));
    assertTrue(markdown.contains("失败原因"));
  }

  @Test
  void truncatesLongToolOutputButSaysSo() {
    // 工具输出可能极大（读文件、构建日志）。静默截断会让读者以为内容就这么多。
    StringBuilder big = new StringBuilder();
    for (int i = 0; i < 5000; i++) {
      big.append('x');
    }

    String markdown =
        ConversationExporter.toMarkdown("t", entries(toolResult("file_read", big.toString(), false)));

    assertTrue(markdown.contains("已截断"), "截断必须注明");
    assertTrue(markdown.contains("共 5000 字符"), markdown);
    assertTrue(markdown.length() < big.length());
  }

  @Test
  void conversationOnlyModeDropsToolDetails() {
    String markdown =
        ConversationExporter.toMarkdown(
            "t",
            entries(user("问题"), assistantWithTools("回答", "file_read"), toolResult("file_read", "内容", false)),
            ConversationExporter.Options.conversationOnly());

    assertTrue(markdown.contains("问题"));
    assertTrue(markdown.contains("回答"));
    // 工具调用与输出都不应出现
    assertFalse(markdown.contains("file_read"), markdown);
    assertFalse(markdown.contains("请求工具"));
  }

  @Test
  void includeToolCallsWithoutOutputShowsNamesOnly() {
    String markdown =
        ConversationExporter.toMarkdown(
            "t",
            entries(assistantWithTools("回答", "file_read"), toolResult("file_read", "详细内容", false)),
            new ConversationExporter.Options().includeToolOutput(false));

    assertTrue(markdown.contains("请求工具：file_read"));
    // 结果行仍在（含工具名），但不含输出内容
    assertTrue(markdown.contains("✓ file_read"));
    assertFalse(markdown.contains("详细内容"));
  }

  // ---- 压缩记录 ----

  @Test
  void exportsCompactionNotice() {
    String markdown =
        ConversationExporter.toMarkdown(
            "t", entries(CompactionEntry.create(null, 1000L, "早期讨论的摘要", 5)));

    assertTrue(markdown.contains("早期对话已压缩为摘要"), markdown);
    assertTrue(markdown.contains("早期讨论的摘要"));
  }

  @Test
  void compactionCanBeOmitted() {
    String markdown =
        ConversationExporter.toMarkdown(
            "t",
            entries(CompactionEntry.create(null, 1000L, "摘要内容", 5)),
            new ConversationExporter.Options().includeCompaction(false));

    assertFalse(markdown.contains("摘要内容"));
  }

  // ---- Markdown 结构 ----

  @Test
  void toolResultLinesFormAListNotParagraphs() {
    // 连续的工具结果应当是列表项，否则会变成一大段连在一起的文字。
    String markdown =
        ConversationExporter.toMarkdown(
            "t",
            entries(
                toolResult("a", "1", false),
                toolResult("b", "2", false),
                toolResult("c", "3", false)));

    assertTrue(markdown.contains("- ✓ a"), markdown);
    assertTrue(markdown.contains("- ✓ b"));
    assertTrue(markdown.contains("- ✓ c"));
  }

  @Test
  void headingAfterToolListIsSeparatedByBlankLine() {
    // 不空行的话 Markdown 会把下面的标题吞进列表里，渲染出来是错的。
    String markdown =
        ConversationExporter.toMarkdown(
            "t", entries(toolResult("a", "1", false), user("后续问题")));

    assertTrue(markdown.contains("\n\n**用户**"), markdown);
  }

  @Test
  void endsWithSingleNewline() {
    String markdown = ConversationExporter.toMarkdown("t", entries(user("x")));

    assertTrue(markdown.endsWith("\n"));
    assertFalse(markdown.endsWith("\n\n"));
  }

  // ---- 时间戳 ----

  @Test
  void timestampsAreIncludedOnlyWhenRequested() {
    String without = ConversationExporter.toMarkdown("t", entries(user("x")));
    String with =
        ConversationExporter.toMarkdown(
            "t", entries(user("x")), new ConversationExporter.Options().includeTimestamps(true));

    assertFalse(without.contains("1970-"));
    // user() 的时间戳是 1000ms → 1970-01-01 00:00 UTC
    assertTrue(with.contains("1970-01-01 00:00"), with);
  }

  @Test
  void timestampsReflectEntryTime() {
    // 用带分钟偏移的时间戳，确认格式化确实用了条目的时间而不是常量。
    UserMessageEntry later = UserMessageEntry.create(null, 1_000_000L, "稍后");
    String markdown =
        ConversationExporter.toMarkdown(
            "t",
            entries(later),
            new ConversationExporter.Options().includeTimestamps(true));

    assertTrue(markdown.contains("1970-01-01 00:16"), markdown);
  }

  @Test
  void formatsTimestampDeterministically() {
    // 手写 UTC 格式化而非 SimpleDateFormat：后者依赖默认时区与 Locale，
    // 会让同一份会话在不同设备上导出不同结果。
    assertEquals("1970-01-01 00:00", ConversationExporter.formatTimestamp(0L));
    // 1000 秒 = 00:16
    assertEquals("1970-01-01 00:16", ConversationExporter.formatTimestamp(1_000_000L));
    // 一天后
    assertEquals("1970-01-02 00:00", ConversationExporter.formatTimestamp(86_400_000L));
    // 2021-01-01 00:00 UTC = 1609459200
    assertEquals("2021-01-01 00:00", ConversationExporter.formatTimestamp(1_609_459_200_000L));
    // 2024-02-29（闰日）
    assertEquals("2024-02-29 12:00", ConversationExporter.formatTimestamp(1_709_208_000_000L));
  }

  // ---- 文件名 ----

  @Test
  void suggestsMarkdownFileName() {
    assertEquals("构建排障.md", ConversationExporter.suggestedFileName("构建排障"));
    assertEquals("conversation.md", ConversationExporter.suggestedFileName(null));
    assertEquals("conversation.md", ConversationExporter.suggestedFileName("   "));
  }

  @Test
  void sanitizesPathSeparatorsInFileName() {
    // 标题可能含用户输入的任意字符；直接当文件名会失败或写到意外位置。
    String name = ConversationExporter.suggestedFileName("a/b\\c:d*e?f\"g<h>i|j");

    assertFalse(name.contains("/"));
    assertFalse(name.contains("\\"));
    assertFalse(name.contains(":"));
    assertFalse(name.contains("*"));
    assertTrue(name.endsWith(".md"));
  }

  @Test
  void sanitizesControlCharactersInFileName() {
    String name = ConversationExporter.suggestedFileName("a\u0000b\u0007c");

    assertFalse(name.contains("\u0000"));
    assertFalse(name.contains("\u0007"));
  }

  @Test
  void truncatesVeryLongFileName() {
    // 文件名过长在多数文件系统上会失败。
    StringBuilder longTitle = new StringBuilder();
    for (int i = 0; i < 500; i++) {
      longTitle.append('a');
    }

    String name = ConversationExporter.suggestedFileName(longTitle.toString());

    assertTrue(name.length() <= 83, "实际长度 " + name.length());
    assertTrue(name.endsWith(".md"));
  }

  @Test
  void plainTextModeExcludesProcessDetails() {
    String text =
        ConversationExporter.toPlainText(
            "t", entries(user("问题"), toolResult("file_read", "内容", false)));

    assertTrue(text.contains("问题"));
    assertFalse(text.contains("内容"));
  }

  // ---- 综合 ----

  @Test
  void fullExportKeepsEverythingInOrder() {
    String markdown =
        ConversationExporter.toMarkdown(
            "完整会话",
            entries(
                user("为什么构建失败"),
                assistantWithTools("让我看看构建文件", "file_read"),
                toolResult("file_read", "缺少 implementation 依赖", false),
                CompactionEntry.create(null, 4000L, "之前的讨论", 2),
                assistant("原因是缺少依赖")),
            ConversationExporter.Options.full());

    int userAt = markdown.indexOf("为什么构建失败");
    int toolAt = markdown.indexOf("file_read");
    int compactAt = markdown.indexOf("早期对话已压缩");
    int finalAt = markdown.indexOf("原因是缺少依赖");

    assertTrue(userAt < toolAt, "用户消息应在工具调用之前");
    assertTrue(toolAt < compactAt, "工具调用应在压缩记录之前");
    assertTrue(compactAt < finalAt, "压缩记录应在后续助手回复之前");
  }
}
