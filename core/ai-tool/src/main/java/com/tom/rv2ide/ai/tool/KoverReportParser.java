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

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * 解析 kover（JaCoCo 格式）的 XML 覆盖率报告，抽成可读的模块/类两级摘要。
 *
 * <p><b>为什么需要它</b>：本仓库有 1700+ 项单测，但覆盖率报告是 700KB 的 XML——直接丢给
 * 模型会吃掉整个上下文，且它真正需要的只有两件事：「哪个模块覆盖率不达标」与
 * 「具体哪个类还没被测到」。本类把 XML 压成这两条信息。
 *
 * <p><b>为什么解析而不是让模型读原始 XML</b>：报告动辄数十万行，{@code <line nr=... mi=...>}
 * 逐行记录会淹没真正的缺口。这里按类聚合（kover 在 {@code <class>} 下写了汇总
 * {@code <counter type="LINE">}），只保留「未覆盖行数 &gt; 0 的类」。
 *
 * <p><b>安全</b>：报告由本机 Gradle 生成，理论上可信，但 XML 解析器必须关闭外部实体
 * ——{@code DOCTYPE} 若指向本地文件（{@code file:///...}）会被读进内存并可能回显给模型，
 * 即典型的 XXE。这里禁用 DTD、外部实体与外部 schema，并限制为不访问网络。
 *
 * <p>纯 Java、零 Android 依赖，因此可在 JVM 上单测（见 {@code KoverReportParserTest}）。
 */
public final class KoverReportParser {

  /** 一个类的覆盖率：未覆盖/已覆盖行数与由此得出的百分比。 */
  public static final class ClassCoverage {
    /** 全限定类名（kover 用的是 {@code a/b/C} 斜杠形式，这里保留原样）。 */
    public final String className;
    public final int missedLines;
    public final int coveredLines;

    ClassCoverage(String className, int missedLines, int coveredLines) {
      this.className = className;
      this.missedLines = missedLines;
      this.coveredLines = coveredLines;
    }

    public int totalLines() {
      return missedLines + coveredLines;
    }

    /** 行覆盖百分比；总行为 0 时返回 100（无可覆盖代码，不构成缺口）。 */
    public double percent() {
      int total = totalLines();
      return total == 0 ? 100d : 100d * coveredLines / total;
    }
  }

  /** 一个模块（一个 kover 报告文件）的汇总。 */
  public static final class ModuleCoverage {
    /** 模块名（取自报告路径，如 {@code core/ai-tool}）。 */
    public final String modulePath;
    public final int missedLines;
    public final int coveredLines;
    /** 未完全覆盖的类，按未覆盖行数降序（缺口最大的排最前）。 */
    public final List<ClassCoverage> uncoveredClasses;

    ModuleCoverage(
        String modulePath,
        int missedLines,
        int coveredLines,
        List<ClassCoverage> uncoveredClasses) {
      this.modulePath = modulePath;
      this.missedLines = missedLines;
      this.coveredLines = coveredLines;
      this.uncoveredClasses = Collections.unmodifiableList(uncoveredClasses);
    }

    public int totalLines() {
      return missedLines + coveredLines;
    }

    public double percent() {
      int total = totalLines();
      return total == 0 ? 100d : 100d * coveredLines / total;
    }
  }

  private KoverReportParser() {}

  /**
   * 解析一个 kover XML 报告文件。
   *
   * @param report kover 生成的 {@code report.xml}
   * @param modulePath 报告所属模块的展示名（如 {@code core/ai-tool}）
   * @return 模块覆盖率；解析失败抛 {@link IOException}（调用方据此如实报告，不静默返回空）
   */
  public static ModuleCoverage parse(File report, String modulePath) throws IOException {
    byte[] bytes;
    try {
      bytes = Files.readAllBytes(report.toPath());
    } catch (IOException e) {
      throw new IOException("读取覆盖率报告失败：" + report.getAbsolutePath(), e);
    }
    return parse(new ByteArrayInputStream(bytes), modulePath);
  }

  /** 解析入口（便于单测直接喂字节）。 */
  public static ModuleCoverage parse(InputStream input, String modulePath) throws IOException {
    Document doc;
    try {
      doc = newSecureBuilder().parse(input);
    } catch (Exception e) {
      throw new IOException("覆盖率报告不是合法 XML：" + e.getMessage(), e);
    }
    Element root = doc.getDocumentElement();
    if (root == null || !"report".equals(root.getTagName())) {
      throw new IOException("覆盖率报告根元素不是 <report>（可能是别的格式）");
    }

    // 汇总行数优先取根上的计数器（kover 在 </report> 前写了全局 LINE 计数），
    // 避免逐类累加时因嵌套重复计数。
    int missed = counter(root, "LINE", "missed");
    int covered = counter(root, "LINE", "covered");

    List<ClassCoverage> classes = new ArrayList<>();
    NodeList packageNodes = root.getElementsByTagName("package");
    for (int i = 0; i < packageNodes.getLength(); i++) {
      Element pkg = (Element) packageNodes.item(i);
      NodeList classNodes = pkg.getElementsByTagName("class");
      for (int j = 0; j < classNodes.getLength(); j++) {
        Element cls = (Element) classNodes.item(j);
        String name = cls.getAttribute("name");
        if (name.isEmpty()) {
          continue;
        }
        int cm = counter(cls, "LINE", "missed");
        int cc = counter(cls, "LINE", "covered");
        if (cm > 0) {
          classes.add(new ClassCoverage(name, cm, cc));
        }
      }
    }
    classes.sort(
        (a, b) -> {
          int byMissed = Integer.compare(b.missedLines, a.missedLines);
          return byMissed != 0 ? byMissed : a.className.compareTo(b.className);
        });
    return new ModuleCoverage(modulePath, missed, covered, classes);
  }

  /**
   * 读取元素下直接子 {@code <counter>} 中指定类型与字段的值。
   *
   * <p>只取直接子元素：kover 在每个层级（method/class/package/report）都写了计数器，
   * 用 {@code getElementsByTagName} 会连子孙的计数一起抓来重复累加——那样 classes 层级
   * 的数字会虚高数倍。
   */
  private static int counter(Element parent, String type, String field) {
    NodeList children = parent.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      Node node = children.item(i);
      if (node.getNodeType() != Node.ELEMENT_NODE) {
        continue;
      }
      Element el = (Element) node;
      if (!"counter".equals(el.getTagName()) || !type.equals(el.getAttribute("type"))) {
        continue;
      }
      String raw = el.getAttribute(field);
      if (raw.isEmpty()) {
        return 0;
      }
      try {
        return Integer.parseInt(raw);
      } catch (NumberFormatException e) {
        return 0;
      }
    }
    return 0;
  }

  /**
   * 构造禁用一切外部访问的 XML 解析器。
   *
   * <p>逐项关闭 DTD / 外部实体 / 外部 schema，并置 {@code XMLConstants.FEATURE_SECURE_PROCESSING}
   * ——该特性同时禁止「用实体引用构造的指数级展开」（billion laughs）。报告是本机产物，
   * 但这些开关零成本，而一旦报告被替换成恶意 XML，回显给模型的内容就不可控。
   */
  private static DocumentBuilder newSecureBuilder() throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
    factory.setXIncludeAware(false);
    factory.setExpandEntityReferences(false);
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    factory.setNamespaceAware(false);
    return factory.newDocumentBuilder();
  }
}
