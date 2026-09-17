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

package com.tom.rv2ide.ai.tool.memory;

import com.tom.rv2ide.ai.tool.BaseTool;
import com.tom.rv2ide.ai.tool.ToolContext;
import com.tom.rv2ide.ai.tool.api.ToolCategory;
import com.tom.rv2ide.ai.tool.api.ToolDisplayCategory;
import com.tom.rv2ide.ai.tool.api.ToolNames;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 保存长期记忆：跨会话保留的事实与偏好。
 *
 * <p><b>与 todo_update 的区别</b>：待办是**本次任务**的进度，任务结束就没用了；
 * 记忆是**跨任务**的知识（「这个项目用 Hilt 而不是 Koin」「用户偏好 Kotlin DSL」），
 * 下次打开仍然有效。
 *
 * <p><b>为什么由模型主动调用而不是自动抽取</b>：判断「什么值得长期记住」很难。
 * 自动抽取会把一次性的调试细节也存进去，积累成噪声，而每条噪声都会进入后续每一轮的
 * 提示词——污染是累积的。让模型显式调用，配合工具说明里的判断标准，准确率高得多。
 */
public final class MemoryUpdateTool extends BaseTool {

  /** 单次保存的记忆条数上限。 */
  static final int MAX_BATCH = 10;

  /** 工具说明里给出的判断标准，直接影响模型的调用质量。 */
  private static final String DESCRIPTION =
      "Save durable facts for future conversations. Use this for things that stay true across "
          + "tasks: project conventions (\"uses Hilt for DI\"), user preferences (\"prefers "
          + "Kotlin DSL over Groovy\"), environment quirks (\"Gradle needs --offline here\"). "
          + "Do NOT save one-off debugging details, file contents, or anything already obvious "
          + "from the code. Keep each entry short and self-contained. "
          + "Also supports listing and deleting existing memories.";

  private final MemoryStore store;

  public MemoryUpdateTool(MemoryStore store) {
    this.store = store == null ? MemoryStore.inMemory() : store;
  }

  @Override
  public String getName() {
    return ToolNames.MEMORY_UPDATE;
  }

  @Override
  public String getDescription() {
    return DESCRIPTION;
  }

  @Override
  public ToolCategory getCategory() {
    // 记忆不改用户代码，只读模式下也应放行。
    return ToolCategory.GENERATE;
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
  public JSONObject getParameters() throws org.json.JSONException {
    JSONObject entry = new JSONObject();
    entry.put("type", "object");
    entry.put(
        "properties",
        new JSONObject()
            .put(
                "text",
                new JSONObject()
                    .put("type", "string")
                    .put("description", "The fact to remember, one sentence"))
            .put(
                "tags",
                new JSONObject()
                    .put("type", "array")
                    .put("items", new JSONObject().put("type", "string"))
                    .put("description", "Optional keywords to improve later retrieval")));
    entry.put("required", new JSONArray().put("text"));

    return new JSONObject()
        .put("type", "object")
        .put(
            "properties",
            new JSONObject()
                .put(
                    "action",
                    new JSONObject()
                        .put("type", "string")
                        .put("description", "One of: add (default), list, delete, clear"))
                .put(
                    "memories",
                    new JSONObject()
                        .put("type", "array")
                        .put("items", entry)
                        .put("description", "Entries to add; used when action is add"))
                .put(
                    "id",
                    new JSONObject()
                        .put("type", "string")
                        .put("description", "Entry id to delete; used when action is delete")))
        .put("required", new JSONArray());
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    String action = input.optString("action", "add").trim().toLowerCase(java.util.Locale.ROOT);
    switch (action) {
      case "list":
        return list();
      case "delete":
        return delete(input.optString("id", "").trim());
      case "clear":
        return clear();
      case "add":
        return add(input);
      default:
        // 未知动作要明确拒绝并列出合法值，否则模型会反复重试同一个错拼。
        return error("未知的 action: " + action + "。可用值：add / list / delete / clear。");
    }
  }

  private ToolResult add(JSONObject input) {
    JSONArray array = input.optJSONArray("memories");
    if (array == null || array.length() == 0) {
      return error("action=add 时需要提供 memories 数组（至少一条）。");
    }
    if (array.length() > MAX_BATCH) {
      return error("单次最多保存 " + MAX_BATCH + " 条记忆（当前 " + array.length() + " 条）。");
    }

    List<String> saved = new ArrayList<>();
    for (int i = 0; i < array.length(); i++) {
      JSONObject raw = array.optJSONObject(i);
      if (raw == null) {
        continue;
      }
      String text = raw.optString("text", "");
      if (text.trim().isEmpty()) {
        continue;
      }
      List<String> tags = new ArrayList<>();
      JSONArray tagArray = raw.optJSONArray("tags");
      if (tagArray != null) {
        for (int t = 0; t < tagArray.length(); t++) {
          String tag = tagArray.optString(t, "");
          if (!tag.trim().isEmpty()) {
            tags.add(tag.trim());
          }
        }
      }
      MemoryEntry entry = store.add(text, tags, System.currentTimeMillis());
      if (entry != null) {
        saved.add(entry.getId() + ": " + entry.getText());
      }
    }

    if (saved.isEmpty()) {
      return error("没有可保存的内容：每条记忆的 text 都不能为空。");
    }
    StringBuilder sb = new StringBuilder();
    sb.append("已保存 ").append(saved.size()).append(" 条记忆：\n");
    for (String line : saved) {
      sb.append("- ").append(line).append('\n');
    }
    sb.append("（当前共 ").append(store.size()).append(" 条）");
    return ok(sb.toString());
  }

  private ToolResult list() {
    List<MemoryEntry> all = store.all();
    if (all.isEmpty()) {
      return ok("当前没有保存任何记忆。");
    }
    StringBuilder sb = new StringBuilder();
    sb.append("已保存 ").append(all.size()).append(" 条记忆：\n");
    for (MemoryEntry entry : all) {
      sb.append("- ").append(entry.getId()).append(": ").append(entry.toLine()).append('\n');
    }
    sb.append("用 action=delete 并传入 id 可删除某条。");
    return ok(sb.toString());
  }

  private ToolResult delete(String id) {
    if (id.isEmpty()) {
      return error("action=delete 时需要提供 id。可用 action=list 查看全部 id。");
    }
    if (!store.remove(id)) {
      return error("找不到 id 为 " + id + " 的记忆。用 action=list 查看全部 id。");
    }
    return ok("已删除记忆 " + id + "。（剩余 " + store.size() + " 条）");
  }

  private ToolResult clear() {
    int before = store.size();
    store.clear();
    return ok("已清空全部记忆（原有 " + before + " 条）。");
  }
}
