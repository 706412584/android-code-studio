/*
 * This file is part of AndroidCodeStudio.
 *
 * AndroidCodeStudio is free software: you can redistribute it and/or modify
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

import java.util.List;
import org.junit.Test;

/**
 * 锁定 {@link CodeFenceParser} 的边界行为。
 *
 * <p>这些用例对应的是「实机上真的会遇到」的输入：模型写 `~~~` 分隔符、信息串带参数、
 * 流式输出到一半没有闭合围栏。拆段一旦出错，用户看到的是代码块被当成正文渲染
 * （或反过来，正文被吞进代码块），这类问题光看代码不容易发现。
 */
public class CodeFenceParserTest {

  private static List<CodeFenceParser.Segment> split(String md) {
    return CodeFenceParser.INSTANCE.split(md);
  }

  @Test
  public void pureTextYieldsSingleTextSegment() {
    List<CodeFenceParser.Segment> out = split("just some text");
    assertEquals(1, out.size());
    assertTrue(out.get(0) instanceof CodeFenceParser.Segment.Text);
  }

  @Test
  public void emptyInputYieldsNothing() {
    assertTrue(split("").isEmpty());
  }

  @Test
  public void simpleCodeBlockIsExtracted() {
    List<CodeFenceParser.Segment> out = split("before\n```kotlin\nval x = 1\n```\nafter");
    assertEquals(3, out.size());

    assertTrue(out.get(0) instanceof CodeFenceParser.Segment.Text);
    assertEquals("before\n", ((CodeFenceParser.Segment.Text) out.get(0)).getMarkdown());

    CodeFenceParser.Segment.Code code = (CodeFenceParser.Segment.Code) out.get(1);
    assertEquals("kotlin", code.getLanguage());
    assertEquals("val x = 1", code.getCode());

    assertTrue(out.get(2) instanceof CodeFenceParser.Segment.Text);
    assertEquals("after\n", ((CodeFenceParser.Segment.Text) out.get(2)).getMarkdown());
  }

  @Test
  public void languageIsLowercased() {
    List<CodeFenceParser.Segment> out = split("```Kotlin\nx\n```");
    assertEquals("kotlin", ((CodeFenceParser.Segment.Code) out.get(0)).getLanguage());
  }

  @Test
  public void infoStringExtraWordsIgnored() {
    // 模型常写 ```java title="Foo.java"，只有第一个词是语言。
    List<CodeFenceParser.Segment> out = split("```java title=\"Foo.java\"\nx\n```");
    assertEquals("java", ((CodeFenceParser.Segment.Code) out.get(0)).getLanguage());
  }

  @Test
  public void missingLanguageYieldsEmptyString() {
    List<CodeFenceParser.Segment> out = split("```\nx\n```");
    assertEquals("", ((CodeFenceParser.Segment.Code) out.get(0)).getLanguage());
  }

  @Test
  public void tildeFenceSupported() {
    List<CodeFenceParser.Segment> out = split("~~~python\nx\n~~~");
    assertEquals("python", ((CodeFenceParser.Segment.Code) out.get(0)).getLanguage());
    assertEquals("x", ((CodeFenceParser.Segment.Code) out.get(0)).getCode());
  }

  @Test
  public void backtickInsideTildeFenceIsLiteral() {
    // ~~~ 围栏内出现 ``` 不应闭合。
    List<CodeFenceParser.Segment> out = split("~~~\n```\n~~~");
    assertEquals(1, out.size());
    assertEquals("```", ((CodeFenceParser.Segment.Code) out.get(0)).getCode());
  }

  @Test
  public void unclosedFenceStillEmitsCode() {
    // 流式输出到一半：模型还没吐闭合行。必须按代码块输出，否则正在显示的代码会消失。
    List<CodeFenceParser.Segment> out = split("```kotlin\nval x = 1");
    assertEquals(1, out.size());
    assertEquals("val x = 1", ((CodeFenceParser.Segment.Code) out.get(0)).getCode());
  }

  @Test
  public void longerClosingFenceCloses() {
    // CommonMark：闭合围栏不得短于开启行，但可以更长。
    List<CodeFenceParser.Segment> out = split("```\nx\n`````\ntail");
    assertEquals(2, out.size());
    assertEquals("x", ((CodeFenceParser.Segment.Code) out.get(0)).getCode());
  }

  @Test
  public void shorterClosingFenceDoesNotClose() {
    List<CodeFenceParser.Segment> out = split("````\nx\n```\n````");
    assertEquals(1, out.size());
    // 中间的 ``` 被当作代码内容。
    assertEquals("x\n```", ((CodeFenceParser.Segment.Code) out.get(0)).getCode());
  }

  @Test
  public void trailingBlankLinesInCodeStripped() {
    List<CodeFenceParser.Segment> out = split("```\nx\n\n\n```");
    assertEquals("x", ((CodeFenceParser.Segment.Code) out.get(0)).getCode());
  }

  @Test
  public void indentedFenceUpToThreeSpacesRecognized() {
    List<CodeFenceParser.Segment> out = split("   ```go\nx\n   ```");
    assertEquals(1, out.size());
    assertEquals("go", ((CodeFenceParser.Segment.Code) out.get(0)).getLanguage());
  }

  @Test
  public void fourSpaceIndentIsNotFence() {
    // 4 个前导空格是缩进代码块，不是围栏——按普通文本处理。
    List<CodeFenceParser.Segment> out = split("    ```go\nx\n    ```");
    assertEquals(1, out.size());
    assertTrue(out.get(0) instanceof CodeFenceParser.Segment.Text);
  }

  @Test
  public void twoCodeBlocksExtracted() {
    List<CodeFenceParser.Segment> out = split("a\n```\n1\n```\nb\n```\n2\n```\nc");
    assertEquals(5, out.size());
    assertEquals("1", ((CodeFenceParser.Segment.Code) out.get(1)).getCode());
    assertEquals("2", ((CodeFenceParser.Segment.Code) out.get(3)).getCode());
  }

  @Test
  public void backtickInInfoStringRejectedAsFence() {
    // CommonMark：反引号围栏的信息串里不能再有反引号。
    List<CodeFenceParser.Segment> out = split("```fo`o\nx\n```");
    assertTrue(out.get(0) instanceof CodeFenceParser.Segment.Text);
  }

  @Test
  public void codeBlockAtStartWithoutLeadingText() {
    List<CodeFenceParser.Segment> out = split("```\nx\n```");
    assertEquals(1, out.size());
    assertTrue(out.get(0) instanceof CodeFenceParser.Segment.Code);
  }

  @Test
  public void emptyCodeBlockKept() {
    List<CodeFenceParser.Segment> out = split("```\n```");
    assertEquals(1, out.size());
    assertEquals("", ((CodeFenceParser.Segment.Code) out.get(0)).getCode());
  }
}
