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

package com.tom.rv2ide.ai.tool.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tom.rv2ide.ai.tool.skill.SkillMarketClient.FileEntry;
import com.tom.rv2ide.ai.tool.skill.SkillMarketClient.MarketSkill;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 技能市场两个上游的响应解析。
 *
 * <p>这些解析方法是唯一值得单测的部分——发请求那侧是硬编码的上游 URL，测试里连不上
 * 也不该连（会把单测变成集成测试）。解析的容错规则直接决定用户看到什么：把「形态不符」
 * 当空列表（而不是抛异常）是刻意的——一个上游改了响应结构不该让整个市场页面崩掉。
 */
final class SkillMarketParsingTest {

  // ---- ClawHub 列表 ----

  private static final String CLAWHUB_LIST =
      "{\"skills\":[{\"slug\":\"pdf-tools\",\"name\":\"PDF 工具\",\"description\":\"处理 PDF\","
          + "\"version\":\"1.2.0\",\"downloads\":1234},"
          + "{\"id\":\"no-slug-uses-id\",\"name\":\"回退到 id\"}]}";

  private static final String CLAWHUB_LIST_DATA_KEY =
      "{\"data\":[{\"slug\":\"d1\",\"name\":\"D1\"}]}";

  private static final String CLAWHUB_LIST_BAD_ITEM =
      "{\"skills\":[{\"name\":\"没有 slug 也没有 id\"},{\"slug\":\"ok\"},null]}";

  @Test
  void clawhubParsesAllFields() {
    List<MarketSkill> skills = SkillMarketClient.parseClawhubList(CLAWHUB_LIST);
    assertEquals(2, skills.size());
    MarketSkill first = skills.get(0);
    assertEquals(SkillMarketClient.SOURCE_CLAWHUB, first.source);
    assertEquals("pdf-tools", first.slug);
    assertEquals("PDF 工具", first.name);
    assertEquals("处理 PDF", first.description);
    assertEquals("1.2.0", first.version);
    assertEquals(1234L, first.downloads);
  }

  @Test
  void clawhubFallsBackToIdWhenSlugMissing() {
    // 有的上游用 id 而不是 slug 作主键；缺 slug 时回退，否则条目会被整条丢掉。
    List<MarketSkill> skills = SkillMarketClient.parseClawhubList(CLAWHUB_LIST);
    assertEquals("no-slug-uses-id", skills.get(1).slug);
    assertEquals("回退到 id", skills.get(1).name);
  }

  @Test
  void clawhubAcceptsDataKeyAsAlternative() {
    // 有的响应把数组放在 data 下——两种形态都要认。
    assertEquals(1, SkillMarketClient.parseClawhubList(CLAWHUB_LIST_DATA_KEY).size());
  }

  @Test
  void clawhubSkipsItemsWithoutAnyIdentifier() {
    // 既无 slug 也无 id 的条目无法指代，跳过；null 元素也要跳过。
    List<MarketSkill> skills = SkillMarketClient.parseClawhubList(CLAWHUB_LIST_BAD_ITEM);
    assertEquals(1, skills.size(), "只应保留有标识的那条");
    assertEquals("ok", skills.get(0).slug);
  }

  @Test
  void clawhubMissingArrayYieldsEmptyNotThrow() {
    assertTrue(SkillMarketClient.parseClawhubList("{\"other\":1}").isEmpty());
  }

  @Test
  void clawhubMalformedJsonYieldsEmptyNotThrow() {
    // 形态不符 → 空列表（由上层提示），而不是把异常抛给 UI 线程。
    assertTrue(SkillMarketClient.parseClawhubList("not json at all").isEmpty());
    assertTrue(SkillMarketClient.parseClawhubList("").isEmpty());
  }

  // ---- SkillHub 列表 ----

  @Test
  void skillhubParsesListAndDataForms() {
    String withSkills =
        "{\"skills\":[{\"slug\":\"s1\",\"name\":\"S1\",\"version\":\"2.0\",\"downloads\":9}]}";
    String withData = "{\"data\":[{\"id\":\"s2\",\"name\":\"S2\"}]}";

    List<MarketSkill> a = SkillMarketClient.parseSkillhubList(withSkills);
    assertEquals(1, a.size());
    assertEquals(SkillMarketClient.SOURCE_SKILLHUB, a.get(0).source);
    assertEquals("s1", a.get(0).slug);
    assertEquals(9L, a.get(0).downloads);

    List<MarketSkill> b = SkillMarketClient.parseSkillhubList(withData);
    assertEquals(1, b.size());
    assertEquals("s2", b.get(0).slug);
  }

  @Test
  void skillhubMalformedJsonYieldsEmpty() {
    assertTrue(SkillMarketClient.parseSkillhubList("{oops").isEmpty());
    assertTrue(SkillMarketClient.parseSkillhubList("{\"nope\":[]}").isEmpty());
  }

  // ---- 文件清单 ----

  @Test
  void clawhubFilesParsedFromFlatAndNestedForms() {
    String flat = "{\"files\":[{\"path\":\"SKILL.md\",\"size\":120,\"sha256\":\"aa\"}]}";
    List<FileEntry> fromFlat = SkillMarketClient.parseClawhubFiles(flat);
    assertEquals(1, fromFlat.size());
    assertEquals("SKILL.md", fromFlat.get(0).path);
    assertEquals(120L, fromFlat.get(0).size);
    assertEquals("aa", fromFlat.get(0).sha256);

    // 有的上游把文件挂在 version 下。
    String nested =
        "{\"version\":{\"files\":[{\"path\":\"a/b.md\",\"size\":5,\"sha256\":\"bb\"}]}}";
    List<FileEntry> fromNested = SkillMarketClient.parseClawhubFiles(nested);
    assertEquals(1, fromNested.size());
    assertEquals("a/b.md", fromNested.get(0).path);
  }

  @Test
  void clawhubFilesSkipsEntriesWithoutPath() {
    String body = "{\"files\":[{\"size\":1},{\"path\":\"good.md\"},{\"path\":\"\"}]}";
    List<FileEntry> files = SkillMarketClient.parseClawhubFiles(body);
    assertEquals(1, files.size(), "无 path 的文件无法下载，应跳过");
    assertEquals("good.md", files.get(0).path);
  }

  @Test
  void clawhubFilesMissingArrayIsEmpty() {
    assertTrue(SkillMarketClient.parseClawhubFiles("{\"x\":1}").isEmpty());
    assertTrue(SkillMarketClient.parseClawhubFiles("{\"version\":{}}").isEmpty());
  }

  @Test
  void skillhubFilesAcceptsFilesAndDataAndNameFallback() {
    List<FileEntry> fromFiles =
        SkillMarketClient.parseSkillhubFiles("{\"files\":[{\"path\":\"p.md\",\"size\":3}]}");
    assertEquals(1, fromFiles.size());
    assertEquals("p.md", fromFiles.get(0).path);

    // data 形态 + 缺 path 时用 name 兜底（SkillHub 两种都出现过）。
    List<FileEntry> fromData =
        SkillMarketClient.parseSkillhubFiles("{\"data\":[{\"name\":\"n.md\",\"size\":7}]}");
    assertEquals(1, fromData.size());
    assertEquals("n.md", fromData.get(0).path);
    assertEquals(7L, fromData.get(0).size);
  }

  @Test
  void fileListParsersThrowOnMalformedJsonAndCallerCatches() {
    // 与列表解析方法不同：parse*Files 不吞异常（调用方 install() 有 `catch (Exception)`
    // 统一转成 InstallResult.failure）。这里钉住这个分工——若给它们加 try-catch，
    // 安装失败会被静默当成「清单为空」，用户看到「市场没有返回文件清单」而不是真实原因。
    org.junit.jupiter.api.Assertions.assertThrows(
        RuntimeException.class, () -> SkillMarketClient.parseSkillhubFiles("]["));
    org.junit.jupiter.api.Assertions.assertThrows(
        RuntimeException.class, () -> SkillMarketClient.parseClawhubFiles("]["));
  }
}
