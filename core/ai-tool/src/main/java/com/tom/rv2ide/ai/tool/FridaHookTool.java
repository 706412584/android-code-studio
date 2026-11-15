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

import com.tom.rv2ide.ai.tool.api.ToolCategory;
import com.tom.rv2ide.ai.tool.api.ToolDisplayCategory;
import com.tom.rv2ide.ai.tool.api.ToolNames;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import org.json.JSONObject;

/**
 * frida 动态插桩：在运行中的 Android 进程里注入 JS，hook 方法、读内存、改行为。
 *
 * <p><b>危险且需 root</b>：注入的是**别的应用**的进程，需要设备已 root 且 frida-server
 * 在跑。标记 {@link ToolCategory#SYSTEM} + 逐次确认（{@link #isSensitiveCall}）。
 *
 * <p><b>非交互设计</b>：frida 默认进 REPL 一直挂着，这里用 `timeout <n>` 限定运行窗口，
 * 返回窗口内脚本的输出（`console.log`/`send`）。action=ps 列进程则立即返回。
 */
public final class FridaHookTool extends BaseTool {

  /** 默认插桩窗口（秒），可用 timeout_sec 覆盖。 */
  private static final int DEFAULT_TIMEOUT_SEC = 30;

  private final ShellBackendRegistry registry;

  public FridaHookTool(ShellBackendRegistry registry) {
    this.registry = registry;
  }

  @Override
  public String getName() {
    return ToolNames.FRIDA_HOOK;
  }

  @Override
  public String getDescription() {
    return "用 frida 动态插桩 Android 进程（hook 方法/读内存/改行为），需 root 且 frida-server 在跑。"
        + "action=ps 列出可注入进程；action=spawn 启动并注入（frida -U -f 包名 -l 脚本）；"
        + "action=attach 附着到运行中进程（frida -U -n 包名 -l 脚本）。"
        + "script_path 为 JS 脚本路径（先 file_write 写好）。timeout_sec 限定运行窗口，返回窗口内输出。";
  }

  @Override
  public ToolCategory getCategory() {
    return ToolCategory.SYSTEM;
  }

  @Override
  public ToolDisplayCategory getDisplayCategory() {
    return ToolDisplayCategory.SHELL;
  }

  /** 动态插桩危险，任何调用都需用户确认。 */
  @Override
  public boolean isSensitiveCall(JSONObject args) {
    return true;
  }

  @Override
  public JSONObject getParameters() throws org.json.JSONException {
    return new JSONObject()
        .put("type", "object")
        .put(
            "properties",
            new JSONObject()
                .put(
                    "action",
                    new JSONObject()
                        .put("type", "string")
                        .put("description", "ps（列进程，默认）/ spawn（启动注入）/ attach（附着注入）"))
                .put(
                    "package_name",
                    new JSONObject()
                        .put("type", "string")
                        .put("description", "目标应用包名（spawn/attach 必填）"))
                .put(
                    "script_path",
                    new JSONObject()
                        .put("type", "string")
                        .put("description", "JS 插桩脚本路径（spawn/attach 必填）"))
                .put(
                    "timeout_sec",
                    new JSONObject()
                        .put("type", "number")
                        .put("description", "运行窗口秒数（默认 30）")))
        .put("required", new org.json.JSONArray());
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    String action = input.optString("action", "ps").trim().toLowerCase();
    String packageName = input.optString("package_name", "").trim();
    String scriptPath = input.optString("script_path", "").trim();
    int timeoutSec = input.optInt("timeout_sec", DEFAULT_TIMEOUT_SEC);
    if (timeoutSec <= 0 || timeoutSec > 300) {
      timeoutSec = DEFAULT_TIMEOUT_SEC;
    }

    if ((action.equals("spawn") || action.equals("attach"))
        && (packageName.isEmpty() || scriptPath.isEmpty())) {
      return error("action=" + action + " 需要 package_name 与 script_path 参数。");
    }

    String cwd = context == null ? "" : context.getHomePath();
    ShellBackendRegistry.Resolution resolution = registry.resolveActive();
    if (!resolution.isUsable()) {
      return error("没有可用的 shell 后端，无法运行 frida。请在设置里启用 Termux 或 Shizuku 后端。");
    }
    ShellBackend backend = resolution.getBackend();

    String command = buildCommand(backend, cwd, action, packageName, scriptPath, timeoutSec);
    if (command == null) {
      return error(
          ReverseToolSupport.notInstalled(
              "frida",
              "安装方式：Termux `pkg install frida`（或 `pip install frida`）。"
                  + "另需 root 并在设备上运行 frida-server（与 frida 版本一致）。"));
    }

    long timeoutMs = (timeoutSec + 20) * 1000L; // 比脚本窗口略长，避免外层先超时
    ToolContext.CancellationSignal runCancel = context == null ? null : context.getCancellation();
    ShellRequest.CancellationSignal cancel = runCancel == null ? null : runCancel::isCancelled;
    ShellRequest.ShellResult result =
        ReverseToolSupport.run(backend, command, cwd, timeoutMs, cancel);

    String out = result.getStdout() == null ? "" : result.getStdout();
    String err = result.getStderr() == null ? "" : result.getStderr();
    if (result.isTimedOut()) {
      return error("frida 执行超时。\n" + ReverseToolSupport.truncate(out + "\n" + err, 2000));
    }
    if (result.getExitCode() != 0) {
      return error(
          "frida 执行失败（退出码 " + result.getExitCode() + "）。"
              + "若是连接失败，通常是 frida-server 未运行或版本不匹配、设备未 root：\n"
              + ReverseToolSupport.truncate(out + "\n" + err, 2000));
    }
    String text = out.trim().isEmpty() ? err : out;
    return ok(ReverseToolSupport.truncate(text.isEmpty() ? "（无输出）" : text, 8000));
  }

  /** 拼 frida 命令；frida 未安装时返回 null。 */
  private String buildCommand(
      ShellBackend backend,
      String cwd,
      String action,
      String packageName,
      String scriptPath,
      int timeoutSec) {
    if (action.equals("ps")) {
      String fridaPs = ReverseToolSupport.probeExecutable(backend, "frida-ps", cwd);
      return fridaPs == null ? null : ReverseToolSupport.quote(fridaPs) + " -U";
    }
    String frida = ReverseToolSupport.probeExecutable(backend, "frida", cwd);
    if (frida == null) {
      return null;
    }
    String mode = action.equals("attach") ? "-n" : "-f";
    return "timeout "
        + timeoutSec
        + " "
        + ReverseToolSupport.quote(frida)
        + " -U "
        + mode
        + " "
        + ReverseToolSupport.quote(packageName)
        + " -l "
        + ReverseToolSupport.quote(scriptPath)
        + " --no-pause";
  }
}
