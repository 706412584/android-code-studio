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
 * along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.ai.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tom.rv2ide.ai.tool.api.ToolCategory;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link CodeActionsTool} 的行为：参数校验、action 白名单、五态文案、1 基换算、只读判定。
 */
class CodeActionsToolTest {

  /** 假端口：记录调用参数，返回预置结果。 */
  private static final class FakePort implements CodeActionsPort {
    Result next = Result.done(false, 0, Collections.<String>emptyList(), "unset");
    String lastPath = null;
    String lastAction = null;
    int lastLine = -1;
    int lastColumn = -1;
    String lastArgument = null;
    boolean available = true;
    String reason = "";
    boolean supported = true;
    List<String> actions =
        Arrays.asList(
            "implement_abstract_methods",
            "create_missing_method",
            "add_import",
            "add_throws",
            "remove_unused_throws",
            "suppress_unchecked");

    @Override
    public Result perform(String absolutePath, String action, int line, int column,
        String argument, long timeoutMs) {
      lastPath = absolutePath;
      lastAction = action;
      lastLine = line;
      lastColumn = column;
      lastArgument = argument;
      return next;
    }

    @Override
    public List<String> supportedActions() {
      return actions;
    }

    @Override
    public boolean isSupported(String filePath) {
      return supported;
    }

    @Override
    public boolean isAvailable() {
      return available;
    }

    @Override
    public String unavailableReason() {
      return reason;
    }
  }

  private static ToolContext contextWith(CodeActionsPort port, String home) {
    return ToolContext.builder().homePath(home).codeActions(port).build();
  }

  private static String writeJava(java.nio.file.Path dir, String name) throws Exception {
    java.nio.file.Path file = dir.resolve(name);
    Files.write(file, "class A {}\n".getBytes(StandardCharsets.UTF_8));
    return file.toString();
  }

  private static JSONObject input(String path, String action) {
    JSONObject o = new JSONObject();
    try {
      o.put("file_path", path);
      o.put("action", action);
    } catch (org.json.JSONException ignored) {
    }
    return o;
  }

  @Test
  void reportsExplicitErrorWhenPortMissing(@TempDir java.nio.file.Path dir) throws Exception {
    String path = writeJava(dir, "A.java");
    ToolResult result =
        new CodeActionsTool().execute(input(path, "add_import"),
            ToolContext.builder().homePath(dir.toString()).build());
    assertTrue(result.isError());
    assertTrue(result.getContent().contains("未接入"));
  }

  @Test
  void rejectsUnsupportedFileType(@TempDir java.nio.file.Path dir) throws Exception {
    FakePort port = new FakePort();
    port.supported = false;
    java.nio.file.Path file = dir.resolve("a.kt");
    Files.write(file, "fun main() {}".getBytes(StandardCharsets.UTF_8));
    ToolResult result =
        new CodeActionsTool().execute(input(file.toString(), "add_import"),
            contextWith(port, dir.toString()));
    assertTrue(result.isError());
    assertTrue(result.getContent().contains("仅 .java"));
  }

  @Test
  void rejectsUnknownAction(@TempDir java.nio.file.Path dir) throws Exception {
    FakePort port = new FakePort();
    String path = writeJava(dir, "A.java");
    ToolResult result =
        new CodeActionsTool().execute(input(path, "foo"), contextWith(port, dir.toString()));
    assertTrue(result.isError());
    assertTrue(result.getContent().contains("add_import"));
  }

  @Test
  void addImportRequiresQualifiedName(@TempDir java.nio.file.Path dir) throws Exception {
    FakePort port = new FakePort();
    String path = writeJava(dir, "A.java");
    ToolResult result =
        new CodeActionsTool().execute(input(path, "add_import"), contextWith(port, dir.toString()));
    assertTrue(result.isError());
    assertTrue(result.getContent().contains("完全限定类名"));
  }

  @Test
  void convertsOneBasedLineColumnToZeroBased(@TempDir java.nio.file.Path dir) throws Exception {
    FakePort port = new FakePort();
    String path = writeJava(dir, "A.java");
    JSONObject o = input(path, "create_missing_method");
    try {
      o.put("line", 5);
      o.put("column", 3);
    } catch (org.json.JSONException ignored) {
    }
    new CodeActionsTool().execute(o, contextWith(port, dir.toString()));
    assertEquals(4, port.lastLine);
    assertEquals(2, port.lastColumn);
  }

  @Test
  void reportsChangedFiles(@TempDir java.nio.file.Path dir) throws Exception {
    FakePort port = new FakePort();
    port.next = CodeActionsPort.Result.done(true, 42,
        Collections.singletonList("A.java"), "代码动作已完成。");
    String path = writeJava(dir, "A.java");
    JSONObject o = input(path, "add_import");
    try {
      o.put("argument", "java.util.List");
    } catch (org.json.JSONException ignored) {
    }
    ToolResult result = new CodeActionsTool().execute(o, contextWith(port, dir.toString()));
    assertFalse(result.isError());
    assertTrue(result.getContent().contains("42"));
    assertTrue(result.getContent().contains("A.java"));
  }

  @Test
  void timedOutIsError(@TempDir java.nio.file.Path dir) throws Exception {
    FakePort port = new FakePort();
    port.next = CodeActionsPort.Result.timedOut("代码动作超时（超过 30 秒）。");
    String path = writeJava(dir, "A.java");
    JSONObject o = input(path, "add_import");
    try {
      o.put("argument", "java.util.List");
    } catch (org.json.JSONException ignored) {
    }
    ToolResult result = new CodeActionsTool().execute(o, contextWith(port, dir.toString()));
    assertTrue(result.isError());
    assertTrue(result.getContent().contains("超时"));
  }

  @Test
  void unavailableIsError(@TempDir java.nio.file.Path dir) throws Exception {
    FakePort port = new FakePort();
    port.reason = "当前没有打开的项目";
    String path = writeJava(dir, "A.java");
    ToolResult result =
        new CodeActionsTool().execute(input(path, "add_import"), contextWith(port, dir.toString()));
    assertTrue(result.isError());
    assertTrue(result.getContent().contains("没有打开的项目"));
  }

  @Test
  void toolMetadata() {
    CodeActionsTool tool = new CodeActionsTool();
    assertEquals(ToolCategory.WRITE, tool.getCategory());
    assertTrue(tool.needsConfirmation());
    assertTrue(tool.shouldRecordDiff());
    assertFalse(tool.isAllowedInReadonlyMode());
    assertFalse(tool.isConcurrencySafe());
  }

  /**
   * schema 结构回归：required 必须在顶层，且 properties 的每一项都必须是对象。
   *
   * <p>历史 bug（2026-10-12 真机验证发现）：required 被误嵌进 properties 内部，
   * 生成非法 JSON Schema。严格校验的服务商（agnes）直接 400 拒绝整个请求——
   * 工具列表里只要有它就全部请求失败。假服务器不校验 schema，单元测试只查
   * 「required 含哪些名字」也查不出嵌套位置，所以单列这个结构断言。
   */
  @Test
  void parametersSchemaHasRequiredAtTopLevel() throws Exception {
    JSONObject params = new CodeActionsTool().getParameters();
    assertEquals("object", params.optString("type"));
    JSONObject props = params.optJSONObject("properties");
    assertTrue(props != null && props.length() > 0, "properties 不能为空");
    // 顶层 required：存在、是数组、含两个必填项。
    org.json.JSONArray required = params.optJSONArray("required");
    assertTrue(required != null, "required 必须在顶层");
    assertEquals(2, required.length(), "required 应含 file_path 与 action");
    // 反向断言：properties 内绝不能有名为 required 的项（旧 bug 的形态）。
    assertFalse(props.has("required"), "required 不得嵌套在 properties 内");
    // properties 的每一项都必须是 schema 对象（数组/字符串会导致服务商 400）。
    for (java.util.Iterator<String> it = props.keys(); it.hasNext(); ) {
      String key = it.next();
      Object value = props.get(key);
      assertTrue(
          value instanceof JSONObject,
          "properties." + key + " 必须是 schema 对象，实际是 " + value.getClass().getSimpleName());
    }
  }
}
