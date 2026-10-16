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

package com.tom.rv2ide.ai.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tom.rv2ide.ai.tool.api.ToolCategory;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * {@link ToolSearchTool} 与 {@link ToolRegistry} 延迟加载语义的回归测试。
 *
 * <p>锁定三条行为（都对应真实失败模式）：
 * <ul>
 *   <li>未展开的按需工具**不进** tools 数组——否则延迟加载形同虚设，MCP schema
 *       照旧吃满上下文。
 *   <li>检索命中后**从下一轮起**可请求——展开不生效的话，模型检索到却调不到，
 *       表现为「工具名明明在返回里，调用却报未知工具」。
 *   <li>目录注入提示词——目录不可见则模型不知道去检索，按需加载变成「永不加载」。
 * </ul>
 */
final class ToolSearchTest {

  /** 常驻工具：isDeferredLoading 默认 false。 */
  private static final class ResidentTool extends BaseTool {
    private final String name;

    ResidentTool(String name) {
      this.name = name;
    }

    @Override
    public String getName() {
      return name;
    }

    @Override
    public String getDescription() {
      return "resident tool " + name;
    }

    @Override
    public ToolCategory getCategory() {
      return ToolCategory.READ;
    }

    @Override
    public JSONObject getParameters() {
      return new JSONObject();
    }

    @Override
    public ToolResult execute(JSONObject input, ToolContext context) {
      return ok("ok");
    }
  }

  /** 按需加载工具（模拟 MCP 工具）。 */
  private static final class DeferredTool extends BaseTool {
    private final String name;
    private final String description;

    DeferredTool(String name, String description) {
      this.name = name;
      this.description = description;
    }

    @Override
    public String getName() {
      return name;
    }

    @Override
    public String getDescription() {
      return description;
    }

    @Override
    public ToolCategory getCategory() {
      return ToolCategory.WRITE;
    }

    @Override
    public boolean isDeferredLoading() {
      return true;
    }

    @Override
    public JSONObject getParameters() {
      return new JSONObject();
    }

    @Override
    public ToolResult execute(JSONObject input, ToolContext context) {
      return ok("ok");
    }
  }

  private static ToolRegistry registry() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(new ResidentTool("file_read"));
    registry.register(new DeferredTool("mcpx_fetch_weather", "查询指定城市的天气"));
    registry.register(new DeferredTool("mcpx_send_email", "给某人发送电子邮件"));
    return registry;
  }

  private static List<String> requestableNames(ToolRegistry registry) {
    List<String> names = new ArrayList<>();
    for (BaseTool tool : registry.getRequestableTools()) {
      names.add(tool.getName());
    }
    return names;
  }

  private static ToolResult runSearch(ToolRegistry registry, String query) {
    ToolSearchTool tool = new ToolSearchTool(registry);
    try {
      JSONObject input = new JSONObject();
      if (query != null) {
        input.put("query", query);
      }
      return tool.execute(input, null);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  void deferredToolsAreHiddenUntilActivated() {
    ToolRegistry registry = registry();
    // 未展开：常驻在，按需不在。
    List<String> names = requestableNames(registry);
    assertTrue(names.contains("file_read"));
    assertFalse(names.contains("mcpx_fetch_weather"));
    assertFalse(names.contains("mcpx_send_email"));
    assertEquals(1, names.size());
  }

  @Test
  void searchActivatesMatchingToolsForNextTurn() {
    ToolRegistry registry = registry();
    ToolResult result = runSearch(registry, "天气");
    assertFalse(result.isError(), result.getContent());
    assertTrue(result.getContent().contains("mcpx_fetch_weather"));

    // 展开后进入可请求集合；未命中的另一个仍不可请求。
    List<String> names = requestableNames(registry);
    assertTrue(names.contains("mcpx_fetch_weather"));
    assertFalse(names.contains("mcpx_send_email"));
    // 常驻工具不受影响。
    assertTrue(names.contains("file_read"));
  }

  @Test
  void emptyQueryListsAllDeferred() {
    ToolRegistry registry = registry();
    ToolResult result = runSearch(registry, "");
    assertTrue(result.getContent().contains("mcpx_fetch_weather"));
    assertTrue(result.getContent().contains("mcpx_send_email"));
    assertEquals(2, registry.getRequestableTools().size() - 1, "两个按需工具都应展开");
  }

  @Test
  void multiTermQueryRequiresAllTerms() {
    ToolRegistry registry = registry();
    // "天气 邮件" 两个词分属不同工具，不应命中任何一个。
    ToolResult result = runSearch(registry, "天气 邮件");
    assertTrue(result.getContent().contains("没有匹配"), result.getContent());
    assertEquals(1, registry.getRequestableTools().size(), "未命中时不应展开任何工具");
  }

  @Test
  void noMatchReturnsGuidanceWithoutActivating() {
    ToolRegistry registry = registry();
    ToolResult result = runSearch(registry, "不存在的关键词xyz");
    assertTrue(result.getContent().contains("没有匹配"));
    assertTrue(result.getContent().contains("2 个工具"), "应告知目录里有几个工具");
  }

  @Test
  void emptyCatalogReturnsExplicitMessage() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(new ResidentTool("file_read"));
    ToolResult result = runSearch(registry, "any");
    assertTrue(result.getContent().contains("目录是空的"));
  }

  @Test
  void promptSupplementListsCatalog() {
    ToolRegistry registry = registry();
    ToolSearchTool tool = new ToolSearchTool(registry);
    String supplement = tool.promptSupplement("");
    assertNotNull(supplement);
    // 目录可见：模型据此知道有这些工具存在。
    assertTrue(supplement.contains("mcpx_fetch_weather"));
    assertTrue(supplement.contains("mcpx_send_email"));
    assertTrue(supplement.contains("tool_search"), "应提示用哪个工具展开");
  }

  @Test
  void promptSupplementIsNullWithoutDeferredTools() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(new ResidentTool("file_read"));
    ToolSearchTool tool = new ToolSearchTool(registry);
    assertNull(tool.promptSupplement(""), "没有按需工具时不注入目录段落");
  }

  @Test
  void activationIsIdempotent() {
    ToolRegistry registry = registry();
    runSearch(registry, "天气");
    runSearch(registry, "天气");
    assertEquals(2, registry.getRequestableTools().size());
  }

  /**
   * 展开状态钉在**注册表实例**上，不跨注册表泄漏。
   *
   * <p>这是「每次运行新建注册表」这一装配约定的回归保护：若有人把 activated 挪成
   * 静态字段或共享单例，上一轮运行展开的 MCP 工具会泄漏到下一轮——上下文又悄悄变回
   * 全量注入，按需加载形同虚设，且不会有任何报错。
   */
  @Test
  void activationDoesNotLeakAcrossRegistries() {
    ToolRegistry a = registry();
    ToolRegistry b = registry();

    runSearch(a, "天气");

    assertTrue(requestableNames(a).contains("mcpx_fetch_weather"));
    assertFalse(
        requestableNames(b).contains("mcpx_fetch_weather"),
        "A 注册表展开的工具不得出现在 B 注册表里");
  }

  /**
   * 一次匹配超过上限时只展开前 {@code MAX_RESULTS} 个，且结果里如实说明截断
   * （「单次最多展开 N 个」）——静默截断会让模型以为目录里只有这么几个工具。
   */
  @Test
  void maxResultsTruncatesLargeMatches() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(new ResidentTool("file_read"));
    for (int i = 0; i < 12; i++) {
      registry.register(new DeferredTool("mcpx_tool_" + i, "批量工具 " + i));
    }

    ToolResult result = runSearch(registry, "");

    assertFalse(result.isError(), result.getContent());
    assertTrue(result.getContent().contains("单次最多展开 10 个"), "应如实说明截断");
    // 1 个常驻 + 10 个展开 = 11；剩下 2 个仍在目录里不可请求。
    assertEquals(11, registry.getRequestableTools().size());
    assertEquals(12, registry.getDeferredTools().size(), "目录大小不受展开影响");
  }

  // ---- 剩余分支：截断、目录摘要截断、元数据 ----

  @Test
  void longDescriptionIsAbbreviatedInCatalogAndResults() {
    ToolRegistry registry = new ToolRegistry();
    StringBuilder longDesc = new StringBuilder();
    for (int i = 0; i < 200; i++) {
      longDesc.append("很长的描述文本 ");
    }
    registry.register(new DeferredTool("mcpx_long", longDesc.toString()));

    // promptSupplement 里目录行截到 80 字符并标注省略。
    String supplement = new ToolSearchTool(registry).promptSupplement("");
    assertNotNull(supplement);
    assertTrue(supplement.contains("省略"), "目录描述应被截断并标注：" + supplement);

    // 展开结果里的 schema/描述也走同一截断。
    ToolResult result = runSearch(registry, "mcpx_long");
    assertFalse(result.isError(), result.getContent());
    assertTrue(result.getContent().contains("mcpx_long"), result.getContent());
  }

  @Test
  void displayMetadataAndReadonlyAccess() {
    ToolSearchTool tool = new ToolSearchTool(registry());
    assertEquals(com.tom.rv2ide.ai.tool.api.ToolDisplayCategory.GENERIC, tool.getDisplayCategory());
    assertTrue(tool.isAllowedInReadonlyMode(), "纯目录查询应允许只读模式");
    assertEquals(ToolCategory.READ, tool.getCategory());
  }

  @Test
  void nullInputListsAllDeferred() {
    // input 为 null（协议层异常路径）不应 NPE——按空 query 列出全部。
    ToolRegistry registry = registry();
    ToolResult result = new ToolSearchTool(registry).execute(null, null);
    assertFalse(result.isError(), result.getContent());
    assertTrue(result.getContent().contains("mcpx_fetch_weather"), result.getContent());
  }
}
