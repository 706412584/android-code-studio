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

import com.tom.rv2ide.ai.tool.api.ToolCall;
import com.tom.rv2ide.ai.tool.api.ToolNames;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 工具名别名解析的回归测试。
 *
 * <p><b>为什么需要它</b>：模型的工具命名先验来自训练语料里大量 `read` / `write` / `edit` /
 * `bash` 风格的调用。即使 tools 定义里写的是 `file_read`，它仍会时不时发出 `read`。
 * 真机实测一次构建排障中，模型连续 5 次调用不存在的 `read`，每次白烧一轮工具调用。
 */
final class ToolAliasTest {

  private static ToolRegistry registry() {
    ToolRegistry registry = new ToolRegistry();
    registry.register(new FileReadTool());
    registry.register(new FileWriteTool());
    registry.register(new FileEditTool());
    registry.register(new FileDeleteTool());
    registry.register(new GlobTool());
    registry.register(new ListDirectoryTool());
    return registry;
  }

  @Test
  void resolvesModelStyleAliases() {
    ToolRegistry registry = registry();
    assertEquals(ToolNames.FILE_READ, registry.get("read").getName());
    assertEquals(ToolNames.FILE_WRITE, registry.get("write").getName());
    assertEquals(ToolNames.FILE_EDIT, registry.get("edit").getName());
    assertEquals(ToolNames.FILE_DELETE, registry.get("delete").getName());
    assertEquals(ToolNames.LIST_DIR, registry.get("ls").getName());
    assertEquals(ToolNames.GLOB, registry.get("find").getName());
  }

  @Test
  void canonicalNamesStillWork() {
    ToolRegistry registry = registry();
    assertEquals(ToolNames.FILE_READ, registry.get("file_read").getName());
    assertEquals(ToolNames.FILE_WRITE, registry.get("file_write").getName());
    assertEquals(ToolNames.GLOB, registry.get("glob").getName());
  }

  @Test
  void unknownNameReturnsNull() {
    assertNull(registry().get("definitely_not_a_tool"));
    assertNull(registry().get(null));
  }

  @Test
  void canonicalNameMapsAliasAndPassesThroughOthers() {
    assertEquals(ToolNames.FILE_READ, ToolRegistry.canonicalName("read"));
    assertEquals(ToolNames.SHELL_EXECUTE, ToolRegistry.canonicalName("bash"));
    // 已是规范名 / 未知名 → 原样返回，便于错误信息里保留模型的原始输入
    assertEquals("file_read", ToolRegistry.canonicalName("file_read"));
    assertEquals("whatever", ToolRegistry.canonicalName("whatever"));
  }

  @Test
  void aliasActuallyExecutesTheTool(@TempDir Path workspace) throws Exception {
    // 别名必须能真正跑通，而不只是查找命中
    ToolRegistry registry = registry();
    ToolExecutor executor = new ToolExecutor(registry, null, null);
    ToolContext context = ToolContext.builder().homePath(workspace.toString()).build();

    ToolResult result =
        executor.execute(
            new ToolCall("c1", "write", "{\"file_path\":\"a.txt\",\"content\":\"hi\"}"),
            context);

    assertNotNull(result);
    assertEquals(ToolNames.FILE_WRITE, result.getToolName(), "结果里的工具名应为规范名");
    assertEquals("hi", java.nio.file.Files.readString(workspace.resolve("a.txt")));
  }
}
