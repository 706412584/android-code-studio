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
 * along with AndroidCodeStudio.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.ai.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.tom.rv2ide.ai.tool.api.ToolCategory;
import com.tom.rv2ide.ai.tool.api.ToolNames;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 全量工具的元数据与 schema 契约。
 *
 * <p><b>为什么需要它</b>：工具的 {@code getParameters()} 输出会**原样**进模型的 tools 数组。
 * 严格校验的 provider（实测 agnes）会在 schema 有形状错误时以 400 拒绝**整个数组**——
 * 不是那一个工具不可用，而是所有工具全部失效、agent 退化为纯文本对话。这类错误的
 * 代价与发现难度完全不成比例，因此用实例化遍历把契约钉死。
 *
 * <p>覆盖三类不变量：
 * <ol>
 *   <li><b>身份</b>：getName 与 ToolNames 常量一致（工具名是权限规则、日志、UI 的键，
 *       拼错会让权限规则匹配不上）、描述非空（模型靠它决定用不用）；
 *   <li><b>schema 形状</b>：type=object、properties 是对象、required（若有）是数组
 *       且其每一项都能在 properties 里找到（写错字段名 = 模型永远调不对）；
 *   <li><b>分类</b>：getCategory 非空（只读模式据此放行）。
 * </ol>
 */
class ToolContractTest {

  /**
   * 可无参构造的全量工具。新增工具时把它加进来——这正是本测试的意义。
   *
   * <p>需要注入依赖的工具（如 {@code TodoUpdateTool(MemoryStore)}）在
   * {@link #allToolsWithDeps} 里单独列出。
   */
  private static List<BaseTool> allTools() {
    List<BaseTool> tools = new ArrayList<>();
    tools.add(new ArchiveTool());
    tools.add(new AskUserQuestionTool());
    tools.add(new BuildVariantTool());
    tools.add(new CodeActionsTool());
    tools.add(new DiagnosticsTool());
    tools.add(new EditorStateTool());
    tools.add(new FileDeleteTool());
    tools.add(new FileEditTool());
    tools.add(new FileOpenTool());
    tools.add(new FileReadTool());
    tools.add(new FileWriteTool());
    tools.add(new FileMoveTool());
    tools.add(new FileCopyTool());
    tools.add(new FormatCodeTool());
    tools.add(new GlobTool());
    tools.add(new GrepTool());
    tools.add(new IdeLogTool());
    tools.add(new ListDirectoryTool());
    tools.add(new LspNavigateTool());
    tools.add(new PhoneUiautomatorTool());
    tools.add(new SaveAllTool());
    tools.add(new TaskOutputTool());
    tools.add(new TaskStopTool());
    tools.add(new TerminalReadTool());
    tools.add(new WorkspaceSymbolTool());
    return tools;
  }

  /** 需注入依赖的工具；用最小可用实现构造，schema 与元数据同样纳入契约校验。 */
  private static List<BaseTool> allToolsWithDeps(java.io.File tmpDir) {
    List<BaseTool> tools = new ArrayList<>();
    tools.add(new TodoUpdateTool(TodoStateStore.inMemory()));
    tools.add(
        new com.tom.rv2ide.ai.tool.memory.MemoryUpdateTool(
            new com.tom.rv2ide.ai.tool.memory.MemoryStore(new java.io.File(tmpDir, "mem.json"))));
    // 只验证 schema/元数据，不触发执行，因此端口给最小实现即可。
    tools.add(new HttpRequestTool(new NoopHttpRequestPort()));
    tools.add(new WebFetchTool(new NoopHttpPort()));
    tools.add(new ImageTool(new NoopImageProcessingPort()));
    tools.add(
        new ImageGenerationTool(new NoopHttpRequestPort(), new NoopHttpPort()));
    tools.add(new VideoGenerationTool(new NoopHttpRequestPort()));
    tools.add(new com.tom.rv2ide.ai.tool.skill.SkillTool(com.tom.rv2ide.ai.tool.skill.SkillRegistry.empty()));
    tools.add(new com.tom.rv2ide.ai.tool.skill.SkillWriteTool(com.tom.rv2ide.ai.tool.skill.SkillRegistry.empty()));
    tools.add(new ToolSearchTool(new ToolRegistry()));
    tools.add(new CodeGraphTool(new ShellBackendRegistry()));
    tools.add(new ShellExecuteTool(new ShellBackendRegistry()));
    return tools;
  }

  private static List<BaseTool> everyTool(java.io.File tmpDir) {
    List<BaseTool> tools = new ArrayList<>(allTools());
    tools.addAll(allToolsWithDeps(tmpDir));
    return tools;
  }

  @Test
  void everyToolHasIdentityAndValidSchema(@TempDir Path tmpDir) {
    List<String> problems = new ArrayList<>();
    for (BaseTool tool : everyTool(tmpDir.toFile())) {
      String name = safe(tool, () -> tool.getName());
      if (name == null || name.isEmpty()) {
        problems.add(tool.getClass().getSimpleName() + ": getName 为空");
        continue;
      }
      if (safe(tool, () -> tool.getDescription()) == null
          || tool.getDescription().trim().isEmpty()) {
        problems.add(name + ": getDescription 为空（模型无法判断用途）");
      }
      if (safe(tool, () -> tool.getCategory()) == null) {
        problems.add(name + ": getCategory 为空");
      }
      JSONObject schema = safe(tool, () -> tool.getParameters());
      if (schema == null) {
        problems.add(name + ": getParameters 抛异常或返回 null");
        continue;
      }
      problems.addAll(validateSchema(name, schema));
    }
    if (!problems.isEmpty()) {
      fail("工具契约问题：\n  " + String.join("\n  ", problems));
    }
  }

  @Test
  void toolNamesAreUnique(@TempDir Path tmpDir) {
    Set<String> seen = new HashSet<>();
    for (BaseTool tool : everyTool(tmpDir.toFile())) {
      String name = tool.getName();
      assertTrue(seen.add(name), "工具名重复：" + name + "（模型无法区分两个同名工具）");
    }
  }

  @Test
  void schemaJsonRoundTrips(@TempDir Path tmpDir) throws Exception {
    // toJson() 是发给 provider 的最终形态：若某个工具的 schema 里含 NaN/循环引用
    // 等不可序列化的值，这里会炸——比线上 400 早得多。
    for (BaseTool tool : everyTool(tmpDir.toFile())) {
      JSONObject json = tool.toJson();
      assertNotNull(json, tool.getName() + ": toJson 返回 null");
      assertEquals("function", json.optString("type"), tool.getName() + ": 包装类型应为 function");
      JSONObject fn = json.optJSONObject("function");
      assertNotNull(fn, tool.getName() + ": 缺少 function 对象");
      assertEquals(tool.getName(), fn.optString("name"));
      assertNotNull(fn.optJSONObject("parameters"), tool.getName() + ": 缺少 parameters");
    }
  }

  @Test
  void categoryMatchesReadOnlyContract() {
    // 只读模式的放行规则是「category == READ 才放行」。这里钉住几个已知的只读工具
    // 确实声明为 READ——否则只读模式会把它们一起挡掉（用户以为只读模式什么都看不了）。
    assertEquals(ToolCategory.READ, new FileReadTool().getCategory());
    assertEquals(ToolCategory.READ, new GrepTool().getCategory());
    assertEquals(ToolCategory.READ, new GlobTool().getCategory());
    assertEquals(ToolCategory.READ, new ListDirectoryTool().getCategory());
    // 写类工具不得声明为 READ，否则只读模式下会被错误放行。
    assertFalse(new FileWriteTool().getCategory() == ToolCategory.READ);
    assertFalse(new FileDeleteTool().getCategory() == ToolCategory.READ);
  }

  @Test
  void nameConstantsExistForKnownTools() {
    // 抽查名称常量的实际值（工具名是持久化权限规则的键，改值等于让用户的
    // 「始终允许」全部失效——这是需要显式决策的事，不应被静默改掉）。
    assertEquals("file_read", ToolNames.FILE_READ);
    assertEquals("file_write", ToolNames.FILE_WRITE);
    assertEquals("shell_execute", ToolNames.SHELL_EXECUTE);
    assertEquals("file_read", new FileReadTool().getName());
    assertEquals("file_write", new FileWriteTool().getName());
    assertEquals("grep", new GrepTool().getName());
  }

  // ---- schema 校验 ----

  private static List<String> validateSchema(String toolName, JSONObject schema) {
    List<String> problems = new ArrayList<>();
    if (!"object".equals(schema.optString("type"))) {
      problems.add(toolName + ": schema.type 应为 object，实际 " + schema.opt("type"));
    }
    Object props = schema.opt("properties");
    if (props != null && !(props instanceof JSONObject)) {
      problems.add(toolName + ": properties 应为对象，实际 " + props.getClass().getSimpleName());
    }
    Object required = schema.opt("required");
    if (required != null) {
      if (!(required instanceof JSONArray)) {
        problems.add(toolName + ": required 应为数组，实际 " + required.getClass().getSimpleName());
      } else {
        JSONArray arr = (JSONArray) required;
        JSONObject propsObj = props instanceof JSONObject ? (JSONObject) props : new JSONObject();
        for (int i = 0; i < arr.length(); i++) {
          String field = arr.optString(i, "");
          if (field.isEmpty()) {
            problems.add(toolName + ": required[" + i + "] 为空字符串");
          } else if (!propsObj.has(field)) {
            // 字段名拼错 = 模型照 required 传参也永远过不了校验（若 provider 校验）
            // 或传了一个 schema 里不存在的参数。
            problems.add(toolName + ": required 里的 " + field + " 不在 properties 中");
          }
        }
      }
    }
    return problems;
  }

  /** 捕获异常地取一个值：让失败以「收集问题」而非「测试中断」呈现。 */
  private interface Getter<T> {
    T get();
  }

  private static <T> T safe(BaseTool tool, Getter<T> getter) {
    try {
      return getter.get();
    } catch (Throwable t) {
      return null;
    }
  }

  // ---- 最小端口实现（契约测试只读 schema/元数据，不触发执行）----

  private static final class NoopHttpPort implements HttpPort {
    @Override
    public String getText(String url, java.util.Map<String, String> headers) {
      return "";
    }

    @Override
    public BinaryResponse getBytes(String url, java.util.Map<String, String> headers) {
      return new BinaryResponse("application/octet-stream", new byte[0]);
    }

    @Override
    public TextResponse postJson(
        String url, String jsonBody, java.util.Map<String, String> headers) {
      return new TextResponse("{}", java.util.Map.of());
    }

    @Override
    public StreamHandle openStream(String url, java.util.Map<String, String> headers) {
      return HttpPort.streamHandle(200, new java.io.StringReader(""));
    }
  }

  private static final class NoopHttpRequestPort implements HttpRequestPort {
    @Override
    public Response request(
        String method,
        String url,
        java.util.Map<String, String> headers,
        String body,
        int timeoutMs) {
      return new Response(200, "OK", "application/json", java.util.Map.of(), "{}");
    }
  }

  private static final class NoopImageProcessingPort implements ImageProcessingPort {
    @Override
    public Decoded decode(byte[] raw, long maxPixels) {
      return null;
    }

    @Override
    public Encoded encodePng(int[] pixels, int width, int height, long maxInlineBytes) {
      return new Encoded(new byte[0], width, height);
    }

    @Override
    public Decoded scale(
        int[] pixels, int width, int height, int targetWidth, int targetHeight, String filter) {
      return new Decoded(pixels, targetWidth, targetHeight);
    }
  }
}
