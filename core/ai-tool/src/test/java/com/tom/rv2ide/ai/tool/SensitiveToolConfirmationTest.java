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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tom.rv2ide.ai.tool.api.ToolCall;
import com.tom.rv2ide.ai.tool.api.ToolCategory;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * 敏感档调用的确认语义：**逐次确认、不可记忆、不受 AUTO 模式豁免**。
 *
 * <p>背景（reviewer 实测）：影子屏 {@code tap}/{@code type} 的 label 命中「确认支付」
 * 「转账」这类词时，schema 承诺会提级为敏感操作、逐次确认——但移植时这一层没有接上，
 * 无论 label 是什么都走普通确认（用户点一次「始终允许」即永久放行）。这些断言钉住
 * 修复后的语义：把 {@code isSensitiveCall} 的分支删掉，本测试会失败。
 */
final class SensitiveToolConfirmationTest {

  /** 可编程敏感判定的探针工具：参数里 {@code sensitive=true} 即视为敏感。 */
  private static final class ProbeTool extends BaseTool {
    boolean executed = false;

    @Override
    public String getName() {
      return "probe_sensitive";
    }

    @Override
    public String getDescription() {
      return "测试用敏感探针";
    }

    @Override
    public ToolCategory getCategory() {
      return ToolCategory.SYSTEM;
    }

    @Override
    public boolean needsConfirmation() {
      return true;
    }

    @Override
    public boolean isSensitiveCall(JSONObject args) {
      return args != null && args.optBoolean("sensitive", false);
    }

    @Override
    public JSONObject getParameters() {
      return new JSONObject();
    }

    @Override
    public ToolResult execute(JSONObject input, ToolContext context) {
      executed = true;
      return ok("ran");
    }
  }

  /**
   * 记录调用路径的设置：普通确认与敏感确认分开计数；普通确认被「全局放行」短路，
   * 敏感确认不会。
   */
  private static final class RecordingSettings implements ToolSettingsPort {
    int sensitiveCalls = 0;
    int dangerousCalls = 0;
    boolean allowSensitive = true;

    @Override
    public String getPermissionMode() {
      return PERMISSION_AUTO;
    }

    @Override
    public boolean areDangerousToolsConfirmed() {
      // 用户勾了「始终允许」——普通危险工具应被短路放行。
      return true;
    }

    @Override
    public boolean confirmDangerousTool(String conversationId, String toolName, String arguments) {
      dangerousCalls++;
      return true;
    }

    @Override
    public boolean confirmSensitiveTool(String conversationId, String toolName, String arguments) {
      sensitiveCalls++;
      return allowSensitive;
    }
  }

  private static ToolExecutor executorFor(ProbeTool tool, RecordingSettings settings) {
    ToolRegistry registry = new ToolRegistry();
    registry.register(tool);
    return new ToolExecutor(registry, new ToolPermissionService(settings, registry), null);
  }

  /**
   * 上下文必须带上设置——执行器对 {@code context == null} 的调用回落
   * {@link ToolSettingsPort#defaults()}（AUTO + 无 UI），那会让确认逻辑不被触达。
   */
  private static ToolContext contextWith(RecordingSettings settings) {
    return ToolContext.builder().homePath("").settings(settings).conversationId("conv-test").build();
  }

  @Test
  void sensitiveCallBypassesGlobalAllowAndAlwaysAsks() {
    ProbeTool probe = new ProbeTool();
    RecordingSettings settings = new RecordingSettings();
    ToolExecutor executor = executorFor(probe, settings);

    ToolResult result =
        executor.execute(
            new ToolCall("c1", "probe_sensitive", "{\"sensitive\":true}"),
            contextWith(settings));

    assertFalse(result.isError(), "用户同意时敏感调用应执行");
    assertTrue(probe.executed);
    assertEquals(1, settings.sensitiveCalls, "敏感调用必须真的询问一次");
    assertEquals(0, settings.dangerousCalls, "敏感调用不得走普通确认（会被全局放行短路）");
  }

  @Test
  void sensitiveCallIsDeniedWhenUserRefuses() {
    ProbeTool probe = new ProbeTool();
    RecordingSettings settings = new RecordingSettings();
    settings.allowSensitive = false;
    ToolExecutor executor = executorFor(probe, settings);

    ToolResult result =
        executor.execute(
            new ToolCall("c1", "probe_sensitive", "{\"sensitive\":true}"),
            contextWith(settings));

    assertTrue(result.isError(), "用户拒绝后敏感调用必须失败");
    assertFalse(probe.executed, "被拒绝的敏感调用不得执行");
  }

  @Test
  void nonSensitiveCallStillUsesNormalPath() {
    ProbeTool probe = new ProbeTool();
    RecordingSettings settings = new RecordingSettings();
    ToolExecutor executor = executorFor(probe, settings);

    ToolResult result =
        executor.execute(new ToolCall("c1", "probe_sensitive", "{}"), contextWith(settings));

    assertFalse(result.isError(), "非敏感调用在全局放行下应直接执行");
    assertTrue(probe.executed);
    assertEquals(0, settings.sensitiveCalls, "非敏感调用不应触发敏感确认");
  }

  /**
   * AUTO 模式下敏感调用也必须询问：用户选 AUTO 是为了少弹窗，
   * 不是为了让模型静默按下支付按钮。
   */
  @Test
  void autoModeDoesNotExemptSensitiveCalls() {
    ProbeTool probe = new ProbeTool();
    RecordingSettings settings = new RecordingSettings();
    settings.allowSensitive = false; // AUTO + 拒绝：若被豁免则 executed 会变 true
    ToolExecutor executor = executorFor(probe, settings);

    ToolResult result =
        executor.execute(
            new ToolCall("c1", "probe_sensitive", "{\"sensitive\":true}"),
            contextWith(settings));

    assertTrue(result.isError(), "AUTO 模式下敏感调用被拒后必须失败");
    assertFalse(probe.executed, "AUTO 模式不得让敏感调用绕过确认");
  }

  /** 已确认（executeConfirmed）的嵌套调用不再重复询问，敏感档同理（外层已问过）。 */
  @Test
  void alreadyConfirmedSensitiveCallDoesNotAskAgain() {
    ProbeTool probe = new ProbeTool();
    RecordingSettings settings = new RecordingSettings();
    ToolExecutor executor = executorFor(probe, settings);

    ToolResult result =
        executor.executeConfirmed(
            new ToolCall("c1", "probe_sensitive", "{\"sensitive\":true}"),
            contextWith(settings));

    assertFalse(result.isError());
    assertTrue(probe.executed);
    assertEquals(0, settings.sensitiveCalls, "外层已确认的内层调用不应重复弹窗");
  }
}
