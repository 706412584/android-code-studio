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
import org.json.JSONObject;

/**
 * 把编辑器全部缓冲落盘（等同用户执行「全部保存」）。
 *
 * <p><b>为什么需要它</b>：编辑器缓冲领先磁盘是常见状态（用户改完没保存），
 * 此时构建 / 分析用的都是旧内容。AI 在「改完 → 构建」闭环里调用本工具，
 * 确保磁盘与编辑器一致——这是通用 agent 看不到的差异。
 *
 * <p><b>为什么归 SYSTEM 而不是 WRITE</b>：它写磁盘（只读模式必须拦截），
 * 但不产生新内容——写的是用户已有的修改，因此 {@code shouldRecordDiff} 为 false：
 * 记 diff 会让「回滚 AI 改动」覆盖用户的修改。同 {@code gradle_build} 的定位。
 */
public final class SaveAllTool extends BaseTool {

  /** 整个保存过程的时间上限。写盘是慢操作（大文件 + 逐文件），给足 30s。 */
  static final long SAVE_TIMEOUT_MS = 30_000L;

  @Override
  public String getName() {
    return ToolNames.SAVE_ALL;
  }

  @Override
  public String getDescription() {
    return "把编辑器里全部已打开文件的未保存修改写回磁盘（等同于用户执行「全部保存」）。"
        + "典型场景：在构建 / 安装 / 分析之前调用，确保磁盘上的内容与编辑器一致——"
        + "否则构建用的是旧内容（编辑器缓冲领先磁盘是常见状态）。"
        + "只保存有修改的文件；磁盘上存在比编辑器更新的内容（被其他工具或 git 改写）"
        + "且编辑器有未保存修改的文件会被跳过并在结果中列出，避免静默覆盖外部改动。"
        + "注意：需要项目已在编辑器中打开（有编辑器会话）；"
        + "本工具不改动任何文件内容，只是把编辑器缓冲落盘。";
  }

  @Override
  public ToolCategory getCategory() {
    // 写磁盘（虽是「把用户已有的修改落盘」而非 AI 生成内容），
    // 只读模式下应拦截——磁盘写操作不属于「只读」。与 gradle_build（SYSTEM）同类。
    return ToolCategory.SYSTEM;
  }

  @Override
  public ToolDisplayCategory getDisplayCategory() {
    return ToolDisplayCategory.GENERIC;
  }

  @Override
  public boolean isAllowedInReadonlyMode() {
    return false;
  }

  @Override
  public boolean needsConfirmation() {
    // 不危险：只让磁盘与编辑器一致，不产生新内容；且「改完→保存→构建」闭环
    // 每次都要弹窗会让该工具不可用。冲突场景已由实现层跳过保护。
    return false;
  }

  @Override
  public boolean shouldRecordDiff() {
    // 内容不是 AI 改的（是用户已做的修改落盘），记 diff 会让「回滚 AI 改动」
    // 的语义覆盖用户的修改。明确不记。
    return false;
  }

  @Override
  public boolean isConcurrencySafe() {
    // 保存内部有互斥状态（areFilesSaving / 逐文件写盘），并发无益且顺序不可预期。
    return false;
  }

  @Override
  public JSONObject getParameters() throws org.json.JSONException {
    return new JSONObject().put("type", "object").put("properties", new JSONObject());
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    EditorSessionPort port = context == null ? null : context.getEditorSession();
    if (port == null) {
      return error("当前环境未接入编辑器会话，无法保存编辑器缓冲。");
    }
    String reason = port.unavailableReason();
    if (!reason.isEmpty()) {
      return error(reason);
    }

    EditorSessionPort.SaveReport report = port.saveAll(SAVE_TIMEOUT_MS);
    if (report.isFailed()) {
      return error(report.getFailureReason());
    }
    return ok(EditorStateMessages.formatSaveReport(context, report));
  }
}
