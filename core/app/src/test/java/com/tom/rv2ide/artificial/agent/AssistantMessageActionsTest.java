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

package com.tom.rv2ide.artificial.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/**
 * 消息级操作的四段纯逻辑。
 *
 * <p><b>为什么这些值得钉住</b>：它们的错误形态全都「看起来像对的」，在真机上极难发现——
 * <ul>
 *   <li>复制菜单多给一个选项：两个选项复制出一样的东西，用户以为坏了（不会报错）；
 *   <li>markdown 转换把代码块里的 `*` 当成斜体吃掉：粘出去的代码编译不过，
 *       但屏幕上（渲染后）完全正常，只有复制过的用户才会遇到；
 *   <li>引用多行只给首行加前缀：模型分不清引文与用户的话，答非所问，且没有任何报错；
 *   <li>耗时 0 显示成 "0s"：历史会话里刷出一排无意义的 "0s"。
 * </ul>
 * 这几条都在下面锁死了。
 */
public final class AssistantMessageActionsTest {

  private static List<CopyFormat> formats(String md) {
    return AssistantMessageActions.INSTANCE.availableCopyFormats(md);
  }

  private static String plain(String md) {
    return AssistantMessageActions.INSTANCE.toPlainText(md);
  }

  private static String context(ChatReference... refs) {
    return AssistantMessageActions.INSTANCE.referenceContext(Arrays.asList(refs));
  }

  /** 只传 3 个参数时 sourceName 用空串（Java 不能用 Kotlin 默认参数，显式补齐）。 */
  private static ChatReference ref(long id, String role, String quote) {
    return new ChatReference(id, role, quote, "");
  }

  // ---- 1. 复制形态选择 ----

  @Test
  public void plainTextGetsOnlyMarkdownOption() {
    // 没有任何标记的文本：转换后与原文一字不差，给两个选项等于让用户以为其中一个坏了。
    assertEquals(Collections.singletonList(CopyFormat.MARKDOWN), formats("这是一段没有任何标记的说明。"));
  }

  @Test
  public void emptyTextGetsOnlyMarkdownOption() {
    assertEquals(Collections.singletonList(CopyFormat.MARKDOWN), formats(""));
  }

  @Test
  public void boldTextGetsBothOptions() {
    assertEquals(Arrays.asList(CopyFormat.MARKDOWN, CopyFormat.PLAIN_TEXT), formats("这是 **重点** 内容"));
  }

  @Test
  public void fencedCodeGetsBothOptions() {
    assertEquals(
        Arrays.asList(CopyFormat.MARKDOWN, CopyFormat.PLAIN_TEXT), formats("```\nval x = 1\n```"));
  }

  @Test
  public void bulletListGetsBothOptions() {
    assertEquals(
        Arrays.asList(CopyFormat.MARKDOWN, CopyFormat.PLAIN_TEXT), formats("- 第一项\n- 第二项"));
  }

  @Test
  public void headingGetsBothOptions() {
    assertEquals(Arrays.asList(CopyFormat.MARKDOWN, CopyFormat.PLAIN_TEXT), formats("# 标题"));
  }

  // ---- 2. markdown → 纯文本：各类标记 ----

  @Test
  public void boldMarkersAreRemoved() {
    assertEquals("粗体", plain("**粗体**"));
    assertEquals("粗体", plain("__粗体__"));
  }

  @Test
  public void italicMarkersAreRemoved() {
    assertEquals("斜体", plain("*斜体*"));
    assertEquals("斜体", plain("_斜体_"));
  }

  @Test
  public void strikethroughMarkersAreRemoved() {
    assertEquals("旧值", plain("~~旧值~~"));
  }

  @Test
  public void inlineCodeBackticksAreRemoved() {
    assertEquals("行内代码", plain("`行内代码`"));
  }

  @Test
  public void headingHashesAreRemoved() {
    assertEquals("一级标题", plain("# 一级标题"));
    assertEquals("三级标题", plain("### 三级标题"));
  }

  @Test
  public void linkBecomesTextWithUrlInParens() {
    // 纯文本里链接不可点，必须把 url 一并带出去，否则信息就丢了。
    assertEquals("文档 (https://example.com/doc)", plain("[文档](https://example.com/doc)"));
  }

  @Test
  public void imageBecomesAltWithUrl() {
    assertEquals("截图 (https://example.com/a.png)", plain("![截图](https://example.com/a.png)"));
  }

  @Test
  public void blockquoteMarkerIsRemoved() {
    assertEquals("引用的一行", plain("> 引用的一行"));
  }

  @Test
  public void multilineBlockquoteIsFlattenedButKeepsLines() {
    assertEquals("第一行\n第二行", plain("> 第一行\n> 第二行"));
  }

  @Test
  public void bulletListMarkerIsPreserved() {
    // 列表符号在纯文本里就是可读的结构，去掉反而让并列项连成一段。
    assertEquals("- 第一项\n- 第二项", plain("- 第一项\n- 第二项"));
  }

  @Test
  public void mathLikeAsterisksAreNotTreatedAsItalic() {
    // 模型解释里的乘法算式：两个星号之间隔着空格，不该被当成斜体配对吃掉。
    assertEquals("2 * 3 * 4 = 24", plain("2 * 3 * 4 = 24"));
  }

  @Test
  public void underscoresInsideIdentifiersAreKept() {
    // snake_case 标识符满地都是，不能因为下划线就被拆成强调。
    assertEquals("snake_case_name", plain("snake_case_name"));
  }

  @Test
  public void trailingBlankLinesAreTrimmed() {
    assertEquals("正文", plain("正文\n\n\n"));
  }

  // ---- 3. 代码块内容不被破坏 ----

  @Test
  public void codeBlockContentIsPreservedVerbatim() {
    // 代码里的 `*`（乘法 / 解引用）、`#`（预处理指令）、缩进都必须原样保留：
    // 一旦被当成 markdown 标记处理，复制出去的代码就编译不过。
    String md = "```c\nint a = 2 * 3;\n  if (a > 0) { *p = a; }\n#define N 4\n```";

    assertEquals("int a = 2 * 3;\n  if (a > 0) { *p = a; }\n#define N 4", plain(md));
  }

  @Test
  public void codeBlockKeepsLeadingIndentation() {
    String md = "```python\ndef f():\n    return 1\n```";

    assertEquals("def f():\n    return 1", plain(md));
  }

  @Test
  public void inlineCodeWithAsterisksIsNotItalicized() {
    // 行内代码必须先被挖出来保护，否则里面的星号会被强调规则配掉。
    assertEquals("a * b * c", plain("`a * b * c`"));
  }

  @Test
  public void textAroundCodeBlockIsStillConverted() {
    String md = "看这段 **代码**：\n```java\nval x = 1\n```\n以上就是全部。";

    assertEquals("看这段 代码：\nval x = 1\n以上就是全部。", plain(md));
  }

  // ---- 4. 引用上下文 ----

  @Test
  public void singleReferenceIsRenderedAsQuoteBlock() {
    assertEquals("> 引用自 助手回复：\n> 这段代码有问题", context(ref(7L, "assistant", "这段代码有问题")));
  }

  @Test
  public void userRoleLabelIsUsed() {
    assertEquals("> 引用自 用户消息：\n> 你好", context(ref(1L, "user", "你好")));
  }

  @Test
  public void multipleReferencesAreAppendedInOrder() {
    String ctx = context(ref(1L, "user", "第一段"), ref(2L, "assistant", "第二段"));

    assertEquals("> 引用自 用户消息：\n> 第一段\n> 引用自 助手回复：\n> 第二段", ctx);
  }

  @Test
  public void everyLineOfMultilineQuoteGetsPrefix() {
    // 这是本功能存在的理由：只给首行加前缀，多行引文就会「漏出」引用块，
    // 模型分不清哪些是原文、哪些是用户自己的话。
    String ctx = context(ref(3L, "assistant", "第一行\n第二行\n第三行"));

    assertEquals("> 引用自 助手回复：\n> 第一行\n> 第二行\n> 第三行", ctx);
    for (String line : ctx.split("\n", -1)) {
      assertTrue("每一行都必须带引用前缀，漏掉的是: " + line, line.startsWith("> "));
    }
  }

  @Test
  public void multilineQuoteWithMarkdownCharactersIsStillPrefixedPerLine() {
    String ctx = context(ref(4L, "assistant", "**粗体**\n- 列表项"));

    assertEquals("> 引用自 助手回复：\n> **粗体**\n> - 列表项", ctx);
  }

  @Test
  public void emptyReferenceListReturnsEmptyString() {
    // 返回空串而不是 null：调用方直接拼接即可，不必到处判空。
    assertEquals("", AssistantMessageActions.INSTANCE.referenceContext(Collections.emptyList()));
  }

  @Test
  public void blankQuoteIsSkipped() {
    String ctx = context(ref(1L, "user", "   "), ref(2L, "user", "有效内容"));

    assertEquals("> 引用自 用户消息：\n> 有效内容", ctx);
  }

  @Test
  public void quoteWithTrailingNewlineIsTrimmed() {
    assertEquals("> 引用自 用户消息：\n> abc", context(ref(1L, "user", "abc\n")));
  }

  // ---- 5. 轮次耗时 ----

  @Test
  public void zeroDurationIsEmptyString() {
    // 「没有数据」与「瞬间完成」必须能区分，0 返回空串让调用方决定不显示。
    assertEquals("", AssistantMessageActions.INSTANCE.formatDuration(0L));
  }

  @Test
  public void negativeDurationIsEmptyString() {
    assertEquals("", AssistantMessageActions.INSTANCE.formatDuration(-5L));
  }

  @Test
  public void subSecondDurationKeepsOneDecimal() {
    assertEquals("0.8s", AssistantMessageActions.INSTANCE.formatDuration(800L));
  }

  @Test
  public void subSecondDurationTruncatesInsteadOfRounding() {
    // 999ms 若四舍五入成 "1.0s" 会让人以为已经到 1 秒了。
    assertEquals("0.9s", AssistantMessageActions.INSTANCE.formatDuration(999L));
  }

  @Test
  public void secondsDurationKeepsOneDecimal() {
    assertEquals("12.3s", AssistantMessageActions.INSTANCE.formatDuration(12_300L));
  }

  @Test
  public void justUnderOneMinuteStaysInSeconds() {
    assertEquals("59.0s", AssistantMessageActions.INSTANCE.formatDuration(59_000L));
  }

  @Test
  public void exactlyOneMinuteSwitchesToMinutesAndSeconds() {
    assertEquals("1分0秒", AssistantMessageActions.INSTANCE.formatDuration(60_000L));
  }

  @Test
  public void minutesAndSecondsAreBothShown() {
    assertEquals("2分15秒", AssistantMessageActions.INSTANCE.formatDuration(135_000L));
  }

  @Test
  public void longDurationShowsPlainMinutes() {
    assertEquals("60分0秒", AssistantMessageActions.INSTANCE.formatDuration(3_600_000L));
  }

  // ---- 6. 引用回链地址 ----

  @Test
  public void pathFollowsChatScheme() {
    // 格式先定死：等真要做「点引用跳回原消息」时，历史会话里落盘的引用不必迁移。
    assertEquals("chat://assistant/42", ref(42L, "assistant", "x").getPath());
    assertEquals("chat://user/7", ref(7L, "user", "x").getPath());
  }
}
