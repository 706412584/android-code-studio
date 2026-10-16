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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tom.rv2ide.ai.tool.WorkspaceSymbolPort.Kind;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link WorkspaceSymbolMessages}：kind 解析、中文名、结果行格式化。
 *
 * <p>同一符号的文案必须与 {@code file_read}/{@code grep} 的展示一致（行列 1 基），
 * 否则模型拿到 0 基行列去读文件会偏一行。
 */
final class WorkspaceSymbolMessagesTest {

  // ---- parseKind ----

  @Test
  void parseKindAcceptsAllSixKindsCaseInsensitive() {
    assertEquals(Kind.CLASS, WorkspaceSymbolMessages.parseKind("class"));
    assertEquals(Kind.CLASS, WorkspaceSymbolMessages.parseKind("CLASS"));
    assertEquals(Kind.CLASS, WorkspaceSymbolMessages.parseKind("  Class  "));
    assertEquals(Kind.INTERFACE, WorkspaceSymbolMessages.parseKind("interface"));
    assertEquals(Kind.ENUM, WorkspaceSymbolMessages.parseKind("enum"));
    assertEquals(Kind.ANNOTATION, WorkspaceSymbolMessages.parseKind("annotation"));
    assertEquals(Kind.METHOD, WorkspaceSymbolMessages.parseKind("method"));
    assertEquals(Kind.FIELD, WorkspaceSymbolMessages.parseKind("field"));
  }

  @Test
  void parseKindReturnsNullForNullAndUnknown() {
    assertNull(WorkspaceSymbolMessages.parseKind(null));
    assertNull(WorkspaceSymbolMessages.parseKind(""));
    assertNull(WorkspaceSymbolMessages.parseKind("struct"));
    assertNull(WorkspaceSymbolMessages.parseKind("clazz"));
  }

  // ---- kindName ----

  @Test
  void kindNameMapsEveryKindToChinese() {
    assertEquals("类", WorkspaceSymbolMessages.kindName(Kind.CLASS));
    assertEquals("接口", WorkspaceSymbolMessages.kindName(Kind.INTERFACE));
    assertEquals("枚举", WorkspaceSymbolMessages.kindName(Kind.ENUM));
    assertEquals("注解", WorkspaceSymbolMessages.kindName(Kind.ANNOTATION));
    assertEquals("方法", WorkspaceSymbolMessages.kindName(Kind.METHOD));
    assertEquals("字段", WorkspaceSymbolMessages.kindName(Kind.FIELD));
  }

  @Test
  void kindNameHandlesNullAsUnknown() {
    assertEquals("未知", WorkspaceSymbolMessages.kindName(null));
  }

  // ---- formatSymbol ----

  @Test
  void formatSymbolConvertsTo1BasedLineColumnAndShowsOwner(@TempDir Path home) {
    WorkspaceSymbolPort.Symbol symbol = new WorkspaceSymbolPort.Symbol(
        "parseConfig", "com.foo.Config", Kind.METHOD,
        home.resolve("src/Config.java").toString(), 41, 6);

    String line = WorkspaceSymbolMessages.formatSymbol(context(home), symbol);

    // displayPath 在 Windows 上产出反斜杠——只断言文件名与行列部分。
    assertTrue(line.contains("Config.java:42:7"), line);
    assertTrue(line.contains("parseConfig"), line);
    assertTrue(line.contains("(方法, com.foo.Config)"), line);
  }

  @Test
  void formatSymbolOmitsOwnerForTypeItself(@TempDir Path home) {
    // 类型自身的 owner 是空串——括号里不应出现逗号尾巴。
    WorkspaceSymbolPort.Symbol symbol = new WorkspaceSymbolPort.Symbol(
        "MainActivity", "", Kind.CLASS, home.resolve("MainActivity.java").toString(), 0, 0);

    String line = WorkspaceSymbolMessages.formatSymbol(context(home), symbol);

    assertTrue(line.contains("MainActivity.java:1:1"), line);
    assertTrue(line.contains("(类)"), line);
    assertTrue(!line.contains("(类,"), "无 owner 时不应有逗号：" + line);
  }

  @Test
  void formatSymbolFallsBackToRawPathOutsideHome(@TempDir Path home, @TempDir Path outside) {
    // 不在 home 内的路径 displayPath 返回绝对路径原样——不应崩、不应丢路径。
    WorkspaceSymbolPort.Symbol symbol = new WorkspaceSymbolPort.Symbol(
        "X", "", Kind.CLASS, outside.resolve("X.java").toString(), 4, 2);

    String line = WorkspaceSymbolMessages.formatSymbol(context(home), symbol);

    assertTrue(line.contains("X.java"), line);
    assertTrue(line.contains(":5:3"), line);
  }

  private static ToolContext context(Path home) {
    return ToolContext.builder().homePath(home.toString()).build();
  }
}
