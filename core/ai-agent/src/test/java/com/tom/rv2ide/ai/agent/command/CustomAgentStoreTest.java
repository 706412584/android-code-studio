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

package com.tom.rv2ide.ai.agent.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 自定义 agent 的回归测试。
 *
 * <p><b>为什么需要它</b>：名字会拼进工具名（{@code agentx_<名字>}），因此名字规整、
 * 查重、覆盖语义任何一处出错都会表现为「工具没出现」或「模型调到了另一个 agent」——
 * 静默且难归因。
 */
final class CustomAgentStoreTest {

  private static CustomAgent agent(String name, String prompt) {
    return new CustomAgent(name, "", prompt, true);
  }

  // ---- 名字规整 ----

  @Test
  void sanitizesNameIntoAToolSafeIdentifier() {
    // 名字要拼进工具名，而工具名会进入模型的 tools 定义：空格、标点、斜杠会让它
    // 难以被模型稳定复述。
    assertEquals("code_reviewer", CustomAgent.sanitizeName("code reviewer"));
    assertEquals("a_b_c", CustomAgent.sanitizeName("a.b/c"));
    assertEquals("reviewer", CustomAgent.sanitizeName("  reviewer  "));
    assertEquals("keep_under-1", CustomAgent.sanitizeName("keep_under-1"));
    assertEquals("", CustomAgent.sanitizeName(null));
    assertEquals("", CustomAgent.sanitizeName("   "));
    assertEquals("", CustomAgent.sanitizeName("!!!"));
  }

  @Test
  void sanitizePreservesChineseNames() {
    // 中文名字应当保留：用户很可能用中文命名。
    assertEquals("代码审查", CustomAgent.sanitizeName("代码审查"));
  }

  @Test
  void sanitizeDoesNotLeaveLeadingOrTrailingUnderscores() {
    // 前后下划线会产出 agentx__name 这类别扭的名字。
    assertEquals("a", CustomAgent.sanitizeName("_a_"));
    assertEquals("a_b", CustomAgent.sanitizeName(" a b "));
  }

  @Test
  void sanitizeTruncatesOverlongNames() {
    StringBuilder longName = new StringBuilder();
    for (int i = 0; i < 200; i++) {
      longName.append('a');
    }

    assertEquals(CustomAgent.MAX_NAME_CHARS, CustomAgent.sanitizeName(longName.toString()).length());
  }

  @Test
  void toolNameUsesAgentPrefix() {
    assertEquals("agentx_reviewer", agent("reviewer", "审查代码").toolName());
    assertTrue(agent("reviewer", "x").toolName().startsWith(CustomAgent.TOOL_PREFIX));
  }

  @Test
  void isValidNameRejectsUnusableInput() {
    assertTrue(CustomAgent.isValidName("reviewer"));
    assertTrue(CustomAgent.isValidName("代码审查"));
    assertFalse(CustomAgent.isValidName(""));
    assertFalse(CustomAgent.isValidName("   "));
    assertFalse(CustomAgent.isValidName("!!!"));
    assertFalse(CustomAgent.isValidName(null));
  }

  // ---- 校验 ----

  @Test
  void usableRequiresNameAndPromptAndEnabled() {
    assertTrue(agent("a", "prompt").isUsable());
    assertFalse(agent("", "prompt").isUsable());
    assertFalse(agent("a", "").isUsable());
    assertFalse(agent("a", "prompt").withEnabled(false).isUsable());
  }

  @Test
  void validationErrorExplainsWhatIsMissing() {
    assertTrue(agent("", "prompt").validationError().contains("名字"));
    assertTrue(agent("a", "").validationError().contains("提示词"));
    assertEquals("", agent("a", "prompt").validationError());
  }

  @Test
  void truncatesOverlongPrompt() {
    StringBuilder big = new StringBuilder();
    for (int i = 0; i < CustomAgent.MAX_PROMPT_CHARS + 100; i++) {
      big.append('x');
    }

    assertEquals(CustomAgent.MAX_PROMPT_CHARS, agent("a", big.toString()).getPrompt().length());
  }

  // ---- 存储 ----

  @Test
  void savesAndFindsByName() {
    CustomAgentStore store = CustomAgentStore.inMemory();

    assertEquals("", store.save(agent("reviewer", "审查代码")));
    assertEquals(1, store.size());
    assertNotNull(store.find("reviewer"));
    assertEquals("审查代码", store.find("reviewer").getPrompt());
  }

  @Test
  void findIsCaseInsensitiveAndSanitized() {
    // 用户在不同地方可能写成 "Code Reviewer" / "code reviewer"，都应命中同一个 agent。
    CustomAgentStore store = CustomAgentStore.inMemory();
    store.save(agent("code reviewer", "x"));

    assertNotNull(store.find("code_reviewer"));
    assertNotNull(store.find("CODE REVIEWER"));
    assertNotNull(store.find("Code Reviewer"));
    assertNull(store.find("nonexistent"));
    assertNull(store.find(null));
  }

  @Test
  void saveOverwritesByNameInsteadOfDuplicating() {
    // 名字就是工具的标识。同名两条定义会让模型看到的行为随机。
    CustomAgentStore store = CustomAgentStore.inMemory();
    store.save(agent("reviewer", "第一版"));
    store.save(agent("reviewer", "第二版"));

    assertEquals(1, store.size());
    assertEquals("第二版", store.find("reviewer").getPrompt());
  }

  @Test
  void rejectsInvalidAgent() {
    CustomAgentStore store = CustomAgentStore.inMemory();

    assertFalse(store.save(agent("", "prompt")).isEmpty());
    assertFalse(store.save(agent("a", "")).isEmpty());
    assertFalse(store.save(null).isEmpty());
    assertEquals(0, store.size());
  }

  @Test
  void enforcesAgentLimit() {
    CustomAgentStore store = CustomAgentStore.inMemory();
    for (int i = 0; i < CustomAgentStore.MAX_AGENTS; i++) {
      assertEquals("", store.save(agent("agent" + i, "p")));
    }

    assertFalse(store.save(agent("overflow", "p")).isEmpty());
    assertEquals(CustomAgentStore.MAX_AGENTS, store.size());
  }

  @Test
  void overwritingAtLimitIsStillAllowed() {
    // 上限只应阻止新增，不应阻止编辑已有 agent。
    CustomAgentStore store = CustomAgentStore.inMemory();
    for (int i = 0; i < CustomAgentStore.MAX_AGENTS; i++) {
      store.save(agent("agent" + i, "p"));
    }

    assertEquals("", store.save(agent("agent0", "改过的提示词")));
    assertEquals("改过的提示词", store.find("agent0").getPrompt());
  }

  @Test
  void removesByName() {
    CustomAgentStore store = CustomAgentStore.inMemory();
    store.save(agent("reviewer", "x"));

    assertTrue(store.remove("reviewer"));
    assertEquals(0, store.size());
    assertFalse(store.remove("reviewer"));
    assertFalse(store.remove(null));
  }

  @Test
  void usableFiltersDisabledAndInvalid() {
    CustomAgentStore store = CustomAgentStore.inMemory();
    store.save(agent("good", "p"));
    store.save(agent("disabled", "p").withEnabled(false));

    assertEquals(2, store.all().size());
    assertEquals(1, store.usable().size());
    assertEquals("good", store.usable().get(0).getName());
  }

  @Test
  void persistsAcrossInstances(@TempDir Path tempDir) throws Exception {
    File file = tempDir.resolve("custom_agents.json").toFile();

    CustomAgentStore first = new CustomAgentStore(file);
    first.save(new CustomAgent("reviewer", "检查 i18n", "审查时检查 i18n 字符串", true));
    first.save(new CustomAgent("tester", "生成测试", "为改动生成单测", false));

    CustomAgentStore second = new CustomAgentStore(file);
    assertEquals(2, second.size());
    assertEquals("审查时检查 i18n 字符串", second.find("reviewer").getPrompt());
    assertEquals("检查 i18n", second.find("reviewer").getDescription());
    assertFalse(second.find("tester").isEnabled());
    // 只有启用的可用
    assertEquals(1, second.usable().size());
  }

  @Test
  void persistsRemovalAcrossInstances(@TempDir Path tempDir) throws Exception {
    File file = tempDir.resolve("custom_agents.json").toFile();
    CustomAgentStore first = new CustomAgentStore(file);
    first.save(agent("keep", "p"));
    first.save(agent("drop", "p"));
    first.remove("drop");

    CustomAgentStore second = new CustomAgentStore(file);
    assertEquals(1, second.size());
    assertNotNull(second.find("keep"));
    assertNull(second.find("drop"));
  }

  @Test
  void survivesCorruptFile(@TempDir Path tempDir) throws Exception {
    File file = tempDir.resolve("custom_agents.json").toFile();
    Files.write(file.toPath(), "not json".getBytes(StandardCharsets.UTF_8));

    // 配置损坏 → 空配置，不抛异常。自定义 agent 是扩展能力，不该让 AI 不可用。
    assertEquals(0, new CustomAgentStore(file).size());
  }

  @Test
  void skipsEntriesWithoutNameOnLoad(@TempDir Path tempDir) throws Exception {
    File file = tempDir.resolve("custom_agents.json").toFile();
    Files.write(
        file.toPath(),
        ("[{\"name\":\"\",\"prompt\":\"x\"},{\"name\":\"good\",\"prompt\":\"y\"}]")
            .getBytes(StandardCharsets.UTF_8));

    CustomAgentStore store = new CustomAgentStore(file);
    assertEquals(1, store.size());
    assertNotNull(store.find("good"));
  }

  @Test
  void clearRemovesEverything() {
    CustomAgentStore store = CustomAgentStore.inMemory();
    store.save(agent("a", "p"));
    store.save(agent("b", "p"));

    store.clear();
    assertEquals(0, store.size());
    assertTrue(store.usable().isEmpty());
  }

  @Test
  void inMemoryStoreDoesNotPersist() {
    // 无文件 → 写入只落内存。这条确认降级路径不会抛异常。
    CustomAgentStore store = CustomAgentStore.inMemory();
    assertEquals("", store.save(agent("a", "p")));
    assertNotNull(store.find("a"));
  }
}
