/*
 * This file is part of AndroidCodeStudio.
 *
 * AndroidCodeStudio is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AndroidCodeStudio is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.ai.tool.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * 钉住工具 API 各数据类/枚举/默认方法的可观察契约：分类回退规则、扩展工具名前缀判定、
 * 空值处理、以及接口默认实现（如卡片视图的增量更新回退到完整绑定）。
 */
public final class ToolApiContractTest {

  // ---- ToolDisplayCategory ----

  @Test
  public void phonePrefixFallsBackToPhoneControl() {
    assertEquals(ToolDisplayCategory.PHONE_CONTROL,
        ToolDisplayCategory.fallbackDisplayCategory("phone_click"));
  }

  @Test
  public void agentNamesFallBackToAgent() {
    assertEquals(ToolDisplayCategory.AGENT,
        ToolDisplayCategory.fallbackDisplayCategory("agent"));
    assertEquals(ToolDisplayCategory.AGENT,
        ToolDisplayCategory.fallbackDisplayCategory("agentx_reviewer"));
  }

  @Test
  public void pipelineNameFallsBackToItsOwnCategory() {
    assertEquals(ToolDisplayCategory.AGENT_PIPELINE,
        ToolDisplayCategory.fallbackDisplayCategory("agent_pipeline"));
  }

  @Test
  public void unknownAndNullNamesFallBackToGeneric() {
    assertEquals(ToolDisplayCategory.GENERIC,
        ToolDisplayCategory.fallbackDisplayCategory("file_read"));
    assertEquals(ToolDisplayCategory.GENERIC,
        ToolDisplayCategory.fallbackDisplayCategory(null));
  }

  @Test
  public void displayCategoryEnumExposesAllDeclaredValues() {
    assertEquals(10, ToolDisplayCategory.values().length);
    assertEquals(ToolDisplayCategory.WRITE, ToolDisplayCategory.valueOf("WRITE"));
  }

  // ---- ToolCategory ----

  @Test
  public void toolCategoryValuesAreWireStable() {
    assertEquals("read", ToolCategory.READ.getValue());
    assertEquals("generate", ToolCategory.GENERATE.getValue());
    assertEquals("write", ToolCategory.WRITE.getValue());
    assertEquals("system", ToolCategory.SYSTEM.getValue());
  }

  // ---- ToolNames extension predicates ----

  @Test
  public void extensionPredicatesMatchCustomAgentAndMcpPrefixes() {
    assertTrue(ToolNames.isExtensionToolName("agentx_reviewer"));
    assertTrue(ToolNames.isExtensionToolName("mcpx_github"));
    assertFalse(ToolNames.isExtensionToolName("file_read"));
    assertFalse(ToolNames.isExtensionToolName(null));
  }

  @Test
  public void customAgentPredicateIsPrefixScoped() {
    assertTrue(ToolNames.isCustomAgentToolName("agentx_reviewer"));
    assertFalse(ToolNames.isCustomAgentToolName("mcpx_github"));
    assertFalse(ToolNames.isCustomAgentToolName(null));
  }

  @Test
  public void customMcpPredicateIsPrefixScoped() {
    assertTrue(ToolNames.isCustomMcpToolName("mcpx_github"));
    assertFalse(ToolNames.isCustomMcpToolName("agentx_reviewer"));
    assertFalse(ToolNames.isCustomMcpToolName(null));
  }

  // ---- PermissionResult ----

  @Test
  public void allowedResultHasNoReason() {
    PermissionResult result = PermissionResult.allowed();
    assertTrue(result.isAllowed());
    assertEquals("", result.getReason());
  }

  @Test
  public void deniedResultCarriesReason() {
    PermissionResult result = PermissionResult.denied("属于写操作");
    assertFalse(result.isAllowed());
    assertEquals("属于写操作", result.getReason());
  }

  @Test
  public void deniedNullReasonBecomesEmpty() {
    assertEquals("", PermissionResult.denied(null).getReason());
  }

  // ---- ToolArgs ----

  @Test
  public void requireNonEmptyAcceptsRealValue() {
    ToolArgs.requireNonEmpty("x", "path");
  }

  @Test
  public void requireNonEmptyRejectsNullAndBlankWithParamName() {
    IllegalArgumentException e1 = assertThrows(IllegalArgumentException.class,
        () -> ToolArgs.requireNonEmpty(null, "path"));
    assertEquals("path cannot be empty", e1.getMessage());
    assertThrows(IllegalArgumentException.class,
        () -> ToolArgs.requireNonEmpty("   ", "path"));
  }

  // ---- Strings ----

  @Test
  public void nullToEmptyNormalizesOnlyNull() {
    assertEquals("", Strings.nullToEmpty(null));
    assertEquals("", Strings.nullToEmpty(""));
    assertEquals("v", Strings.nullToEmpty("v"));
  }

  @Test
  public void isNullOrEmptyDistinguishesNullFromBlank() {
    assertTrue(Strings.isNullOrEmpty(null));
    assertTrue(Strings.isNullOrEmpty(""));
    assertFalse(Strings.isNullOrEmpty(" "));
    assertFalse(Strings.isNullOrEmpty("v"));
  }

  // ---- ToolCall ----

  @Test
  public void toolCallGeneratesIdWhenBlank() {
    ToolCall call = new ToolCall(null, "glob", null);
    assertTrue(call.getId().startsWith("tool_call_"));
    assertEquals("glob", call.getName());
    assertEquals("{}", call.getArguments());
  }

  @Test
  public void toolCallKeepsProvidedIdAndArguments() {
    ToolCall call = new ToolCall("c1", "glob", "{\"pattern\":\"**\"}");
    assertEquals("c1", call.getId());
    assertEquals("{\"pattern\":\"**\"}", call.getArguments());
    // 空字符串 id 同样触发兜底生成
    assertTrue(new ToolCall("", "glob", "{}").getId().startsWith("tool_call_"));
    // 名字缺省是空串
    assertEquals("", new ToolCall("c1", null, "{}").getName());
  }

  // ---- ToolInfo.toJsonArray ----

  @Test
  public void toJsonArraySerializesEachTool() throws Exception {
    ToolInfo tool = new StubTool("file_read", ToolCategory.READ);
    JSONArray array = ToolInfo.toJsonArray(Collections.singletonList(tool));
    assertEquals(1, array.length());
    assertEquals("file_read", array.getJSONObject(0).getString("name"));
  }

  @Test
  public void toJsonArrayOfNullYieldsEmptyArray() throws Exception {
    assertEquals(0, ToolInfo.toJsonArray(null).length());
  }

  @Test
  public void toJsonArrayPreservesOrderOverCollection() throws Exception {
    JSONArray array = ToolInfo.toJsonArray(Arrays.asList(
        new StubTool("a", ToolCategory.READ), new StubTool("b", ToolCategory.WRITE)));
    assertEquals("a", array.getJSONObject(0).getString("name"));
    assertEquals("b", array.getJSONObject(1).getString("name"));
  }

  @Test
  public void toolInfoDefaultsToNoCustomCardView() {
    assertNull(new StubTool("x", ToolCategory.SYSTEM).getToolCallViewClass());
  }

  // ---- 接口默认方法 ----

  @Test
  public void setSinkRestoresDefaultLoggingWhenCleared() {
    // 传 null 表示退回默认 java.util.logging 通道（app 层可在关闭时调用）
    ErrorLog.setSink((type, summary, throwable, details) -> { });
    ErrorLog.setSink(null);
    ErrorLog.record("api", "退化到默认通道", null, null);
  }

  @Test
  public void cardViewUpdateContentFallsBackToFullBind() {
    RecordingCardView view = new RecordingCardView();
    ToolCall call = new ToolCall("c1", "glob", "{}");
    ToolResult result = ToolResult.success("out");
    view.updateContent(call, result);
    assertTrue(view.bound, "默认实现应回退到 bind");
  }

  @Test
  public void reviewListenerDefaultShellHookIsNoOp() {
    // 默认实现不做任何事，子类只覆写需要的方法
    ToolReviewListener listener = (toolCallId, state, diffId) -> { };
    listener.onViewShellCommand("ls");
  }

  private static final class StubTool implements ToolInfo {
    private final String name;
    private final ToolCategory category;

    StubTool(String name, ToolCategory category) {
      this.name = name;
      this.category = category;
    }

    @Override public String getName() { return name; }
    @Override public String getDescription() { return ""; }
    @Override public ToolCategory getCategory() { return category; }
    @Override public boolean needsConfirmation() { return false; }
    @Override public String promptSupplement(String executionMode) { return null; }
    @Override public JSONObject getParameters() { return new JSONObject(); }
    @Override public JSONObject toJson() {
      JSONObject object = new JSONObject();
      try {
        object.put("name", name);
      } catch (Exception ignored) {
      }
      return object;
    }
  }

  private static final class RecordingCardView implements ToolCallCardView {
    boolean bound;

    @Override public void bind(ToolCall call, ToolResult result) { bound = true; }
    @Override public void setToolReviewListener(ToolReviewListener listener) { }
    @Override public void setProjectPath(String projectPath) { }
  }
}
