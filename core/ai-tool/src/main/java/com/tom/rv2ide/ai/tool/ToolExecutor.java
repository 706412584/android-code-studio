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

import com.tom.rv2ide.ai.tool.api.ErrorLog;
import com.tom.rv2ide.ai.tool.api.PermissionResult;
import com.tom.rv2ide.ai.tool.api.ToolArgsCleaner;
import com.tom.rv2ide.ai.tool.api.ToolCall;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import org.json.JSONObject;

/**
 * 单次工具调用的执行器：校验权限 → 解析参数 → 执行 → 记录 diff。
 *
 * <p><b>关键约定</b>：本类不抛异常表达失败，一律返回 {@link ToolResult} 的 error 变体。
 * 错误要能回灌给模型让它自我修正，而不是中断整轮对话——这是 agent 循环能持续的前提。
 *
 * <p><b>与上游的差异</b>：LineCode Pro 的构造器注入 7 个数据层依赖
 * （ModelStore / SshFileTreeStore / ModelServiceProvider / PromptTemplateRepository /
 * LearningContextStore 等）并在执行时做依赖注入。本移植只保留工具执行真正需要的三样：
 * 注册表、权限服务、可选的 diff 记录器。其余能力已不在移植范围。
 */
public final class ToolExecutor {

  private final ToolRegistry registry;
  private final ToolPermissionService permissionService;
  private final DiffRecorder diffRecorder;

  /**
   * @param registry 工具注册表
   * @param permissionService 权限判定；传 null 则使用默认配置（自动放行）
   * @param diffRecorder diff 记录器；传 null 则不做改动记录
   */
  public ToolExecutor(
      ToolRegistry registry, ToolPermissionService permissionService, DiffRecorder diffRecorder) {
    this.registry = registry;
    this.permissionService =
        permissionService == null
            ? new ToolPermissionService(ToolSettingsPort.defaults(), registry)
            : permissionService;
    this.diffRecorder = diffRecorder;
  }

  /** 执行工具调用。需要确认的工具会被拒绝，除非改用 {@link #executeConfirmed}。 */
  public ToolResult execute(ToolCall toolCall, ToolContext context) {
    return execute(toolCall, context, false);
  }

  /** 在用户已确认的前提下执行工具调用。 */
  public ToolResult executeConfirmed(ToolCall toolCall, ToolContext context) {
    return execute(toolCall, context, true);
  }

  private ToolResult execute(ToolCall toolCall, ToolContext context, boolean confirmed) {
    ToolResult result = executeTool(toolCall, context, confirmed);
    if (result.isError()) {
      ErrorLog.record(
          "tool_execution",
          "Tool failed: " + result.getToolName(),
          null,
          "Call: " + result.getToolCallId() + "\n" + result.getContent());
    }
    return result;
  }

  /**
   * 按规则判定是否放行一次危险调用，必要时才询问用户。
   *
   * <p>判定顺序是刻意的：先查已持久化的规则，命中就直接放行。规则按「工具 + 参数粒度」
   * 匹配（见 {@link ToolPermissionRule}），因此用户对 {@code git status} 点过「始终允许」
   * 不会顺带放行 {@code git push --force}。
   *
   * <p>「本次运行内已确认」的记忆交由实现方的 {@code confirmDangerousTool} 维护——
   * 那里能同时记录工具名与参数粒度，比在这里再放一个粗粒度布尔更精确。
   */
  private boolean confirmViaRules(ToolSettingsPort settings, String toolName, String arguments) {
    String ruleKey = ToolPermissionRule.keyFor(toolName, arguments);
    if (!ruleKey.isEmpty() && settings.hasDangerousToolRule(ruleKey)) {
      return true;
    }
    return settings.confirmDangerousTool(toolName, arguments);
  }

  private ToolResult executeTool(ToolCall toolCall, ToolContext context, boolean confirmed) {
    if (toolCall == null) {
      return ToolResult.error("工具调用为空");
    }
    if (registry == null) {
      return ToolResult.error("工具注册表未初始化");
    }

    String callId = toolCall.getId();
    // 模型可能用别名（如 read 而非 file_read）。权限判定与工具自检都按规范名，
    // 因此这里先归一，后续一律使用规范名。
    String toolName = ToolRegistry.canonicalName(toolCall.getName());

    BaseTool tool = registry.get(toolName);
    if (tool == null) {
      return ToolResult.of(callId, toolName, "未知工具: " + toolName, true);
    }

    PermissionResult permission = permissionService.canExecuteTool(toolName, tool.getCategory());
    if (!permission.isAllowed()) {
      return ToolResult.of(callId, toolName, permission.getReason(), true);
    }

    if (tool.needsConfirmation() && permissionService.needsConfirmation(toolName) && !confirmed) {
      // 带上工具名与参数询问，用户才能判断要放行的是什么。
      ToolSettingsPort callSettings =
          context == null ? ToolSettingsPort.defaults() : context.getSettings();
      if (!confirmViaRules(callSettings, toolName, toolCall.getArguments())) {
        return ToolResult.of(
            callId, toolName, "用户拒绝执行工具 " + toolName + "（或未确认）。", true);
      }
    }

    ToolContext callContext =
        context == null
            ? ToolContext.builder().homePath("").build()
            : context.withToolCallId(callId);

    JSONObject input;
    try {
      String args = ToolArgsCleaner.clean(toolCall.getArguments());
      input = args.trim().isEmpty() ? new JSONObject() : new JSONObject(args);
    } catch (Exception e) {
      ExceptionUtils.restoreInterrupt(e);
      return ToolResult.of(
          callId, toolName, "参数解析失败: " + ExceptionUtils.describeException(e), true);
    }

    try {
      ToolResult result =
          diffRecorder != null && diffRecorder.shouldRecordDiff(tool)
              ? diffRecorder.executeWithDiff(tool, input, callContext)
              : tool.execute(input, callContext);
      return result.withCall(callId, toolName);
    } catch (Exception e) {
      ExceptionUtils.restoreInterrupt(e);
      return ToolResult.of(
          callId, toolName, "工具执行失败: " + ExceptionUtils.describeException(e), true);
    }
  }
}
