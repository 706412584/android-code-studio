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

package com.tom.rv2ide.ai.tool.skill;

import com.tom.rv2ide.ai.tool.BaseTool;
import com.tom.rv2ide.ai.tool.ToolContext;
import com.tom.rv2ide.ai.tool.api.ToolCategory;
import com.tom.rv2ide.ai.tool.api.ToolDisplayCategory;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.util.List;
import org.json.JSONObject;

/**
 * 加载 skill 的完整内容。
 *
 * <p><b>渐进披露的另一半</b>：系统提示词里只列出 skill 的名字与一句话说明；模型判断
 * 某个 skill 与当前任务相关时，用本工具读取完整正文。这样几十个 skill 的常驻成本只有
 * 几百 token，而需要时能拿到完整指导。
 *
 * <p>不带参数调用时列出全部 skill（含说明），供模型在不确定名字时查看。
 */
public final class SkillTool extends BaseTool {

  /** 工具名。 */
  public static final String NAME = "skill";

  private final SkillRegistry registry;

  public SkillTool(SkillRegistry registry) {
    this.registry = registry == null ? SkillRegistry.empty() : registry;
  }

  @Override
  public String getName() {
    return NAME;
  }

  @Override
  public String getDescription() {
    return "Load the full content of a skill by name. The system prompt lists available skills "
        + "with a one-line summary; call this when a skill is relevant to the current task. "
        + "Call without arguments to list all skills.";
  }

  @Override
  public ToolCategory getCategory() {
    // 只读取本地文档，不改任何东西。
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
  public JSONObject getParameters() throws org.json.JSONException {
    return new JSONObject()
        .put("type", "object")
        .put(
            "properties",
            new JSONObject()
                .put(
                    "name",
                    new JSONObject()
                        .put("type", "string")
                        .put("description", "Skill name; omit to list all skills")))
        .put("required", new org.json.JSONArray());
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    String name = input.optString("name", "").trim();

    if (name.isEmpty()) {
      return list();
    }

    Skill skill = registry.find(name);
    if (skill == null) {
      // 找不到时列出可用名字：模型拼错一个字母就白跑一轮，给出候选能省下这一轮。
      String available = Skill.namesOf(registry.all()).toString();
      return error("找不到名为「" + name + "」的 skill。可用：" + available);
    }

    if (context != null) {
      context.reportProgress("加载 skill " + skill.getName());
    }

    StringBuilder sb = new StringBuilder();
    sb.append("[ skill: ").append(skill.getName()).append(" ]\n");
    if (!skill.getDescription().isEmpty()) {
      sb.append(skill.getDescription()).append('\n');
    }
    sb.append('\n');
    sb.append(SkillRegistry.truncateBody(skill.getBody()));
    return ok(sb.toString());
  }

  private ToolResult list() {
    List<Skill> skills = registry.all();
    if (skills.isEmpty()) {
      return ok("当前没有可用的 skill。");
    }
    StringBuilder sb = new StringBuilder();
    sb.append("可用 skill（共 ").append(skills.size()).append(" 个）：\n");
    for (Skill skill : skills) {
      sb.append(skill.toPromptLine()).append('\n');
    }
    sb.append("\n用 skill 工具并传入 name 可读取完整内容。");
    return ok(sb.toString());
  }
}
