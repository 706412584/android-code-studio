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

package com.tom.rv2ide.ai.agent.prompt;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 提示词模板渲染：把 {@code {{NAME}}} 占位符替换为实际内容。
 *
 * <p><b>三个刻意的语义选择</b>：
 *
 * <ol>
 *   <li><b>单趟替换</b>。替换进去的文本里若含 {@code {{X}}}（例如用户代码里就有这种
 *       字符串），不会被二次展开。多趟替换会让提示词内容可被数据影响——这是注入风险，
 *       不只是意外。
 *   <li><b>未提供的占位符替换为空串</b>，而不是原样保留。用户删掉某段可选内容时，
 *       希望那一段整体消失；留下 {@code {{TODO_LIST}}} 会让模型看到无意义的字面量。
 *   <li><b>未知占位符同样替换为空串</b>，但可通过 {@link #unknownPlaceholders} 查出，
 *       供设置界面提示拼写错误。
 * </ol>
 *
 * <p>本类不引用 Android 类型，可单测。
 */
public final class PromptRenderer {

  /** 占位符语法：{@code {{NAME}}}，名字不含花括号。 */
  private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([^{}]*)\\}\\}");

  private PromptRenderer() {}

  /**
   * 渲染模板。
   *
   * @param template 模板文本；null 视作空
   * @param values 占位符名 → 替换内容；值为 null 视作空串
   * @return 渲染结果；模板为空时返回空串
   */
  public static String render(String template, Map<String, String> values) {
    if (template == null || template.isEmpty()) {
      return "";
    }
    Matcher matcher = PLACEHOLDER.matcher(template);
    // 必须是 StringBuffer 而非 StringBuilder：Matcher.appendReplacement(StringBuilder, ...)
    // 是 Java 9 才加的 API，Android 上直到 API 34 才有。用 StringBuilder 的后果是
    // 编译期（JDK 17）不报错、JVM 单测也全绿，但真机一执行就抛
    // NoSuchMethodError: No virtual method appendReplacement(Ljava/lang/StringBuilder;...)
    // ——Android 10 实测必崩。
    StringBuffer sb = new StringBuffer(template.length());
    while (matcher.find()) {
      String name = matcher.group(1) == null ? "" : matcher.group(1).trim();
      String replacement = values == null ? null : values.get(name);
      matcher.appendReplacement(
          sb, Matcher.quoteReplacement(replacement == null ? "" : replacement));
    }
    matcher.appendTail(sb);
    return sb.toString();
  }

  /**
   * 渲染并整理空白。
   *
   * <p>占位符替换为空串后会留下空行与行尾空格（例如某个可选段落的标题还在、内容没了）。
   * 这里做一次整理：去掉行尾空白、把连续空行压成一个。
   *
   * <p><b>不</b>整段删除「标题 + 空内容」：判断哪一行是标题、哪一行属于该段落在通用文本里
   * 做不到可靠。用户若在意，应当把整个段落（含标题）一起放进模板并用占位符包住。
   */
  public static String renderAndTidy(String template, Map<String, String> values) {
    return tidy(render(template, values));
  }

  /** 整理空白：去行尾空白、连续空行压成一个、去首尾空行。 */
  public static String tidy(String text) {
    if (text == null || text.isEmpty()) {
      return "";
    }
    String[] lines = text.split("\n", -1);
    StringBuilder sb = new StringBuilder(text.length());
    boolean lastWasBlank = true; // 首部的空行会被跳过
    for (String raw : lines) {
      String line = stripTrailing(raw);
      if (line.isEmpty()) {
        if (lastWasBlank) {
          continue;
        }
        lastWasBlank = true;
        sb.append('\n');
        continue;
      }
      lastWasBlank = false;
      sb.append(line).append('\n');
    }
    // 去掉结尾多余换行
    int end = sb.length();
    while (end > 0 && (sb.charAt(end - 1) == '\n' || sb.charAt(end - 1) == ' ')) {
      end--;
    }
    return sb.substring(0, end);
  }

  private static String stripTrailing(String line) {
    int end = line.length();
    while (end > 0) {
      char c = line.charAt(end - 1);
      if (c == ' ' || c == '\t' || c == '\r') {
        end--;
      } else {
        break;
      }
    }
    return line.substring(0, end);
  }

  /**
   * 找出模板里出现但未被识别的占位符名。
   *
   * <p>用于设置界面的校验提示——用户把 {@code {{TODO_LIST}}} 拼成 {@code {{TODOLIST}}}
   * 时不会报错，只会静默变成空串，表现为「模型行为莫名其妙」。
   *
   * @return 未知占位符名，按出现顺序去重；无未知项时返回空列表
   */
  public static List<String> unknownPlaceholders(String template) {
    List<String> unknown = new ArrayList<>();
    if (template == null || template.isEmpty()) {
      return unknown;
    }
    Set<String> seen = new LinkedHashSet<>();
    Matcher matcher = PLACEHOLDER.matcher(template);
    while (matcher.find()) {
      String name = matcher.group(1) == null ? "" : matcher.group(1).trim();
      if (name.isEmpty() || PromptPlaceholders.isKnown(name)) {
        continue;
      }
      if (seen.add(name)) {
        unknown.add(name);
      }
    }
    return unknown;
  }

  /**
   * 找出模板里出现但本次渲染未提供值的**已知**占位符。
   *
   * <p>与 {@link #unknownPlaceholders} 的区别：这里指的是「名字对，但这次调用没给值」。
   * 多数情况下是正常的（可选段落），因此只作提示不作错误。
   */
  public static List<String> unprovidedPlaceholders(
      String template, Map<String, String> values) {
    List<String> missing = new ArrayList<>();
    if (template == null || template.isEmpty()) {
      return missing;
    }
    Set<String> seen = new LinkedHashSet<>();
    Matcher matcher = PLACEHOLDER.matcher(template);
    while (matcher.find()) {
      String name = matcher.group(1) == null ? "" : matcher.group(1).trim();
      if (name.isEmpty() || !PromptPlaceholders.isKnown(name)) {
        continue;
      }
      if (values != null && values.containsKey(name)) {
        continue;
      }
      if (seen.add(name)) {
        missing.add(name);
      }
    }
    return missing;
  }
}
