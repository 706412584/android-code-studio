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

  /**
   * 已加载的 skill，按加载顺序。
   *
   * <p><b>为什么是 volatile 的不可变快照而不是 final 的 LinkedHashMap</b>：注册表会被
   * 两个线程同时访问——UI 线程（设置界面列出 skill）与 agent 线程（注入提示词、执行
   * {@code skill} 工具）。而 {@link #reload()} 会整体替换内容（AI 写完新 skill 后要立刻
   * 可见）。用可变 Map 就需要在每次读时加锁，且遍历中改会抛
   * {@code ConcurrentModificationException}；换成「构建新快照 + 原子换引用」后，
   * 读侧完全无锁，也不会看到半更新的状态。
   */
  private volatile Map<String, Skill> skills;

  /** 加载来源目录；用于 {@link #reload()}。为 null 表示注册表没有来源（如 {@link #empty()}）。 */
  private final File root;

  private SkillRegistry(File root, Map<String, Skill> skills) {
    this.root = root;
    this.skills = skills;
  }

  /**
   * 从目录加载。
   *
   * @param root 根目录；不存在或不是目录时返回空注册表
   */
  public static SkillRegistry load(File root) {
    return new SkillRegistry(root, scan(root));
  }

  /** 空注册表。 */
  public static SkillRegistry empty() {
    return new SkillRegistry(null, Collections.<String, Skill>emptyMap());
  }

  /**
   * 重新扫描来源目录，原子替换全部内容。
   *
   * <p><b>为什么需要它</b>：注册表原先只在构造时加载一次。AI 通过 skill 写入工具新增
   * 或修改 skill 后，本次运行内看不到变化——要等重启应用。重新加载让「AI 记录一条教训」
   * 能立刻在后续轮次里生效，闭环才成立。
   *
   * <p>没有来源目录（{@link #empty()} 造的）时是空操作。
   *
   * @return 重新加载后的 skill 数量
   */
  public int reload() {
    if (root == null) {
      return 0;
    }
    skills = scan(root);
    return skills.size();
  }

  /** 扫描目录构建快照。纯函数，不触碰实例状态，因此可在替换前先构建完成。 */
  private static Map<String, Skill> scan(File root) {
    Map<String, Skill> loaded = new LinkedHashMap<>();
    if (root == null || !root.isDirectory()) {
      return Collections.unmodifiableMap(loaded);
    }
    List<File> candidates = collectCandidates(root);
    // 按路径排序：让重名时的胜出者稳定可预期。
    Collections.sort(candidates);

    for (File file : candidates) {
      if (loaded.size() >= MAX_SKILLS) {
        break;
      }
      addFile(loaded, file);
    }
    return Collections.unmodifiableMap(loaded);
  }

  /** 加载来源目录；无来源时为 null。 */
  public File getRoot() {
    return root;
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

  /** 解析一个文件并放进目标 map。静态方法：构建快照时不触碰实例状态。 */
  private static void addFile(Map<String, Skill> target, File file) {
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
    if (!target.containsKey(skill.normalizedName())) {
      target.put(skill.normalizedName(), skill);
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
    // 先取本地引用：遍历期间若发生 reload()，用局部变量仍指向同一个快照，
    // 不会出现「遍历到一半换了 map」的情况。
    Map<String, Skill> snapshot = skills;
    if (snapshot.isEmpty()) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    for (Skill skill : snapshot.values()) {
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
