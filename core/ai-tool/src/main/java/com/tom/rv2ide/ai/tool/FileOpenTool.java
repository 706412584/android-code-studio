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
import org.json.JSONObject;

/**
 * 在编辑器中打开文件并定位/选中——把用户带到代码位置。
 *
 * <p>典型闭环：诊断 / 搜索 / 分析给出 {@code file:line} 后，调用本工具让编辑器跳过去，
 * 用户看到的与模型说的是同一处。通用 agent 只能告诉用户「问题在第 42 行」，无法把界面带过去。
 *
 * <p><b>只改视图状态</b>（当前文件、光标、选区），不改文件内容，因此归
 * {@link ToolCategory#READ} 且只读模式下放行。
 *
 * <p><b>1 基 → 0 基换算在这里做</b>：模型给 1 基（与 file_read/grep 一致），
 * 端口收 0 基（与 IDE 内部一致）。换算是本工具的核心契约，测试用假端口参数断言钉死。
 */
public final class FileOpenTool extends BaseTool {

  @Override
  public String getName() {
    return ToolNames.FILE_OPEN;
  }

  @Override
  public String getDescription() {
    return "在编辑器中打开指定文件并定位到指定行/列，可选选中一段范围——把用户带到代码位置。"
        + "典型场景：诊断 / 搜索 / 分析给出 file:line 后，调用本工具让编辑器跳过去"
        + "（如「问题在第 42 行」→ 同时打开文件并高亮）。"
        + "只改变编辑器视图状态（当前文件、光标、选区），不改动文件内容。"
        + "行号从 1 开始；若目标行超出文件实际长度，编辑器会把它夹到文件末尾。"
        + "若要高亮整行，传 end_line = line + 1、end_column = 1。"
        + "注意：需要项目已在编辑器中打开（编辑器会话存活）；"
        + "若用户当前不在编辑器界面，文件仍会在后台编辑器中打开，用户切回时即可看到定位结果。"
        + "文件必须在当前工作区内（越界路径会明确报错）。";
  }

  @Override
  public ToolCategory getCategory() {
    // 不改文件内容，只改视图状态；归 READ 使只读模式下也能「指给用户看」。
    return ToolCategory.READ;
  }

  @Override
  public ToolDisplayCategory getDisplayCategory() {
    return ToolDisplayCategory.GENERIC;
  }

  @Override
  public boolean isAllowedInReadonlyMode() {
    // 只读模式的本意是「不要动代码」；打开文件与移动光标不动任何代码。
    return true;
  }

  @Override
  public boolean isConcurrencySafe() {
    // 改变共享视图状态（当前 tab / 光标），多个并行调用结果不可预期，必须串行。
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
                    new JSONObject()
                        .put("type", "string")
                        .put("description", "要打开的文件（相对工作区或绝对路径）"))
                .put(
                    "line",
                    new JSONObject()
                        .put("type", "integer")
                        .put("description", "目标行号，从 1 开始"))
                .put(
                    "column",
                    new JSONObject()
                        .put("type", "integer")
                        .put("description", "目标列号，从 1 开始；默认 1"))
                .put(
                    "end_line",
                    new JSONObject()
                        .put("type", "integer")
                        .put(
                            "description",
                            "选中范围的结束行，从 1 开始；"
                                + "与 end_column 一起提供时才产生选区，省略则只定位光标"))
                .put(
                    "end_column",
                    new JSONObject()
                        .put("type", "integer")
                        .put("description", "选中范围的结束列，从 1 开始")))
        .put("required", new org.json.JSONArray().put("file_path").put("line"));
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    EditorSessionPort port = context == null ? null : context.getEditorSession();
    if (port == null) {
      return error("当前环境未接入编辑器会话，无法打开文件。");
    }
    String reason = port.unavailableReason();
    if (!reason.isEmpty()) {
      return error(reason);
    }

    String rawPath = input.optString("file_path", "").trim();
    if (rawPath.isEmpty()) {
      return error("缺少参数 file_path。");
    }
    int line = input.optInt("line", -1);
    if (line < 1) {
      return error("参数 line 必须 >= 1（行号从 1 开始）。");
    }
    int column = Math.max(1, input.optInt("column", 1));

    // 选区参数：只有 end_line/end_column 显式提供时才构成选区，否则是光标点定位。
    boolean withSelection = input.has("end_line") || input.has("end_column");
    int endLine = withSelection ? Math.max(1, input.optInt("end_line", line)) : line;
    int endColumn = withSelection ? Math.max(1, input.optInt("end_column", column)) : column;
    // 保证 start <= end（模型可能给反）；排序在工具层做，端口不重复防御。
    if (endLine < line || (endLine == line && endColumn < column)) {
      int tl = line;
      int tc = column;
      line = endLine;
      column = endColumn;
      endLine = tl;
      endColumn = tc;
    }

    File file;
    try {
      file = FileToolPathPolicy.resolve(context, rawPath);
    } catch (IOException e) {
      return error("路径无效（必须在当前工作区内）：" + e.getMessage());
    }
    if (!file.exists()) {
      return error("文件不存在：" + rawPath);
    }
    if (!file.isFile()) {
      return error("路径不是文件：" + rawPath);
    }

    EditorSessionPort.OpenResult result =
        port.openAt(file.getAbsolutePath(), line - 1, column - 1, endLine - 1, endColumn - 1);
    if (result.isFailed()) {
      return error(result.getFailureReason());
    }

    String shown = EditorStateMessages.display(context, file);
    String where =
        withSelection
            ? "并选中第 " + line + " 行第 " + column + " 列 → 第 " + endLine + " 行第 " + endColumn + " 列"
            : "并定位到第 " + line + " 行第 " + column + " 列";
    String suffix =
        result.isEditorVisible() ? "" : "（编辑器当前不在前台，用户切回项目界面即可看到该位置）";
    return ok("已在编辑器中打开 " + shown + " " + where + "。" + suffix);
  }
}
