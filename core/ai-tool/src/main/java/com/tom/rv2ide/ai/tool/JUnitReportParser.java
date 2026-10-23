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

package com.tom.rv2ide.ai.tool;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * 解析 Gradle 产出的 JUnit XML 测试报告。
 *
 * <p><b>为什么需要它</b>：{@code gradle_build} 只能告诉你「BUILD FAILED」——它的
 * {@code TaskExecutionResult} 只带 Failure 枚举，不含任何错误文本。模型改完代码要
 * 验证时，只能再去 {@code build/test-results/} 里逐个读 XML。这份解析器把「跑了多少、
 * 挂了哪几个、断言说了什么」直接变成一段可读文本。
 *
 * <p><b>为什么自己写 DOM 解析而不是引入 JUnit 的 launcher</b>：报告是磁盘上的静态
 * 文件，格式由 Gradle 固定；引入整套 JUnit Platform 只为了读几个字段，会把测试运行时
 * 拖进 AI 工具进程（体积与初始化成本都不划算）。DOM 对几百 KB 的报告足够快。
 *
 * <p><b>容错</b>：单个 XML 坏了（进程被杀时报告可能写一半）只跳过那一个文件并计数，
 * 不能让整次查询失败——其余模块的结果仍然有参考价值。
 */
public final class JUnitReportParser {

  /** 一条失败的用例。 */
  public static final class Failure {
    public final String className;
    public final String testName;
    /** failure 或 error 的 message 属性；可能为空。 */
    public final String message;
    /** 堆栈正文（可能很长，由调用方截断）。 */
    public final String stack;
    /** true = error（用例抛异常），false = failure（断言失败）。 */
    public final boolean isError;

    Failure(String className, String testName, String message, String stack, boolean isError) {
      this.className = className == null ? "" : className;
      this.testName = testName == null ? "" : testName;
      this.message = message == null ? "" : message;
      this.stack = stack == null ? "" : stack;
      this.isError = isError;
    }

    /** 类名.方法名（参数化测试的方法名里已含 [1] 这类后缀）。 */
    public String display() {
      return className.isEmpty() ? testName : className + "." + testName;
    }
  }

  /** 一个模块（或一个报告目录）的汇总。 */
  public static final class Summary {
    public final List<Failure> failures = new ArrayList<>();
    public int tests;
    public int skipped;
    /** 报告 XML 解析失败的个数（报告文件损坏）。 */
    public int unreadableFiles;
    /** 实际读到的报告文件数。 */
    public int files;

    public int failed() {
      return failures.size();
    }

    public int passed() {
      return Math.max(0, tests - failed() - skipped);
    }

    public boolean isSuccess() {
      return failed() == 0 && unreadableFiles == 0;
    }

    /** 合并另一个汇总（跨模块统计）。 */
    public void add(Summary other) {
      if (other == null) {
        return;
      }
      tests += other.tests;
      skipped += other.skipped;
      unreadableFiles += other.unreadableFiles;
      files += other.files;
      failures.addAll(other.failures);
    }
  }

  private JUnitReportParser() {}

  /**
   * 解析一个报告目录下的全部 {@code TEST-*.xml}。
   *
   * @param dir {@code build/test-results/<task>} 目录；不存在时返回空汇总
   */
  public static Summary parseDirectory(File dir) {
    Summary summary = new Summary();
    if (dir == null || !dir.isDirectory()) {
      return summary;
    }
    File[] files = dir.listFiles((d, name) -> name.startsWith("TEST-") && name.endsWith(".xml"));
    if (files == null) {
      return summary;
    }
    for (File file : files) {
      summary.files++;
      try {
        parseFile(file, summary);
      } catch (Exception e) {
        // 单个报告坏了（写一半/编码异常）不该让整次查询失败
        summary.unreadableFiles++;
      }
    }
    // 按类名排序：同一模块里失败用例的排列稳定，便于跨次对比
    Collections.sort(summary.failures,
        (a, b) -> a.display().compareTo(b.display()));
    return summary;
  }

  private static void parseFile(File file, Summary summary) throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    // 报告是本地文件，不需要 DTD/外部实体；显式关掉可以避免 XXE 与被外部 DTD 拖慢
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setExpandEntityReferences(false);
    DocumentBuilder builder = factory.newDocumentBuilder();
    // 报告写一半时会有解析错误输出到 stderr，这里静音——由调用方按 unreadableFiles 计数
    builder.setErrorHandler(null);
    Document doc = builder.parse(file);
    Element root = doc.getDocumentElement();
    if (root == null) {
      return;
    }
    // 顶层可能是 <testsuite> 或 <testsuites>（Gradle 默认前者）
    if ("testsuites".equals(root.getTagName())) {
      NodeList suites = root.getElementsByTagName("testsuite");
      for (int i = 0; i < suites.getLength(); i++) {
        Node node = suites.item(i);
        if (node instanceof Element) {
          readSuite((Element) node, summary);
        }
      }
    } else {
      readSuite(root, summary);
    }
  }

  private static void readSuite(Element suite, Summary summary) {
    // 优先用 suite 的计数属性：它们与 testcase 子节点可能不一致（分片报告），
    // 但总数以 suite 声明为准更接近 Gradle 的统计口径
    summary.tests += intAttr(suite, "tests");
    summary.skipped += intAttr(suite, "skipped");

    NodeList cases = suite.getElementsByTagName("testcase");
    for (int i = 0; i < cases.getLength(); i++) {
      Node node = cases.item(i);
      if (!(node instanceof Element)) {
        continue;
      }
      Element testCase = (Element) node;
      Element failure = firstChild(testCase, "failure");
      Element error = firstChild(testCase, "error");
      if (failure == null && error == null) {
        continue;
      }
      Element detail = failure != null ? failure : error;
      summary.failures.add(new Failure(
          testCase.getAttribute("classname"),
          testCase.getAttribute("name"),
          detail.getAttribute("message"),
          detail.getTextContent(),
          failure == null));
    }
  }

  /** 直接子节点里的第一个指定标签（不能用 getElementsByTagName，它会连孙节点一起返回）。 */
  private static Element firstChild(Element parent, String tag) {
    NodeList children = parent.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      Node node = children.item(i);
      if (node instanceof Element && tag.equals(((Element) node).getTagName())) {
        return (Element) node;
      }
    }
    return null;
  }

  private static int intAttr(Element element, String name) {
    try {
      String value = element.getAttribute(name);
      return value == null || value.isEmpty() ? 0 : Integer.parseInt(value.trim());
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  /**
   * 截取堆栈里有信息量的部分：跳过 JUnit/Gradle 的反射帧，只保留前若干行。
   *
   * <p>完整堆栈常有 60+ 行，其中大部分是 {@code java.base/jdk.internal.reflect...}、
   * {@code org.junit.platform...}。全塞进上下文只会挤掉真正要看的业务代码帧。
   */
  public static String trimStack(String stack, int maxLines) {
    if (stack == null || stack.isEmpty()) {
      return "";
    }
    String[] lines = stack.split("\n");
    StringBuilder out = new StringBuilder();
    int kept = 0;
    for (String line : lines) {
      String trimmed = line.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      // 反射/框架帧对定位业务代码没有帮助，且它们总是在最前面
      if (trimmed.startsWith("at java.")
          || trimmed.startsWith("at jdk.")
          || trimmed.startsWith("at org.junit.")
          || trimmed.startsWith("at org.gradle.")
          || trimmed.startsWith("at worker.org.gradle.")) {
        continue;
      }
      out.append("    ").append(trimmed).append('\n');
      if (++kept >= maxLines) {
        out.append("    …（堆栈已截断）\n");
        break;
      }
    }
    return out.toString();
  }
}
