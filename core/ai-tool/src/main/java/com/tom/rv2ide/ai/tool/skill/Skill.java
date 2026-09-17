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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 一个 skill：带元信息的说明文档，按需加载。
 *
 * <p><b>为什么需要 skill 而不是把说明写进系统提示词</b>：系统提示词的每一个字都会进入
 * **每一次**请求。项目里可能有十几条「这个模块的约定」，全部写进提示词会永久占用上下文，
 * 而其中绝大多数与当前任务无关。
 *
 * <p>skill 采用**渐进披露**：提示词里只放名字与一句话说明（每条约 20 token），
 * 模型判断需要时再用 {@code skill} 工具读取完整内容。这样几十个 skill 的常驻成本
 * 只有几百 token，而需要时能拿到完整指导。
 *
 * <p>格式是 Markdown + frontmatter：
 *
 * <pre>
 * ---
 * name: api-conventions
 * description: 本项目 REST 接口的命名与错误码约定
 * ---
 * （正文：完整的约定说明）
 * </pre>
 *
 * <p>本类不引用 Android 类型，可单测。
 */
public final class Skill {

  /** 名字长度上限。 */
  public static final int MAX_NAME_CHARS = 60;

  /** 说明长度上限。提示词里每条只占一行。 */
  public static final int MAX_DESCRIPTION_CHARS = 200;

  private final String name;
  private final String description;
  private final String body;
  private final String source;

  public Skill(String name, String description, String body, String source) {
    this.name = truncate(name == null ? "" : name.trim(), MAX_NAME_CHARS);
    this.description = truncate(description == null ? "" : description.trim(), MAX_DESCRIPTION_CHARS);
    this.body = body == null ? "" : body.trim();
    this.source = source == null ? "" : source;
  }

  /** 标识名，模型用它来加载。 */
  public String getName() {
    return name;
  }

  /** 一句话说明，展示在提示词里让模型知道何时该加载。 */
  public String getDescription() {
    return description;
  }

  /** 完整正文，按需加载时才进入上下文。 */
  public String getBody() {
    return body;
  }

  /** 来源（文件路径），用于诊断与展示。 */
  public String getSource() {
    return source;
  }

  /** 是否可用：名字与正文都不能为空。 */
  public boolean isUsable() {
    return !name.isEmpty() && !body.isEmpty();
  }

  /** 归一化名字，用于查重与查找。 */
  public String normalizedName() {
    return name.toLowerCase(Locale.ROOT);
  }

  /** 提示词里的一行：{@code - name: description}。 */
  public String toPromptLine() {
    if (description.isEmpty()) {
      return "- " + name;
    }
    return "- " + name + ": " + description;
  }

  private static String truncate(String value, int max) {
    return value.length() <= max ? value : value.substring(0, max);
  }

  /** 名字是否合法（能作为标识被模型引用）。 */
  public static boolean isValidName(String name) {
    if (name == null || name.trim().isEmpty()) {
      return false;
    }
    for (int i = 0; i < name.length(); i++) {
      char c = name.charAt(i);
      if (!Character.isLetterOrDigit(c) && c != '-' && c != '_') {
        return false;
      }
    }
    return true;
  }

  /** 供 UI 展示的名称列表。 */
  public static List<String> namesOf(List<Skill> skills) {
    List<String> names = new ArrayList<>();
    if (skills == null) {
      return names;
    }
    for (Skill skill : skills) {
      names.add(skill.getName());
    }
    return Collections.unmodifiableList(names);
  }
}
