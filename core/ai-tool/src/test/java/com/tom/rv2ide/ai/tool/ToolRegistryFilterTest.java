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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tom.rv2ide.ai.tool.api.ToolCategory;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/** {@link ToolRegistry#filtered} 与 {@link SubAgentRunner.Request} 白名单语义的回归测试。 */
final class ToolRegistryFilterTest {

  /** 最小可用工具：名字固定，其余实现无关紧要。 */
  private static final class NamedTool extends BaseTool {
    private final String name;

    NamedTool(String name) {
      this.name = name;
    }

    @Override
    public String getName() {
      return name;
    }

    @Override
    public String getDescription() {
      return "tool " + name;
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

  private static ToolRegistry registry(String... names) {
    ToolRegistry registry = new ToolRegistry();
    for (String name : names) {
      registry.register(new NamedTool(name));
    }
    return registry;
  }

  private static List<String> names(ToolRegistry registry) {
    List<String> result = new java.util.ArrayList<>();
    for (BaseTool tool : registry.getAll()) {
      result.add(tool.getName());
    }
    return result;
  }

  @Test
  void filteredKeepsOnlyWhitelistedTools() {
    ToolRegistry source = registry("file_read", "file_write", "glob", "shell_execute");
    ToolRegistry filtered =
        source.filtered(ToolNameFilter.of(new HashSet<>(Arrays.asList("file_read", "glob"))));

    assertEquals(Arrays.asList("file_read", "glob"), names(filtered));
    // 未列出的工具根本不存在——这是结构边界，不是提示词建议。
    assertNull(filtered.get("file_write"));
    assertNull(filtered.get("shell_execute"));
  }

  @Test
  void filteredDoesNotMutateSource() {
    ToolRegistry source = registry("file_read", "file_write");
    source.filtered(ToolNameFilter.of(new HashSet<>(Arrays.asList("file_read"))));
    assertEquals(2, source.getAll().size());
  }

  @Test
  void unrestrictedFilterCopiesAllTools() {
    ToolRegistry source = registry("a", "b", "c");
    ToolRegistry copy = source.filtered(ToolNameFilter.unrestricted());
    assertEquals(3, copy.getAll().size());
    assertNotNull(copy.get("a"));
  }

  @Test
  void nullFilterCopiesAllTools() {
    ToolRegistry source = registry("a");
    assertEquals(1, source.filtered(null).getAll().size());
  }

  @Test
  void requestDefaultsToNoRolePromptAndUnrestrictedTools() {
    SubAgentRunner.Request request = new SubAgentRunner.Request("t", SubAgentRunner.Mode.EXPLORE, 1);
    assertEquals("", request.getSystemPrompt());
    assertNotNull(request.getToolFilter());
    assertTrue(request.getToolFilter().allows("file_write"));
  }

  @Test
  void requestCarriesRolePromptAndFilter() {
    ToolNameFilter filter = ToolNameFilter.of(new HashSet<>(Arrays.asList("file_read")));
    SubAgentRunner.Request request =
        new SubAgentRunner.Request(
            "t", SubAgentRunner.Mode.CODE, 0, "role prompt", filter);

    assertEquals("role prompt", request.getSystemPrompt());
    assertEquals(filter, request.getToolFilter());
    // 角色白名单能覆盖模式：即使 mode=CODE，只读白名单仍挡住写工具。
    assertTrue(request.getToolFilter().restricts());
    assertTrue(!request.getToolFilter().allows("file_write"));
  }

  @Test
  void requestNullFilterFallsBackToUnrestricted() {
    SubAgentRunner.Request request =
        new SubAgentRunner.Request("t", SubAgentRunner.Mode.EXPLORE, 0, null, null);
    assertNotNull(request.getToolFilter());
    assertTrue(request.getToolFilter().allows("anything"));
  }
}
