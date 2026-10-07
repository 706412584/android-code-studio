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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link ProjectRulesLoader} 的读取、回退与截断语义。 */
public class ProjectRulesLoaderTest {

  private static void write(Path root, String name, String content) throws IOException {
    Files.write(root.resolve(name), content.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void loadsClaudeMd(@TempDir Path root) throws IOException {
    write(root, "CLAUDE.md", "# 规则\n用 Kotlin 2.1。");
    List<ProjectRulesLoader.ProjectRule> rules = ProjectRulesLoader.load(root.toFile());

    assertEquals(1, rules.size());
    assertEquals("CLAUDE.md", rules.get(0).fileName());
    assertEquals("# 规则\n用 Kotlin 2.1。", rules.get(0).content());
    assertFalse(rules.get(0).isTruncated());
  }

  @Test
  void fallsBackToAgentsMdWhenClaudeMdAbsent(@TempDir Path root) throws IOException {
    write(root, "AGENTS.md", "只改必要部分。");
    List<ProjectRulesLoader.ProjectRule> rules = ProjectRulesLoader.load(root.toFile());

    assertEquals(1, rules.size());
    assertEquals("AGENTS.md", rules.get(0).fileName());
    assertEquals("只改必要部分。", rules.get(0).content());
  }

  /**
   * 两者都在时只取 CLAUDE.md。
   *
   * <p>这是刻意的取舍而非遗漏：AGENTS.md 常是 CLAUDE.md 的副本（为兼容其它工具而复制），
   * 叠加注入会让同一段规则出现两遍，浪费上下文并让模型误以为存在两条独立要求。
   */
  @Test
  void prefersClaudeMdOverAgentsMd(@TempDir Path root) throws IOException {
    write(root, "CLAUDE.md", "主规则");
    write(root, "AGENTS.md", "回退规则");
    List<ProjectRulesLoader.ProjectRule> rules = ProjectRulesLoader.load(root.toFile());

    assertEquals(1, rules.size());
    assertEquals("CLAUDE.md", rules.get(0).fileName());
    assertEquals("主规则", rules.get(0).content());
  }

  /** 空的 CLAUDE.md 不该屏蔽已写好内容的 AGENTS.md。 */
  @Test
  void emptyClaudeMdFallsThroughToAgentsMd(@TempDir Path root) throws IOException {
    write(root, "CLAUDE.md", "   \n\n  ");
    write(root, "AGENTS.md", "回退规则");
    List<ProjectRulesLoader.ProjectRule> rules = ProjectRulesLoader.load(root.toFile());

    assertEquals(1, rules.size());
    assertEquals("AGENTS.md", rules.get(0).fileName());
  }

  @Test
  void noRulesYieldsEmptyList(@TempDir Path root) {
    assertTrue(ProjectRulesLoader.load(root.toFile()).isEmpty());
  }

  @Test
  void nullAndNonDirectoryRootsAreSafe(@TempDir Path root) throws IOException {
    assertTrue(ProjectRulesLoader.load(null).isEmpty());
    assertTrue(ProjectRulesLoader.load(root.resolve("不存在").toFile()).isEmpty());
    // 传文件而非目录：不应抛异常，也不应把该文件当规则读进来
    Path file = root.resolve("somefile.md");
    Files.write(file, "x".getBytes(StandardCharsets.UTF_8));
    assertTrue(ProjectRulesLoader.load(file.toFile()).isEmpty());
  }

  @Test
  void truncatesOverlongContentAndFlagsIt(@TempDir Path root) throws IOException {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < ProjectRulesLoader.MAX_RULE_CHARS + 500; i++) {
      sb.append('x');
    }
    write(root, "CLAUDE.md", sb.toString());

    List<ProjectRulesLoader.ProjectRule> rules = ProjectRulesLoader.load(root.toFile());
    assertEquals(1, rules.size());
    assertTrue(rules.get(0).isTruncated());
    assertEquals(ProjectRulesLoader.MAX_RULE_CHARS, rules.get(0).content().length());

    // 截断必须在提示词里说明，否则模型会以为自己看到了全文。
    String prompt = ProjectRulesLoader.renderForPrompt(rules);
    assertTrue(prompt.contains("截断"));
    assertTrue(prompt.contains("file_read"));
  }

  @Test
  void rendersPromptWithFileNameAndContent(@TempDir Path root) throws IOException {
    write(root, "CLAUDE.md", "禁止修改 signing/ 目录。");
    String prompt =
        ProjectRulesLoader.renderForPrompt(ProjectRulesLoader.load(root.toFile()));

    assertTrue(prompt.startsWith("[ 项目规则 ]"));
    assertTrue(prompt.contains("CLAUDE.md"));
    assertTrue(prompt.contains("禁止修改 signing/ 目录。"));
  }

  /** 无规则时渲染为空串——调用方据此决定不注入，而不是注入一个空标题。 */
  @Test
  void rendersEmptyStringWhenNoRules() {
    assertEquals("", ProjectRulesLoader.renderForPrompt(null));
    assertEquals("", ProjectRulesLoader.renderForPrompt(Collections.emptyList()));
  }

  @Test
  void readsUtf8Content(@TempDir Path root) throws IOException {
    write(root, "CLAUDE.md", "中文规则：使用「最小改动」原则。");
    List<ProjectRulesLoader.ProjectRule> rules = ProjectRulesLoader.load(root.toFile());
    assertEquals("中文规则：使用「最小改动」原则。", rules.get(0).content());
  }

  @Test
  void templateIsNonEmptyAndMentionsRules() {
    String template = ProjectRulesLoader.template();
    assertTrue(template.contains("项目规则"));
    assertFalse(template.trim().isEmpty());
  }
}
