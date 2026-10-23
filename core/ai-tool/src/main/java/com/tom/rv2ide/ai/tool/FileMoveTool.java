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
 * 移动 / 重命名文件或目录。
 *
 * <p><b>为什么需要它</b>：此前重命名只能 read → write → delete 三步。中间任一步失败
 * 都会留下两个半成品（或丢掉内容），而重命名是重构里最高频的动作之一。
 * 这里做成单个原子操作：同文件系统内是 rename，跨文件系统时先复制再删源。
 *
 * <p><b>为什么与 file_copy 分成两个工具</b>：{@code needsConfirmation()} 与
 * 权限规则都按**工具**分级。移动会删除源文件（不可逆），复制不会——混在一起
 * 只能整包按危险处理，或者反过来让「复制」也能删东西。
 */
public final class FileMoveTool extends BaseTool {

  public static final String NAME = ToolNames.FILE_MOVE;

  @Override
  public String getName() {
    return NAME;
  }

  @Override
  public String getDescription() {
    return "Move or rename a file or directory. Directories are moved recursively. "
        + "Works across filesystems (falls back to copy + delete source). "
        + "Fails if the target exists unless overwrite=true. "
        + "Prefer this over file_read + file_write + file_delete when renaming or relocating.";
  }

  @Override
  public ToolCategory getCategory() {
    return ToolCategory.WRITE;
  }

  @Override
  public ToolDisplayCategory getDisplayCategory() {
    return ToolDisplayCategory.WRITE;
  }

  /**
   * 移动会删除源文件，且跨文件系统时是先复制后删除（中途失败会留下两份）。
   * 与 file_delete 同级：需要用户确认。
   */
  @Override
  public boolean needsConfirmation() {
    return true;
  }

  @Override
  public JSONObject getParameters() throws org.json.JSONException {
    return new JSONObject()
        .put("type", "object")
        .put("properties", new JSONObject()
            .put("source", new JSONObject()
                .put("type", "string")
                .put("description", "Path to move (absolute or relative to workspace)"))
            .put("target", new JSONObject()
                .put("type", "string")
                .put("description",
                    "Destination path. If it names an existing directory, the source is moved "
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
      // `mv a b` 的常规语义：b 是已存在的目录时，移动到 b/a 而不是替换整个 b。
      // 少了这一步，模型写 target="lib" 会把整个 lib 目录替换掉。
      if (target.isDirectory() && !target.getAbsolutePath().equals(source.getAbsolutePath())) {
        target = new File(target, source.getName());
      }

      boolean overwrite = input.optBoolean("overwrite", false);
      FileTransfer.Outcome outcome = FileTransfer.move(source, target, overwrite);
      if (!outcome.isSuccess()) {
        return error(outcome.error);
      }

      String displaySource = FileToolPathPolicy.displayPath(context.getHomePath(), source);
      String displayTarget = FileToolPathPolicy.displayPath(context.getHomePath(), target);
      // 编辑器里打开着的旧路径必须刷新，否则用户会继续编辑一份已经不存在的文件，
      // 自动保存时又把旧路径写回来（与 file_write 同一个坑）。
      DocumentSync.notifyChanged(context, target);
      DocumentSync.notifyChanged(context, source);
      return ok(ToolMessages.format(
          ToolMessages.FILE_MOVE_SUCCESS, displaySource, displayTarget));
    } catch (Exception e) {
      return error(ToolMessages.format(
          ToolMessages.FILE_MOVE_FAILED, "move",
          input.optString("source").trim(), describe(e)));
    }
  }

  private static String describe(Exception e) {
    String message = e.getMessage();
    return message == null || message.trim().isEmpty()
        ? e.getClass().getSimpleName() : message;
  }
}
