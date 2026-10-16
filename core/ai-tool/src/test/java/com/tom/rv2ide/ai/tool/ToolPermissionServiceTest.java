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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tom.rv2ide.ai.tool.api.PermissionResult;
import com.tom.rv2ide.ai.tool.api.ToolCategory;
import com.tom.rv2ide.ai.tool.api.ToolNames;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link ToolPermissionService} 的三档权限语义与确认判定。
 *
 * <p>此前只经 ToolExecutor 间接覆盖。直接测的意义：确认判定在注册表不可用时
 * 必须按工具名兜底（file_delete/shell_execute 恒确认），把危险操作放过去
 * 是安全回退里最不能接受的失败方向。
 */
final class ToolPermissionServiceTest {

  /** 可配置的假设置。 */
  private static ToolSettingsPort settings(String mode, Set<String> enabled) {
    return new ToolSettingsPort() {
      @Override
      public String getPermissionMode() {
        return mode;
      }

      @Override
      public Set<String> getEnabledToolNames() {
        return enabled;
      }
    };
  }

  // ---- canExecuteTool ----

  @Test
  void autoModeAllowsEverything() {
    ToolPermissionService service =
        new ToolPermissionService(settings(ToolSettingsPort.PERMISSION_AUTO, null), null);
    assertTrue(service.canExecuteTool("shell_execute", ToolCategory.SYSTEM).isAllowed());
  }

  @Test
  void emptyNameIsDenied() {
    ToolPermissionService service =
        new ToolPermissionService(settings(ToolSettingsPort.PERMISSION_AUTO, null), null);
    assertFalse(service.canExecuteTool("", ToolCategory.READ).isAllowed());
    assertFalse(service.canExecuteTool(null, ToolCategory.READ).isAllowed());
  }

  @Test
  void disabledToolIsDeniedEvenInAutoMode() {
    ToolPermissionService service =
        new ToolPermissionService(
            settings(ToolSettingsPort.PERMISSION_AUTO, Collections.singleton("file_read")), null);
    assertFalse(service.canExecuteTool("shell_execute", ToolCategory.SYSTEM).isAllowed());
    assertTrue(service.canExecuteTool("file_read", ToolCategory.READ).isAllowed());
  }

  @Test
  void readonlyModeAllowsReadAndGenerateOnly() {
    ToolPermissionService service =
        new ToolPermissionService(settings(ToolSettingsPort.PERMISSION_READONLY, null), null);
    assertTrue(service.canExecuteTool("file_read", ToolCategory.READ).isAllowed());
    assertTrue(service.canExecuteTool("image_tool", ToolCategory.GENERATE).isAllowed());
    assertFalse(service.canExecuteTool("file_write", ToolCategory.WRITE).isAllowed());
    assertFalse(service.canExecuteTool("shell_execute", ToolCategory.SYSTEM).isAllowed());
    // 拒绝文案必须指路（告诉用户怎么改）。
    String message = service.canExecuteTool("file_write", ToolCategory.WRITE).getReason();
    assertTrue(message.contains("只读模式"), message);
  }

  @Test
  void readonlyModeAlwaysAllowsTodoUpdate() {
    // 待办只是记录进度、不改用户代码——只读模式下应放行（否则长任务无法记录状态）。
    ToolPermissionService service =
        new ToolPermissionService(settings(ToolSettingsPort.PERMISSION_READONLY, null), null);
    assertTrue(service.canExecuteTool(ToolNames.TODO_UPDATE, ToolCategory.WRITE).isAllowed());
  }

  @Test
  void nullSettingsFallsBackToDefaults() {
    // 构造器收到 null 时应回落默认（auto），不得 NPE。
    ToolPermissionService service = new ToolPermissionService(null, null);
    assertTrue(service.canExecuteTool("file_read", ToolCategory.READ).isAllowed());
  }

  // ---- needsConfirmation ----

  @Test
  void autoModeNeverNeedsConfirmation() {
    ToolPermissionService service =
        new ToolPermissionService(settings(ToolSettingsPort.PERMISSION_AUTO, null), null);
    assertFalse(service.needsConfirmation(ToolNames.SHELL_EXECUTE));
    assertFalse(service.needsConfirmation(ToolNames.FILE_DELETE));
  }

  @Test
  void registryLookupDrivesConfirmationWhenAvailable() {
    // 注册表可用时以工具的 needsConfirmation() 为准。
    ToolRegistry registry = new ToolRegistry();
    registry.register(new ConfirmingTool("needs_confirm"));
    registry.register(new SafeTool("safe"));
    ToolPermissionService service =
        new ToolPermissionService(settings(ToolSettingsPort.PERMISSION_CONFIRM, null), registry);

    assertTrue(service.needsConfirmation("needs_confirm"));
    assertFalse(service.needsConfirmation("safe"));
  }

  @Test
  void unknownToolInConfirmModeDefaultsToConfirm() {
    // 注册表里没有的工具（名字拼写错误等）：confirm 模式下按需确认处理——宁可多问。
    ToolPermissionService service =
        new ToolPermissionService(settings(ToolSettingsPort.PERMISSION_CONFIRM, null), null);
    assertTrue(service.needsConfirmation("mystery_tool"));
  }

  @Test
  void dangerousNamesAlwaysNeedConfirmationEvenWithoutRegistry() {
    // 注册表不可用时按名字兜底：这两个是明确的危险工具，必须确认。
    ToolPermissionService service =
        new ToolPermissionService(settings(ToolSettingsPort.PERMISSION_READONLY, null), null);
    assertTrue(service.needsConfirmation(ToolNames.FILE_DELETE));
    assertTrue(service.needsConfirmation(ToolNames.SHELL_EXECUTE));
  }

  @Test
  void enabledNamesSetDoesNotAffectConfirmation() {
    // 启用集合只管「能不能跑」，与「要不要问」是正交的两件事。
    Set<String> enabled = new HashSet<>();
    enabled.add("shell_execute");
    ToolPermissionService service =
        new ToolPermissionService(settings(ToolSettingsPort.PERMISSION_CONFIRM, enabled), null);
    assertTrue(service.needsConfirmation("shell_execute"));
  }

  // ---- DangerousToolDecision 枚举 ----

  @Test
  void dangerousToolDecisionHasExactlyThreeStates() {
    // 三档是本枚举存在的全部理由（布尔无法区分「这次」与「以后」）；
    // 增加/删除档位会改变确认弹窗的语义，必须显式失败提醒。
    DangerousToolDecision[] values = DangerousToolDecision.values();
    assertEquals(3, values.length);
    assertEquals(DangerousToolDecision.ALLOW_ONCE, DangerousToolDecision.valueOf("ALLOW_ONCE"));
    assertEquals(DangerousToolDecision.ALLOW_ALWAYS, DangerousToolDecision.valueOf("ALLOW_ALWAYS"));
    assertEquals(DangerousToolDecision.DENY, DangerousToolDecision.valueOf("DENY"));
  }

  // ---- 假工具 ----

  private static final class ConfirmingTool extends BaseTool {
    private final String name;

    ConfirmingTool(String name) {
      this.name = name;
    }

    @Override
    public String getName() {
      return name;
    }

    @Override
    public String getDescription() {
      return "confirming";
    }

    @Override
    public ToolCategory getCategory() {
      return ToolCategory.WRITE;
    }

    @Override
    public boolean needsConfirmation() {
      return true;
    }

    @Override
    public org.json.JSONObject getParameters() {
      return new org.json.JSONObject();
    }

    @Override
    public com.tom.rv2ide.ai.tool.api.ToolResult execute(
        org.json.JSONObject input, ToolContext context) {
      return ok("ok");
    }
  }

  private static final class SafeTool extends BaseTool {
    private final String name;

    SafeTool(String name) {
      this.name = name;
    }

    @Override
    public String getName() {
      return name;
    }

    @Override
    public String getDescription() {
      return "safe";
    }

    @Override
    public ToolCategory getCategory() {
      return ToolCategory.READ;
    }

    @Override
    public org.json.JSONObject getParameters() {
      return new org.json.JSONObject();
    }

    @Override
    public com.tom.rv2ide.ai.tool.api.ToolResult execute(
        org.json.JSONObject input, ToolContext context) {
      return ok("ok");
    }
  }
}
