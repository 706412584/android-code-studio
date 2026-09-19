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

package com.tom.rv2ide.ai.tool.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * skill 写入工具。
 *
 * <p>重点是**名字校验**：名字直接参与文件路径，含分隔符或 `..` 的名字能把文件写到
 * skill 目录之外。以及「写完立刻可见」——注册表原先只在构造时加载一次，若写入后不重载，
 * 模型会以为没写成功而反复重试。
 */
final class SkillWriteToolTest {

  private static SkillWriteTool toolFor(Path dir) {
    return new SkillWriteTool(SkillRegistry.load(dir.toFile()));
  }

  private static JSONObject writeInput(String name, String description, String body) {
    JSONObject skill = new JSONObject();
    skill.put("name", name);
    skill.put("description", description);
    skill.put("body", body);
    JSONObject input = new JSONObject();
    input.put("action", "write");
    input.put("skills", new JSONArray().put(skill));
    return input;
  }

  @Test
  void writesSkillThatRegistryCanReadBack(@TempDir Path dir) throws IOException {
    SkillWriteTool tool = toolFor(dir);

    ToolResult result = tool.execute(writeInput("my-skill", "何时用我", "正文内容\n第二行"), null);

    assertFalse(result.isError(), "写入应成功：" + result.getContent());
    // 落盘路径为 <root>/<name>/SKILL.md
    File file = new File(dir.toFile(), "my-skill/SKILL.md");
    assertTrue(file.isFile(), "应写出 SKILL.md");

    String content = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    assertTrue(content.startsWith("---\n"), "应有 frontmatter");
    assertTrue(content.contains("name: my-skill"));
    assertTrue(content.contains("description: 何时用我"));
    assertTrue(content.contains("正文内容"));
  }

  @Test
  void writtenSkillIsVisibleImmediatelyWithoutRestart(@TempDir Path dir) {
    SkillRegistry registry = SkillRegistry.load(dir.toFile());
    SkillWriteTool tool = new SkillWriteTool(registry);
    assertEquals(0, registry.size());

    tool.execute(writeInput("fresh", "刚写的", "内容"), null);

    // 这是自写闭环的关键：不重载的话本次运行内看不到，模型会重复写。
    assertEquals(1, registry.size(), "写入后注册表应立即可见");
    assertNotNull(registry.find("fresh"));
    assertTrue(registry.renderForPrompt().contains("fresh"));
  }

  @Test
  void overwriteReplacesExistingBody(@TempDir Path dir) {
    SkillWriteTool tool = toolFor(dir);
    tool.execute(writeInput("dup", "第一版", "旧内容"), null);

    tool.execute(writeInput("dup", "第二版", "新内容"), null);

    SkillRegistry fresh = SkillRegistry.load(dir.toFile());
    assertEquals(1, fresh.size(), "重名应覆盖而不是新增");
    assertEquals("新内容", fresh.find("dup").getBody());
    assertEquals("第二版", fresh.find("dup").getDescription());
  }

  @Test
  void rejectsNameWithPathSeparator(@TempDir Path dir) {
    SkillWriteTool tool = toolFor(dir);

    ToolResult result = tool.execute(writeInput("../escaped", "试图越界", "内容"), null);

    assertTrue(result.isError(), "含路径分隔符的名字必须拒绝");
    // 目录之外不应出现任何文件
    assertFalse(new File(dir.toFile().getParentFile(), "escaped").exists(), "不得写到目录之外");
    assertEquals(0, SkillRegistry.load(dir.toFile()).size());
  }

  @Test
  void rejectsNameWithDotDotOnly(@TempDir Path dir) {
    SkillWriteTool tool = toolFor(dir);

    ToolResult result = tool.execute(writeInput("..", "试图越界", "内容"), null);

    assertTrue(result.isError(), "`..` 必须拒绝");
  }

  @Test
  void rejectsEmptyBody(@TempDir Path dir) {
    SkillWriteTool tool = toolFor(dir);

    ToolResult result = tool.execute(writeInput("nobody", "说明", "   "), null);

    assertTrue(result.isError(), "空正文应拒绝——没有正文的 skill 无法被加载");
  }

  @Test
  void rejectsOverlongBody(@TempDir Path dir) {
    SkillWriteTool tool = toolFor(dir);
    StringBuilder huge = new StringBuilder();
    for (int i = 0; i < SkillWriteTool.MAX_BODY_CHARS + 10; i++) {
      huge.append('x');
    }

    ToolResult result = tool.execute(writeInput("huge", "说明", huge.toString()), null);

    assertTrue(result.isError(), "超长正文应拒绝而不是静默截断");
  }

  @Test
  void rejectsEmptyName(@TempDir Path dir) {
    SkillWriteTool tool = toolFor(dir);

    assertTrue(tool.execute(writeInput("", "说明", "内容"), null).isError(), "空名字应拒绝");
  }

  @Test
  void deleteRemovesSkillAndReloads(@TempDir Path dir) {
    SkillRegistry registry = SkillRegistry.load(dir.toFile());
    SkillWriteTool tool = new SkillWriteTool(registry);
    tool.execute(writeInput("temp", "临时", "内容"), null);
    assertEquals(1, registry.size());

    JSONObject input = new JSONObject();
    input.put("action", "delete");
    input.put("name", "temp");
    ToolResult result = tool.execute(input, null);

    assertFalse(result.isError(), "删除应成功：" + result.getContent());
    assertFalse(new File(dir.toFile(), "temp").exists(), "目录应被删除");
    assertEquals(0, registry.size(), "删除后注册表应立即可见");
  }

  @Test
  void deleteReportsMissingName(@TempDir Path dir) {
    SkillWriteTool tool = toolFor(dir);
    JSONObject input = new JSONObject();
    input.put("action", "delete");
    input.put("name", "nope");

    assertTrue(tool.execute(input, null).isError(), "删不存在的应报错而不是假装成功");
  }

  @Test
  void listShowsExistingSkills(@TempDir Path dir) {
    SkillWriteTool tool = toolFor(dir);
    tool.execute(writeInput("alpha", "第一个", "内容"), null);

    JSONObject input = new JSONObject();
    input.put("action", "list");
    ToolResult result = tool.execute(input, null);

    assertFalse(result.isError());
    assertTrue(result.getContent().contains("alpha"));
  }

  @Test
  void unknownActionIsRejectedWithValidValues(@TempDir Path dir) {
    SkillWriteTool tool = toolFor(dir);
    JSONObject input = new JSONObject();
    input.put("action", "writ");

    ToolResult result = tool.execute(input, null);

    assertTrue(result.isError());
    // 必须列出合法值，否则模型会反复重试同一个错拼。
    assertTrue(result.getContent().contains("write"));
    assertTrue(result.getContent().contains("delete"));
  }

  @Test
  void rejectsWhenRegistryHasNoRoot() {
    // empty() 造的注册表没有来源目录，无处可写。必须明确报错而不是静默丢弃。
    SkillWriteTool tool = new SkillWriteTool(SkillRegistry.empty());

    ToolResult result = tool.execute(writeInput("x", "说明", "内容"), null);

    assertTrue(result.isError());
    assertTrue(result.getContent().contains("目录"));
  }

  @Test
  void multiLineDescriptionIsCollapsedToSingleLine(@TempDir Path dir) {
    SkillWriteTool tool = toolFor(dir);

    tool.execute(writeInput("multi", "第一行\n第二行", "内容"), null);

    // frontmatter 的值含换行会把格式写坏，必须压成单行。
    Skill skill = SkillRegistry.load(dir.toFile()).find("multi");
    assertNotNull(skill);
    assertFalse(skill.getDescription().contains("\n"), "说明必须压成单行");
    assertTrue(skill.getDescription().contains("第一行"));
  }

  @Test
  void partialSuccessReportsBothWrittenAndRejected(@TempDir Path dir) {
    SkillWriteTool tool = toolFor(dir);
    JSONObject good = new JSONObject();
    good.put("name", "good");
    good.put("description", "可以");
    good.put("body", "内容");
    JSONObject bad = new JSONObject();
    bad.put("name", "../evil");
    bad.put("description", "不行");
    bad.put("body", "内容");
    JSONObject input = new JSONObject();
    input.put("action", "write");
    input.put("skills", new JSONArray().put(good).put(bad));

    ToolResult result = tool.execute(input, null);

    // 一条成功一条失败时整体算成功，但必须把被拒的原因如实报出来。
    assertFalse(result.isError());
    assertTrue(result.getContent().contains("good"));
    assertTrue(result.getContent().contains("evil"), "应说明哪条被拒");
  }
}
