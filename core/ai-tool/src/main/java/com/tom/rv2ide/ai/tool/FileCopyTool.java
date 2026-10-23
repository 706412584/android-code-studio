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

import com.tom.rv2ide.ai.tool.api.ToolArgs;
import com.tom.rv2ide.ai.tool.api.ToolCategory;
import com.tom.rv2ide.ai.tool.api.ToolDisplayCategory;
import com.tom.rv2ide.ai.tool.api.ToolNames;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.io.File;
import org.json.JSONObject;

/**
 * 复制文件或目录（目录递归）。
 *
 * <p><b>为什么不需要确认</b>：复制只产生新文件，不删除任何东西，爆炸半径是
 * 「多占一份空间」——用户能直接删掉。与 {@link FileMoveTool} 分开正是为了
 * 让这条免打扰（同 git 与 git_write 的拆分理由）。
 */
public final class FileCopyTool extends BaseTool {

  public static final String NAME = ToolNames.FILE_COPY;

  @Override
  public String getName() {
    return NAME;
  }

  @Override
  public String getDescription() {
    return "Copy a file or directory (recursively) to a new location. "
        + "Fails if the target exists unless overwrite=true. "
        + "Use this instead of reading and rewriting a file when you just need a duplicate.";
  }

  @Override
  public ToolCategory getCategory() {
    return ToolCategory.WRITE;
  }

  @Override
  public ToolDisplayCategory getDisplayCategory() {
    return ToolDisplayCategory.WRITE;
  }

  @Override
  public JSONObject getParameters() throws org.json.JSONException {
    return new JSONObject()
        .put("type", "object")
        .put("properties", new JSONObject()
            .put("source", new JSONObject()
                .put("type", "string")
                .put("description", "Path to copy (absolute or relative to workspace)"))
            .put("target", new JSONObject()
                .put("type", "string")
                .put("description",
                    "Destination path. If it names an existing directory, the source is copied "
                        + "inside it under its own name."))
            .put("overwrite", new JSONObject()
                .put("type", "boolean")
                .put("description", "Replace the target if it already exists. Default false.")))
        .put("required", new org.json.JSONArray().put("source").put("target"));
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    try {
      String sourceArg = input.optString("source").trim();
      String targetArg = input.optString("target").trim();
      ToolArgs.requireNonEmpty(sourceArg, "source");
      ToolArgs.requireNonEmpty(targetArg, "target");

      File source = FileToolPathPolicy.resolve(context, sourceArg);
      File target = FileToolPathPolicy.resolve(context, targetArg);
      // 与 file_move 同一语义：目标是已存在目录时，复制到它里面
      if (target.isDirectory() && !target.getAbsolutePath().equals(source.getAbsolutePath())) {
        target = new File(target, source.getName());
      }

      FileTransfer.Outcome outcome = FileTransfer.copy(source, target, input.optBoolean("overwrite", false));
      if (!outcome.isSuccess()) {
        return error(outcome.error);
      }

      String displaySource = FileToolPathPolicy.displayPath(context.getHomePath(), source);
      String displayTarget = FileToolPathPolicy.displayPath(context.getHomePath(), target);
      DocumentSync.notifyChanged(context, target);
      String message = ToolMessages.format(
          ToolMessages.FILE_COPY_SUCCESS, displaySource, displayTarget);
      return ok(outcome.entries > 1 ? message + " (" + outcome.entries + " entries)" : message);
    } catch (Exception e) {
      String message = e.getMessage();
      return error(ToolMessages.format(
          ToolMessages.FILE_MOVE_FAILED, "copy", input.optString("source").trim(),
          message == null || message.trim().isEmpty() ? e.getClass().getSimpleName() : message));
    }
  }
}
