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

package com.tom.rv2ide.ai.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * HTML 正文提取的回归测试。
 *
 * <p><b>为什么需要它</b>：提取结果直接回灌给模型。两类错误代价最高——把正文丢掉
 * （模型收到空内容，误以为页面是空的）、以及把 {@code <script>} 内容当成正文
 * （模型收到一堆 JS，既浪费上下文又干扰理解）。
 */
final class HtmlTextExtractorTest {

  @Test
  void extractsTextFromSimpleDocument() {
    String html = "<html><body><p>Hello world</p></body></html>";
    assertEquals("Hello world", HtmlTextExtractor.extract(html));
  }

  @Test
  void dropsScriptAndStyleContent() {
    // script/style 的内容不是给人读的正文。把它们当正文回灌会占满上下文。
    String html =
        "<html><head><style>body{color:red}</style></head>"
            + "<body><script>var x = 1; alert('hi');</script><p>Real content</p></body></html>";
    String text = HtmlTextExtractor.extract(html);

    assertEquals("Real content", text);
    assertFalse(text.contains("alert"));
    assertFalse(text.contains("color:red"));
  }

  @Test
  void dropsNestedScriptSections() {
    String html = "<div><script>a</script><script>b</script><p>keep</p></div>";
    assertEquals("keep", HtmlTextExtractor.extract(html));
  }

  @Test
  void unclosedScriptDropsToEndWithoutCrashing() {
    // 截断的页面（网络中断）会留下未闭合的 script。不能抛异常，也不能把 JS 当正文。
    String html = "<p>before</p><script>var x = 1;";
    String text = HtmlTextExtractor.extract(html);

    assertEquals("before", text);
    assertFalse(text.contains("var x"));
  }

  @Test
  void blockTagsProduceLineBreaks() {
    // 段落结构是正文可读性的一部分；全压成一行会让标题与正文混在一起。
    String html = "<div>First</div><div>Second</div>";
    assertEquals("First\nSecond", HtmlTextExtractor.extract(html));
  }

  @Test
  void inlineTagsDoNotBreakLines() {
    // <b>/<span> 是行内标签，不应把一句话拆成多行。
    String html = "<p>Hello <b>bold</b> and <span>span</span> text</p>";
    assertEquals("Hello bold and span text", HtmlTextExtractor.extract(html));
  }

  @Test
  void decodesCommonEntities() {
    String html = "<p>a &amp; b &lt;tag&gt; &quot;quoted&quot; &nbsp;end</p>";
    assertEquals("a & b <tag> \"quoted\" end", HtmlTextExtractor.extract(html));
  }

  @Test
  void doesNotDoubleDecodeEntities() {
    // &amp;lt; 表示字面量 "&lt;"，不是 "<"。先解 &amp; 会把它变成 &lt; 再解成 <，
    // 造成双重解码——正文里的示例代码会被破坏。
    String html = "<p>&amp;lt;div&amp;gt;</p>";
    assertEquals("&lt;div&gt;", HtmlTextExtractor.extract(html));
  }

  @Test
  void collapsesRepeatedWhitespaceButKeepsStructure() {
    String html = "<p>a     b</p>\n\n\n\n<p>c</p>";
    assertEquals("a b\nc", HtmlTextExtractor.extract(html));
  }

  @Test
  void keepsLiteralLessThanSignInText() {
    // 真实页面里有「a < b」这类裸 '<'。从这里开始丢弃到下一个 '>' 会吃掉正常正文。
    String html = "<p>if a < b then done</p>";
    String text = HtmlTextExtractor.extract(html);

    assertTrue(text.contains("if a"), "正文不应被丢弃，实际: " + text);
    assertTrue(text.contains("done"));
  }

  @Test
  void handlesCommentsAndDoctype() {
    String html = "<!DOCTYPE html><!-- a comment --><html><body><p>Body</p></body></html>";
    assertEquals("Body", HtmlTextExtractor.extract(html));
  }

  @Test
  void returnsEmptyForNullOrEmptyInput() {
    assertEquals("", HtmlTextExtractor.extract(null));
    assertEquals("", HtmlTextExtractor.extract(""));
  }

  @Test
  void returnsEmptyForMarkupOnlyDocument() {
    // 全靠 JS 渲染的页面抓下来是空壳，应当得到空串（由工具层提示用户）。
    assertEquals("", HtmlTextExtractor.extract("<html><head></head><body><div></div></body></html>"));
  }

  @Test
  void doesNotConfuseTagPrefixesWithBlockTags() {
    // <pre> 是块级，<presentation> 不是。按前缀匹配会把后者的内容错误换行。
    String html = "<presentation>inline</presentation><p>after</p>";
    String text = HtmlTextExtractor.extract(html);

    assertTrue(text.contains("inline"));
    assertTrue(text.contains("after"));
  }

  @Test
  void handlesUnquotedAndUppercaseTags() {
    String html = "<P CLASS=lead>Upper</P><DIV>case</DIV>";
    assertEquals("Upper\ncase", HtmlTextExtractor.extract(html));
  }
}
