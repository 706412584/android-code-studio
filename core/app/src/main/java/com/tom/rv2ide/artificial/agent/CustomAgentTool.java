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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.artificial.agent;

import com.tom.rv2ide.ai.agent.command.CustomAgent;
import com.tom.rv2ide.ai.tool.BaseTool;
import com.tom.rv2ide.ai.tool.ExceptionUtils;
import com.tom.rv2ide.ai.tool.SubAgentRunner;
import com.tom.rv2ide.ai.tool.ToolContext;
import com.tom.rv2ide.ai.tool.api.ToolCategory;
import com.tom.rv2ide.ai.tool.api.ToolDisplayCategory;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import org.json.JSONObject;

/**
 * 把一个用户自定义 agent 暴露为工具。
 *
 * <p><b>与 {@code agent} 工具的区别</b>：{@code agent} 是模型自己决定派发的、临时的
 * 上下文隔离，任务描述由模型即席写出；本工具是用户**预先定义**的角色，提示词固定，
 * 模型只需给出「要处理什么」。用户因此可以固化「审查时必须检查 i18n」这类项目特定要求，
 * 而不必每次在对话里重复说明。
 *
 * <p>放在 app 层而不是 {@code ai-tool}：它需要 {@link SubAgentRunner} 的 app 层实现，
 * 而自定义 agent 的定义类型在 {@code ai-agent}——{@code ai-tool} 同时依赖两者会形成
 * 循环。app 层是这三者的汇聚点。
 */
public final class CustomAgentTool extends BaseTool {

  private final CustomAgent agent;
  private final SubAgentRunner runner;
  private final int depth;

  public CustomAgentTool(CustomAgent agent, SubAgentRunner runner, int depth) {
    this.agent = agent;
    this.runner = runner;
    this.depth = depth;
  }

  @Override
  public String getName() {
    return agent.toolName();
  }

  @Override
  public String getDescription() {
    String description = agent.getDescription();
    if (description.isEmpty()) {
      description = "用户自定义的 agent：" + agent.getName();
    }
    // 标明这是用户定义的：模型据此知道该按用户的既定要求工作，而不是自由发挥。
    return description + "\n（用户自定义 agent，按用户预设的职责与要求工作。）";
  }

  @Override
  public ToolCategory getCategory() {
    // 自定义 agent 内部的工具集由实现方决定，可能写文件。保守归为 WRITE。
    return ToolCategory.WRITE;
  }

  @Override
  public ToolDisplayCategory getDisplayCategory() {
    return ToolDisplayCategory.AGENT;
  }

  @Override
  public JSONObject getParameters() throws org.json.JSONException {
    return new JSONObject()
        .put("type", "object")
        .put(
            "properties",
            new JSONObject()
                .put(
                    "task",
                    new JSONObject()
                        .put("type", "string")
                        .put(
                            "description",
                            "What this custom agent should work on. Include the file paths "
                                + "or scope it needs; it cannot see this conversation.")))
        .put("required", new org.json.JSONArray().put("task"));
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    String task = input.optString("task", "").trim();
    if (task.isEmpty()) {
      return error("task 不能为空：请说明要让这个 agent 处理什么。");
    }
    if (runner == null) {
      return error("当前环境不支持自定义 agent。");
    }
    if (depth >= com.tom.rv2ide.ai.tool.AgentTool.MAX_DEPTH) {
      return error(
          "已达子 agent 嵌套上限（"
              + com.tom.rv2ide.ai.tool.AgentTool.MAX_DEPTH
              + " 层）。请直接完成这个任务。");
    }

    if (context != null) {
      context.reportProgress("自定义 agent " + agent.getName());
    }

    try {
      // 把用户的提示词与具体任务拼在一起交给子 agent 循环。
      // 提示词作为「职责说明」放在前面，任务在后——模型先建立角色，再处理具体事项。
      String fullTask = agent.getPrompt() + "\n\n[ 本次任务 ]\n" + task;

      SubAgentRunner.Result result =
          runner.run(new SubAgentRunner.Request(fullTask, SubAgentRunner.Mode.EXPLORE, depth));

      if (result == null) {
        return error("自定义 agent 没有返回结果。");
      }
      if (!result.isSuccess()) {
        String detail = result.getOutput().isEmpty() ? "（没有更多信息）" : result.getOutput();
        return error("自定义 agent 未能完成任务：" + detail);
      }
      String output = result.getOutput().trim();
      if (output.isEmpty()) {
        return error("自定义 agent 完成了执行但没有给出结论。");
      }
      return ok(output + "\n\n（自定义 agent: " + agent.getName() + "）");
    } catch (Exception e) {
      ExceptionUtils.restoreInterrupt(e);
      return error("自定义 agent 执行失败：" + ExceptionUtils.describeException(e));
    }
  }
}
