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

package com.tom.rv2ide.ai.agent.builtin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tom.rv2ide.ai.tool.api.ToolNames;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 内置子 agent 的回归测试。
 *
 * <p><b>为什么需要它</b>：内置 agent 的两类失败都很隐蔽——id 改名会让用户既有覆盖失效
 * 且无法察觉；只读角色的工具白名单里混进一个写工具，会让「审查不会改文件」这一保证
 * 静默失效。两者在真机上都只表现为「AI 行为不对」，很难归因。
 */
final class BuiltinAgentsTest {

  @Test
  void hasExpectedPresetsWithUniqueStableIds() {
    List<BuiltinAgent> all = BuiltinAgents.all();
    assertEquals(5, all.size());

    Set<String> ids = new HashSet<>();
    for (BuiltinAgent agent : all) {
      assertFalse(agent.getId().isEmpty(), "id 不能为空");
      assertTrue(ids.add(agent.getId()), "id 重复: " + agent.getId());
      assertTrue(agent.isUsable(), agent.getId() + " 应当可用");
    }
    // id 是稳定契约：改名等于让用户既有覆盖失效。
    assertEquals("code-reviewer", BuiltinAgents.CODE_REVIEWER);
    assertEquals("explore", BuiltinAgents.EXPLORE);
    assertEquals("debugger", BuiltinAgents.DEBUGGER);
    assertEquals("test-author", BuiltinAgents.TEST_AUTHOR);
    assertEquals("docs-writer", BuiltinAgents.DOCS_WRITER);
  }

  @Test
  void findResolvesKnownAndRejectsUnknown() {
    assertNotNull(BuiltinAgents.find("code-reviewer"));
    assertNotNull(BuiltinAgents.find("CODE-REVIEWER"), "id 大小写不敏感");
    assertNull(BuiltinAgents.find("no-such-agent"));
    assertNull(BuiltinAgents.find(null));
    assertNull(BuiltinAgents.find(""));
  }

  @Test
  void readOnlyRolesHaveNoWriteTools() {
    // 只读保证靠工具白名单，不靠提示词。白名单里混进写工具 = 保证失效。
    for (String id :
        new String[] {
          BuiltinAgents.CODE_REVIEWER, BuiltinAgents.EXPLORE, BuiltinAgents.DEBUGGER
        }) {
      BuiltinAgent agent = BuiltinAgents.find(id);
      assertTrue(agent.isReadOnly(), id + " 应当是只读角色");
      for (String tool : agent.getTools()) {
        assertFalse(
            ToolNames.FILE_WRITE.equals(tool)
                || ToolNames.FILE_EDIT.equals(tool)
                || ToolNames.FILE_DELETE.equals(tool),
            id + " 的只读白名单里不应有写工具: " + tool);
      }
    }
  }

  @Test
  void writeRolesAreNotMarkedReadOnly() {
    for (String id : new String[] {BuiltinAgents.TEST_AUTHOR, BuiltinAgents.DOCS_WRITER}) {
      BuiltinAgent agent = BuiltinAgents.find(id);
      assertFalse(agent.isReadOnly(), id + " 需要写文件");
      assertTrue(agent.restrictsTools());
      assertTrue(agent.getTools().contains(ToolNames.FILE_EDIT), id + " 应能编辑文件");
    }
  }

  @Test
  void everyPresetHasDescriptionPromptAndTools() {
    for (BuiltinAgent agent : BuiltinAgents.all()) {
      assertFalse(agent.getName().isEmpty(), agent.getId() + " 缺展示名");
      assertFalse(agent.getDescription().isEmpty(), agent.getId() + " 缺描述（模型据此判断何时用）");
      assertTrue(
          agent.getPrompt().length() >= BuiltinAgent.MIN_PROMPT_CHARS,
          agent.getId() + " 提示词过短");
      assertTrue(agent.restrictsTools(), agent.getId() + " 应有明确工具白名单");
    }
  }

  @Test
  void toolNameUsesStablePrefix() {
    assertEquals("agentb_code-reviewer", BuiltinAgents.find(BuiltinAgents.CODE_REVIEWER).toolName());
    assertEquals("agentb_", BuiltinAgents.TOOL_PREFIX);
  }

  @Test
  void defaultPromptAndToolsAreRetrievableById() {
    assertEquals(
        BuiltinAgents.find(BuiltinAgents.CODE_REVIEWER).getPrompt(),
        BuiltinAgents.defaultPrompt(BuiltinAgents.CODE_REVIEWER));
    assertEquals("", BuiltinAgents.defaultPrompt("nope"));
    assertEquals(
        BuiltinAgents.find(BuiltinAgents.EXPLORE).getTools(),
        BuiltinAgents.defaultTools(BuiltinAgents.EXPLORE));
    assertTrue(BuiltinAgents.defaultTools("nope").isEmpty());
  }

  @Test
  void sanitizeIdKeepsStableCharsAndNormalizesSeparators() {
    assertEquals("code-reviewer", BuiltinAgent.sanitizeId("code-reviewer"));
    assertEquals("code-reviewer", BuiltinAgent.sanitizeId("Code Reviewer"));
    assertEquals("a-b", BuiltinAgent.sanitizeId(" a.b "));
    assertEquals("", BuiltinAgent.sanitizeId(null));
    // 中文会被丢弃（id 要能拼进工具名）
    assertEquals("reviewer", BuiltinAgent.sanitizeId("审查 reviewer"));
  }

  @Test
  void validationRejectsEmptyNameOrShortPrompt() {
    BuiltinAgent noName =
        new BuiltinAgent("x", "", "d", "这是一个足够长的提示词用来通过校验的测试文本。", null, true);
    assertFalse(noName.validationError().isEmpty());

    BuiltinAgent shortPrompt = new BuiltinAgent("x", "n", "d", "太短", null, true);
    assertFalse(shortPrompt.validationError().isEmpty());
    assertFalse(shortPrompt.isUsable());
  }

  @Test
  void emptyToolListMeansUnrestrictedAndNotReadOnly() {
    // 空白名单 = 不限制，而不是「零个工具」。语义区分很重要：
    // 误判成只读会让一个通用 agent 被错误地当成不会写文件。
    BuiltinAgent open =
        new BuiltinAgent("general", "通用", "d", "这是一个足够长的提示词用来通过校验的测试文本。", null, true);
    assertFalse(open.restrictsTools());
    assertFalse(open.isReadOnly());
  }

  // ---- BuiltinAgentStore ----

  @Test
  void storeReturnsPresetsWhenNoOverride() {
    BuiltinAgentStore store = BuiltinAgentStore.inMemory();
    assertEquals(BuiltinAgents.all().size(), store.all().size());
    assertEquals(BuiltinAgents.all().size(), store.enabled().size());
    assertFalse(store.isCustomized(BuiltinAgents.CODE_REVIEWER));
  }

  @Test
  void storeMergesUserOverride() {
    BuiltinAgentStore store = BuiltinAgentStore.inMemory();
    BuiltinAgent preset = BuiltinAgents.find(BuiltinAgents.CODE_REVIEWER);
    BuiltinAgent edited = preset.withPrompt("这是一个足够长的自定义审查提示词用于测试覆盖。");

    assertEquals("", store.save(edited));
    assertTrue(store.isCustomized(BuiltinAgents.CODE_REVIEWER));
    assertEquals("这是一个足够长的自定义审查提示词用于测试覆盖。", store.find(BuiltinAgents.CODE_REVIEWER).getPrompt());
    // 其它预设不受影响
    assertFalse(store.isCustomized(BuiltinAgents.EXPLORE));
  }

  @Test
  void resetRestoresPresetDefault() {
    BuiltinAgentStore store = BuiltinAgentStore.inMemory();
    BuiltinAgent preset = BuiltinAgents.find(BuiltinAgents.EXPLORE);
    store.save(preset.withPrompt("这是一个足够长的自定义探索提示词用于测试覆盖。"));

    assertEquals("", store.reset(BuiltinAgents.EXPLORE));
    assertFalse(store.isCustomized(BuiltinAgents.EXPLORE));
    assertEquals(preset.getPrompt(), store.find(BuiltinAgents.EXPLORE).getPrompt());
  }

  @Test
  void resetUnknownIdReportsError() {
    BuiltinAgentStore store = BuiltinAgentStore.inMemory();
    assertFalse(store.reset("no-such-agent").isEmpty());
    assertFalse(store.reset(null).isEmpty());
  }

  @Test
  void saveRejectsUnknownIdAndInvalidAgent() {
    BuiltinAgentStore store = BuiltinAgentStore.inMemory();
    BuiltinAgent ghost =
        new BuiltinAgent("ghost", "幽灵", "d", "这是一个足够长的提示词用来通过校验的测试文本。", null, true);
    assertFalse(store.save(ghost).isEmpty(), "未知 id 不得写入");
    assertFalse(store.save(null).isEmpty());

    BuiltinAgent preset = BuiltinAgents.find(BuiltinAgents.CODE_REVIEWER);
    assertFalse(store.save(preset.withPrompt("短")).isEmpty(), "过短提示词不得写入");
  }

  @Test
  void savingDefaultEquivalentDropsOverride() {
    // 改回默认值 = 恢复默认，不应留下冗余覆盖记录。
    BuiltinAgentStore store = BuiltinAgentStore.inMemory();
    BuiltinAgent preset = BuiltinAgents.find(BuiltinAgents.DEBUGGER);
    store.save(preset.withPrompt("这是一个足够长的自定义定位提示词用于测试覆盖。"));
    assertTrue(store.isCustomized(BuiltinAgents.DEBUGGER));

    store.save(preset);
    assertFalse(store.isCustomized(BuiltinAgents.DEBUGGER));
  }

  @Test
  void disabledAgentIsExcludedFromEnabled() {
    BuiltinAgentStore store = BuiltinAgentStore.inMemory();
    BuiltinAgent preset = BuiltinAgents.find(BuiltinAgents.DOCS_WRITER);
    store.save(preset.withEnabled(false));

    List<String> enabledIds = new ArrayList<>();
    for (BuiltinAgent agent : store.enabled()) {
      enabledIds.add(agent.getId());
    }
    assertFalse(enabledIds.contains(BuiltinAgents.DOCS_WRITER));
    assertTrue(enabledIds.contains(BuiltinAgents.CODE_REVIEWER));
  }
}
