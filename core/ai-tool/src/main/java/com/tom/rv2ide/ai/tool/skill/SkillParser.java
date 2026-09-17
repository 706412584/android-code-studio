/*
 * This file is part of AndroidCodeStudio.
 *
 * Adapted from LineCode Pro (https://github.com/LangLang03/LineCodePro),
 * licensed under the GNU General Public License v3.0 or later.
 * Modifications for AndroidCodeStudio are licensed under the same terms.
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

package com.tom.rv2ide.ai.tool.skill;

import java.util.Locale;

/**
 * 解析 skill 文件的 frontmatter。
 *
 * <p>格式与静态站点生成器一致：
 *
 * <pre>
 * ---
 * name: api-conventions
 * description: 本项目 REST 接口的命名与错误码约定
 * ---
 * 正文……
 * </pre>
 *
 * <p><b>手写解析而非引入 YAML 库</b>：只需要 {@code key: value} 两种标量，
 * 而 YAML 的完整语法（锚点、多行折叠、类型推断）远超需求，引入依赖只为解析两行不值。
 *
 * <p><b>没有 frontmatter 时的降级</b>：用文件名当名字、正文首行当说明。用户很可能
 * 直接写一个 Markdown 文件就丢进来，报错拒绝不如尽力解释。
 *
 * <p>本类不引用 Android 类型，可单测。
 */
public final class SkillParser {

  /** frontmatter 分隔符。 */
  private static final String DELIMITER = "---";

  private SkillParser() {}

  /**
   * 解析一个 skill。
   *
   * @param fileName 文件名（不含目录），用于降级取名字
   * @param content 文件内容
   * @param source 来源路径，用于诊断
   * @return 解析结果；内容为空时返回 null
   */
  public static Skill parse(String fileName, String content, String source) {
    if (content == null || content.trim().isEmpty()) {
      return null;
    }

    String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
    Frontmatter frontmatter = extractFrontmatter(normalized);

    String name = frontmatter.get("name");
    String description = frontmatter.get("description");
    String body = frontmatter.body;

    if (name.isEmpty()) {
      // 降级：用文件名。用户很可能直接写一个 Markdown 就丢进来。
      name = nameFromFile(fileName);
    }
    if (description.isEmpty()) {
      // 降级：正文首行。比留空好——空说明让模型无从判断该不该加载。
      description = firstMeaningfulLine(body);
    }

    return new Skill(name, description, body, source);
  }

  /** 从文件名取名字（去掉扩展名）。 */
  static String nameFromFile(String fileName) {
    if (fileName == null || fileName.trim().isEmpty()) {
      return "";
    }
    String base = fileName.trim();
    int dot = base.lastIndexOf('.');
    if (dot > 0) {
      base = base.substring(0, dot);
    }
    // 常见约定：SKILL.md 放在以 skill 名命名的目录里，此时文件名本身没有信息量。
    if ("skill".equalsIgnoreCase(base) || "readme".equalsIgnoreCase(base) || "index".equalsIgnoreCase(base)) {
      return "";
    }
    return base;
  }

  /** 正文里第一行有内容的文本，去掉 Markdown 标题标记。 */
  static String firstMeaningfulLine(String body) {
    if (body == null) {
      return "";
    }
    for (String raw : body.split("\n")) {
      String line = raw.trim();
      if (line.isEmpty()) {
        continue;
      }
      // 去掉 # 标题标记与列表标记，说明里不需要它们
      while (line.startsWith("#")) {
        line = line.substring(1).trim();
      }
      while (line.startsWith("-") || line.startsWith("*")) {
        line = line.substring(1).trim();
      }
      if (!line.isEmpty()) {
        return line;
      }
    }
    return "";
  }

  /** frontmatter 的解析结果。 */
  private static final class Frontmatter {
    private final java.util.Map<String, String> values = new java.util.HashMap<>();
    private final String body;

    Frontmatter(java.util.Map<String, String> values, String body) {
      this.values.putAll(values);
      this.body = body;
    }

    String get(String key) {
      String value = values.get(key.toLowerCase(Locale.ROOT));
      return value == null ? "" : value;
    }
  }

  /**
   * 提取 frontmatter。
   *
   * <p>只认**文件开头**的 {@code ---} 块。正文中间出现的 {@code ---}（Markdown 的
   * 分隔线）不应当被当成 frontmatter 边界——否则一个含分隔线的普通文档会被截断。
   */
  private static Frontmatter extractFrontmatter(String content) {
    java.util.Map<String, String> values = new java.util.HashMap<>();

    String trimmedLeading = stripLeadingBlankLines(content);
    if (!trimmedLeading.startsWith(DELIMITER)) {
      return new Frontmatter(values, content);
    }

    // 找到第二个 --- 行
    int firstLineEnd = trimmedLeading.indexOf('\n');
    if (firstLineEnd < 0) {
      // 只有一行 "---"，不是 frontmatter
      return new Frontmatter(values, content);
    }

    int searchFrom = firstLineEnd + 1;
    int closingStart = -1;
    int lineStart = searchFrom;
    while (lineStart <= trimmedLeading.length()) {
      int lineEnd = trimmedLeading.indexOf('\n', lineStart);
      String line =
          lineEnd < 0
              ? trimmedLeading.substring(lineStart)
              : trimmedLeading.substring(lineStart, lineEnd);
      if (line.trim().equals(DELIMITER)) {
        closingStart = lineStart;
        break;
      }
      if (lineEnd < 0) {
        break;
      }
      lineStart = lineEnd + 1;
    }

    if (closingStart < 0) {
      // 没有闭合分隔符 → 不是 frontmatter，整份内容当正文
      return new Frontmatter(values, content);
    }

    String header = trimmedLeading.substring(firstLineEnd + 1, closingStart);
    for (String rawLine : header.split("\n")) {
      String line = rawLine.trim();
      if (line.isEmpty() || line.startsWith("#")) {
        continue;
      }
      int colon = line.indexOf(':');
      if (colon <= 0) {
        continue;
      }
      String key = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
      String value = line.substring(colon + 1).trim();
      value = stripQuotes(value);
      if (!key.isEmpty()) {
        values.put(key, value);
      }
    }

    int closingLineEnd = trimmedLeading.indexOf('\n', closingStart);
    String body =
        closingLineEnd < 0 ? "" : trimmedLeading.substring(closingLineEnd + 1).trim();

    return new Frontmatter(values, body);
  }

  private static String stripLeadingBlankLines(String content) {
    int i = 0;
    while (i < content.length()) {
      char c = content.charAt(i);
      if (c == '\n' || c == ' ' || c == '\t') {
        i++;
        continue;
      }
      break;
    }
    return content.substring(i);
  }

  /** 去掉成对的引号。用户可能写 {@code name: "foo"}。 */
  private static String stripQuotes(String value) {
    if (value.length() >= 2) {
      char first = value.charAt(0);
      char last = value.charAt(value.length() - 1);
      if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
        return value.substring(1, value.length() - 1).trim();
      }
    }
    return value;
  }
}
