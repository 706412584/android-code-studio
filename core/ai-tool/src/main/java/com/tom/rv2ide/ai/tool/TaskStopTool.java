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

import com.tom.rv2ide.ai.tool.BackgroundShellPort.TaskStatus;
import com.tom.rv2ide.ai.tool.api.ToolCategory;
import com.tom.rv2ide.ai.tool.api.ToolDisplayCategory;
import com.tom.rv2ide.ai.tool.api.ToolNames;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import org.json.JSONObject;

/**
 * 停止一个运行中的后台任务。
 *
 * <p><b>语义照 cc-haha 的 TaskStopTool</b>，包括它刻意写进说明的使用约束：
 * <ul>
 *   <li>仅当用户明确要求取消/停止，或任务明显失控、有害、重复、已无用时才停；
 *   <li>**不要**因为「已经读够了输出」就停——那会浪费一次已经投入的等待，
 *       而且后台任务本来就设计成不阻塞你，让它跑完通常更省事。
 * </ul>
 *
 * <p>停止是**有副作用**的操作（可能中断一个正在写文件的命令），因此归为 WRITE 分类，
 * 需要确认（除非用户在自动模式）。这与 cc-haha 把它当作普通工具的宽松处理不同——
 * ACS 的权限模型对「可能造成状态不一致」的操作保守是既定原则。
 */
public final class TaskStopTool extends BaseTool {

  @Override
  public String getName() {
    return ToolNames.TASK_STOP;
  }

  @Override
  public String getDescription() {
    return "停止一个运行中的后台任务。仅当用户明确要求取消，或任务明显失控/无用时使用；"
        + "不要因为「已经看够输出」就停止它——后台任务不阻塞你，让它跑完通常更好。";
  }

  @Override
  public ToolCategory getCategory() {
    // 停止可能中断一个正在写文件的命令，归为 WRITE：只读模式下不放行。
    return ToolCategory.WRITE;
  }

  @Override
  public ToolDisplayCategory getDisplayCategory() {
    return ToolDisplayCategory.SHELL;
  }

  @Override
  public boolean needsConfirmation() {
    // 中断运行中的命令是有副作用的操作，确认模式下应经用户同意。
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
                    "task_id",
                    new JSONObject()
                        .put("type", "string")
                        .put("description", "要停止的后台任务 id")))
        .put("required", new org.json.JSONArray().put("task_id"));
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    BackgroundShellPort port = context == null ? null : context.getBackgroundShell();
    if (port == null) {
      return error("后台任务功能未接入。");
    }
    String taskId = input.optString("task_id", "").trim();
    if (taskId.isEmpty()) {
      return error("task_id 不能为空");
    }

    TaskStatus before = port.status(taskId);
    if (before == null || before.getState() == BackgroundShellPort.State.UNKNOWN) {
      return error("找不到任务 " + taskId + "。它可能已经结束并被清理。");
    }
    if (before.isFinished()) {
      // 已经结束的任务不必再停——如实告知，而不是假装停成功。
      return ok(
          "任务 " + taskId + " 已经结束（state=" + before.getState().name().toLowerCase()
              + "），无需停止。");
    }

    boolean stopped = port.stop(taskId);
    if (!stopped) {
      return error("停止任务 " + taskId + " 失败：它可能刚好在此期间结束。可用 task_output 查看状态。");
    }
    return ok("已请求停止任务 " + taskId + "。用 task_output 可确认最终状态。");
  }
}
