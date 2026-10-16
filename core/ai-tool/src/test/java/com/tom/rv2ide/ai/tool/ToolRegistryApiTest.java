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

package com.tom.rv2ide.ai.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tom.rv2ide.ai.tool.api.ToolDisplayCategory;
import com.tom.rv2ide.ai.tool.api.ToolInfo;
import com.tom.rv2ide.ai.tool.api.ToolNames;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * 钉住 {@link ToolRegistry} 的注册/查找/别名归一、延迟加载过滤、白名单裁剪与
 * 序列化行为——这些是「模型发的工具名能不能被解析」「子 agent 能不能越权」的关键。
 */
final class ToolRegistryApiTest {

  private static RecordingTool tool(String name) {
    return new RecordingTool(name);
  }

  @Test
  void registersAndFindsByExactName() {
    ToolRegistry registry = new ToolRegistry(Collections.singletonList(tool("file_read")));
    assertEquals("file_read", registry.get("file_read").getName());
    assertNull(registry.get("nope"));
    assertNull(registry.get(null));
    assertEquals(1, registry.getAll().size());
    assertTrue(registry.getToolNames().contains("file_read"));
  }

  @Test
  void registerIgnoresNullAndOverwritesSameName() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(null);
    assertTrue(registry.getAll().isEmpty());
    registry.register(tool("a"));
    RecordingTool replacement = tool("a");
    registry.register(replacement);
    assertSame(replacement, registry.get("a"));
    assertEquals(1, registry.getAll().size());
  }

  @Test
  void nullInitialCollectionIsTolerated() {
    assertTrue(new ToolRegistry(null).getAll().isEmpty());
  }

  @Test
  void aliasResolvesToCanonicalTool() {
    ToolRegistry registry = new ToolRegistry(Collections.singletonList(tool(ToolNames.FILE_READ)));
    // 模型常发裸 read；应归一为 file_read
    assertEquals(ToolNames.FILE_READ, registry.get("read").getName());
    assertEquals(ToolNames.FILE_READ, registry.get("read_file").getName());
  }

  @Test
  void exactNameWinsOverAlias() {
    // 存在一个真的叫 "read" 的工具时，它不应被别名遮蔽
    ToolRegistry registry = new ToolRegistry(Arrays.asList(tool("read"), tool(ToolNames.FILE_READ)));
    assertEquals("read", registry.get("read").getName());
  }

  @Test
  void canonicalNameNormalizesKnownAliasesAndPassesThroughUnknown() {
    assertEquals(ToolNames.FILE_WRITE, ToolRegistry.canonicalName("write"));
    assertEquals(ToolNames.SHELL_EXECUTE, ToolRegistry.canonicalName("bash"));
    assertEquals("custom_tool", ToolRegistry.canonicalName("custom_tool"));
    assertNull(ToolRegistry.canonicalName(null));
  }

  @Test
  void displayCategoryIsCachedAndFallsBackToGeneric() {
    ToolRegistry registry = new ToolRegistry();
    RecordingTool writeTool = tool("file_write");
    writeTool.displayCategory = ToolDisplayCategory.WRITE;
    registry.register(writeTool);
    assertEquals(ToolDisplayCategory.WRITE, registry.getCachedDisplayCategory("file_write"));
    assertEquals(ToolDisplayCategory.GENERIC, registry.getCachedDisplayCategory("unknown"));
  }

  @Test
  void byNameSetKeepsRegistrationOrderAndHandlesEmpty() {
    ToolRegistry registry = new ToolRegistry(Arrays.asList(tool("b"), tool("a"), tool("c")));
    Set<String> names = new LinkedHashSet<>(Arrays.asList("c", "a"));
    List<BaseTool> selected = registry.getByNameSet(names);
    // 保持注册顺序（a 先于 c），而非请求集合的顺序
    assertEquals(Arrays.asList("a", "c"), Arrays.asList(selected.get(0).getName(), selected.get(1).getName()));
    assertTrue(registry.getByNameSet(null).isEmpty());
    assertTrue(registry.getByNameSet(Collections.emptySet()).isEmpty());
  }

  @Test
  void toolInfoViewMirrorsByNameSet() {
    ToolRegistry registry = new ToolRegistry(Arrays.asList(tool("a"), tool("b")));
    List<ToolInfo> info = registry.getToolInfoByNameSet(new LinkedHashSet<>(Collections.singletonList("b")));
    assertEquals(1, info.size());
    assertEquals("b", info.get(0).getName());
    assertTrue(registry.getToolInfoByNameSet(null).isEmpty());
  }

  @Test
  void restrictedFilterBuildsWhitelistRegistry() {
    ToolRegistry registry = new ToolRegistry(Arrays.asList(tool("file_read"), tool("file_write")));
    ToolRegistry filtered = registry.filtered(
        ToolNameFilter.of(new LinkedHashSet<>(Collections.singletonList("file_read"))));
    // 未列出的工具根本不注册——能力边界是结构性的
    assertEquals(1, filtered.getAll().size());
    assertNull(filtered.get("file_write"));
    assertEquals("file_read", filtered.get("file_read").getName());
  }

  @Test
  void unrestrictedFilterCopiesAllTools() {
    ToolRegistry registry = new ToolRegistry(Arrays.asList(tool("a"), tool("b")));
    assertEquals(2, registry.filtered(null).getAll().size());
    assertEquals(2, registry.filtered(ToolNameFilter.unrestricted()).getAll().size());
    // 过滤结果是新注册表，不影响原表
    assertEquals(2, registry.getAll().size());
  }

  @Test
  void deferredToolsOnlyAppearWhenActivated() {
    ToolRegistry registry = new ToolRegistry();
    RecordingTool deferred = tool("mcpx_x");
    deferred.deferred = true;
    RecordingTool always = tool("file_read");
    registry.register(deferred);
    registry.register(always);

    assertEquals(1, registry.getDeferredTools().size());
    assertFalse(registry.isRequestable("mcpx_x"));
    assertTrue(registry.isRequestable("file_read"));
    assertFalse(registry.isRequestable("unknown"));
    assertEquals(1, registry.getRequestableTools().size());

    registry.activate(Collections.singletonList("mcpx_x"));
    assertTrue(registry.isActivated("mcpx_x"));
    assertTrue(registry.isRequestable("mcpx_x"));
    assertEquals(2, registry.getRequestableTools().size());
  }

  @Test
  void activationEdgeCases() {
    ToolRegistry registry = new ToolRegistry(Collections.singletonList(tool("a")));
    registry.activate(null);
    registry.activate(Collections.emptyList());
    assertFalse(registry.isActivated(null));
    assertFalse(registry.isActivated("a"));
  }

  @Test
  void toJsonArraySerializesToolsAndHandlesNull() throws Exception {
    JSONArray array = ToolRegistry.toJsonArray(Arrays.asList(tool("a"), tool("b")));
    assertEquals(2, array.length());
    // BaseTool.toJson 采用 OpenAI function 包装形态
    assertEquals("function", array.getJSONObject(0).getString("type"));
    assertEquals("a", array.getJSONObject(0).getJSONObject("function").getString("name"));
    assertEquals(0, ToolRegistry.toJsonArray(null).length());
  }

  @Test
  void toToolInfoJsonArraySerializesToolInfoAndHandlesNull() throws Exception {
    List<ToolInfo> tools = Arrays.asList(tool("a"));
    JSONArray array = ToolRegistry.toToolInfoJsonArray(tools);
    assertEquals(1, array.length());
    assertEquals("a", array.getJSONObject(0).getJSONObject("function").getString("name"));
    assertEquals(0, ToolRegistry.toToolInfoJsonArray(null).length());
  }

  @Test
  void extensionNamePredicatesDelegateToToolNames() {
    assertTrue(ToolRegistry.isExtensionToolName("agentx_r"));
    assertTrue(ToolRegistry.isCustomAgentToolName("agentx_r"));
    assertTrue(ToolRegistry.isCustomMcpToolName("mcpx_g"));
    assertFalse(ToolRegistry.isExtensionToolName("file_read"));
    assertFalse(ToolRegistry.isCustomAgentToolName("mcpx_g"));
    assertFalse(ToolRegistry.isCustomMcpToolName("agentx_r"));
  }

  /** 最小可配置工具：只关心注册表读取的元数据。 */
  private static final class RecordingTool extends BaseTool {
    private final String name;
    ToolDisplayCategory displayCategory = ToolDisplayCategory.GENERIC;
    boolean deferred;

    RecordingTool(String name) {
      this.name = name;
    }

    @Override public String getName() { return name; }
    @Override public String getDescription() { return "desc"; }
    @Override public com.tom.rv2ide.ai.tool.api.ToolCategory getCategory() {
      return com.tom.rv2ide.ai.tool.api.ToolCategory.READ;
    }
    @Override public ToolDisplayCategory getDisplayCategory() { return displayCategory; }
    @Override public boolean isDeferredLoading() { return deferred; }
    @Override public JSONObject getParameters() { return new JSONObject(); }
    @Override public com.tom.rv2ide.ai.tool.api.ToolResult execute(JSONObject input, ToolContext context) {
      return ok("ok");
    }
  }
}
