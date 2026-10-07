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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.ai.agent;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 从项目根目录加载项目规则文件，供系统提示词注入。
 *
 * <p><b>为什么需要它</b>：项目约定（模块划分、命名风格、构建命令、禁区）此前只能靠用户
 * 每次对话重复交代，或写进全局提示词模板——前者会忘，后者会把 A 项目的规矩带进 B 项目。
 * 规则文件是「跟着仓库走」的载体：换项目即换规则，且能提交进版本库让整个团队共享。
 *
 * <p><b>文件约定（对齐参考项目 cc-haha）</b>：优先 {@code CLAUDE.md}，不存在时回退
 * {@code AGENTS.md}。两者只取其一而不是叠加，理由是同名文件往往互为副本（用户把
 * CLAUDE.md 复制成 AGENTS.md 以兼容别的工具），叠加会把同一段规则注入两遍——既浪费
 * 上下文，又会让模型以为那是两条独立要求。cc-haha 的 {@code canUseAgentsFallback}
 * 是同一取舍。
 *
 * <p><b>为什么放在 core/ai-agent 而不是 app 层</b>：本类不引用任何 Android 类型，
 * 因此可以在 JVM 上直接单测——规则文件的读取与截断语义（回退、空文件、超长、编码）
 * 都是容易写错又难以在设备上复现的逻辑，值得有测试兜着。
 */
public final class ProjectRulesLoader {

  /** 主规则文件名。 */
  public static final String CLAUDE_MD = "CLAUDE.md";

  /** 回退规则文件名。 */
  public static final String AGENTS_MD = "AGENTS.md";

  /**
   * 单个规则文件的长度上限（字符）。
   *
   * <p>取值与 cc-haha 的 {@code MAX_MEMORY_CHARACTER_COUNT} 一致。规则是每轮都要常驻
   * 上下文的成本，超长文件会挤占真正的工作空间；而规则「写得越多越没人看」也适用于模型。
   * 超出部分截断并明确告知模型「后面还有内容被省略」，避免它以为自己看到了全文。
   */
  public static final int MAX_RULE_CHARS = 40000;

  private ProjectRulesLoader() {}

  /** 一条已加载的项目规则。 */
  public static final class ProjectRule {
    private final File file;
    private final String content;
    private final boolean truncated;

    ProjectRule(File file, String content, boolean truncated) {
      this.file = file;
      this.content = content;
      this.truncated = truncated;
    }

    /** 规则文件。 */
    public File file() {
      return file;
    }

    /** 文件名（{@code CLAUDE.md} 或 {@code AGENTS.md}）。 */
    public String fileName() {
      return file.getName();
    }

    /** 文件正文（已按 {@link #MAX_RULE_CHARS} 截断）。 */
    public String content() {
      return content;
    }

    /** 是否因超长被截断。 */
    public boolean isTruncated() {
      return truncated;
    }
  }

  /** {@code <root>/CLAUDE.md}。即使文件不存在也返回路径，供设置界面创建用。 */
  public static File claudeMd(File projectRoot) {
    return new File(projectRoot, CLAUDE_MD);
  }

  /** {@code <root>/AGENTS.md}。 */
  public static File agentsMd(File projectRoot) {
    return new File(projectRoot, AGENTS_MD);
  }

  /**
   * 加载项目规则。
   *
   * <p>读取失败一律当作「没有规则」而不是抛异常：规则文件是可选增强，它坏了不该让
   * 整个 agent 跑不起来。这与 {@code SkillRegistry} 对坏文件的处理一致。
   *
   * @param projectRoot 项目根目录；null 或非目录时返回空列表
   * @return 至多一条规则（CLAUDE.md 优先，否则 AGENTS.md）；都没有则空列表
   */
  public static List<ProjectRule> load(File projectRoot) {
    if (projectRoot == null || !projectRoot.isDirectory()) {
      return Collections.emptyList();
    }

    // 顺序即优先级：先 CLAUDE.md，命中就不再读 AGENTS.md（见类注释的「只取其一」）。
    for (String name : new String[] {CLAUDE_MD, AGENTS_MD}) {
      File file = new File(projectRoot, name);
      String raw = readIfPresent(file);
      if (raw == null) {
        continue;
      }
      String trimmed = raw.trim();
      if (trimmed.isEmpty()) {
        // 空文件视为「没有规则」并继续回退：用户新建了 CLAUDE.md 但还没写内容时，
        // 不该因此把已存在的 AGENTS.md 也一起屏蔽掉。
        continue;
      }
      boolean truncated = trimmed.length() > MAX_RULE_CHARS;
      String content = truncated ? trimmed.substring(0, MAX_RULE_CHARS) : trimmed;
      List<ProjectRule> result = new ArrayList<>(1);
      result.add(new ProjectRule(file, content, truncated));
      return result;
    }
    return Collections.emptyList();
  }

  /**
   * 渲染为可注入系统提示词的段落。
   *
   * @return 无规则时返回空串（调用方据此决定不注入，而不是注入一个空标题）
   */
  public static String renderForPrompt(List<ProjectRule> rules) {
    if (rules == null || rules.isEmpty()) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    sb.append("[ 项目规则 ]\n");
    sb.append("以下内容来自项目根目录的 ")
        .append(rules.get(0).fileName())
        .append("，是本项目的强制约定；与默认习惯冲突时以它为准。\n\n");
    for (ProjectRule rule : rules) {
      sb.append(rule.content());
      if (rule.isTruncated()) {
        sb.append("\n\n（该文件超过 ")
            .append(MAX_RULE_CHARS)
            .append(" 字符，以上为截断后的内容；需要后续部分请用 file_read 读取原文件。）");
      }
      sb.append('\n');
    }
    return sb.toString().trim();
  }

  /** 新建规则文件时写入的模板。 */
  public static String template() {
    return "# 项目规则\n"
        + "\n"
        + "在此写下本项目的约定，助手会在每次对话开始时读取。例如：\n"
        + "\n"
        + "- 模块划分与包名约定\n"
        + "- 命名与代码风格\n"
        + "- 构建/验证命令\n"
        + "- 禁止改动的文件或目录\n";
  }

  /** 读文件；不存在、是目录或读失败都返回 null。 */
  private static String readIfPresent(File file) {
    if (!file.isFile()) {
      return null;
    }
    try (FileInputStream in = new FileInputStream(file)) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      int read;
      while ((read = in.read(buffer)) != -1) {
        out.write(buffer, 0, read);
      }
      return new String(out.toByteArray(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      return null;
    }
  }
}
