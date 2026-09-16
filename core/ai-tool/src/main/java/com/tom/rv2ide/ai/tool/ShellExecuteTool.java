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

import com.tom.rv2ide.ai.tool.api.ToolCategory;
import com.tom.rv2ide.ai.tool.api.ToolDisplayCategory;
import com.tom.rv2ide.ai.tool.api.ToolNames;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import org.json.JSONObject;

/**
 * 执行 shell 命令。
 *
 * <p>后端由 {@link ShellBackendRegistry} 决定：优先用配置选定的后端，不可用时回退，
 * 并在结果里如实说明实际使用的后端——权限级别不同，能执行的命令也不同。
 *
 * <p><b>与上游的差异</b>：LineCode Pro 的 {@code ShellExecuteTool} 内联了 SSH /
 * TerminalProvider / proot 三种分支，且硬编码了「是否为内置 provider」的判断。
 * 本移植改为面向 {@link ShellBackend} 接口，后端差异被隔离在各自的实现里。
 * 命令结果不再直接返回给模型，而是先经过长度截断，避免一次 {@code cat} 大文件
 * 就把上下文撑爆。
 */
public final class ShellExecuteTool extends BaseTool {

  /** 返回给模型的输出上限：32KB。超出时保留首尾，中间省略。 */
  private static final int MAX_OUTPUT_CHARS = 32 * 1024;

  /** 单次命令允许的最长超时：10 分钟。 */
  private static final long MAX_TIMEOUT_MS = 10 * 60 * 1000L;

  private final ShellBackendRegistry registry;

  public ShellExecuteTool(ShellBackendRegistry registry) {
    this.registry = registry;
  }

  @Override
  public String getName() {
    return ToolNames.SHELL_EXECUTE;
  }

  @Override
  public String getDescription() {
    return "在本机执行 shell 命令并返回退出码与输出。"
        + "可用于运行脚本、查看文件、调用系统命令。"
        + "注意：能否执行 pm/am 等特权命令取决于当前后端是否有 adb 权限。";
  }

  @Override
  public ToolCategory getCategory() {
    return ToolCategory.SYSTEM;
  }

  @Override
  public ToolDisplayCategory getDisplayCategory() {
    return ToolDisplayCategory.SHELL;
  }

  @Override
  public boolean needsConfirmation() {
    // 执行任意命令是有副作用的操作，确认模式下应经用户同意。
    return true;
  }

  @Override
  public boolean isConcurrencySafe() {
    // 命令可能有依赖顺序（如先 cd 再执行），串行执行更可预期。
    return false;
  }

  @Override
  public JSONObject getParameters() throws org.json.JSONException {
    return new JSONObject()
        .put("type", "object")
        .put(
            "properties",
            new JSONObject()
                .put(
                    "command",
                    new JSONObject()
                        .put("type", "string")
                        .put("description", "要执行的 shell 命令"))
                .put(
                    "cwd",
                    new JSONObject()
                        .put("type", "string")
                        .put("description", "工作目录，省略时使用工作区根目录"))
                .put(
                    "timeoutMs",
                    new JSONObject()
                        .put("type", "number")
                        .put(
                            "description",
                            "超时毫秒数，默认 120000，最大 " + MAX_TIMEOUT_MS)))
        .put("required", new org.json.JSONArray().put("command"));
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    String command = input.optString("command", "").trim();
    if (command.isEmpty()) {
      return error("command 不能为空");
    }
    if (registry == null) {
      return error("shell 后端未配置");
    }

    ShellBackendRegistry.Resolution resolution = registry.resolveActive();
    if (!resolution.isUsable()) {
      return error("没有可用的 shell 后端。" + resolution.getFallbackReason());
    }
    ShellBackend backend = resolution.getBackend();

    String cwd = input.optString("cwd", "").trim();
    if (cwd.isEmpty() && context != null) {
      cwd = context.getHomePath();
    }

    long timeout = (long) input.optDouble("timeoutMs", ShellRequest.DEFAULT_TIMEOUT_MS);
    if (timeout <= 0) {
      timeout = ShellRequest.DEFAULT_TIMEOUT_MS;
    }
    timeout = Math.min(timeout, MAX_TIMEOUT_MS);

    if (context != null) {
      context.reportProgress("执行命令: " + abbreviate(command, 80));
    }

    ShellRequest request = new ShellRequest(command, cwd, timeout, null);
    ShellRequest.ShellResult result;
    try {
      result = backend.execute(request, ShellRequest.ShellOutputSink.NOOP);
    } catch (RuntimeException e) {
      ExceptionUtils.restoreInterrupt(e);
      return error("命令执行异常: " + ExceptionUtils.describeException(e));
    }

    StringBuilder sb = new StringBuilder();
    sb.append("[后端: ").append(backend.displayName()).append("]\n");
    if (resolution.isFallback()) {
      // 如实报告回退，避免模型以为用的是有特权的后端
      sb.append("[注意: 已回退 — ").append(resolution.getFallbackReason()).append("]\n");
    }
    sb.append(truncate(result.toDisplayText()));

    // 命令失败也是有效结果（模型需要看到错误去修正），因此只在超时时标记为错误。
    if (result.isTimedOut()) {
      return error(sb.toString());
    }
    return ok(sb.toString());
  }

  /** 中间截断：保留首尾，中间标注省略量。 */
  static String truncate(String text) {
    if (text == null || text.length() <= MAX_OUTPUT_CHARS) {
      return text == null ? "" : text;
    }
    int half = MAX_OUTPUT_CHARS / 2;
    int omitted = text.length() - MAX_OUTPUT_CHARS;
    return text.substring(0, half)
        + "\n…（省略 " + omitted + " 字符）…\n"
        + text.substring(text.length() - half);
  }

  private static String abbreviate(String value, int max) {
    if (value == null) {
      return "";
    }
    return value.length() <= max ? value : value.substring(0, max) + "…";
  }
}
