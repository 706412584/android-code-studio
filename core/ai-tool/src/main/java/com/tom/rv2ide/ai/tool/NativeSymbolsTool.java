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
 * 查看原生库（.so）的符号表与 ELF 头：导出/导入函数、架构、依赖库。
 *
 * <p>与 {@link BinaryDisasmTool}（radare2 反汇编指令）互补：本工具看**符号层面**
 * （导出了哪些函数、依赖哪些库、什么架构），用 binutils 的 `nm` / `readelf`，
 * 比 radare2 更轻更快。Termux 自带 binutils。
 */
public final class NativeSymbolsTool extends BaseTool {

  private static final long TIMEOUT_MS = 60_000L;
  private static final int MAX_OUTPUT_CHARS = 12 * 1024;

  private final ShellBackendRegistry registry;

  public NativeSymbolsTool(ShellBackendRegistry registry) {
    this.registry = registry;
  }

  @Override
  public String getName() {
    return ToolNames.NATIVE_SYMBOLS;
  }

  @Override
  public String getDescription() {
    return "查看原生库（.so）的符号表与 ELF 头：action=symbols 列出导出/导入函数"
        + "（readelf --dyn-syms）；action=header 看架构/类型/入口（readelf -h）；"
        + "action=needed 看依赖的共享库（readelf -d）。用 binutils，比 radare2 更轻更快。";
  }

  @Override
  public ToolCategory getCategory() {
    return ToolCategory.READ;
  }

  @Override
  public ToolDisplayCategory getDisplayCategory() {
    return ToolDisplayCategory.READ;
  }

  @Override
  public boolean isAllowedInReadonlyMode() {
    return true;
  }

  @Override
  public boolean isConcurrencySafe() {
    return true;
  }

  @Override
  public JSONObject getParameters() throws org.json.JSONException {
    return new JSONObject()
        .put("type", "object")
        .put(
            "properties",
            new JSONObject()
                .put("file_path", new JSONObject().put("type", "string").put("description", "原生库 .so 路径"))
                .put("action", new JSONObject().put("type", "string").put("description", "symbols（默认）/ header / needed")))
        .put("required", new org.json.JSONArray().put("file_path"));
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    String filePath = input.optString("file_path", "").trim();
    if (filePath.isEmpty()) {
      return error("file_path 不能为空");
    }
    String action = input.optString("action", "symbols").trim().toLowerCase();

    String cwd = context == null ? "" : context.getHomePath();
    ShellBackendRegistry.Resolution resolution = registry.resolveActive();
    if (!resolution.isUsable()) {
      return error("没有可用的 shell 后端。请在设置里启用 Termux 或 Shizuku 后端。");
    }
    ShellBackend backend = resolution.getBackend();

    String readelf = ReverseToolSupport.probeExecutable(backend, "readelf", cwd);
    if (readelf == null) {
      return error(
          ReverseToolSupport.notInstalled("readelf", "Termux：`pkg install binutils`。"));
    }

    String flag;
    switch (action) {
      case "header":
        flag = "-h";
        break;
      case "needed":
        flag = "-d";
        break;
      case "symbols":
      default:
        flag = "--dyn-syms";
        break;
    }
    String command =
        ReverseToolSupport.quote(readelf)
            + " "
            + flag
            + " "
            + ReverseToolSupport.quote(filePath);
    ToolContext.CancellationSignal runCancel = context == null ? null : context.getCancellation();
    ShellRequest.CancellationSignal cancel = runCancel == null ? null : runCancel::isCancelled;
    ShellRequest.ShellResult result =
        ReverseToolSupport.run(backend, command, cwd, TIMEOUT_MS, cancel);

    String out = result.getStdout() == null ? "" : result.getStdout();
    if (result.isTimedOut()) {
      return error("readelf 执行超时。");
    }
    if (result.getExitCode() != 0) {
      return error(
          "readelf 执行失败（退出码 " + result.getExitCode() + "）：\n"
              + ReverseToolSupport.truncate(
                  (result.getStderr() == null ? "" : result.getStderr()) + "\n" + out, 2000));
    }
    if (out.trim().isEmpty()) {
      return ok("readelf " + action + " 无输出：" + filePath);
    }
    return ok(ReverseToolSupport.truncate(out, MAX_OUTPUT_CHARS));
  }
}
