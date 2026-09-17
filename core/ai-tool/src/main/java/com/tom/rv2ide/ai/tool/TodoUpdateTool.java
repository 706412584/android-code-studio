/*
 * This file is part of AndroidCodeStudio.
 *
 * Adapted from LineCode Pro (https://github.com/LangLang03/LineCodePro),
 * licensed under the GNU General Public License v3.0 or later.
 * Modifications for AndroidCodeStudio are licensed under the same terms.
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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.ai.tool;

import com.tom.rv2ide.ai.tool.api.ToolCategory;
import com.tom.rv2ide.ai.tool.api.ToolDisplayCategory;
import com.tom.rv2ide.ai.tool.api.ToolNames;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 维护任务待办列表。
 *
 * <p><b>为什么需要这个工具</b>：长任务（「给这个模块加上分页」）包含十几个步骤，
 * 模型在几十轮工具调用后容易忘记自己已经做了什么、还剩什么，表现为重复读取同一个文件
 * 或漏掉最后一步。显式的待办列表把它自己的计划外化成可见状态：列表同时进入系统提示词，
 * 使「我做到哪了」不再依赖模型的记忆。
 *
 * <p>整表覆盖而非增量修改：模型每轮提交完整列表，避免「删一条」这类操作需要额外的
 * 索引协议；列表本身很短（通常 3-10 项），覆盖的代价可以忽略。
 */
public final class TodoUpdateTool extends BaseTool {

  /** 单次提交的待办上限。防止模型把整个需求文档逐条列进来，把上下文撑爆。 */
  static final int MAX_ITEMS = 50;

  /** 单项内容长度上限。 */
  static final int MAX_CONTENT_CHARS = 200;

  private final TodoStateStore store;

  public TodoUpdateTool(TodoStateStore store) {
    this.store = store == null ? TodoStateStore.none() : store;
  }

  @Override
  public String getName() {
    return ToolNames.TODO_UPDATE;
  }

  @Override
  public String getDescription() {
    return "Create or update the task plan as a todo list. Pass the full list every time; "
        + "the previous list is replaced. Mark exactly one item as in_progress while working "
        + "on it, and completed as soon as it is done. Use this for multi-step tasks so you "
        + "do not lose track of progress.";
  }

  @Override
  public ToolCategory getCategory() {
    // 待办只是记录，不改用户代码，因此属于 GENERATE 而非 WRITE——
    // 只读模式下也应放行（见 ToolPermissionService.isReadonlyAlwaysAllowed）。
    return ToolCategory.GENERATE;
  }

  @Override
  public ToolDisplayCategory getDisplayCategory() {
    return ToolDisplayCategory.TODO;
  }

  @Override
  public boolean isAllowedInReadonlyMode() {
    return true;
  }

  @Override
  public JSONObject getParameters() throws org.json.JSONException {
    JSONObject item = new JSONObject();
    item.put("type", "object");
    item.put(
        "properties",
        new JSONObject()
            .put(
                "content",
                new JSONObject()
                    .put("type", "string")
                    .put("description", "Short description of the step"))
            .put(
                "status",
                new JSONObject()
                    .put("type", "string")
                    .put("description", "One of: pending, in_progress, completed")));
    item.put("required", new JSONArray().put("content").put("status"));

    return new JSONObject()
        .put("type", "object")
        .put(
            "properties",
            new JSONObject()
                .put(
                    "todos",
                    new JSONObject()
                        .put("type", "array")
                        .put("items", item)
                        .put(
                            "description",
                            "The full todo list. Replaces the previous list entirely.")))
        .put("required", new JSONArray().put("todos"));
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    JSONArray array = input.optJSONArray("todos");
    if (array == null) {
      return error("todos 不能为空：请提交完整的待办数组（可以是空数组以清空）。");
    }
    if (array.length() > MAX_ITEMS) {
      return error("待办条目过多（" + array.length() + " 条，上限 " + MAX_ITEMS + " 条）。请合并成更少的步骤。");
    }

    List<TodoItem> items = new ArrayList<>();
    for (int i = 0; i < array.length(); i++) {
      JSONObject raw = array.optJSONObject(i);
      if (raw == null) {
        continue;
      }
      String content = raw.optString("content", "").trim();
      if (content.isEmpty()) {
        continue;
      }
      if (content.length() > MAX_CONTENT_CHARS) {
        content = content.substring(0, MAX_CONTENT_CHARS) + "…";
      }
      items.add(new TodoItem(content, raw.optString("status", TodoItem.STATUS_PENDING)));
    }

    store.setItems(items);

    if (items.isEmpty()) {
      return ok("待办列表已清空。");
    }

    // 回显归一化后的列表：模型若写了 done / 完成 这类同义词，这里让它看到实际记录下来的
    // 状态，避免下一轮因为「状态没生效」而重复提交。
    StringBuilder sb = new StringBuilder();
    sb.append("待办列表已更新（").append(items.size()).append(" 项）：\n");
    for (TodoItem item : items) {
      sb.append(item.toLine()).append('\n');
    }
    int inProgress = countInProgress(items);
    if (inProgress == 0) {
      // 提示而非拒绝：没有进行中的项不算错误（例如全部完成时），
      // 但多数情况下意味着模型忘了标记当前步骤。
      sb.append("提示：当前没有标记为 in_progress 的项。");
    } else if (inProgress > 1) {
      sb.append("提示：有 ").append(inProgress).append(" 项同时处于 in_progress，通常应当只有一项。");
    }
    return ok(sb.toString().trim());
  }

  private static int countInProgress(List<TodoItem> items) {
    int count = 0;
    for (TodoItem item : items) {
      if (TodoItem.STATUS_IN_PROGRESS.equals(item.getStatus())) {
        count++;
      }
    }
    return count;
  }
}
