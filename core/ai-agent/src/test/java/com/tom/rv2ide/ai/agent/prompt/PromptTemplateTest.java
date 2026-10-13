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

package com.tom.rv2ide.ai.agent.prompt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tom.rv2ide.ai.agent.AgentPromptBuilder;
import com.tom.rv2ide.ai.tool.api.ToolCategory;
import com.tom.rv2ide.ai.tool.api.ToolInfo;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * 提示词模板系统的回归测试。
 *
 * <p><b>为什么需要它</b>：模板渲染的失败模式都是静默的——占位符拼错只会变成空串，
 * 多趟替换会让用户代码里的字符串被意外展开，模式约束没生效会让「纯对话模式」实际上
 * 仍然去改文件。这些在真机上都表现为「AI 行为莫名其妙」，很难归因到模板。
 */
final class PromptTemplateTest {

  private static Map<String, String> values(String... keyValuePairs) {
    Map<String, String> map = new HashMap<>();
    for (int i = 0; i < keyValuePairs.length; i += 2) {
      map.put(keyValuePairs[i], keyValuePairs[i + 1]);
    }
    return map;
  }

  // ---- PromptRenderer ----

  @Test
  void rendersKnownPlaceholders() {
    String result =
        PromptRenderer.render("Hello {{NAME}}, mode={{MODE}}", values("NAME", "world", "MODE", "agent"));
    assertEquals("Hello world, mode=agent", result);
  }

  @Test
  void unprovidedPlaceholderBecomesEmptyNotLiteral() {
    // 用户删掉某段可选内容时，希望那一段整体消失，而不是留下看不懂的标记。
    assertEquals("before  after", PromptRenderer.render("before {{MISSING}} after", values()));
    assertEquals("", PromptRenderer.render("{{MISSING}}", values()));
  }

  @Test
  void nullValueBecomesEmpty() {
    Map<String, String> map = new HashMap<>();
    map.put("X", null);
    assertEquals("[]", PromptRenderer.render("[{{X}}]", map));
  }

  @Test
  void substitutionIsSinglePassSoInsertedTextIsNotReExpanded() {
    // 替换进去的内容里若含 {{...}}（用户代码里就有这种字符串），不得被二次展开。
    // 多趟替换会让提示词内容可被数据影响——这是注入风险，不只是意外。
    String result =
        PromptRenderer.render("{{A}}", values("A", "literal {{B}} here", "B", "EXPANDED"));

    assertEquals("literal {{B}} here", result);
    assertFalse(result.contains("EXPANDED"));
  }

  @Test
  void handlesPlaceholdersWithSurroundingSpaces() {
    assertEquals("x", PromptRenderer.render("{{  A  }}", values("A", "x")));
  }

  @Test
  void emptyBracesAreNotPlaceholders() {
    // "{{}}" 里的名字为空 → 视为未提供 → 空串。不能抛异常。
    assertEquals("", PromptRenderer.render("{{}}", values()));
  }

  @Test
  void nullAndEmptyTemplateReturnEmpty() {
    assertEquals("", PromptRenderer.render(null, values()));
    assertEquals("", PromptRenderer.render("", values()));
    assertEquals("", PromptRenderer.render("   ", values()).trim());
  }

  @Test
  void templateWithNoPlaceholdersIsUnchanged() {
    assertEquals("plain text", PromptRenderer.render("plain text", values()));
  }

  @Test
  void unknownPlaceholdersAreReported() {
    List<String> unknown =
        PromptRenderer.unknownPlaceholders("{{HOME_PATH}} {{TYPO_HERE}} {{ALSO_BAD}} {{TYPO_HERE}}");

    assertEquals(2, unknown.size(), "重复的未知占位符应去重");
    assertTrue(unknown.contains("TYPO_HERE"));
    assertTrue(unknown.contains("ALSO_BAD"));
    assertFalse(unknown.contains("HOME_PATH"));
  }

  @Test
  void unknownPlaceholdersIsEmptyForCleanTemplate() {
    assertTrue(PromptRenderer.unknownPlaceholders("{{HOME_PATH}} {{TODO_LIST}}").isEmpty());
    assertTrue(PromptRenderer.unknownPlaceholders(null).isEmpty());
  }

  @Test
  void unprovidedKnownPlaceholdersAreReported() {
    List<String> missing =
        PromptRenderer.unprovidedPlaceholders("{{HOME_PATH}} {{TODO_LIST}}", values("HOME_PATH", "/w"));

    assertEquals(1, missing.size());
    assertEquals("TODO_LIST", missing.get(0));
  }

  // ---- {{DEFAULT}} 引用 ----

  @Test
  void expandDefaultReplacesReferenceAndKeepsOtherPlaceholders() {
    // 展开只处理 {{DEFAULT}}，其余占位符原样保留（由外层渲染统一替换）。
    String result = PromptRenderer.expandDefault("{{DEFAULT}}\n\n额外：{{TODO_LIST}}", "默认内容");

    assertEquals("默认内容\n\n额外：{{TODO_LIST}}", result);
  }

  @Test
  void expandDefaultIsSinglePassAndNonRecursive() {
    // 默认内容里若含 {{DEFAULT}}（理论上不会，但要防注入），不得被二次展开。
    String result = PromptRenderer.expandDefault("{{DEFAULT}}", "含 {{DEFAULT}} 的默认内容");

    assertEquals("含 {{DEFAULT}} 的默认内容", result);
  }

  @Test
  void expandDefaultReturnsTemplateUnchangedWhenNoReference() {
    String template = "只有 {{TODO_LIST}}，没有引用";
    assertEquals(template, PromptRenderer.expandDefault(template, "默认内容"));
    assertEquals("", PromptRenderer.expandDefault(null, "x"));
    assertEquals("", PromptRenderer.expandDefault("", "x"));
  }

  @Test
  void expandDefaultHandlesNullDefaultContent() {
    assertEquals("前后", PromptRenderer.expandDefault("前{{DEFAULT}}后", null));
  }

  @Test
  void resolveExpandsDefaultReferenceInCustomTemplate() {
    // 用户写「{{DEFAULT}} + 补充」，resolve 应展开为当前默认内容 + 补充。
    PromptTemplateStore store = PromptTemplateStore.inMemory();
    store.write(PromptTemplates.NOTES, "{{DEFAULT}}\n- 额外要求：回复用英文。");

    String resolved = store.resolve(PromptTemplates.NOTES);
    assertTrue(resolved.startsWith(PromptTemplates.defaultNotes()));
    assertTrue(resolved.endsWith("- 额外要求：回复用英文。"));
    assertFalse(resolved.contains("{{DEFAULT}}"));
  }

  @Test
  void resolveKeepsPlainCustomTemplateIntact() {
    // 不含引用的自定义模板行为不变。
    PromptTemplateStore store = PromptTemplateStore.inMemory();
    store.write(PromptTemplates.NOTES, "完全自定义");

    assertEquals("完全自定义", store.resolve(PromptTemplates.NOTES));
  }

  @Test
  void defaultPlaceholderIsRegisteredAndDescribed() {
    // 未注册会让设置界面把它当拼写错误；无说明会在占位符清单里显示为空。
    assertTrue(PromptPlaceholders.isKnown(PromptPlaceholders.DEFAULT));
    assertFalse(PromptPlaceholders.describe(PromptPlaceholders.DEFAULT).isEmpty());
  }

  @Test
  void tidyRemovesTrailingWhitespaceAndCollapsesBlankLines() {
    // 连续空行压成一个：段落之间保留一个空行，多出来的去掉。
    String messy = "line one   \n\n\n\nline two\t\n\n";
    assertEquals("line one\n\nline two", PromptRenderer.tidy(messy));
  }

  @Test
  void tidyKeepsSingleBlankLinesBetweenParagraphs() {
    // 段落之间保留一个空行是可读性的一部分，不能全压掉。
    assertEquals("a\n\nb", PromptRenderer.tidy("a\n\n\n\n\nb"));
  }

  @Test
  void renderAndTidyProducesCleanOutputWhenSectionsAreEmpty() {
    // 占位符变空串后留下空行；整理后不出现连续空行与行尾空白。
    String template = "head\n{{A}}\n{{B}}\ntail";
    String result = PromptRenderer.renderAndTidy(template, values("A", "", "B", ""));

    assertEquals("head\n\ntail", result);
    assertFalse(result.contains("   "));
    assertFalse(result.startsWith("\n"));
    assertFalse(result.endsWith("\n"));
  }

  // ---- PromptPlaceholders ----

  @Test
  void everyDefaultTemplatePlaceholderIsRegistered() {
    // 默认模板里出现未注册的占位符，会让它在渲染时静默变成空串——
    // 用户看到的是「默认提示词缺了一段」，而没有任何报错。
    String[] defaultTemplates = {
      PromptTemplates.defaultSystemPrompt(),
      PromptTemplates.defaultWorkspaceContext(),
      PromptTemplates.defaultToolsContext(),
      PromptTemplates.defaultToolCallFormat(),
      PromptTemplates.defaultTodoSection(),
      PromptTemplates.defaultGuidance(),
      PromptTemplates.defaultNotes(),
      PromptTemplates.defaultChatModeContext(ChatMode.CHAT),
      PromptTemplates.defaultChatModeContext(ChatMode.PLAN),
      PromptTemplates.defaultChatModeContext(ChatMode.AGENT),
      PromptTemplates.defaultChatModeContext(ChatMode.CONTROL),
    };
    for (String template : defaultTemplates) {
      List<String> unknown = PromptRenderer.unknownPlaceholders(template);
      assertTrue(unknown.isEmpty(), "默认模板含未注册占位符 " + unknown + " in: " + template);
    }
  }

  @Test
  void placeholderDescriptionsExistForAll() {
    for (String name : PromptPlaceholders.all()) {
      assertFalse(
          PromptPlaceholders.describe(name).isEmpty(), "占位符 " + name + " 缺少说明");
    }
  }

  // ---- ChatMode ----

  @Test
  void chatModeIdsAreStableAndUnique() {
    // id 会写进用户偏好，改名等于让既有配置失效。
    assertEquals("chat", ChatMode.CHAT.getId());
    assertEquals("plan", ChatMode.PLAN.getId());
    assertEquals("agent", ChatMode.AGENT.getId());
    assertEquals("control", ChatMode.CONTROL.getId());
    assertEquals(4, ChatMode.allIds().size());
  }

  @Test
  void chatModeFallsBackToDefaultForUnknownValue() {
    assertEquals(ChatMode.DEFAULT, ChatMode.fromId(null));
    assertEquals(ChatMode.DEFAULT, ChatMode.fromId(""));
    assertEquals(ChatMode.DEFAULT, ChatMode.fromId("nonsense"));
    assertEquals(ChatMode.PLAN, ChatMode.fromId("PLAN"));
  }

  @Test
  void onlyChatModeDisallowsTools() {
    assertFalse(ChatMode.CHAT.allowsTools());
    assertTrue(ChatMode.PLAN.allowsTools());
    assertTrue(ChatMode.AGENT.allowsTools());
    assertTrue(ChatMode.CONTROL.allowsTools());
  }

  @Test
  void onlyAgentAndControlAllowModification() {
    // 供 UI 提示「当前模式不会改动你的代码」。
    assertFalse(ChatMode.CHAT.allowsModification());
    assertFalse(ChatMode.PLAN.allowsModification());
    assertTrue(ChatMode.AGENT.allowsModification());
    assertTrue(ChatMode.CONTROL.allowsModification());
  }

  @Test
  void chatModeTemplateIdsAreCamelCase() {
    assertEquals("chatModeChat", PromptTemplates.chatModeTemplateId(ChatMode.CHAT));
    assertEquals("chatModePlan", PromptTemplates.chatModeTemplateId(ChatMode.PLAN));
    assertEquals("chatModeAgent", PromptTemplates.chatModeTemplateId(ChatMode.AGENT));
    assertEquals("chatModeControl", PromptTemplates.chatModeTemplateId(ChatMode.CONTROL));
    // null 回退到默认模式而不是抛异常
    assertEquals("chatModeAgent", PromptTemplates.chatModeTemplateId(null));
  }

  @Test
  void chatModeTemplatesRoundTripThroughDefaultFor() {
    for (ChatMode mode : ChatMode.values()) {
      String id = PromptTemplates.chatModeTemplateId(mode);
      assertEquals(
          PromptTemplates.defaultChatModeContext(mode),
          PromptTemplates.defaultFor(id),
          "模式 " + mode + " 的模板 ID 无法反查默认内容");
    }
  }

  // ---- PromptTemplateStore ----

  @Test
  void storeResolvesCustomOverDefault() {
    PromptTemplateStore store = PromptTemplateStore.inMemory();
    assertEquals(PromptTemplates.defaultNotes(), store.resolve(PromptTemplates.NOTES));
    assertFalse(store.isCustomized(PromptTemplates.NOTES));

    store.write(PromptTemplates.NOTES, "custom notes");
    assertEquals("custom notes", store.resolve(PromptTemplates.NOTES));
    assertTrue(store.isCustomized(PromptTemplates.NOTES));

    store.reset(PromptTemplates.NOTES);
    assertEquals(PromptTemplates.defaultNotes(), store.resolve(PromptTemplates.NOTES));
    assertFalse(store.isCustomized(PromptTemplates.NOTES));
  }

  @Test
  void writingBlankContentIsTreatedAsReset() {
    // 否则会出现 isCustomized=true 但 resolve 又走默认的中间态，UI 显示矛盾。
    PromptTemplateStore store = PromptTemplateStore.inMemory();
    store.write(PromptTemplates.NOTES, "custom");
    store.write(PromptTemplates.NOTES, "   ");

    assertFalse(store.isCustomized(PromptTemplates.NOTES));
    assertEquals(PromptTemplates.defaultNotes(), store.resolve(PromptTemplates.NOTES));
  }

  @Test
  void defaultStoreIgnoresWrites() {
    PromptTemplateStore store = PromptTemplateStore.defaults();
    store.write(PromptTemplates.NOTES, "custom");

    assertFalse(store.isCustomized(PromptTemplates.NOTES));
    assertEquals(PromptTemplates.defaultNotes(), store.resolve(PromptTemplates.NOTES));
  }

  @Test
  void unknownTemplateIdResolvesToEmpty() {
    assertEquals("", PromptTemplateStore.inMemory().resolve("no-such-template"));
  }

  // ---- AgentPromptBuilder ----

  private static final class FakeTool implements ToolInfo {
    private final String name;

    FakeTool(String name) {
      this.name = name;
    }

    @Override
    public String getName() {
      return name;
    }

    @Override
    public String getDescription() {
      return "does " + name;
    }

    @Override
    public ToolCategory getCategory() {
      return ToolCategory.READ;
    }

    @Override
    public boolean needsConfirmation() {
      return false;
    }

    @Override
    public JSONObject getParameters() {
      return new JSONObject();
    }

    @Override
    public JSONObject toJson() {
      return new JSONObject();
    }

    @Override
    public String promptSupplement(String executionMode) {
      return null;
    }
  }

  private static List<ToolInfo> tools(String... names) {
    List<ToolInfo> list = new ArrayList<>();
    for (String name : names) {
      list.add(new FakeTool(name));
    }
    return list;
  }

  @Test
  void builderRendersDefaultPromptWithTools() {
    AgentPromptBuilder builder = new AgentPromptBuilder("TestAgent");
    String prompt = builder.build("/workspace", tools("file_read", "file_write"), true);

    assertTrue(prompt.contains("TestAgent"));
    assertTrue(prompt.contains("/workspace"));
    assertTrue(prompt.contains("file_read"));
    assertTrue(prompt.contains("file_write"));
    assertTrue(prompt.contains("执行"), "默认模式为执行");
  }

  @Test
  void builderOmitsXmlFormatForNativeToolCallingEndpoints() {
    // 对支持原生工具调用的端点注入 XML 说明会显著诱导模型改用文本调用（实测成功率
    // 从 8/8 掉到 1/6）。
    AgentPromptBuilder builder = new AgentPromptBuilder("A");
    String nativePrompt = builder.build("/w", tools("file_read"), true);
    String textPrompt = builder.build("/w", tools("file_read"), false);

    assertFalse(nativePrompt.contains("<tool_calls>"));
    assertTrue(textPrompt.contains("<tool_calls>"));
  }

  @Test
  void chatModeHidesToolsAndForbidsToolUse() {
    // 纯对话模式若仍然给出工具清单，模型会「顺手」去改文件，模式形同虚设。
    AgentPromptBuilder builder = new AgentPromptBuilder("A");
    String prompt =
        builder.build(
            "/w",
            tools("file_read", "file_write"),
            true,
            null,
            ChatMode.CHAT,
            new AgentPromptBuilder.ModelInfo("deepseek", "deepseek-chat", "OpenAI"));

    assertFalse(prompt.contains("file_read"), "对话模式不应列出工具");
    assertTrue(prompt.contains("不要调用任何工具"));
  }

  @Test
  void planModeAllowsToolsButForbidsModification() {
    AgentPromptBuilder builder = new AgentPromptBuilder("A");
    String prompt =
        builder.build(
            "/w",
            tools("file_read"),
            true,
            null,
            ChatMode.PLAN,
            new AgentPromptBuilder.ModelInfo("deepseek", "deepseek-chat", "OpenAI"));

    assertTrue(prompt.contains("file_read"), "计划模式应能读文件");
    assertTrue(prompt.contains("不要修改任何文件"));
  }

  @Test
  void todoStateIsInjectedOnlyWhenPresent() {
    AgentPromptBuilder builder = new AgentPromptBuilder("A");

    String without =
        builder.build("/w", tools("t"), true, null, ChatMode.AGENT, null);
    String blank =
        builder.build("/w", tools("t"), true, "   ", ChatMode.AGENT, null);
    String with =
        builder.build("/w", tools("t"), true, "[ ] step one", ChatMode.AGENT, null);

    assertFalse(without.contains("当前待办"));
    assertFalse(blank.contains("当前待办"));
    assertTrue(with.contains("当前待办"));
    assertTrue(with.contains("step one"));
  }

  @Test
  void modelInfoIsRendered() {
    AgentPromptBuilder builder = new AgentPromptBuilder("A");
    String prompt =
        builder.build(
            "/w",
            tools("t"),
            true,
            null,
            ChatMode.AGENT,
            new AgentPromptBuilder.ModelInfo("deepseek", "deepseek-chat", "OpenAI"));

    // 默认模板未引用 MODEL_*，但自定义模板可能引用；这里验证不抛异常且能渲染。
    assertNotNull(prompt);
  }

  @Test
  void customTemplateOverridesDefault() {
    // 核心验收：改模板不必重新编译。
    PromptTemplateStore store = PromptTemplateStore.inMemory();
    store.write(PromptTemplates.NOTES, "CUSTOM-NOTES-MARKER {{HOME_PATH}}");

    AgentPromptBuilder builder = new AgentPromptBuilder("A", store);
    String prompt = builder.build("/my-workspace", tools("t"), true);

    assertTrue(prompt.contains("CUSTOM-NOTES-MARKER"));
    assertTrue(prompt.contains("/my-workspace"), "自定义模板里的占位符也要被渲染");
    assertFalse(prompt.contains("{{HOME_PATH}}"));
  }

  // ---- {{DEFAULT}} 端到端（主场景） ----

  @Test
  void defaultReferenceInCustomSystemPromptExpandsEndToEnd() {
    // 主场景：用户在系统提示词模板写「{{DEFAULT}} + 补充」，构建出的最终提示词
    // 应包含默认文本的全部填充结果（身份/路径/工具清单），且无任何占位符残留。
    PromptTemplateStore store = PromptTemplateStore.inMemory();
    store.write(
        PromptTemplates.SYSTEM_PROMPT, "{{DEFAULT}}\n\n[ 我的补充 ]\n- 额外要求：用英文回复。");

    AgentPromptBuilder builder = new AgentPromptBuilder("MyBot", store);
    String prompt = builder.build("/ws", tools("file_read"), true);

    // 默认文本展开并被渲染：身份、路径、工具都在
    assertTrue(prompt.contains("MyBot"));
    assertTrue(prompt.contains("/ws"));
    assertTrue(prompt.contains("file_read"));
    // 用户的追加段落在
    assertTrue(prompt.contains("额外要求：用英文回复。"));
    // 引用已展开、无残留
    assertFalse(prompt.contains("{{DEFAULT}}"));
    // 展开进来的默认文本里的占位符也全部被外层渲染填充，不留字面量
    assertFalse(prompt.contains("{{MODEL_IDENTITY}}"));
    assertFalse(prompt.contains("{{GUIDANCE_SECTION}}"));
    assertFalse(prompt.contains("{{TOOLS_CONTEXT}}"));
  }

  @Test
  void sectionTemplatesCanReferenceLaterAssignedAtomicValues() {
    // 顺序依赖回归（2026-10-14 修复）：两阶段构建后，工作区段引用 CHAT_MODE
    // （赋值顺序在它之后的原子值）不再静默变空串。此前 WORKSPACE_CONTEXT 渲染
    // 发生在 CHAT_MODE put 之前，同一种写法在不同段落有的生效有的不生效。
    PromptTemplateStore store = PromptTemplateStore.inMemory();
    store.write(
        PromptTemplates.WORKSPACE_CONTEXT,
        "WS[{{CHAT_MODE}}] tools[{{TOOL_LIST}}] todo[{{TODO_LIST}}]");

    AgentPromptBuilder builder = new AgentPromptBuilder("A", store);
    String prompt =
        builder.build(
            "/w",
            tools("file_read"),
            true,
            "[ ] step",
            ChatMode.AGENT,
            new AgentPromptBuilder.ModelInfo("p", "m", "t"));

    assertTrue(prompt.contains("WS[agent]"), "CHAT_MODE 应可被工作区段引用");
    assertTrue(prompt.contains("tools[- file_read"), "TOOL_LIST 应可被工作区段引用");
    assertTrue(prompt.contains("todo[[ ] step]"), "TODO_LIST 应可被工作区段引用");
  }

  @Test
  void expandDefaultEscapesRegexSpecialCharacters() {
    // 默认内容（或其中间产物）含 $ 与 \ 时，appendReplacement 的引用语义
    // 必须经 quoteReplacement 转义——否则 $ 会被当成分组引用抛异常或吞字符。
    String result = PromptRenderer.expandDefault("前{{DEFAULT}}后", "价格 $5 \\ 完成");
    assertEquals("前价格 $5 \\ 完成后", result);
  }

  @Test
  void fullyCustomSystemPromptIsUsed() {
    PromptTemplateStore store = PromptTemplateStore.inMemory();
    store.write(PromptTemplates.SYSTEM_PROMPT, "ONLY {{MODEL_IDENTITY}} at {{HOME_PATH}}");

    AgentPromptBuilder builder = new AgentPromptBuilder("MyBot", store);
    String prompt = builder.build("/ws", tools("t"), true);

    assertEquals("ONLY MyBot at /ws", prompt);
  }

  @Test
  void builderHandlesNullAndEmptyInputs() {
    AgentPromptBuilder builder = new AgentPromptBuilder(null);
    assertNotNull(builder.build(null, null, false));
    assertNotNull(builder.build("", Collections.<ToolInfo>emptyList(), true));
    // 无工具时应给出明确说明而不是留空
    assertTrue(builder.build("/w", null, true).contains("没有可用工具"));
  }

  @Test
  void defaultTemplatesAreNonEmpty() {
    assertFalse(PromptTemplates.defaultSystemPrompt().isEmpty());
    assertFalse(PromptTemplates.defaultWorkspaceContext().isEmpty());
    assertFalse(PromptTemplates.defaultToolsContext().isEmpty());
    assertFalse(PromptTemplates.defaultToolCallFormat().isEmpty());
    assertFalse(PromptTemplates.defaultTodoSection().isEmpty());
    assertFalse(PromptTemplates.defaultGuidance().isEmpty());
    assertFalse(PromptTemplates.defaultNotes().isEmpty());
  }

  // ---- 工作准则段落 ----

  @Test
  void defaultPromptIncludesGuidanceSection() {
    // 第二轮优化：默认系统提示词必须带上工作准则（验证纪律、安全边界、子代理时机），
    // 否则模型仍会「改完就算完成」、不问就做破坏性操作、该委派时硬读文件。
    AgentPromptBuilder builder = new AgentPromptBuilder("A");
    String prompt = builder.build("/w", tools("file_read"), true);

    assertTrue(prompt.contains("工作准则"), prompt);
    assertTrue(prompt.contains("验证"), "应包含验证纪律");
    assertTrue(prompt.contains("子代理"), "应包含子代理使用时机");
  }

  @Test
  void guidanceSectionIsIndependentlyCustomizable() {
    // 准则单独成段的意义：用户可以只替换它，而保留工具清单等其它段落。
    PromptTemplateStore store = PromptTemplateStore.inMemory();
    store.write(PromptTemplates.GUIDANCE_SECTION, "CUSTOM-GUIDANCE {{HOME_PATH}}");

    AgentPromptBuilder builder = new AgentPromptBuilder("A", store);
    String prompt = builder.build("/ws", tools("file_read"), true);

    assertTrue(prompt.contains("CUSTOM-GUIDANCE"));
    assertFalse(prompt.contains("{{HOME_PATH}}"), "自定义段落里的占位符也要被渲染");
    // 其它段落仍在，说明只替换了准则这一段。
    assertTrue(prompt.contains("file_read"));
  }

  @Test
  void guidanceSectionResolvesByTemplateId() {
    // 模板 ID 是稳定契约：guidance 必须能通过 defaultFor 反查，否则设置界面改不了它。
    assertEquals(
        PromptTemplates.defaultGuidance(), PromptTemplates.defaultFor(PromptTemplates.GUIDANCE_SECTION));
    assertEquals(PromptTemplates.GUIDANCE_SECTION, "guidance");
  }
}
