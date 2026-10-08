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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/**
 * {@link AssistantDiffRenderer} 的排版测试。
 *
 * <p><b>为什么这些必须机器验证</b>：行号列宽、正文起始列、词级高亮的偏移，错一格在截图上看
 * 都"差不多"，但整块 diff 的可读性恰恰依赖左边缘严格对齐。肉眼看不出，断言能。
 */
public class AssistantDiffRendererTest {

  private static DiffResult result(DiffLine... lines) {
    int added = 0;
    int removed = 0;
    for (DiffLine line : lines) {
      if (line.getType() == DiffLineType.INSERT) added++;
      if (line.getType() == DiffLineType.DELETE) removed++;
    }
    return new DiffResult(Arrays.asList(lines), added, removed, false);
  }

  private static DiffLine equal(int oldNo, int newNo, String text) {
    return new DiffLine(oldNo, newNo, DiffLineType.EQUAL, text, new ArrayList<>());
  }

  private static DiffLine insert(int newNo, String text) {
    return new DiffLine(null, newNo, DiffLineType.INSERT, text, new ArrayList<>());
  }

  private static DiffLine delete(int oldNo, String text) {
    return new DiffLine(oldNo, null, DiffLineType.DELETE, text, new ArrayList<>());
  }

  /** 正文的起始列在所有行上必须一致——这是 diff 可读性的基础。 */
  @Test
  public void bodyColumnIsIdenticalOnEveryLine() {
    DiffResult r =
        result(
            equal(1, 1, "aaa"),
            delete(2, "bbb"),
            insert(2, "ccc"),
            equal(3, 3, "ddd"));

    List<AssistantDiffRenderer.RenderedLine> lines = AssistantDiffRenderer.render(r);
    // 正文起点 = 分隔符位置 + 1(分隔符后的空格) + 2(增删标记宽度) = +4
    int expected = lines.get(0).getText().indexOf("│") + 4;
    for (AssistantDiffRenderer.RenderedLine line : lines) {
      assertEquals(
          "正文起始列必须一致: [" + line.getText() + "]",
          expected,
          line.getText().indexOf("│") + 4);
    }
    // 再直接验证三行正文确实各自出现在该列上
    assertEquals("aaa", lines.get(0).getText().substring(expected));
    assertEquals("bbb", lines.get(1).getText().substring(expected));
    assertEquals("ccc", lines.get(2).getText().substring(expected));
  }

  /** 行号右对齐：位数不同时也不能让左边缘参差。 */
  @Test
  public void lineNumbersAreRightAligned() {
    DiffResult r =
        result(
            equal(9, 9, "x"),
            equal(10, 10, "y"),
            equal(100, 100, "z"));

    List<AssistantDiffRenderer.RenderedLine> lines = AssistantDiffRenderer.render(r);
    // 最大行号 100 有 3 位，因此第 1 行的 "9" 前应有 2 个空格。
    String first = lines.get(0).getText();
    assertTrue("短行号应左补空格: [" + first + "]", first.startsWith("  9"));
    String third = lines.get(2).getText();
    assertTrue("长行号应顶格: [" + third + "]", third.startsWith("100"));
  }

  /** 缺失的行号输出等宽空格，而不是空串——否则该行正文会左移。 */
  @Test
  public void missingLineNumberKeepsColumnWidth() {
    DiffResult r = result(equal(1, 1, "keep"), insert(2, "added"));
    List<AssistantDiffRenderer.RenderedLine> lines = AssistantDiffRenderer.render(r);
    String insertLine = lines.get(1).getText();
    // 新增行没有旧行号，但前缀宽度必须与上一行一致。
    int sep1 = lines.get(0).getText().indexOf("│");
    int sep2 = insertLine.indexOf("│");
    assertEquals("分隔符列必须一致", sep1, sep2);
  }

  /** 增删标记必须正确。 */
  @Test
  public void markersMatchLineType() {
    DiffResult r = result(delete(1, "old"), insert(1, "new"), equal(2, 2, "same"));
    List<AssistantDiffRenderer.RenderedLine> lines = AssistantDiffRenderer.render(r);
    // 分隔符两侧都有空格（GUTTER = " │ "），标记紧跟其后。
    assertTrue("删除行应有 - 标记: [" + lines.get(0).getText() + "]",
        lines.get(0).getText().contains("│ - "));
    assertTrue("新增行应有 + 标记: [" + lines.get(1).getText() + "]",
        lines.get(1).getText().contains("│ + "));
    assertTrue("未变行应是空格标记: [" + lines.get(2).getText() + "]",
        lines.get(2).getText().contains("│   "));
  }

  /**
   * 词级高亮的区间必须落在正文上，不能落到行号区。
   *
   * <p>这是最容易错的一处：偏移忘了加前缀长度时，高亮会画在行号上，
   * 看起来"有颜色"但完全指错了地方。
   */
  @Test
  public void changedRangesPointIntoBodyNotGutter() {
    List<DiffSegment> segs =
        Arrays.asList(new DiffSegment("val ", false), new DiffSegment("oldName", true));
    DiffLine line = new DiffLine(3, null, DiffLineType.DELETE, "val oldName", segs);
    DiffResult r = result(line);

    AssistantDiffRenderer.RenderedLine rendered = AssistantDiffRenderer.render(r).get(0);
    assertEquals(1, rendered.getChangedRanges().size());
    // Kotlin 的 IntRange 在 Java 侧是 getStart()/getEndInclusive()，
    // 不是 first/last（那是扩展属性，Java 看不到）。
    kotlin.ranges.IntRange range = rendered.getChangedRanges().get(0);
    int sep = rendered.getText().indexOf("│");
    assertTrue("高亮起点必须晚于分隔符", range.getStart() > sep);
    assertEquals(
        "高亮内容应正好是被改的词",
        "oldName",
        rendered.getText().substring(range.getStart(), range.getEndInclusive() + 1));
  }

  /** 折叠：maxLines 生效，且只输出前 N 行。 */
  @Test
  public void maxLinesTruncates() {
    DiffLine[] lines = new DiffLine[10];
    for (int i = 0; i < 10; i++) {
      lines[i] = equal(i + 1, i + 1, "line" + i);
    }
    DiffResult r = result(lines);
    assertEquals(3, AssistantDiffRenderer.render(r, 3).size());
    assertEquals(10, AssistantDiffRenderer.render(r).size());
    assertEquals(10, AssistantDiffRenderer.render(r, 999).size());
  }

  /** 空 diff 不抛异常，返回空列表。 */
  @Test
  public void emptyResultRendersNothing() {
    DiffResult r = new DiffResult(new ArrayList<>(), 0, 0, false);
    assertTrue(AssistantDiffRenderer.render(r).isEmpty());
  }

  /** 纯文本导出用 +/- 前缀，不含行号排版——它是给编辑器/issue 用的。 */
  @Test
  public void plainTextUsesDiffNotationWithoutGutter() {
    DiffResult r = result(delete(1, "old"), insert(1, "new"), equal(2, 2, "same"));
    String text = AssistantDiffRenderer.toPlainText(r);
    assertEquals("- old\n+ new\n  same\n", text);
    assertTrue("导出不应含行号分隔符", !text.contains("│"));
  }

  /** 空段落不应产生零长度高亮区间。 */
  @Test
  public void emptySegmentsDoNotProduceEmptyRanges() {
    List<DiffSegment> segs =
        Arrays.asList(new DiffSegment("", true), new DiffSegment("real", true));
    DiffLine line = new DiffLine(1, null, DiffLineType.DELETE, "real", segs);
    AssistantDiffRenderer.RenderedLine rendered =
        AssistantDiffRenderer.render(result(line)).get(0);
    for (kotlin.ranges.IntRange range : rendered.getChangedRanges()) {
      assertTrue("不应有零长度区间", range.getEndInclusive() >= range.getStart());
    }
    assertTrue(rendered.getText().endsWith("real"));
  }
}
