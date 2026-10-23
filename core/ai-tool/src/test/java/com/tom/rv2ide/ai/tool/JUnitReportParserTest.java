/*
 * This file is part of AndroidCodeStudio.
 *
 * This program is free software: you can redistribute it and/or modify
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

package com.tom.rv2ide.ai.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link JUnitReportParser}：Gradle 产出的 JUnit XML 解析。
 *
 * <p>报告是外部输入（写一半、编码异常都可能），因此除了正常路径，重点测**容错**：
 * 一份坏报告不能把整次查询拖垮。
 */
class JUnitReportParserTest {

  private static void write(File file, String content) throws Exception {
    file.getParentFile().mkdirs();
    Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
  }

  private static String suite(String body, int tests, int skipped) {
    return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
        + "<testsuite name=\"s\" tests=\"" + tests + "\" skipped=\"" + skipped
        + "\" failures=\"0\" errors=\"0\">\n" + body + "</testsuite>\n";
  }

  @Test
  void parsesPassingSuite(@TempDir File dir) throws Exception {
    write(new File(dir, "TEST-a.xml"), suite(
        "  <testcase classname=\"com.A\" name=\"ok\" time=\"0.01\"/>\n", 1, 0));

    JUnitReportParser.Summary summary = JUnitReportParser.parseDirectory(dir);
    assertEquals(1, summary.tests);
    assertEquals(0, summary.failed());
    assertEquals(1, summary.passed());
    assertTrue(summary.isSuccess());
  }

  @Test
  void parsesFailuresWithMessageAndStack(@TempDir File dir) throws Exception {
    write(new File(dir, "TEST-a.xml"), suite(
        "  <testcase classname=\"com.A\" name=\"bad\">\n"
            + "    <failure message=\"expected 1 but was 2\">java.lang.AssertionError: expected 1 but was 2\n"
            + "      at com.A.bad(A.java:10)\n"
            + "      at org.junit.runners.ParentRunner.run(ParentRunner.java:1)\n"
            + "    </failure>\n"
            + "  </testcase>\n", 1, 0));

    JUnitReportParser.Summary summary = JUnitReportParser.parseDirectory(dir);
    assertEquals(1, summary.failed());
    JUnitReportParser.Failure failure = summary.failures.get(0);
    assertEquals("com.A.bad", failure.display());
    assertEquals("expected 1 but was 2", failure.message);
    assertFalse(failure.isError, "failure 标签不是 error");
    assertTrue(failure.stack.contains("at com.A.bad(A.java:10)"));
  }

  /** error 与 failure 是两回事：前者是用例抛异常，后者是断言失败。 */
  @Test
  void distinguishesErrorFromFailure(@TempDir File dir) throws Exception {
    write(new File(dir, "TEST-a.xml"), suite(
        "  <testcase classname=\"com.A\" name=\"boom\">\n"
            + "    <error message=\"NPE\">java.lang.NullPointerException</error>\n"
            + "  </testcase>\n", 1, 0));

    JUnitReportParser.Failure failure = JUnitReportParser.parseDirectory(dir).failures.get(0);
    assertTrue(failure.isError);
    assertEquals("NPE", failure.message);
  }

  @Test
  void skippedTestsAreCountedSeparately(@TempDir File dir) throws Exception {
    write(new File(dir, "TEST-a.xml"), suite(
        "  <testcase classname=\"com.A\" name=\"a\"/>\n"
            + "  <testcase classname=\"com.A\" name=\"b\">\n"
            + "    <skipped/>\n"
            + "  </testcase>\n", 2, 1));

    JUnitReportParser.Summary summary = JUnitReportParser.parseDirectory(dir);
    assertEquals(2, summary.tests);
    assertEquals(1, summary.skipped);
    assertEquals(1, summary.passed());
    assertEquals(0, summary.failed());
    assertTrue(summary.isSuccess());
  }

  @Test
  void parsesTestsuitesWrapper(@TempDir File dir) throws Exception {
    write(new File(dir, "TEST-a.xml"),
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<testsuites>\n"
            + "  <testsuite name=\"outer\" tests=\"2\">\n"
            + "    <testcase classname=\"com.A\" name=\"a\"/>\n"
            + "    <testcase classname=\"com.A\" name=\"b\">\n"
            + "      <failure message=\"x\">stack</failure>\n"
            + "    </testcase>\n"
            + "  </testsuite>\n"
            + "</testsuites>\n");

    JUnitReportParser.Summary summary = JUnitReportParser.parseDirectory(dir);
    assertEquals(2, summary.tests);
    assertEquals(1, summary.failed());
  }

  /**
   * 嵌套 testsuite（Gradle 分片时会出现）里，failure 只能算它所属 testcase 的——
   * 用 getElementsByTagName 直接取会把孙节点的也算进来，导致同一处失败被计两次。
   */
  @Test
  void failureIsAttachedToItsOwnTestcaseOnly(@TempDir File dir) throws Exception {
    write(new File(dir, "TEST-a.xml"),
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<testsuite name=\"outer\" tests=\"2\">\n"
            + "  <testcase classname=\"com.A\" name=\"passes\"/>\n"
            + "  <testcase classname=\"com.A\" name=\"fails\">\n"
            + "    <failure message=\"boom\">stack</failure>\n"
            + "  </testcase>\n"
            + "</testsuite>\n");

    JUnitReportParser.Summary summary = JUnitReportParser.parseDirectory(dir);
    assertEquals(1, summary.failed(), "只应记录一处失败");
    assertEquals("com.A.fails", summary.failures.get(0).display());
  }

  /* ───────────────────────── 容错 ───────────────────────── */

  @Test
  void brokenReportIsCountedNotFatal(@TempDir File dir) throws Exception {
    write(new File(dir, "TEST-broken.xml"), "<testsuite><testcase");
    write(new File(dir, "TEST-good.xml"), suite(
        "  <testcase classname=\"com.A\" name=\"ok\"/>\n", 1, 0));

    JUnitReportParser.Summary summary = JUnitReportParser.parseDirectory(dir);
    // 坏报告不该让整次查询失败——好报告的结果仍然有用
    assertEquals(1, summary.unreadableFiles);
    assertEquals(1, summary.tests);
    assertFalse(summary.isSuccess(), "有坏报告时不能报告「全部通过」");
  }

  @Test
  void missingDirectoryReturnsEmpty(@TempDir File dir) {
    JUnitReportParser.Summary summary =
        JUnitReportParser.parseDirectory(new File(dir, "nope"));
    assertEquals(0, summary.tests);
    assertEquals(0, summary.files);
    assertTrue(summary.isSuccess());
  }

  @Test
  void ignoresNonTestXmlFiles(@TempDir File dir) throws Exception {
    write(new File(dir, "not-a-report.xml"), suite("<testcase name=\"x\"/>", 1, 0));
    assertEquals(0, JUnitReportParser.parseDirectory(dir).files,
        "只应处理 TEST-*.xml");
  }

  @Test
  void summariesAddUp(@TempDir File dir) throws Exception {
    write(new File(dir, "a/TEST-1.xml"), suite(
        "  <testcase classname=\"com.A\" name=\"a\"/>\n", 1, 0));
    write(new File(dir, "b/TEST-2.xml"), suite(
        "  <testcase classname=\"com.B\" name=\"b\">\n  <failure message=\"x\">s</failure>\n  </testcase>\n",
        1, 0));

    JUnitReportParser.Summary total = new JUnitReportParser.Summary();
    total.add(JUnitReportParser.parseDirectory(new File(dir, "a")));
    total.add(JUnitReportParser.parseDirectory(new File(dir, "b")));
    assertEquals(2, total.tests);
    assertEquals(1, total.failed());
    assertEquals(1, total.passed());
  }

  /* ───────────────────────── 堆栈裁剪 ───────────────────────── */

  /** 完整堆栈里大部分是反射/框架帧，全塞进上下文只会挤掉业务代码帧。 */
  @Test
  void trimStackDropsFrameworkFrames() {
    String stack = "java.lang.AssertionError: boom\n"
        + "\tat java.base/java.lang.reflect.Method.invoke(Method.java:1)\n"
        + "\tat jdk.internal.reflect.NativeMethodAccessorImpl.invoke0(Native Method)\n"
        + "\tat org.junit.platform.engine.support.hierarchical.NodeTestTask.execute(NodeTestTask.java:1)\n"
        + "\tat com.example.MyTest.shouldWork(MyTest.java:42)\n"
        + "\tat com.example.Service.run(Service.java:7)\n";

    String trimmed = JUnitReportParser.trimStack(stack, 10);
    assertTrue(trimmed.contains("com.example.MyTest.shouldWork"), trimmed);
    assertTrue(trimmed.contains("com.example.Service.run"), trimmed);
    assertFalse(trimmed.contains("java.base/java.lang.reflect"), trimmed);
    assertFalse(trimmed.contains("org.junit.platform"), trimmed);
  }

  @Test
  void trimStackRespectsLineLimit() {
    StringBuilder stack = new StringBuilder();
    for (int i = 0; i < 40; i++) {
      stack.append("\tat com.example.Frame").append(i).append("(F.java:1)\n");
    }
    String trimmed = JUnitReportParser.trimStack(stack.toString(), 5);
    assertTrue(trimmed.contains("（堆栈已截断）"), trimmed);
    long frames = trimmed.lines().filter(l -> l.contains("com.example.Frame")).count();
    assertEquals(5, frames);
  }

  @Test
  void trimStackHandlesEmpty() {
    assertEquals("", JUnitReportParser.trimStack(null, 5));
    assertEquals("", JUnitReportParser.trimStack("", 5));
  }
}
