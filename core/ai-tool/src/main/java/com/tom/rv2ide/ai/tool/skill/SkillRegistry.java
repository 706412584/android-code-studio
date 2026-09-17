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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 从目录里加载 skill。
 *
 * <p><b>目录约定</b>：扫描 {@code <root>/*.md} 与 {@code <root>/*&#47;SKILL.md}。
 * 两种都支持是因为两种写法都常见——单文件适合短说明，子目录适合带附件的复杂 skill。
 *
 * <p><b>重名时先加载的胜出</b>（按路径排序）：顺序稳定，用户能预期哪个生效；
 * 而「后加载覆盖」会让结果依赖文件系统的遍历顺序，不可预期。
 *
 * <p>读取失败一律跳过该文件：一个坏文件不该让全部 skill 不可用。
 */
public final class SkillRegistry {

  /** 单个 skill 正文的长度上限。超出部分截断。 */
  public static final int MAX_BODY_CHARS = 20000;

  /** 最多加载多少个 skill。每个都会在提示词里占一行。 */
  public static final int MAX_SKILLS = 50;

  private static final String SKILL_FILE_NAME = "SKILL.md";

  private final Map<String, Skill> skills = new LinkedHashMap<>();

  private SkillRegistry() {}

  /**
   * 从目录加载。
   *
   * @param root 根目录；不存在或不是目录时返回空注册表
   */
  public static SkillRegistry load(File root) {
    SkillRegistry registry = new SkillRegistry();
    if (root == null || !root.isDirectory()) {
      return registry;
    }
    List<File> candidates = collectCandidates(root);
    // 按路径排序：让重名时的胜出者稳定可预期。
    Collections.sort(candidates);

    for (File file : candidates) {
      if (registry.skills.size() >= MAX_SKILLS) {
        break;
      }
      registry.addFile(file);
    }
    return registry;
  }

  /** 空注册表。 */
  public static SkillRegistry empty() {
    return new SkillRegistry();
  }

  /** 收集候选文件：直接子文件 {@code *.md} 与子目录里的 {@code SKILL.md}。 */
  private static List<File> collectCandidates(File root) {
    List<File> result = new ArrayList<>();
    File[] children = root.listFiles();
    if (children == null) {
      return result;
    }
    for (File child : children) {
      if (child.isFile() && child.getName().toLowerCase(Locale.ROOT).endsWith(".md")) {
        result.add(child);
      } else if (child.isDirectory()) {
        File skillFile = new File(child, SKILL_FILE_NAME);
        if (skillFile.isFile()) {
          result.add(skillFile);
        }
      }
    }
    return result;
  }

  private void addFile(File file) {
    String content;
    try {
      content = readAll(file);
    } catch (IOException e) {
      // 读不出来就跳过：一个坏文件不该让全部 skill 不可用。
      return;
    }

    // 子目录形式（SKILL.md）用目录名当名字：文件名本身没有信息量，
    // 而 skill 的标识通常是目录名。把它作为「降级名字」传给解析器，
    // 使 frontmatter 里的 name 仍然优先。
    String fallbackName = file.getName();
    if (SKILL_FILE_NAME.equalsIgnoreCase(file.getName()) && file.getParentFile() != null) {
      fallbackName = file.getParentFile().getName();
    }

    Skill skill = SkillParser.parse(fallbackName, content, file.getPath());
    if (skill == null || !skill.isUsable() || !Skill.isValidName(skill.getName())) {
      return;
    }

    // 重名时先加载的胜出（见类注释）。
    if (!skills.containsKey(skill.normalizedName())) {
      skills.put(skill.normalizedName(), skill);
    }
  }

  /** 全部 skill，按加载顺序。 */
  public List<Skill> all() {
    return new ArrayList<>(skills.values());
  }

  public int size() {
    return skills.size();
  }

  public boolean isEmpty() {
    return skills.isEmpty();
  }

  /** 按名字查找；大小写不敏感。 */
  public Skill find(String name) {
    if (name == null) {
      return null;
    }
    return skills.get(name.trim().toLowerCase(Locale.ROOT));
  }

  /**
   * 渲染为注入提示词的清单。
   *
   * <p>只有名字与一句话说明——这正是渐进披露的关键：几十个 skill 的常驻成本只有
   * 几百 token，完整内容在模型调用 {@code skill} 工具时才加载。
   *
   * <p>返回空串表示无 skill，调用方不应把它拼进提示词。
   */
  public String renderForPrompt() {
    if (skills.isEmpty()) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    for (Skill skill : skills.values()) {
      sb.append(skill.toPromptLine()).append('\n');
    }
    return sb.toString().trim();
  }

  /** 正文超限时截断并注明。 */
  static String truncateBody(String body) {
    if (body == null) {
      return "";
    }
    if (body.length() <= MAX_BODY_CHARS) {
      return body;
    }
    return body.substring(0, MAX_BODY_CHARS) + "\n\n…（内容过长，已截断）";
  }

  private static String readAll(File file) throws IOException {
    try (FileInputStream input = new FileInputStream(file)) {
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      int read;
      while ((read = input.read(buffer)) != -1) {
        output.write(buffer, 0, read);
      }
      return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }
  }
}
