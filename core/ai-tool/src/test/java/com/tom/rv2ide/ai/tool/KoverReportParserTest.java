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
 * along with AndroidCodeStudio.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.ai.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 钉住 kover（JaCoCo 格式）报告的解析语义。
 *
 * <p>这些断言锁住三个容易写错的地方：<br>
 * ① 计数器只取**直接子元素**——kover 在 method/class/package/report 四层都写了
 * {@code <counter>}，用 {@code getElementsByTagName} 会把子孙的计数重复累加；<br>
 * ② 未覆盖类按缺口降序（模型的注意力要先落在最差的类上）；<br>
 * ③ 非法/异常输入必须抛异常而不是静默返回空——否则「解析失败」会被上层当成
 * 「全部达标」报给模型。
 */
final class KoverReportParserTest {

  /** 一份仿真的 mini 报告：两个包、三个类，计数刻意做成有层级的嵌套。 */
  private static final String MINI_REPORT =
      "<?xml version=\"1.0\" ?>\n"
          + "<report name=\"Kover XML for :core:x\">\n"
          + "<package name=\"com/x\">\n"
          + "<class name=\"com/x/Alpha\" sourcefilename=\"Alpha.java\">\n"
          + "<method name=\"run\" desc=\"()V\">\n"
          + "<counter type=\"LINE\" missed=\"5\" covered=\"5\"/>\n"
          + "</method>\n"
          + "<counter type=\"LINE\" missed=\"5\" covered=\"5\"/>\n"
          + "</class>\n"
          + "<class name=\"com/x/Beta\" sourcefilename=\"Beta.java\">\n"
          + "<method name=\"go\" desc=\"()V\">\n"
          + "<counter type=\"LINE\" missed=\"0\" covered=\"20\"/>\n"
          + "</method>\n"
          + "<counter type=\"LINE\" missed=\"0\" covered=\"20\"/>\n"
          + "</class>\n"
          + "</package>\n"
          + "<package name=\"com/y\">\n"
          + "<class name=\"com/y/Gamma\" sourcefilename=\"Gamma.java\">\n"
          + "<counter type=\"LINE\" missed=\"30\" covered=\"0\"/>\n"
          + "</class>\n"
          + "</package>\n"
          + "<counter type=\"LINE\" missed=\"35\" covered=\"25\"/>\n"
          + "<counter type=\"BRANCH\" missed=\"4\" covered=\"2\"/>\n"
          + "</report>\n";

  private static KoverReportParser.ModuleCoverage parseMini() throws IOException {
    return KoverReportParser.parse(
        new ByteArrayInputStream(MINI_REPORT.getBytes(StandardCharsets.UTF_8)), "core/x");
  }

  @Test
  void readsRootCountersForModuleTotals() throws Exception {
    KoverReportParser.ModuleCoverage m = parseMini();
    // 取根上的汇总计数器，而不是逐类累加（累加会因 method 层重复计数而虚高）。
    assertEquals(35, m.missedLines, "模块未覆盖行数应取根计数器");
    assertEquals(25, m.coveredLines, "模块已覆盖行数应取根计数器");
    assertEquals(60, m.totalLines());
    assertEquals(60d, m.totalLines());
    assertTrue(m.percent() > 41.6 && m.percent() < 41.7, "25/60 ≈ 41.7%，实际 " + m.percent());
  }

  @Test
  void classCounterIsNotDoubleCountedFromMethods() throws Exception {
    KoverReportParser.ModuleCoverage m = parseMini();
    KoverReportParser.ClassCoverage alpha = findClass(m, "com/x/Alpha");
    // Alpha 的类级 counter 是 5/5；若把 method 层（也是 5/5）累加进去会变成 10/10。
    assertEquals(5, alpha.missedLines, "类计数不得把 method 层的计数重复累加");
    assertEquals(5, alpha.coveredLines);
  }

  @Test
  void fullyCoveredClassesAreExcluded() throws Exception {
    KoverReportParser.ModuleCoverage m = parseMini();
    // Beta 是 0 missed，不属于「缺口」，不应出现在清单里。
    for (KoverReportParser.ClassCoverage c : m.uncoveredClasses) {
      assertTrue(c.missedLines > 0, "完全覆盖的类不应出现：" + c.className);
    }
    assertTrue(findOrNull(m, "com/x/Beta") == null, "Beta 应被排除");
  }

  @Test
  void uncoveredClassesSortedByBiggestGapFirst() throws Exception {
    KoverReportParser.ModuleCoverage m = parseMini();
    List<KoverReportParser.ClassCoverage> list = m.uncoveredClasses;
    assertEquals(2, list.size(), "应只有 Alpha 与 Gamma 两个未完全覆盖的类");
    // Gamma 缺 30 行 > Alpha 缺 5 行 → Gamma 在前。
    assertEquals("com/y/Gamma", list.get(0).className);
    assertEquals(30, list.get(0).missedLines);
    assertEquals("com/x/Alpha", list.get(1).className);
  }

  @Test
  void zeroCoveredClassReportsZeroPercent() throws Exception {
    KoverReportParser.ModuleCoverage m = parseMini();
    KoverReportParser.ClassCoverage gamma = findClass(m, "com/y/Gamma");
    assertEquals(0d, gamma.percent(), "缺 30/30 应为 0%");
  }

  @Test
  void malformedXmlThrowsInsteadOfReturningEmpty() {
    // 关键：解析失败必须抛异常。若静默返回空，上层会把它当成「全部达标」报给模型。
    assertThrows(
        IOException.class,
        () ->
            KoverReportParser.parse(
                new ByteArrayInputStream("<report><unclosed>".getBytes(StandardCharsets.UTF_8)),
                "core/x"));
  }

  @Test
  void wrongRootElementThrows() {
    assertThrows(
        IOException.class,
        () ->
            KoverReportParser.parse(
                new ByteArrayInputStream("<coverage/>".getBytes(StandardCharsets.UTF_8)),
                "core/x"));
  }

  @Test
  void doctypeWithExternalEntityIsRejected() {
    // XXE 防护：带 DTD 的输入必须被解析器直接拒绝，而不是尝试读取 file:///。
    String xxe =
        "<?xml version=\"1.0\"?>\n"
            + "<!DOCTYPE report [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>\n"
            + "<report name=\"&xxe;\"><counter type=\"LINE\" missed=\"0\" covered=\"1\"/></report>";
    assertThrows(
        IOException.class,
        () ->
            KoverReportParser.parse(
                new ByteArrayInputStream(xxe.getBytes(StandardCharsets.UTF_8)), "core/x"));
  }

  @Test
  void emptyReportIsOneHundredPercent() throws Exception {
    String empty = "<?xml version=\"1.0\" ?><report name=\"x\">"
        + "<counter type=\"LINE\" missed=\"0\" covered=\"0\"/></report>";
    KoverReportParser.ModuleCoverage m =
        KoverReportParser.parse(
            new ByteArrayInputStream(empty.getBytes(StandardCharsets.UTF_8)), "core/x");
    // 无可覆盖代码不构成缺口：报 100% 而不是 0%（0% 会让上层误报「全都没测」）。
    assertEquals(100d, m.percent());
    assertTrue(m.uncoveredClasses.isEmpty());
  }

  private static KoverReportParser.ClassCoverage findClass(
      KoverReportParser.ModuleCoverage m, String name) {
    KoverReportParser.ClassCoverage c = findOrNull(m, name);
    assertTrue(c != null, "找不到类 " + name);
    return c;
  }

  private static KoverReportParser.ClassCoverage findOrNull(
      KoverReportParser.ModuleCoverage m, String name) {
    for (KoverReportParser.ClassCoverage c : m.uncoveredClasses) {
      if (c.className.equals(name)) {
        return c;
      }
    }
    return null;
  }
}
