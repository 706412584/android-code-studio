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

import com.tom.rv2ide.ai.agent.builtin.BuiltinAgent;
import com.tom.rv2ide.ai.tool.AgentTool;
import com.tom.rv2ide.ai.tool.BaseTool;
import com.tom.rv2ide.ai.tool.ExceptionUtils;
import com.tom.rv2ide.ai.tool.SubAgentRunner;
import com.tom.rv2ide.ai.tool.ToolContext;
import com.tom.rv2ide.ai.tool.ToolNameFilter;
import com.tom.rv2ide.ai.tool.api.ToolCategory;
import com.tom.rv2ide.ai.tool.api.ToolDisplayCategory;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.util.LinkedHashSet;
import org.json.JSONObject;

/**
 * 把一个内置子 agent 暴露为工具（{@code agentb_<id>}）。
 *
 * <p><b>与 {@code agent} 工具的区别</b>：{@code agent} 是模型即席描述一个子任务；
 * 本工具是**预设角色**——职责提示词与工具白名单都已固定，模型只需说明「要处理什么」。
 * 对审查、探索、定位、写测试、写文档这类反复出现、要求固定的角色，这比每次现写约束
 * 更省 token 也更稳定。
 *
 * <p><b>工具白名单是硬边界</b>：{@link BuiltinAgent#getTools()} 会传给
 * {@link SubAgentRunner}，由执行方裁剪实际注册的工具。只读角色（审查/探索/定位）
 * 在结构上就拿不到写工具，不依赖提示词约束。
 *
 * <p>放在 app 层而不是 {@code ai-tool}：它需要 {@link SubAgentRunner} 的 app 层实现，
 * 而内置 agent 的定义类型在 {@code ai-agent}——{@code ai-tool} 同时依赖两者会形成循环。
 */
public final class BuiltinAgentTool extends BaseTool {

  private final BuiltinAgent agent;
  private final SubAgentRunner runner;
  private final int depth;

  public BuiltinAgentTool(BuiltinAgent agent, SubAgentRunner runner, int depth) {
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
    StringBuilder sb = new StringBuilder();
    sb.append(agent.getDescription());
    sb.append("\n（内置 agent「").append(agent.getName()).append("」");
    if (agent.isReadOnly()) {
      sb.append("，只读：只能读取与分析，不会改动任何文件");
    } else {
      sb.append("，可写文件");
    }
    sb.append("。）");
    return sb.toString();
  }

  @Override
  public ToolCategory getCategory() {
    // 只读角色归 READ，可写角色归 WRITE：只读模式下不应放行一个可能写文件的工具。
    return agent.isReadOnly() ? ToolCategory.READ : ToolCategory.WRITE;
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
                            "What this agent should work on. Include the file paths or scope "
                                + "it needs; it cannot see this conversation.")))
        .put("required", new org.json.JSONArray().put("task"));
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    String task = input.optString("task", "").trim();
    if (task.isEmpty()) {
      return error("task 不能为空：请说明要让「" + agent.getName() + "」处理什么。");
    }
    if (task.length() > AgentTool.MAX_TASK_CHARS) {
      return error(
          "task 过长（" + task.length() + " 字符，上限 " + AgentTool.MAX_TASK_CHARS + "）。请精简到任务要点。");
    }
    if (runner == null) {
      return error("当前环境不支持子 agent。");
    }
    if (depth >= AgentTool.MAX_DEPTH) {
      return error(
          "已达子 agent 嵌套上限（" + AgentTool.MAX_DEPTH + " 层）。请直接完成这个任务，不要再委派。");
    }

    // 只读角色用 EXPLORE，可写角色用 CODE。模式决定执行方是否注册写工具；
    // 白名单再在其上收窄到角色允许的具体工具。
    SubAgentRunner.Mode mode =
        agent.isReadOnly() ? SubAgentRunner.Mode.EXPLORE : SubAgentRunner.Mode.CODE;
    ToolNameFilter filter =
        agent.restrictsTools()
            ? ToolNameFilter.of(new LinkedHashSet<>(agent.getTools()))
            : ToolNameFilter.unrestricted();

    if (context != null) {
      context.reportProgress("内置 agent " + agent.getName() + ": " + abbreviate(task, 80));
    }

    try {
      SubAgentRunner.Result result =
          runner.run(new SubAgentRunner.Request(task, mode, depth, agent.getPrompt(), filter));
      if (result == null) {
        return error("内置 agent 没有返回结果。");
      }
      if (!result.isSuccess()) {
        String detail = result.getOutput().isEmpty() ? "（没有更多信息）" : result.getOutput();
        return error("内置 agent「" + agent.getName() + "」未能完成任务：" + detail);
      }
      String output = result.getOutput().trim();
      if (output.isEmpty()) {
        return error("内置 agent 完成了执行但没有给出结论。请直接完成这个任务。");
      }
      return ok(
          output
              + "\n\n（内置 agent: "
              + agent.getName()
              + "，轮次 "
              + result.getTurns()
              + "，工具调用 "
              + result.getToolCallCount()
              + "）");
    } catch (Exception e) {
      ExceptionUtils.restoreInterrupt(e);
      return error("内置 agent 执行失败：" + ExceptionUtils.describeException(e));
    }
  }

  private static String abbreviate(String value, int max) {
    if (value.length() <= max) {
      return value;
    }
    return value.substring(0, max) + "…";
  }
}
