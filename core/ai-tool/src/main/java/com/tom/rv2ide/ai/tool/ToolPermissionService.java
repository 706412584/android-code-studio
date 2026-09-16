/*
 * This file is part of AndroidCodeStudio.
 *
 * Adapted from LineCode Pro (https://github.com/LangLang03/LineCodePro),
 * licensed under the GNU General Public License v3.0 or later.
 * Modifications for AndroidCodeStudio are licensed under the same terms.
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

import com.tom.rv2ide.ai.tool.api.PermissionResult;
import com.tom.rv2ide.ai.tool.api.ToolCategory;
import com.tom.rv2ide.ai.tool.api.ToolInfo;
import com.tom.rv2ide.ai.tool.api.ToolNames;

/**
 * 工具权限判断：决定某个工具在当前配置下能否执行、是否需要用户确认。
 *
 * <p>三档权限语义（与上游一致）：
 * <ul>
 *   <li>{@link ToolSettingsPort#PERMISSION_AUTO} — 永不拦截
 *   <li>{@link ToolSettingsPort#PERMISSION_CONFIRM} — 工具自声明危险的（如 shell_execute、
 *       file_delete）需确认；其余放行
 *   <li>{@link ToolSettingsPort#PERMISSION_READONLY} — 只允许 READ / GENERATE 类工具
 * </ul>
 *
 * <p><b>与上游的差异</b>：LineCode Pro 依赖 {@code ToolSettingsStore}（Room + Context）以及
 * SSH / TerminalProvider 执行模式的判定。本移植改用 {@link ToolSettingsPort} 窄接口，
 * 并去掉 SSH 相关的分支——只读模式下允许 shell 是因为它可能指向远程主机，
 * 而本移植的 shell 只在本机执行，因此不放行。
 */
public final class ToolPermissionService {

  private final ToolSettingsPort settings;
  private final ToolRegistry registry;

  public ToolPermissionService(ToolSettingsPort settings, ToolRegistry registry) {
    this.settings = settings == null ? ToolSettingsPort.defaults() : settings;
    this.registry = registry;
  }

  /** 工具在当前配置下是否允许执行。 */
  public PermissionResult canExecuteTool(String toolName, ToolCategory category) {
    if (toolName == null || toolName.isEmpty()) {
      return PermissionResult.denied("工具名为空");
    }

    if (!isToolEnabled(toolName)) {
      return PermissionResult.denied("工具未启用: " + toolName);
    }

    if (ToolSettingsPort.PERMISSION_READONLY.equals(settings.getPermissionMode())
        && !isReadonlyAllowed(category)
        && !isReadonlyAlwaysAllowed(toolName)) {
      return PermissionResult.denied(
          "只读模式下不允许执行 " + toolName + "。请在权限设置中切换到自动或确认模式。");
    }

    return PermissionResult.allowed();
  }

  /** 该工具是否需要用户确认后才执行。 */
  public boolean needsConfirmation(String toolName) {
    if (ToolSettingsPort.PERMISSION_AUTO.equals(settings.getPermissionMode())) {
      return false;
    }

    ToolInfo tool = registry == null ? null : registry.get(toolName);
    if (tool != null) {
      return tool.needsConfirmation();
    }

    // 注册表不可用时按工具名兜底判断，避免把危险操作放过去。
    if (ToolNames.FILE_DELETE.equals(toolName) || ToolNames.SHELL_EXECUTE.equals(toolName)) {
      return true;
    }

    return ToolSettingsPort.PERMISSION_CONFIRM.equals(settings.getPermissionMode());
  }

  /**
   * 工具是否在启用列表中。
   *
   * <p>启用集合为空表示「不限制」，这样默认配置无需逐项登记即可使用全部内置工具。
   */
  private boolean isToolEnabled(String toolName) {
    java.util.Set<String> enabled = settings.getEnabledToolNames();
    if (enabled == null || enabled.isEmpty()) {
      return true;
    }
    return enabled.contains(toolName);
  }

  private static boolean isReadonlyAllowed(ToolCategory category) {
    return category == ToolCategory.READ || category == ToolCategory.GENERATE;
  }

  private static boolean isReadonlyAlwaysAllowed(String toolName) {
    return ToolNames.TODO_UPDATE.equals(toolName);
  }
}
