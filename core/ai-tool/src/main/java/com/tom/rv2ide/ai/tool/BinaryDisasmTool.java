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
 * radare2 反汇编：分析原生二进制（Android .so / Windows PE / Linux ELF / macOS Mach-O）。
 *
 * <p>覆盖「跨平台」的诉求：同一份工具可反汇编任意架构的可执行文件/原生库。
 * 动作白名单（afl 函数清单 / pdf 反汇编函数 / iz 字符串 / iI 二进制信息），
 * function 参数做字符白名单校验，防止 r2 命令注入。
 */
public final class BinaryDisasmTool extends BaseTool {

  private static final long TIMEOUT_MS = 180_000L;

  /** 反汇编/字符串输出上限，保留首尾。 */
  private static final int MAX_OUTPUT_CHARS = 16 * 1024;

  private final ShellBackendRegistry registry;

  public BinaryDisasmTool(ShellBackendRegistry registry) {
    this.registry = registry;
  }

  @Override
  public String getName() {
    return ToolNames.BINARY_DISASM;
  }

  @Override
  public String getDescription() {
    return "用 radare2 反汇编原生二进制（Android .so / Windows PE / Linux ELF / macOS Mach-O）。"
        + "action=afl 列函数清单；action=pdf 反汇编指定函数（需 function 参数，函数名或地址）；"
        + "action=iz 列字符串；action=iI 看二进制信息（架构/类型/位数）。"
        + "radare2 未安装时给出安装提示（Termux：pkg install radare2）。";
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
                .put(
                    "file_path",
                    new JSONObject()
                        .put("type", "string")
                        .put("description", "二进制文件路径（.so/.exe/.dll/ELF 等）"))
                .put(
                    "action",
                    new JSONObject()
                        .put("type", "string")
                        .put("description", "afl（函数清单，默认）/ pdf（反汇编函数）/ iz（字符串）/ iI（信息）"))
                .put(
                    "function",
                    new JSONObject()
                        .put("type", "string")
                        .put("description", "action=pdf 时的目标函数名或地址（如 main、sym.foo、0x1234）")))
        .put("required", new org.json.JSONArray().put("file_path"));
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    String filePath = input.optString("file_path", "").trim();
    if (filePath.isEmpty()) {
      return error("file_path 不能为空");
    }
    String action = input.optString("action", "afl").trim().toLowerCase();
    String function = input.optString("function", "").trim();
    if (action.equals("pdf") && function.isEmpty()) {
      return error("action=pdf 需要 function 参数（函数名或地址）。");
    }
    // function 字符白名单：函数名/地址，防止 r2 命令注入（r2 用 ; 分隔命令）。
    if (!function.isEmpty() && !function.matches("[a-zA-Z0-9_.:@$]+")) {
      return error("function 含非法字符，只允许字母、数字、_ . : @ $。");
    }

    String cwd = context == null ? "" : context.getHomePath();
    ShellBackendRegistry.Resolution resolution = registry.resolveActive();
    if (!resolution.isUsable()) {
      return error("没有可用的 shell 后端，无法运行 radare2。请在设置里启用 Termux 或 Shizuku 后端。");
    }
    ShellBackend backend = resolution.getBackend();

    String r2 = ReverseToolSupport.probeExecutable(backend, "r2", cwd);
    if (r2 == null) {
      r2 = ReverseToolSupport.probeExecutable(backend, "radare2", cwd);
    }
    if (r2 == null) {
      return error(
          ReverseToolSupport.notInstalled("radare2", "安装方式：Termux 执行 `pkg install radare2`。"));
    }

    String r2Command = buildR2Command(action, function);
    // 分析类动作（afl/pdf）需要 -A 自动分析；字符串/信息类不需要。
    String flags = (action.equals("afl") || action.equals("pdf")) ? "-A -q" : "-q";
    String command =
        ReverseToolSupport.quote(r2)
            + " "
            + flags
            + " -c "
            + ReverseToolSupport.quote(r2Command)
            + " "
            + ReverseToolSupport.quote(filePath);
    ToolContext.CancellationSignal runCancel = context == null ? null : context.getCancellation();
    ShellRequest.CancellationSignal cancel = runCancel == null ? null : runCancel::isCancelled;
    ShellRequest.ShellResult result =
        ReverseToolSupport.run(backend, command, cwd, TIMEOUT_MS, cancel);

    if (result.isTimedOut()) {
      return error("radare2 分析超时（" + (TIMEOUT_MS / 1000) + "s）。大二进制可重试或缩小范围。");
    }
    String out = result.getStdout() == null ? "" : result.getStdout();
    if (result.getExitCode() != 0) {
      return error(
          "radare2 执行失败（退出码 " + result.getExitCode() + "）：\n"
              + ReverseToolSupport.truncate(
                  (result.getStderr() == null ? "" : result.getStderr()) + "\n" + out, 2000));
    }
    if (out.trim().isEmpty()) {
      return ok("radare2 " + action + " 无输出。可能文件无法识别或该动作无结果：" + filePath);
    }
    return ok(ReverseToolSupport.truncate(out, MAX_OUTPUT_CHARS));
  }

  /** 拼 r2 子命令（不含 flags 与 -c）：afl / s <func>;pdf / iz / iI。 */
  private static String buildR2Command(String action, String function) {
    switch (action) {
      case "pdf":
        return "s " + function + ";pdf";
      case "iz":
        return "iz";
      case "iI":
        return "iI";
      case "afl":
      default:
        return "afl";
    }
  }
}
