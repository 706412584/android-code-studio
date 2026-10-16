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

package com.tom.rv2ide.ai.tool.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * 钉住 {@link ToolArgsCleaner} 的容错行为：模型返回的工具参数 JSON 经常不是合法
 * JSON（带 markdown 围栏、注释、单引号、多余尾逗号、被截断），这些用例固定
 * 「清理后能当 JSON 解析」这一契约。
 */
public final class ToolArgsCleanerTest {

  @Test
  public void nullAndBlankCollapseToEmptyObject() {
    assertEquals("{}", ToolArgsCleaner.clean(null));
    assertEquals("{}", ToolArgsCleaner.clean(""));
    assertEquals("{}", ToolArgsCleaner.clean("   \t\n"));
  }

  @Test
  public void alreadyValidJsonIsPassedThrough() {
    assertEquals("{\"a\":1}", ToolArgsCleaner.clean("{\"a\":1}"));
    // 前后空白会被裁掉
    assertEquals("{\"a\":1}", ToolArgsCleaner.clean("  {\"a\":1}  "));
  }

  @Test
  public void stripsJsonMarkdownFence() {
    assertEquals("{\"a\":1}", ToolArgsCleaner.clean("```json\n{\"a\":1}\n```"));
    assertEquals("{\"a\":1}", ToolArgsCleaner.clean("```\n{\"a\":1}\n```"));
  }

  @Test
  public void stripsFenceWithCrLfLineEndings() {
    assertEquals("{\"a\":1}", ToolArgsCleaner.clean("```json\r\n{\"a\":1}\r\n```"));
  }

  @Test
  public void stripsFenceWithBareCarriageReturn() {
    assertEquals("{\"a\":1}", ToolArgsCleaner.clean("```json\r{\"a\":1}\r```"));
  }

  @Test
  public void unterminatedFenceIsLeftIntact() {
    // 只有起始围栏、没有闭合围栏（模型输出被截断）：不做剥离，走早退分支
    String result = ToolArgsCleaner.clean("```json\n{\"a\":1}");
    assertTrue(result.contains("```"));
    assertTrue(result.contains("{\"a\":1}"));
  }

  @Test
  public void lastFenceBeforeFirstLineBreakIsLeftIntact() {
    // 闭合围栏出现在首个换行之前：不构成合法围栏，原样保留（换行本身作为控制字符被丢弃）
    assertEquals("``` ```rest", ToolArgsCleaner.clean("``` ```\nrest"));
  }

  @Test
  public void stripsZeroWidthCharacters() {
    String withZeroWidth = "{\u200B\"a\u200C\":\u200D1\uFEFF}\u2060";
    assertEquals("{\"a\":1}", ToolArgsCleaner.clean(withZeroWidth));
  }

  @Test
  public void normalizesFullWidthCharactersViaNfkc() {
    // 全角冒号与全角数字经 NFKC 归一化回 ASCII，否则 JSON 解析失败
    assertEquals("{\"a\":1}", ToolArgsCleaner.clean("{\"\uFF41\":\uFF11}"));
  }

  @Test
  public void removesLineComments() {
    String result = ToolArgsCleaner.clean("{\"a\":1, //comment\n\"b\":2}");
    assertFalse(result.contains("comment"));
    assertTrue(result.contains("\"b\":2"));
  }

  @Test
  public void removesTrailingLineCommentWithoutNewline() {
    assertEquals("{\"a\":1}", ToolArgsCleaner.clean("{\"a\":1} //comment").trim());
  }

  @Test
  public void removesBlockComments() {
    assertEquals("{\"a\":1}", ToolArgsCleaner.clean("{\"a\":/*why*/1}"));
  }

  @Test
  public void unterminatedBlockCommentIsDroppedFromMarker() {
    // 未闭合的 /* 起，剩下的内容按注释丢弃——只留下标记前的残留字符 `d`
    assertEquals("{\"a\":1} d", ToolArgsCleaner.clean("{\"a\":1} /* never closed").trim());
  }

  @Test
  public void doesNotTreatDoubleSlashInsideStringAsComment() {
    // URL 里的 // 若被当注释删掉，file_read 的路径参数就被破坏
    assertEquals("{\"url\":\"http://x\"}", ToolArgsCleaner.clean("{\"url\":\"http://x\"}"));
  }

  @Test
  public void controlCharsInsideStringsBecomeEscapes() {
    assertEquals("{\"a\":\"l1\\nl2\\r\\t\"}",
        ToolArgsCleaner.clean("{\"a\":\"l1\nl2\r\t\"}"));
  }

  @Test
  public void otherControlCharsInsideStringsAreDropped() {
    assertEquals("{\"a\":\"ab\"}", ToolArgsCleaner.clean("{\"a\":\"a\u0001b\"}"));
  }

  @Test
  public void controlCharsOutsideStringsAreDropped() {
    assertEquals("{\"a\":1}", ToolArgsCleaner.clean("{\u0001\"a\":\t1}"));
  }

  @Test
  public void removesTrailingCommaInObject() {
    assertEquals("{\"a\":1}", ToolArgsCleaner.clean("{\"a\":1,}"));
  }

  @Test
  public void removesTrailingCommaInArray() {
    assertEquals("[1,2]", ToolArgsCleaner.clean("[1,2,]"));
  }

  @Test
  public void removesTrailingCommaEvenWithWhitespaceBeforeCloser() {
    assertEquals("{\"a\":1}", ToolArgsCleaner.clean("{\"a\":1 , }").replace(" ", ""));
  }

  @Test
  public void trailingCommaInsideStringIsNotRemoved() {
    assertEquals("{\"a\":\"x,\"}", ToolArgsCleaner.clean("{\"a\":\"x,\"}"));
  }

  @Test
  public void convertsSimpleSingleQuotedStringsToDoubleQuotes() {
    assertEquals("{\"a\":\"hello\"}", ToolArgsCleaner.clean("{'a':'hello'}"));
  }

  @Test
  public void singleQuotedContentWithDoubleQuoteOnlyMovesTheKey() {
    // 内容里含双引号时不能安全替换，只把 key 转成双引号；值保持单引号（后续由
    // balanceBraces 认为字符串已闭合，故补上 `"}`）
    assertEquals("{\"a\":'he\"llo'}\"}", ToolArgsCleaner.clean("{'a':'he\"llo'}"));
  }

  @Test
  public void unterminatedSingleQuoteConvertsKeyAndLeavesTail() {
    // 找不到配对的右单引号：key 仍被转换，值原样保留
    assertEquals("{\"a\":'oops}", ToolArgsCleaner.clean("{'a':'oops}"));
  }

  @Test
  public void keepsSingleQuoteInsideDoubleQuotedString() {
    assertEquals("{\"a\":\"it's\"}", ToolArgsCleaner.clean("{\"a\":\"it's\"}"));
  }

  @Test
  public void escapesInsideSingleQuotedConversionArePreserved() {
    assertEquals("{\"a\":\"it\\'s\"}", ToolArgsCleaner.clean("{'a':'it\\'s'}"));
  }

  @Test
  public void balancesTruncatedObject() {
    assertEquals("{\"a\":1}", ToolArgsCleaner.clean("{\"a\":1"));
  }

  @Test
  public void balancesTruncatedArray() {
    assertEquals("[1,2]", ToolArgsCleaner.clean("[1,2"));
  }

  @Test
  public void closesUnterminatedString() {
    assertEquals("{\"a\":\"abc\"}", ToolArgsCleaner.clean("{\"a\":\"abc"));
  }

  @Test
  public void balancesMixedNestedStructure() {
    assertEquals("{\"a\":{\"b\":[1,2]}}", ToolArgsCleaner.clean("{\"a\":{\"b\":[1,2"));
  }

  @Test
  public void preservesEscapedQuoteInStringWhileBalancing() {
    assertEquals("{\"a\":\"say \\\"hi\\\"\"}", ToolArgsCleaner.clean("{\"a\":\"say \\\"hi\\\"\"}"));
  }

  @Test
  public void preservesBackslashPathInString() {
    assertEquals("{\"p\":\"c:\\\\path\"}", ToolArgsCleaner.clean("{\"p\":\"c:\\\\path\"}"));
  }

  @Test
  public void mismatchedCloserDoesNotPopStackSoItIsReClosed() {
    // `]` 与栈顶 `{` 不匹配，栈不弹出；最后补上真正的闭合符号
    assertEquals("{]}", ToolArgsCleaner.clean("{]"));
  }

  @Test
  public void trailingSlashOutsideStringIsKept() {
    assertEquals("{\"a\":1}/", ToolArgsCleaner.clean("{\"a\":1}/"));
  }

  @Test
  public void partiallyValidInputStillParsesAsJson() {
    String cleaned = ToolArgsCleaner.clean(
        "```json\n{ 'file_path': 'a/b.java', 'count': 3, } // note\n```");
    JSONObject parsed = new JSONObject(cleaned);
    assertEquals("a/b.java", parsed.getString("file_path"));
    assertEquals(3, parsed.getInt("count"));
  }

  @Test
  public void contentThatCleansDownToNothingBecomesEmptyObject() {
    // 全是注释/空白：清理后为空，必须回退成合法的 "{}" 而不是空串
    assertEquals("{}", ToolArgsCleaner.clean("// just a comment"));
    assertEquals("{}", ToolArgsCleaner.clean("/* block */"));
    assertEquals("{}", ToolArgsCleaner.clean("```json\n\n```"));
  }
}
