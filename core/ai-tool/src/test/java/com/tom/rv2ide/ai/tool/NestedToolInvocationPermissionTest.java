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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tom.rv2ide.ai.tool.api.ToolCall;
import com.tom.rv2ide.ai.tool.api.ToolCategory;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * 编排类工具的子调用必须经过权限判定。
 *
 * <p>背景：{@code phone_test_scenario} / {@code phone_action_capture} 这类工具会用
 * 任意已注册工具当步骤（{@code shell_execute}、{@code file_delete}、
 * {@code phone_clear_data} …）。早先它们直接调用 {@code BaseTool.execute}，
 * 于是内层工具自身的 {@code needsConfirmation()} 不会被复查——用户只在确认框里
 * 看到一大坨 steps JSON，未必意识到其中一步的破坏性。
 *
 * <p>这些断言钉住修复后的语义：<b>只读模式</b>下，场景里嵌的写操作会被拒绝；
 * 若有人把实现改回直接 {@code execute}，本测试会失败。
 */
final class NestedToolInvocationPermissionTest {

  /** 记录 execute 是否真的跑过，用来区分「被拒绝」与「根本没执行」。 */
  private static final class ProbeTool extends BaseTool {
    private final String name;
    private final ToolCategory category;
    private final boolean needsConfirmation;
    boolean executed = false;

    ProbeTool(String name, ToolCategory category, boolean needsConfirmation) {
      this.name = name;
      this.category = category;
      this.needsConfirmation = needsConfirmation;
    }

    @Override
    public String getName() {
      return name;
    }

    @Override
    public String getDescription() {
      return "测试用探针工具";
    }

    @Override
    public ToolCategory getCategory() {
      return category;
    }

    @Override
    public boolean needsConfirmation() {
      return needsConfirmation;
    }

    @Override
    public JSONObject getParameters() {
      return new JSONObject();
    }

    @Override
    public ToolResult execute(JSONObject input, ToolContext context) {
      executed = true;
      return ToolResult.of("", name, "probe ran", false);
    }
  }

  private static ToolSettingsPort readonlySettings() {
    return new ToolSettingsPort() {
      @Override
      public String getPermissionMode() {
        return ToolSettingsPort.PERMISSION_READONLY;
      }
    };
  }

  private static ToolSettingsPort confirmSettings() {
    return new ToolSettingsPort() {
      @Override
      public String getPermissionMode() {
        return ToolSettingsPort.PERMISSION_CONFIRM;
      }

      @Override
      public boolean areDangerousToolsConfirmed() {
        // 刻意返回 false：模拟「用户没有勾选始终允许」。
        // 此时普通 execute 会停下来要确认（在无 UI 的单测里等同于被拒），
        // 而 executeConfirmed 必须直接放行——这正是编排工具需要的行为：
        // 外层已确认过，内层不再逐级弹窗。
        return false;
      }
    };
  }

  private static ToolExecutor executorFor(ToolRegistry registry, ToolSettingsPort settings) {
    return new ToolExecutor(registry, new ToolPermissionService(settings, registry), null);
  }

  @Test
  void readonlyModeRejectsWriteInsideScenario() {
    ToolRegistry registry = new ToolRegistry();
    ProbeTool dangerous = new ProbeTool("probe_write", ToolCategory.WRITE, false);
    registry.register(dangerous);

    ToolExecutor executor = executorFor(registry, readonlySettings());
    ToolResult result =
        new ExecutorToolInvoker(executor).invoke("probe_write", new JSONObject(), null);

    // 关键断言：只读模式下，子调用被拒绝，且工具**没有真正执行**。
    // 若改回直接 tool.execute(...)，这两条都会失败。
    assertTrue(result.isError(), "只读模式下写工具的子调用必须被拒绝");
    assertFalse(dangerous.executed, "被拒绝的写工具不应真正执行");
  }

  @Test
  void readonlyModeStillAllowsReadInsideScenario() {
    ToolRegistry registry = new ToolRegistry();
    ProbeTool read = new ProbeTool("probe_read", ToolCategory.READ, false);
    registry.register(read);

    ToolExecutor executor = executorFor(registry, readonlySettings());
    ToolResult result =
        new ExecutorToolInvoker(executor).invoke("probe_read", new JSONObject(), null);

    assertFalse(result.isError(), "只读模式下读工具应放行");
    assertTrue(read.executed);
  }

  /**
   * 外层已确认时，内层不再逐级弹窗（否则多步场景每步都弹，不可用）。
   *
   * <p>本用例特意让 {@code areDangerousToolsConfirmed()=false}：此时普通
   * {@code ToolExecutor.execute} 会停下来要确认（无 UI 的单测里等同于被拒），
   * 而 {@code executeConfirmed} 直接放行。因此它能真正区分两条路径——
   * 若实现改回 {@code execute}，本用例会失败。
   */
  @Test
  void confirmedOuterContextSkipsInnerPromptButExecutes() {
    ToolRegistry registry = new ToolRegistry();
    ProbeTool interactive = new ProbeTool("probe_click", ToolCategory.SYSTEM, true);
    registry.register(interactive);

    ToolExecutor executor = executorFor(registry, confirmSettings());

    // 对照：未经确认的普通调用在同样设置下会被拦下。
    ToolResult plain = executor.execute(
        new ToolCall("plain", "probe_click", "{}"), null);
    assertTrue(plain.isError(), "未确认的交互工具普通调用应被拦下（对照组）");
    assertFalse(interactive.executed, "对照组不应真正执行");

    // 编排工具走 executeConfirmed：放行且真的执行。
    ToolResult result =
        new ExecutorToolInvoker(executor).invoke("probe_click", new JSONObject(), null);
    assertFalse(result.isError(), "外层已确认时内层不应再被拦下");
    assertTrue(interactive.executed);
  }

  @Test
  void unknownNestedToolFailsClearly() {
    ToolRegistry registry = new ToolRegistry();
    ToolExecutor executor = executorFor(registry, readonlySettings());

    ToolResult result =
        new ExecutorToolInvoker(executor).invoke("no_such_tool", new JSONObject(), null);
    assertTrue(result.isError());
  }

  @Test
  void nullExecutorIsRejectedAtConstruction() {
    assertThrows(IllegalArgumentException.class, () -> new ExecutorToolInvoker(null));
  }

  @Test
  void invokerPassesThroughRegisteredToolName() {
    ToolRegistry registry = new ToolRegistry();
    ProbeTool probe = new ProbeTool("probe_read", ToolCategory.READ, false);
    registry.register(probe);
    ToolExecutor executor = executorFor(registry, readonlySettings());

    ToolResult result = new ExecutorToolInvoker(executor).invoke("probe_read", null, null);
    assertNotNull(result);
    assertEquals("probe_read", result.getToolName());
  }
}
