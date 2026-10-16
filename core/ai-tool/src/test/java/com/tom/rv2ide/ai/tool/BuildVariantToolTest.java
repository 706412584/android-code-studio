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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * {@link BuildVariantTool} 的行为：参数校验、五态文案、只读/并发判定。
 *
 * <p>重点是**五种切换结果必须给出不同的、可执行的下一步文案**——切换是长操作，
 * 把「还在同步」误读成「已经切好」会让模型继续用旧任务名构建。
 */
class BuildVariantToolTest {

  /** 假端口：可配置列表数据与 switchTo 结果；记录调用参数。 */
  private static final class FakePort implements BuildVariantPort {
    List<Module> modules = new ArrayList<>();
    Result switchResult = Result.failed("unset");
    String moduleArg = null;
    String variantArg = null;
    long timeoutArg = -1;
    boolean available = true;
    String reason = "";

    @Override
    public List<Module> listModules() {
      return modules;
    }

    @Override
    public boolean isAvailable() {
      return available;
    }

    @Override
    public String unavailableReason() {
      return reason;
    }

    @Override
    public Result switchTo(String modulePath, String variantName, long timeoutMs,
        ToolContext.CancellationSignal cancel) {
      moduleArg = modulePath;
      variantArg = variantName;
      timeoutArg = timeoutMs;
      return switchResult;
    }
  }

  private static ToolContext contextWith(BuildVariantPort port) {
    return ToolContext.builder().homePath("/tmp").buildVariant(port).build();
  }

  private static String contentOf(ToolResult result) {
    return result.getContent();
  }

  private static BuildVariantPort.Module appModule() {
    List<BuildVariantPort.Variant> variants = Arrays.asList(
        new BuildVariantPort.Variant("debug", ":app:assembleDebug", "com.example.debug"),
        new BuildVariantPort.Variant("release", ":app:assembleRelease", "com.example"));
    return new BuildVariantPort.Module(":app", "app", true, "com.example", variants, "debug");
  }

  // ---- 1 ----

  @Test
  void reportsExplicitErrorWhenPortMissing() {
    ToolResult result = new BuildVariantTool().execute(new JSONObject(),
        ToolContext.builder().homePath("/tmp").build());
    assertTrue(result.isError());
    assertTrue(contentOf(result).contains("未接入"));
  }

  // ---- 2 ----

  @Test
  void reportsUnavailableReason() {
    FakePort port = new FakePort();
    port.reason = "当前没有已打开/已同步的项目。请先在编辑器中打开项目。";
    ToolResult result = new BuildVariantTool().execute(new JSONObject(), contextWith(port));
    assertTrue(result.isError());
    assertTrue(contentOf(result).contains("请先在编辑器中打开项目"));
  }

  // ---- 3 ----

  @Test
  void listsModulesWithSelectionAndTaskNames() {
    FakePort port = new FakePort();
    port.modules = Collections.singletonList(appModule());
    ToolResult result = new BuildVariantTool().execute(new JSONObject(), contextWith(port));
    assertFalse(result.isError());
    String text = contentOf(result);
    assertTrue(text.contains(":app"));
    assertTrue(text.contains("release"));
    assertTrue(text.contains("（当前）"));
    assertTrue(text.contains(":app:assembleRelease"));
    assertTrue(text.contains("com.example"));
  }

  // ---- 4 ----

  @Test
  void listEmptyExplainsSync() {
    FakePort port = new FakePort();
    port.modules = Collections.emptyList();
    ToolResult result = new BuildVariantTool().execute(new JSONObject(), contextWith(port));
    assertFalse(result.isError());
    assertTrue(contentOf(result).contains("同步"));
  }

  // ---- 5 ----

  @Test
  void listUnknownModuleReportsAvailable() {
    FakePort port = new FakePort();
    port.modules = Collections.singletonList(appModule());
    JSONObject input = new JSONObject();
    try {
      input.put("module", ":lib");
    } catch (org.json.JSONException ignored) {
    }
    ToolResult result = new BuildVariantTool().execute(input, contextWith(port));
    assertTrue(result.isError());
    assertTrue(contentOf(result).contains(":app"));
  }

  // ---- 6 ----

  @Test
  void selectRequiresModuleAndVariant() {
    FakePort port = new FakePort();
    JSONObject input = new JSONObject();
    try {
      input.put("action", "select");
    } catch (org.json.JSONException ignored) {
    }
    ToolResult result = new BuildVariantTool().execute(input, contextWith(port));
    assertTrue(result.isError());
    assertTrue(contentOf(result).contains("module"));
    assertTrue(contentOf(result).contains("list"));
  }

  // ---- 7 ----

  @Test
  void selectSwitchedReportsTaskName() {
    FakePort port = new FakePort();
    port.switchResult = BuildVariantPort.Result.switched(":app", "release", ":app:assembleRelease");
    ToolResult result = new BuildVariantTool().execute(selectInput(), contextWith(port));
    assertFalse(result.isError());
    String text = contentOf(result);
    assertTrue(text.contains(":app:assembleRelease"));
    assertTrue(text.contains("旧变体"));
  }

  // ---- 8 ----

  @Test
  void selectAlreadySelected() {
    FakePort port = new FakePort();
    port.switchResult = BuildVariantPort.Result.alreadySelected(":app", "release");
    ToolResult result = new BuildVariantTool().execute(selectInput(), contextWith(port));
    assertFalse(result.isError());
    assertTrue(contentOf(result).contains("已是"));
  }

  // ---- 9 ----

  @Test
  void selectFailedCarriesReason() {
    FakePort port = new FakePort();
    port.switchResult = BuildVariantPort.Result.failed("模块 \":app\" 没有名为 \"foo\" 的变体。");
    ToolResult result = new BuildVariantTool().execute(selectInput(), contextWith(port));
    assertFalse(result.isError());
    assertTrue(contentOf(result).contains("没有名为"));
  }

  // ---- 10 ----

  @Test
  void selectTimedOut() {
    FakePort port = new FakePort();
    port.switchResult = BuildVariantPort.Result.timedOut(":app", "release", "同步可能仍在后台进行。");
    ToolResult result = new BuildVariantTool().execute(selectInput(), contextWith(port));
    assertFalse(result.isError());
    String text = contentOf(result);
    assertTrue(text.contains("超时"));
    assertTrue(text.contains("仍在后台"));
  }

  // ---- 11 ----

  @Test
  void selectCancelled() {
    FakePort port = new FakePort();
    port.switchResult = BuildVariantPort.Result.cancelled(":app", "release", "用户停止。");
    ToolResult result = new BuildVariantTool().execute(selectInput(), contextWith(port));
    assertFalse(result.isError());
    assertTrue(contentOf(result).contains("不会因此中止"));
  }

  // ---- 12 ----

  @Test
  void timeoutClampedToMax() {
    FakePort port = new FakePort();
    port.switchResult = BuildVariantPort.Result.alreadySelected(":app", "release");
    JSONObject input = selectInput();
    try {
      input.put("timeoutMs", BuildVariantTool.MAX_TIMEOUT_MS * 10);
    } catch (org.json.JSONException ignored) {
    }
    new BuildVariantTool().execute(input, contextWith(port));
    assertEquals(BuildVariantTool.MAX_TIMEOUT_MS, port.timeoutArg);
  }

  // ---- 13 ----

  @Test
  void unknownActionRejected() {
    FakePort port = new FakePort();
    JSONObject input = new JSONObject();
    try {
      input.put("action", "foo");
    } catch (org.json.JSONException ignored) {
    }
    ToolResult result = new BuildVariantTool().execute(input, contextWith(port));
    assertTrue(result.isError());
    assertTrue(contentOf(result).contains("list"));
  }

  // ---- 14 ----

  @Test
  void toolMetadata() {
    BuildVariantTool tool = new BuildVariantTool();
    assertEquals(ToolCategory.SYSTEM, tool.getCategory());
    assertTrue(tool.needsConfirmation());
    assertFalse(tool.isAllowedInReadonlyMode());
    assertFalse(tool.isConcurrencySafe());
  }

  // ---- 跨项目守卫 ----

  @Test
  void mismatchedProjectIsRejectedBeforeTouchingPort() {
    // 会话绑定 /projB、编辑器打开 /projA：变体工具必须拒绝，且不调用端口——
    // 静默作用于编辑器项目是数据损坏级的问题。
    FakePort port = new FakePort();
    port.modules = Arrays.asList(appModule());
    ToolContext context =
        ToolContext.builder()
            .homePath("/projB")
            .openProjectPath("/projA")
            .buildVariant(port)
            .build();

    ToolResult result = new BuildVariantTool().execute(new JSONObject(), context);

    assertTrue(result.isError());
    assertTrue(result.getContent().contains("/projB"), result.getContent());
    assertTrue(result.getContent().contains("/projA"), result.getContent());
    assertTrue(result.getContent().contains("shell_execute"), "应给出可行替代：" + result.getContent());
  }

  @Test
  void matchingProjectPassesGuard() {
    FakePort port = new FakePort();
    port.modules = Arrays.asList(appModule());
    ToolContext context =
        ToolContext.builder()
            .homePath("/projA")
            .openProjectPath("/projA")
            .buildVariant(port)
            .build();

    ToolResult result = new BuildVariantTool().execute(new JSONObject(), context);

    assertFalse(result.isError(), result.getContent());
  }

  @Test
  void trailingSeparatorIsNotAMismatch() {
    // `/projA/` 与 `/projA` 是同一目录——路径末尾斜杠不该误报。
    FakePort port = new FakePort();
    port.modules = Arrays.asList(appModule());
    ToolContext context =
        ToolContext.builder()
            .homePath("/projA/")
            .openProjectPath("/projA")
            .buildVariant(port)
            .build();

    ToolResult result = new BuildVariantTool().execute(new JSONObject(), context);

    assertFalse(result.isError(), result.getContent());
  }

  @Test
  void emptyOpenProjectSkipsGuard() {
    // 编辑器没打开项目时守卫放行——错误应由「端口不可用」分支报出，不重复报。
    FakePort port = new FakePort();
    port.modules = Arrays.asList(appModule());
    ToolContext context =
        ToolContext.builder().homePath("/projB").buildVariant(port).build();

    ToolResult result = new BuildVariantTool().execute(new JSONObject(), context);

    assertFalse(result.isError(), result.getContent());
  }

  private static JSONObject selectInput() {
    JSONObject input = new JSONObject();
    try {
      input.put("action", "select");
      input.put("module", ":app");
      input.put("variant", "release");
    } catch (org.json.JSONException ignored) {
    }
    return input;
  }
}
