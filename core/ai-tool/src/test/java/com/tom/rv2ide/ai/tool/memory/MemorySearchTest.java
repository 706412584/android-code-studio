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

package com.tom.rv2ide.ai.tool.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 长期记忆的检索与存储回归测试。
 *
 * <p><b>为什么需要它</b>：中文分词是这个功能的核心难点——用「按空格切词」处理中文，
 * 一整句会变成一个 token，检索等于不存在，而失败表现是「记忆保存了但从来检索不到」，
 * 静默且难归因。此外记忆会进入每一轮提示词，检索出无关条目会持续污染上下文。
 */
final class MemorySearchTest {

  private static MemoryEntry entry(String id, String text, String... tags) {
    return new MemoryEntry(id, text, Arrays.asList(tags), 1000L);
  }

  // ---- 分词 ----

  @Test
  void tokenizesChineseIntoBigrams() {
    // 按空格切词对中文完全失效：整句会变成一个 token。
    List<String> tokens = new ArrayList<>(MemorySearch.tokenize("数据库连接"));
    Collections.sort(tokens);

    assertTrue(tokens.contains("数据"), tokens.toString());
    assertTrue(tokens.contains("据库"), tokens.toString());
    assertTrue(tokens.contains("库连"), tokens.toString());
    assertTrue(tokens.contains("连接"), tokens.toString());
    // 不应产生整句 token
    assertFalse(tokens.contains("数据库连接"));
  }

  @Test
  void tokenizesEnglishIntoWordsLowercased() {
    List<String> tokens = new ArrayList<>(MemorySearch.tokenize("Retrofit Network"));
    Collections.sort(tokens);

    assertEquals(Arrays.asList("network", "retrofit"), tokens);
  }

  @Test
  void dropsSingleCharacterEnglishWords() {
    // 英文单字母（a / i）几乎总是噪声。
    List<String> tokens = new ArrayList<>(MemorySearch.tokenize("a b cd e"));
    assertEquals(Collections.singletonList("cd"), tokens);
  }

  @Test
  void handlesMixedChineseAndEnglish() {
    // 中英混排时不应把西文词与中文粘连成一个 token。
    List<String> tokens = new ArrayList<>(MemorySearch.tokenize("使用Hilt注入"));
    Collections.sort(tokens);

    assertTrue(tokens.contains("hilt"), tokens.toString());
    assertTrue(tokens.contains("使用"), tokens.toString());
    assertTrue(tokens.contains("注入"), tokens.toString());
  }

  @Test
  void handlesEmptyAndNullInput() {
    assertTrue(MemorySearch.tokenize(null).isEmpty());
    assertTrue(MemorySearch.tokenize("").isEmpty());
    assertTrue(MemorySearch.tokenize("   ").isEmpty());
    // 标点不产生 token
    assertTrue(MemorySearch.tokenize("!!! ???").isEmpty());
  }

  @Test
  void singleChineseCharacterProducesNoBigram() {
    // 单个汉字没有相邻字符，无法构成 bigram。这不影响实用性（单字查询噪声极大）。
    assertTrue(MemorySearch.tokenize("库").isEmpty());
  }

  // ---- 检索 ----

  @Test
  void retrievesChineseMemoryByPartialQuery() {
    // 关键场景：用户用不同的说法提问，仍应命中相关记忆。
    List<MemoryEntry> entries =
        Arrays.asList(
            entry("1", "这个项目用 Hilt 做依赖注入"),
            entry("2", "构建时需要加 --offline 参数"),
            entry("3", "用户偏好深色主题"));

    List<MemoryEntry> result = MemorySearch.search(entries, "依赖注入怎么做的", 10);

    assertFalse(result.isEmpty());
    assertEquals("1", result.get(0).getId());
  }

  @Test
  void retrievesEnglishMemoryCaseInsensitively() {
    List<MemoryEntry> entries = Arrays.asList(entry("1", "Uses Retrofit for networking"));

    assertEquals(1, MemorySearch.search(entries, "retrofit", 10).size());
    assertEquals(1, MemorySearch.search(entries, "RETROFIT", 10).size());
  }

  @Test
  void returnsEmptyWhenNothingMatches() {
    List<MemoryEntry> entries = Arrays.asList(entry("1", "uses Hilt"));

    assertTrue(MemorySearch.search(entries, "quantum physics", 10).isEmpty());
    assertTrue(MemorySearch.search(entries, "", 10).isEmpty());
    assertTrue(MemorySearch.search(entries, null, 10).isEmpty());
  }

  @Test
  void returnsEmptyForNullOrEmptyEntryList() {
    assertTrue(MemorySearch.search(null, "query", 10).isEmpty());
    assertTrue(MemorySearch.search(Collections.<MemoryEntry>emptyList(), "query", 10).isEmpty());
  }

  @Test
  void rareTokensOutrankCommonOnes() {
    // 「的」出现在每条记忆里，几乎不携带信息；专有名词才是判据。
    List<MemoryEntry> entries =
        Arrays.asList(
            entry("common", "这是项目的一些说明"),
            entry("rare", "这个项目用 Retrofit 做网络请求"));

    List<MemoryEntry> result = MemorySearch.search(entries, "项目的网络请求", 10);

    assertEquals("rare", result.get(0).getId(), "稀有词命中的记忆应排在前面");
  }

  @Test
  void tagsParticipateInRetrieval() {
    // 标签存在的意义就是改善检索：正文里没写但标签里有，也应命中。
    List<MemoryEntry> entries = Arrays.asList(entry("1", "构建慢的问题已解决", "gradle", "性能"));

    assertFalse(MemorySearch.search(entries, "gradle", 10).isEmpty());
  }

  @Test
  void respectsLimit() {
    List<MemoryEntry> entries = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      entries.add(entry(String.valueOf(i), "共同关键词 条目" + i));
    }

    assertEquals(3, MemorySearch.search(entries, "共同关键词", 3).size());
    // limit <= 0 表示不限
    assertEquals(10, MemorySearch.search(entries, "共同关键词", 0).size());
  }

  @Test
  void skipsBlankEntries() {
    List<MemoryEntry> entries = Arrays.asList(entry("blank", "   "), entry("real", "有内容"));

    List<MemoryEntry> result = MemorySearch.search(entries, "内容", 10);

    assertEquals(1, result.size());
    assertEquals("real", result.get(0).getId());
  }

  @Test
  void moreMatchesRankHigher() {
    List<MemoryEntry> entries =
        Arrays.asList(
            entry("one", "gradle 构建"),
            entry("two", "gradle 构建 需要 offline 参数"));

    List<MemoryEntry> result = MemorySearch.search(entries, "gradle 构建 offline", 10);

    assertEquals("two", result.get(0).getId());
  }

  // ---- 存储 ----

  @Test
  void storeAddsAndRetrieves() {
    MemoryStore store = MemoryStore.inMemory();
    MemoryEntry added = store.add("项目用 Hilt", Arrays.asList("di"), 1000L);

    assertNotNull(added);
    assertEquals(1, store.size());
    assertEquals("项目用 Hilt", store.all().get(0).getText());
    assertEquals(Collections.singletonList("di"), store.all().get(0).getTags());
  }

  @Test
  void storeRejectsBlankText() {
    MemoryStore store = MemoryStore.inMemory();
    assertNull(store.add("", null, 0L));
    assertNull(store.add("   ", null, 0L));
    assertNull(store.add(null, null, 0L));
    assertEquals(0, store.size());
  }

  @Test
  void storeRemovesById() {
    MemoryStore store = MemoryStore.inMemory();
    MemoryEntry entry = store.add("待删除", null, 0L);

    assertTrue(store.remove(entry.getId()));
    assertEquals(0, store.size());
    // 重复删除返回 false
    assertFalse(store.remove(entry.getId()));
    assertFalse(store.remove(null));
    assertFalse(store.remove("nonexistent"));
  }

  @Test
  void storeClears() {
    MemoryStore store = MemoryStore.inMemory();
    store.add("a", null, 0L);
    store.add("b", null, 0L);

    store.clear();
    assertEquals(0, store.size());
    assertTrue(store.all().isEmpty());
  }

  @Test
  void storePersistsAcrossInstances(@TempDir Path tempDir) throws Exception {
    // 长期记忆必须跨进程存活——否则「长期」二字没有意义。
    File file = tempDir.resolve("memories.json").toFile();

    MemoryStore first = new MemoryStore(file);
    first.add("项目用 Hilt 做依赖注入", Arrays.asList("di", "android"), 1000L);
    first.add("构建需要 --offline", null, 2000L);

    MemoryStore second = new MemoryStore(file);
    assertEquals(2, second.size());
    assertEquals("项目用 Hilt 做依赖注入", second.all().get(0).getText());
    assertEquals(Arrays.asList("di", "android"), second.all().get(0).getTags());
    assertEquals(1000L, second.all().get(0).getCreatedAt());
  }

  @Test
  void storePersistsDeletionAcrossInstances(@TempDir Path tempDir) throws Exception {
    File file = tempDir.resolve("memories.json").toFile();
    MemoryStore first = new MemoryStore(file);
    MemoryEntry keep = first.add("保留", null, 0L);
    MemoryEntry drop = first.add("删除", null, 0L);
    first.remove(drop.getId());

    MemoryStore second = new MemoryStore(file);
    assertEquals(1, second.size());
    assertEquals(keep.getId(), second.all().get(0).getId());
  }

  @Test
  void storeSurvivesCorruptFile(@TempDir Path tempDir) throws Exception {
    File file = tempDir.resolve("memories.json").toFile();
    Files.write(file.toPath(), "not json".getBytes(StandardCharsets.UTF_8));

    // 文件损坏 → 空记忆，不抛异常。记忆丢失的代价远小于 AI 功能不可用。
    assertEquals(0, new MemoryStore(file).size());
  }

  @Test
  void storeSkipsCorruptEntriesButKeepsGoodOnes(@TempDir Path tempDir) throws Exception {
    File file = tempDir.resolve("memories.json").toFile();
    Files.write(
        file.toPath(),
        ("[{\"text\":\"good\",\"tags\":[]},"
                + "{\"tags\":[]},"
                + "{\"text\":\"also good\",\"tags\":[\"t\"]}]")
            .getBytes(StandardCharsets.UTF_8));

    MemoryStore store = new MemoryStore(file);
    assertEquals(2, store.size());
    assertEquals("good", store.all().get(0).getText());
  }

  @Test
  void storeEnforcesEntryLimitByDroppingOldest() {
    // 拒绝新增会让「记住这个」在长期使用后突然失效；丢掉最旧的更符合预期。
    MemoryStore store = MemoryStore.inMemory();
    for (int i = 0; i < MemoryStore.MAX_ENTRIES + 10; i++) {
      store.add("条目 " + i, null, i);
    }

    assertEquals(MemoryStore.MAX_ENTRIES, store.size());
    // 最旧的已被丢弃
    assertFalse(store.all().get(0).getText().equals("条目 0"));
  }

  @Test
  void storeTruncatesOverlongText() {
    MemoryStore store = MemoryStore.inMemory();
    StringBuilder big = new StringBuilder();
    for (int i = 0; i < MemoryStore.MAX_TEXT_CHARS + 100; i++) {
      big.append('x');
    }
    MemoryEntry entry = store.add(big.toString(), null, 0L);

    assertEquals(MemoryStore.MAX_TEXT_CHARS, entry.getText().length());
  }

  @Test
  void storeRendersRelevantMemoriesForPrompt() {
    MemoryStore store = MemoryStore.inMemory();
    store.add("项目用 Hilt 做依赖注入", null, 0L);
    store.add("完全无关的内容", null, 0L);

    String rendered = store.renderForPrompt("依赖注入", 5);

    assertTrue(rendered.contains("Hilt"), rendered);
    assertFalse(rendered.contains("完全无关"), rendered);
  }

  @Test
  void storeRendersEmptyStringWhenNothingRelevant() {
    // 空串让调用方跳过注入，避免提示词里出现没有内容的段落。
    MemoryStore store = MemoryStore.inMemory();
    store.add("gradle offline", null, 0L);

    assertEquals("", store.renderForPrompt("quantum physics", 5));
    assertEquals("", MemoryStore.inMemory().renderForPrompt("anything", 5));
  }

  // ---- memory_update 工具 ----

  @Test
  void toolAddsMemories() throws Exception {
    MemoryStore store = MemoryStore.inMemory();
    JSONObject input = new JSONObject();
    input.put("action", "add");
    input.put(
        "memories",
        new org.json.JSONArray()
            .put(new JSONObject().put("text", "项目用 Hilt").put("tags", new org.json.JSONArray().put("di"))));

    com.tom.rv2ide.ai.tool.api.ToolResult result =
        new MemoryUpdateTool(store).execute(input, null);

    assertFalse(result.isError());
    assertEquals(1, store.size());
    assertTrue(result.getContent().contains("已保存 1 条"));
  }

  @Test
  void toolDefaultsToAddAction() throws Exception {
    MemoryStore store = MemoryStore.inMemory();
    JSONObject input = new JSONObject();
    input.put(
        "memories",
        new org.json.JSONArray().put(new JSONObject().put("text", "事实")));

    assertFalse(new MemoryUpdateTool(store).execute(input, null).isError());
    assertEquals(1, store.size());
  }

  @Test
  void toolListsMemories() throws Exception {
    MemoryStore store = MemoryStore.inMemory();
    MemoryEntry entry = store.add("已有记忆", null, 0L);

    JSONObject input = new JSONObject().put("action", "list");
    com.tom.rv2ide.ai.tool.api.ToolResult result =
        new MemoryUpdateTool(store).execute(input, null);

    assertFalse(result.isError());
    assertTrue(result.getContent().contains("已有记忆"));
    // 列出时必须带 id，否则模型无法删除
    assertTrue(result.getContent().contains(entry.getId()));
  }

  @Test
  void toolDeletesById() throws Exception {
    MemoryStore store = MemoryStore.inMemory();
    MemoryEntry entry = store.add("待删", null, 0L);

    JSONObject input = new JSONObject().put("action", "delete").put("id", entry.getId());
    com.tom.rv2ide.ai.tool.api.ToolResult result =
        new MemoryUpdateTool(store).execute(input, null);

    assertFalse(result.isError());
    assertEquals(0, store.size());
  }

  @Test
  void toolExplainsMissingIdOnDelete() throws Exception {
    // 报错要说清怎么拿到 id，否则模型会反复重试。
    JSONObject input = new JSONObject().put("action", "delete");
    com.tom.rv2ide.ai.tool.api.ToolResult result =
        new MemoryUpdateTool(MemoryStore.inMemory()).execute(input, null);

    assertTrue(result.isError());
    assertTrue(result.getContent().contains("action=list"));
  }

  @Test
  void toolRejectsUnknownActionAndListsValidOnes() throws Exception {
    JSONObject input = new JSONObject().put("action", "remove");
    com.tom.rv2ide.ai.tool.api.ToolResult result =
        new MemoryUpdateTool(MemoryStore.inMemory()).execute(input, null);

    assertTrue(result.isError());
    assertTrue(result.getContent().contains("add"));
    assertTrue(result.getContent().contains("delete"));
  }

  @Test
  void toolClears() throws Exception {
    MemoryStore store = MemoryStore.inMemory();
    store.add("a", null, 0L);
    store.add("b", null, 0L);

    JSONObject input = new JSONObject().put("action", "clear");
    assertFalse(new MemoryUpdateTool(store).execute(input, null).isError());
    assertEquals(0, store.size());
  }

  @Test
  void toolRejectsAddWithoutMemories() throws Exception {
    JSONObject input = new JSONObject().put("action", "add");
    com.tom.rv2ide.ai.tool.api.ToolResult result =
        new MemoryUpdateTool(MemoryStore.inMemory()).execute(input, null);

    assertTrue(result.isError());
  }

  @Test
  void toolRejectsBatchOverLimit() throws Exception {
    org.json.JSONArray array = new org.json.JSONArray();
    for (int i = 0; i < MemoryUpdateTool.MAX_BATCH + 1; i++) {
      array.put(new JSONObject().put("text", "x" + i));
    }
    JSONObject input = new JSONObject().put("action", "add").put("memories", array);

    assertTrue(new MemoryUpdateTool(MemoryStore.inMemory()).execute(input, null).isError());
  }

  @Test
  void toolIsAllowedInReadonlyMode() {
    // 记忆不改用户代码，只读模式下应放行。
    assertTrue(new MemoryUpdateTool(MemoryStore.inMemory()).isAllowedInReadonlyMode());
  }

  @Test
  void toolDescriptionStatesWhatNotToSave() {
    // 判断标准直接写进说明，否则模型会把一次性调试细节也存进来，
    // 而每条噪声都会进入后续每一轮提示词——污染是累积的。
    String description = new MemoryUpdateTool(MemoryStore.inMemory()).getDescription();

    assertTrue(description.contains("Do NOT save"), description);
    assertTrue(description.contains("durable"), description);
  }

  @Test
  void toolToleratesNullStore() {
    // null 存储降级为仅内存，而不是抛异常。
    JSONObject input = new JSONObject().put("action", "list");
    assertFalse(new MemoryUpdateTool(null).execute(input, null).isError());
  }
}
