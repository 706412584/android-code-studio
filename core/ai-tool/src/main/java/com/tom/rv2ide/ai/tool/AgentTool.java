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
import org.json.JSONObject;

/**
 * 派发子任务给一个独立的 agent 循环。
 *
 * <p><b>为什么需要它</b>：主 agent 的上下文窗口是最稀缺的资源。一个「搞清楚这个模块
 * 怎么工作」的调查可能要读二十个文件，若在主循环里做，这些文件的全文会永久占据窗口，
 * 后续步骤能用的空间就被挤掉了。子 agent 在自己的上下文里做这件事，只把结论带回来。
 *
 * <p>两种模式见 {@link SubAgentRunner.Mode}：{@code explore} 只读调查，
 * {@code code} 可落地改动。
 *
 * <p><b>深度限制</b>：子 agent 内部也能再调用本工具，因此必须有上限——否则一个
 * 「把任务拆成子任务」的循环会无限递归下去，直到耗尽额度。深度由
 * {@link #MAX_DEPTH} 限制，超过时明确拒绝并说明原因（而不是静默失败）。
 */
public final class AgentTool extends BaseTool {

  /**
   * 允许的最大嵌套深度。
   *
   * <p>取 2：主 agent（0）→ 子 agent（1）→ 孙 agent（2）已经是「把一个大任务拆成子任务、
   * 子任务再拆分」的极限。再深一层带来的收益远小于上下文与额度的消耗，而且模型在深层
   * 已经很难判断自己该做什么。
   */
  public static final int MAX_DEPTH = 2;

  /** 子 agent 任务描述的长度上限。 */
  static final int MAX_TASK_CHARS = 4000;

  private final SubAgentRunner runner;

  /** 当前嵌套深度；主 agent 直接构造时为 0。 */
  private final int depth;

  public AgentTool(SubAgentRunner runner) {
    this(runner, 0);
  }

  public AgentTool(SubAgentRunner runner, int depth) {
    this.runner = runner;
    this.depth = depth;
  }

  /** 当前深度。 */
  public int getDepth() {
    return depth;
  }

  @Override
  public String getName() {
    return ToolNames.AGENT;
  }

  @Override
  public String getDescription() {
    return "Delegate a self-contained task to a sub-agent that runs in its own context and "
        + "returns only its conclusion. Use this when investigating would require reading many "
        + "files — the file contents stay out of your context. "
        + "mode=explore (default) only reads; mode=code may also modify files. "
        + "Give it a complete, self-contained task description: it cannot see your conversation.";
  }

  @Override
  public ToolCategory getCategory() {
    // 子 agent 在 code 模式下可以写文件，因此归为 WRITE：只读模式下不放行。
    // explore 模式虽然只读，但分类是工具级属性，无法按参数变化——
    // 保守归类比放行一个可能写文件的工具更安全。
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
                            "Complete, self-contained task description. The sub-agent cannot "
                                + "see this conversation, so include the file paths, goals and "
                                + "constraints it needs."))
                .put(
                    "mode",
                    new JSONObject()
                        .put("type", "string")
                        .put("description", "explore (read-only, default) or code (may modify)")))
        .put("required", new org.json.JSONArray().put("task"));
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    String task = input.optString("task", "").trim();
    if (task.isEmpty()) {
      return error("task 不能为空：请给出一个完整、自包含的任务描述。");
    }
    if (task.length() > MAX_TASK_CHARS) {
      // 过长的任务描述通常意味着模型把整段对话粘了进来，而那正是子 agent 想避免的。
      return error("task 过长（" + task.length() + " 字符，上限 " + MAX_TASK_CHARS + "）。请精简到任务要点。");
    }

    if (runner == null) {
      return error("当前环境不支持子 agent。");
    }

    if (depth >= MAX_DEPTH) {
      // 明确拒绝而不是静默降级：模型需要知道自己该直接做这件事。
      return error(
          "已达子 agent 嵌套上限（" + MAX_DEPTH + " 层）。请直接完成这个任务，不要再委派。");
    }

    SubAgentRunner.Mode mode = SubAgentRunner.Mode.fromName(input.optString("mode", "explore"));

    if (context != null) {
      context.reportProgress("子 agent（" + mode.wireName() + "）: " + abbreviate(task, 80));
    }

    try {
      SubAgentRunner.Result result = runner.run(new SubAgentRunner.Request(task, mode, depth));
      if (result == null) {
        return error("子 agent 没有返回结果。");
      }
      if (!result.isSuccess()) {
        // 失败也要把子 agent 的说明带回给模型，让它判断是重试、换做法还是自己做。
        String detail = result.getOutput().isEmpty() ? "（没有更多信息）" : result.getOutput();
        return error("子 agent 未能完成任务：" + detail);
      }
      String output = result.getOutput().trim();
      if (output.isEmpty()) {
        // 空结论等于没做——明确说出来，否则模型会以为任务已完成。
        return error("子 agent 完成了执行但没有给出结论。请直接完成这个任务。");
      }
      return ok(
          output
              + "\n\n（子 agent 模式: "
              + mode.wireName()
              + "，轮次 "
              + result.getTurns()
              + "，工具调用 "
              + result.getToolCallCount()
              + "）");
    } catch (Exception e) {
      ExceptionUtils.restoreInterrupt(e);
      return error("子 agent 执行失败：" + ExceptionUtils.describeException(e));
    }
  }

  private static String abbreviate(String value, int max) {
    if (value.length() <= max) {
      return value;
    }
    return value.substring(0, max) + "…";
  }
}
