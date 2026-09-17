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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Skill 系统的回归测试。
 *
 * <p><b>为什么需要它</b>：skill 采用渐进披露——提示词里只有名字与说明，正文按需加载。
 * 因此解析出错会同时影响两处：说明错 → 模型不知道何时该加载；正文截断错 → 模型拿到
 * 不完整的约定却以为拿到了全部。后者尤其危险，它会照着残缺的约定写代码。
 */
final class SkillTest {

  private static void write(Path path, String content) throws Exception {
    Files.createDirectories(path.getParent());
    Files.write(path, content.getBytes(StandardCharsets.UTF_8));
  }

  // ---- 解析 ----

  @Test
  void parsesFrontmatterNameAndDescription() {
    String content =
        "---\n"
            + "name: api-conventions\n"
            + "description: 本项目 REST 接口的命名与错误码约定\n"
            + "---\n"
            + "正文内容";

    Skill skill = SkillParser.parse("whatever.md", content, "/p");

    assertEquals("api-conventions", skill.getName());
    assertEquals("本项目 REST 接口的命名与错误码约定", skill.getDescription());
    assertEquals("正文内容", skill.getBody());
  }

  @Test
  void fallsBackToFileNameWhenNoFrontmatter() {
    // 用户很可能直接写一个 Markdown 就丢进来；报错拒绝不如尽力解释。
    Skill skill = SkillParser.parse("coding-style.md", "# 编码风格\n\n缩进用 2 空格", "/p");

    assertEquals("coding-style", skill.getName());
    // 说明降级取正文首行（去掉标题标记）
    assertEquals("编码风格", skill.getDescription());
    assertTrue(skill.getBody().contains("缩进用 2 空格"));
  }

  @Test
  void skillMdFileNameHasNoInformationSoCallerSuppliesFallback() {
    // SKILL.md 放在以 skill 名命名的目录里，文件名本身没有信息量。
    assertEquals("", SkillParser.nameFromFile("SKILL.md"));
    assertEquals("", SkillParser.nameFromFile("README.md"));
    assertEquals("", SkillParser.nameFromFile("index.md"));
    assertEquals("api-conventions", SkillParser.nameFromFile("api-conventions.md"));
  }

  @Test
  void doesNotTreatMidDocumentDelimiterAsFrontmatter() {
    // Markdown 的分隔线就是 ---。把正文中间的它当边界会截断文档。
    String content =
        "# 标题\n\n第一段\n\n---\n\n第二段\n\n---\n\n第三段";

    Skill skill = SkillParser.parse("doc.md", content, "/p");

    assertTrue(skill.getBody().contains("第一段"), skill.getBody());
    assertTrue(skill.getBody().contains("第二段"));
    assertTrue(skill.getBody().contains("第三段"));
  }

  @Test
  void unclosedFrontmatterIsTreatedAsBody() {
    // 没有闭合分隔符 → 整份内容当正文，不能吞掉内容。
    String content = "---\nname: broken\n\n正文内容还在";

    Skill skill = SkillParser.parse("f.md", content, "/p");

    assertTrue(skill.getBody().contains("正文内容还在"), skill.getBody());
  }

  @Test
  void stripsQuotesAroundValues() {
    Skill skill = SkillParser.parse("f.md", "---\nname: \"quoted\"\ndescription: '单引号'\n---\n正文", "/p");

    assertEquals("quoted", skill.getName());
    assertEquals("单引号", skill.getDescription());
  }

  @Test
  void ignoresUnknownFrontmatterKeysAndComments() {
    Skill skill =
        SkillParser.parse(
            "f.md",
            "---\n# 这是注释\nname: n\nunknown: ignored\ndescription: d\n---\n正文",
            "/p");

    assertEquals("n", skill.getName());
    assertEquals("d", skill.getDescription());
  }

  @Test
  void handlesCrlfLineEndings() {
    // Windows 上编辑过的文件是 CRLF。
    String content = "---\r\nname: crlf\r\ndescription: 说明\r\n---\r\n正文";

    Skill skill = SkillParser.parse("f.md", content, "/p");

    assertEquals("crlf", skill.getName());
    assertEquals("说明", skill.getDescription());
    assertEquals("正文", skill.getBody());
  }

  @Test
  void handlesLeadingBlankLinesBeforeFrontmatter() {
    Skill skill = SkillParser.parse("f.md", "\n\n---\nname: n\n---\n正文", "/p");
    assertEquals("n", skill.getName());
  }

  @Test
  void returnsNullForEmptyContent() {
    assertNull(SkillParser.parse("f.md", null, "/p"));
    assertNull(SkillParser.parse("f.md", "", "/p"));
    assertNull(SkillParser.parse("f.md", "   \n  ", "/p"));
  }

  @Test
  void truncatesOverlongNameAndDescription() {
    // 提示词里每条 skill 只占一行，过长的说明会把它撑成多行。
    StringBuilder longName = new StringBuilder();
    StringBuilder longDesc = new StringBuilder();
    for (int i = 0; i < 300; i++) {
      longName.append('a');
      longDesc.append('b');
    }

    Skill skill = new Skill(longName.toString(), longDesc.toString(), "body", "/p");

    assertEquals(Skill.MAX_NAME_CHARS, skill.getName().length());
    assertEquals(Skill.MAX_DESCRIPTION_CHARS, skill.getDescription().length());
  }

  // ---- 可用性 ----

  @Test
  void usableRequiresNameAndBody() {
    assertTrue(new Skill("n", "d", "body", "/p").isUsable());
    assertFalse(new Skill("", "d", "body", "/p").isUsable());
    assertFalse(new Skill("n", "d", "", "/p").isUsable());
  }

  @Test
  void validatesNameCharacters() {
    // 名字会被模型引用，含空格或标点会让它难以稳定复述。
    assertTrue(Skill.isValidName("api-conventions"));
    assertTrue(Skill.isValidName("code_style"));
    assertTrue(Skill.isValidName("代码风格"));
    assertFalse(Skill.isValidName("has space"));
    assertFalse(Skill.isValidName("has/slash"));
    assertFalse(Skill.isValidName(""));
    assertFalse(Skill.isValidName(null));
  }

  @Test
  void promptLineIncludesDescription() {
    // 提示词里每条 skill 是一行 "- 名字: 说明"
    assertEquals("- api: 接口约定", new Skill("api", "接口约定", "b", "/p").toPromptLine());
    // 无说明时不留悬空的冒号
    assertEquals("- api", new Skill("api", "", "b", "/p").toPromptLine());
  }

  // ---- 注册表 ----

  @Test
  void loadsFlatMarkdownFiles(@TempDir Path root) throws Exception {
    write(root.resolve("api-conventions.md"), "---\ndescription: 接口约定\n---\n正文");
    write(root.resolve("style.md"), "---\ndescription: 代码风格\n---\n正文");

    SkillRegistry registry = SkillRegistry.load(root.toFile());

    assertEquals(2, registry.size());
    assertNotNull(registry.find("api-conventions"));
    assertNotNull(registry.find("style"));
  }

  @Test
  void loadsSkillMdFromSubdirectories(@TempDir Path root) throws Exception {
    // 子目录形式适合带附件的复杂 skill；名字取自目录名。
    write(root.resolve("deploy/SKILL.md"), "---\ndescription: 部署流程\n---\n正文");

    SkillRegistry registry = SkillRegistry.load(root.toFile());

    assertEquals(1, registry.size());
    assertNotNull(registry.find("deploy"), "名字应取自目录名");
  }

  @Test
  void frontmatterNameOverridesDirectoryName(@TempDir Path root) throws Exception {
    write(root.resolve("deploy/SKILL.md"), "---\nname: release-process\n---\n正文");

    SkillRegistry registry = SkillRegistry.load(root.toFile());

    assertNotNull(registry.find("release-process"));
    assertNull(registry.find("deploy"));
  }

  @Test
  void findIsCaseInsensitive(@TempDir Path root) throws Exception {
    write(root.resolve("api.md"), "---\ndescription: d\n---\n正文");

    SkillRegistry registry = SkillRegistry.load(root.toFile());

    assertNotNull(registry.find("API"));
    assertNotNull(registry.find("api"));
    assertNull(registry.find("nonexistent"));
    assertNull(registry.find(null));
  }

  @Test
  void skipsFilesWithoutBody(@TempDir Path root) throws Exception {
    write(root.resolve("empty.md"), "---\nname: empty\ndescription: d\n---\n");

    assertEquals(0, SkillRegistry.load(root.toFile()).size());
  }

  @Test
  void skipsFilesWithInvalidNames(@TempDir Path root) throws Exception {
    write(root.resolve("bad.md"), "---\nname: has space\ndescription: d\n---\n正文");
    write(root.resolve("good.md"), "---\ndescription: d\n---\n正文");

    SkillRegistry registry = SkillRegistry.load(root.toFile());

    assertEquals(1, registry.size());
    assertNotNull(registry.find("good"));
  }

  @Test
  void ignoresNonMarkdownFiles(@TempDir Path root) throws Exception {
    write(root.resolve("notes.txt"), "正文");
    write(root.resolve("readme.rst"), "正文");

    assertEquals(0, SkillRegistry.load(root.toFile()).size());
  }

  @Test
  void duplicateNamesKeepFirstLoadedForStableResults(@TempDir Path root) throws Exception {
    // 后加载覆盖会让结果依赖文件系统遍历顺序，不可预期。
    write(root.resolve("a/api.md"), "---\nname: shared\ndescription: 来自 a\n---\n正文 a");
    write(root.resolve("b/api.md"), "---\nname: shared\ndescription: 来自 b\n---\n正文 b");

    // a/api.md 不是 SKILL.md，所以不会被收集；改用两个 SKILL.md
    write(root.resolve("aa/SKILL.md"), "---\nname: shared\ndescription: 来自 aa\n---\n正文 aa");
    write(root.resolve("bb/SKILL.md"), "---\nname: shared\ndescription: 来自 bb\n---\n正文 bb");

    SkillRegistry registry = SkillRegistry.load(root.toFile());

    assertEquals(1, registry.size());
    // 按路径排序，aa 在前
    assertEquals("来自 aa", registry.find("shared").getDescription());
  }

  @Test
  void handlesMissingOrInvalidRoot() {
    assertEquals(0, SkillRegistry.load(null).size());
    assertEquals(0, SkillRegistry.load(new File("/nonexistent/path/xyz")).size());
    assertTrue(SkillRegistry.empty().isEmpty());
  }

  @Test
  void enforcesSkillLimit(@TempDir Path root) throws Exception {
    for (int i = 0; i < SkillRegistry.MAX_SKILLS + 5; i++) {
      write(root.resolve("s" + i + ".md"), "---\ndescription: d\n---\n正文 " + i);
    }

    assertEquals(SkillRegistry.MAX_SKILLS, SkillRegistry.load(root.toFile()).size());
  }

  @Test
  void rendersPromptListingWithoutBodies(@TempDir Path root) throws Exception {
    // 渐进披露的关键：常驻提示词里只有名字与说明，不含正文。
    write(root.resolve("api.md"), "---\ndescription: 接口约定\n---\nSECRET-BODY-CONTENT");

    SkillRegistry registry = SkillRegistry.load(root.toFile());
    String rendered = registry.renderForPrompt();

    assertTrue(rendered.contains("api"), rendered);
    assertTrue(rendered.contains("接口约定"), rendered);
    assertFalse(rendered.contains("SECRET-BODY-CONTENT"), "提示词里不应含正文");
  }

  @Test
  void rendersEmptyStringWhenNoSkills() {
    assertEquals("", SkillRegistry.empty().renderForPrompt());
  }

  @Test
  void truncatesOverlongBody() {
    StringBuilder big = new StringBuilder();
    for (int i = 0; i < SkillRegistry.MAX_BODY_CHARS + 500; i++) {
      big.append('x');
    }

    String truncated = SkillRegistry.truncateBody(big.toString());

    assertTrue(truncated.contains("已截断"), "截断必须注明");
    assertTrue(truncated.length() < big.length());
  }

  // ---- 工具 ----

  @Test
  void toolLoadsFullBodyByName(@TempDir Path root) throws Exception {
    write(root.resolve("api.md"), "---\ndescription: 接口约定\n---\n完整正文内容");

    SkillTool tool = new SkillTool(SkillRegistry.load(root.toFile()));
    ToolResult result = tool.execute(new JSONObject().put("name", "api"), null);

    assertFalse(result.isError());
    assertTrue(result.getContent().contains("完整正文内容"));
    assertTrue(result.getContent().contains("接口约定"));
  }

  @Test
  void toolListsAllSkillsWhenNameOmitted(@TempDir Path root) throws Exception {
    write(root.resolve("a.md"), "---\ndescription: 说明 a\n---\n正文");
    write(root.resolve("b.md"), "---\ndescription: 说明 b\n---\n正文");

    ToolResult result = new SkillTool(SkillRegistry.load(root.toFile())).execute(new JSONObject(), null);

    assertFalse(result.isError());
    assertTrue(result.getContent().contains("a"));
    assertTrue(result.getContent().contains("b"));
    assertTrue(result.getContent().contains("2 个"));
  }

  @Test
  void toolListsAvailableNamesWhenNameNotFound(@TempDir Path root) throws Exception {
    // 模型拼错一个字母就白跑一轮；给出候选能省下这一轮。
    write(root.resolve("api.md"), "---\ndescription: d\n---\n正文");

    ToolResult result =
        new SkillTool(SkillRegistry.load(root.toFile()))
            .execute(new JSONObject().put("name", "apii"), null);

    assertTrue(result.isError());
    assertTrue(result.getContent().contains("apii"));
    assertTrue(result.getContent().contains("api"), result.getContent());
  }

  @Test
  void toolReportsWhenNoSkillsAvailable() {
    ToolResult result = new SkillTool(SkillRegistry.empty()).execute(new JSONObject(), null);

    assertFalse(result.isError());
    assertTrue(result.getContent().contains("没有可用"));
  }

  @Test
  void toolIsReadOnlyAndAllowedInReadonlyMode() {
    SkillTool tool = new SkillTool(SkillRegistry.empty());

    assertEquals("skill", tool.getName());
    assertEquals(com.tom.rv2ide.ai.tool.api.ToolCategory.READ, tool.getCategory());
    assertTrue(tool.isAllowedInReadonlyMode());
  }

  @Test
  void toolToleratesNullRegistry() {
    assertFalse(new SkillTool(null).execute(new JSONObject(), null).isError());
  }
}
