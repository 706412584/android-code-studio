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
 * 美化（反混淆/格式化）前端 JS：把压缩、混淆的 JavaScript 还原成可读的格式化代码。
 *
 * <p>Web 逆向的入口：网页/小程序里的压缩 JS 先用本工具美化，再由模型分析逻辑。
 * 用 `js-beautify`（node 工具，Termux 可 `npm install -g js-beautify`）。
 */
public final class JsBeautifyTool extends BaseTool {

  private static final long TIMEOUT_MS = 120_000L;
  private static final int MAX_OUTPUT_CHARS = 24 * 1024;

  private final ShellBackendRegistry registry;

  public JsBeautifyTool(ShellBackendRegistry registry) {
    this.registry = registry;
  }

  @Override
  public String getName() {
    return ToolNames.JS_BEAUTIFY;
  }

  @Override
  public String getDescription() {
    return "美化（反混淆/格式化）前端 JS：把压缩、混淆的 JavaScript 还原成可读的格式化代码，"
        + "便于分析网页/小程序的逻辑。参数 file_path 为 JS 文件路径。"
        + "用 js-beautify（未安装时给出 npm 安装提示）。";
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
                .put("file_path", new JSONObject().put("type", "string").put("description", "JS 文件路径")))
        .put("required", new org.json.JSONArray().put("file_path"));
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    String filePath = input.optString("file_path", "").trim();
    if (filePath.isEmpty()) {
      return error("file_path 不能为空");
    }
    String cwd = context == null ? "" : context.getHomePath();
    ShellBackendRegistry.Resolution resolution = registry.resolveActive();
    if (!resolution.isUsable()) {
      return error("没有可用的 shell 后端。请在设置里启用 Termux 或 Shizuku 后端。");
    }
    ShellBackend backend = resolution.getBackend();

    String command = buildCommand(backend, cwd, filePath);
    if (command == null) {
      return error(
          ReverseToolSupport.notInstalled(
              "js-beautify", "Termux：`npm install -g js-beautify`（需 node/npm）。"));
    }
    ToolContext.CancellationSignal runCancel = context == null ? null : context.getCancellation();
    ShellRequest.CancellationSignal cancel = runCancel == null ? null : runCancel::isCancelled;
    ShellRequest.ShellResult result =
        ReverseToolSupport.run(backend, command, cwd, TIMEOUT_MS, cancel);

    String out = result.getStdout() == null ? "" : result.getStdout();
    if (result.isTimedOut()) {
      return error("js-beautify 执行超时（npx 在线拉取可能慢，可重试或先本地安装）。");
    }
    if (result.getExitCode() != 0) {
      return error(
          "js-beautify 执行失败（退出码 " + result.getExitCode() + "）：\n"
              + ReverseToolSupport.truncate(
                  (result.getStderr() == null ? "" : result.getStderr()) + "\n" + out, 2000));
    }
    if (out.trim().isEmpty()) {
      return ok("js-beautify 无输出：" + filePath);
    }
    return ok(ReverseToolSupport.truncate(out, MAX_OUTPUT_CHARS));
  }

  /** 拼命令：优先本地 js-beautify，回退 npx 在线运行；都不可用返回 null。 */
  private String buildCommand(ShellBackend backend, String cwd, String filePath) {
    String jsBeautify = ReverseToolSupport.probeExecutable(backend, "js-beautify", cwd);
    if (jsBeautify != null) {
      return ReverseToolSupport.quote(jsBeautify) + " " + ReverseToolSupport.quote(filePath);
    }
    String npx = ReverseToolSupport.probeExecutable(backend, "npx", cwd);
    if (npx != null) {
      return ReverseToolSupport.quote(npx)
          + " --yes js-beautify "
          + ReverseToolSupport.quote(filePath);
    }
    return null;
  }
}
