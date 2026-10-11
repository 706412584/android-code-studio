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

import com.tom.rv2ide.ai.tool.api.ToolCategory;
import com.tom.rv2ide.ai.tool.api.ToolDisplayCategory;
import com.tom.rv2ide.ai.tool.api.ToolNames;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import org.json.JSONObject;

/**
 * LSP 导航：跳到定义 / 找引用。
 *
 * <p>与 {@code diagnostics} 同属「IDE 自省」能力——复用语言服务器已建立的符号索引，
 * 秒级且按语义而非文本匹配。文本 grep 找同名符号会误判，这项不会。
 *
 * <p>行列参数按 **1 基**给模型（人看行号习惯从 1 开始），内部转成 LSP 的 0 基。
 * 这是刻意的：模型从 {@code file_read}/{@code grep} 拿到的行号也是 1 基，直接沿用可少一次换算。
 */
public final class LspNavigateTool extends BaseTool {

  /** 单次导航超时（ms）。导航比全量诊断轻，但首次仍可能建索引。 */
  static final long TIMEOUT_MS = 20_000L;

  @Override
  public String getName() {
    return ToolNames.LSP_NAVIGATE;
  }

  @Override
  public String getDescription() {
    return "用 IDE 语言服务器做代码导航：kind=definition 跳到符号定义，kind=references 找出所有引用。"
        + "参数：file_path（文件）、line/column（位置，**行号从 1 开始**，与 file_read/grep 一致）、"
        + "kind（definition 或 references）。"
        + "改代码时优先用它代替 grep：按符号语义匹配，不会像文本搜索那样被同名符号误导。"
        + "仅 Java / Kotlin / XML 有语言服务器；需项目已在编辑器中打开。";
  }

  @Override
  public ToolCategory getCategory() {
    return ToolCategory.READ;
  }

  @Override
  public ToolDisplayCategory getDisplayCategory() {
    return ToolDisplayCategory.GENERIC;
  }

  @Override
  public boolean isAllowedInReadonlyMode() {
    return true;
  }

  @Override
  public boolean isConcurrencySafe() {
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
                    "file_path",
                    new JSONObject().put("type", "string").put("description", "文件路径"))
                .put(
                    "line",
                    new JSONObject().put("type", "integer").put("description", "行号，从 1 开始"))
                .put(
                    "column",
                    new JSONObject()
                        .put("type", "integer")
                        .put("description", "列号，从 1 开始（可省略，默认 1）"))
                .put(
                    "kind",
                    new JSONObject()
                        .put("type", "string")
                        .put("description", "definition（默认）或 references")
                        .put(
                            "enum",
                            new org.json.JSONArray().put("definition").put("references"))))
        .put("required", new org.json.JSONArray().put("file_path").put("line"));
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    LspNavigationPort port = context == null ? null : context.getLspNavigation();
    if (port == null) {
      return error("当前环境未接入 IDE 语言服务器，无法做代码导航。请用 grep/file_read 代替。");
    }
    String reason = port.unavailableReason();
    if (!reason.isEmpty()) {
      return error(
          "语言服务器当前不可用：" + reason + "。导航需要项目已在编辑器中打开（语言服务器随项目会话启动）。");
    }

    File file;
    try {
      file = FileToolPathPolicy.resolve(context, input.optString("file_path", ""));
    } catch (IOException e) {
      return error("路径无效：" + e.getMessage());
    }
    if (!file.isFile()) {
      return error("文件不存在：" + input.optString("file_path"));
    }
    if (!port.isSupported(file.getAbsolutePath())) {
      return error("该文件类型没有语言服务器，无法导航：" + file.getName());
    }

    int line = input.optInt("line", 0);
    if (line <= 0) {
      return error("line 必须从 1 开始（收到 " + line + "）。");
    }
    int column = input.optInt("column", 1);
    String kindRaw = input.optString("kind", "definition").trim().toLowerCase(Locale.US);
    LspNavigationPort.Kind kind;
    if ("references".equals(kindRaw)) {
      kind = LspNavigationPort.Kind.REFERENCES;
    } else if ("definition".equals(kindRaw)) {
      kind = LspNavigationPort.Kind.DEFINITION;
    } else {
      return error("kind 只能是 definition 或 references，收到：" + kindRaw);
    }

    // 模型给 1 基（与 grep/file_read 一致），LSP 要 0 基。
    LspNavigationPort.Result result =
        port.navigate(file.getAbsolutePath(), line - 1, column - 1, kind, TIMEOUT_MS);
    if (result.isUnavailable()) {
      return error("语言服务器不可用：" + result.getUnavailableReason());
    }
    if (result.isFailed()) {
      String hint = result.isTimedOut() ? "（可稍后重试，首次分析较慢）" : "";
      return error("导航失败：" + result.getFailureReason() + hint);
    }

    List<LspNavigationPort.Location> locations = result.getLocations();
    String kindLabel = kind == LspNavigationPort.Kind.DEFINITION ? "定义" : "引用";
    if (locations.isEmpty()) {
      // 「查到了但没有结果」与「查不了」必须分开说，否则模型会以为工具坏了而反复重试。
      return ok(
          "在 "
              + file.getName()
              + " 第 "
              + line
              + " 行第 "
              + column
              + " 列没有找到"
              + kindLabel
              + "（该位置可能不是符号引用）。");
    }

    StringBuilder sb = new StringBuilder();
    sb.append("找到 ").append(locations.size()).append(" 处").append(kindLabel).append("：\n");
    for (LspNavigationPort.Location loc : locations) {
      sb.append("  ").append(display(context, loc)).append('\n');
    }
    return ok(ToolResult.truncateContent(sb.toString()));
  }

  /** 展示为「相对路径:行:列」（行列转为 1 基，便于模型直接用于 file_read）。 */
  private static String display(ToolContext context, LspNavigationPort.Location loc) {
    File file = new File(loc.getFilePath());
    String path;
    try {
      path = FileToolPathPolicy.displayPath(context.getHomePath(), file);
    } catch (IOException e) {
      path = loc.getFilePath();
    }
    return path + ":" + (loc.getLine() + 1) + ":" + (loc.getColumn() + 1);
  }
}
