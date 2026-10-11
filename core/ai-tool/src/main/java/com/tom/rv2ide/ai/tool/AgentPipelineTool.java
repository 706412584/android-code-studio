/*
 * This file is part of AndroidCodeStudio.
 *
 * Adapted from LineCode Pro (https://github.com/LangLang03/LineCodePro),
 * licensed under the GNU General Public License v3.0 or later.
 * Modifications for AndroidCodeStudio are licensed under the same terms.
 */

package com.tom.rv2ide.ai.tool;

import com.tom.rv2ide.ai.tool.api.ToolCategory;
import com.tom.rv2ide.ai.tool.api.ToolDisplayCategory;
import com.tom.rv2ide.ai.tool.api.ToolNames;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 编排多个子 agent：按依赖分层，同层并行，上游输出注入下游。
 *
 * <p><b>与 {@code agent} 的差异</b>：{@code agent} 一次派一个子 agent，无法表达「B 等 A
 * 完成」或「两个 agent 不许改同一个文件」。本工具接收一组带 {@code depends_on} 与
 * {@code write_scope} 的子任务，按依赖分层（同层并行、跨层串行），并把上游的结论注入
 * 下游的任务描述。
 *
 * <p><b>工具调用次数上限怎么办</b>：每个子 agent 各自有独立的上限（由 runner 实现），
 * 本工具不额外限制总数——流水线本就用于「把大任务拆成若干独立子任务」，子任务数量由
 * 模型自己控制。
 *
 * <p><b>单层内单个子 agent 失败不中断整条流水线</b>：把失败信息作为该节点的输出继续，
 * 下游能看到「上游失败了」并据此调整，而不是整条流水线直接终止。
 */
public final class AgentPipelineTool extends BaseTool {

  /** 一个流水线允许的最大子任务数。 */
  private static final int MAX_AGENTS = 12;

  /** 每个子任务的 prompt 长度上限。 */
  private static final int MAX_PROMPT_CHARS = 4000;

  private final SubAgentRunner runner;
  private final int depth;

  public AgentPipelineTool(SubAgentRunner runner) {
    this(runner, 0);
  }

  public AgentPipelineTool(SubAgentRunner runner, int depth) {
    this.runner = runner;
    this.depth = depth;
  }

  @Override
  public String getName() {
    return ToolNames.AGENT_PIPELINE;
  }

  @Override
  public String getDescription() {
    return "编排多个子 agent 的流水线：每个子任务可声明 depends_on（等待上游完成）与 "
        + "write_scope（写入范围）。同一层的任务并行执行，上游的结论会注入下游的任务描述。"
        + "适合「先调研 A、B，再基于两者实现 C」这类有依赖的多步工作。"
        + "每个子任务看不到你的对话，prompt 必须自包含。";
  }

  @Override
  public ToolCategory getCategory() {
    // 子任务可写文件（type=code），归为 WRITE：只读模式下不放行。
    return ToolCategory.WRITE;
  }

  @Override
  public ToolDisplayCategory getDisplayCategory() {
    return ToolDisplayCategory.AGENT_PIPELINE;
  }

  @Override
  public JSONObject getParameters() throws org.json.JSONException {
    JSONObject agentSchema =
        new JSONObject()
            .put("type", "object")
            .put(
                "properties",
                new JSONObject()
                    .put("id", new JSONObject().put("type", "string").put("description", "唯一标识，供 depends_on 引用"))
                    .put(
                        "type",
                        new JSONObject()
                            .put("type", "string")
                            .put("description", "explore（只读，默认）或 code（可写文件）"))
                    .put("description", new JSONObject().put("type", "string").put("description", "一句话说明这个子任务做什么"))
                    .put("prompt", new JSONObject().put("type", "string").put("description", "完整、自包含的任务描述"))
                    .put(
                        "depends_on",
                        new JSONObject()
                            .put("type", "array")
                            .put("items", new JSONObject().put("type", "string"))
                            .put("description", "上游 agent 的 id 列表；本任务在它们完成后执行"))
                    .put(
                        "write_scope",
                        new JSONObject()
                            .put("type", "array")
                            .put("items", new JSONObject().put("type", "string"))
                            .put("description", "本任务会写入的文件/目录；同层不得重叠")))
            .put(
                "required",
                new JSONArray().put("id").put("prompt"));
    return new JSONObject()
        .put("type", "object")
        .put(
            "properties",
            new JSONObject()
                .put(
                    "agents",
                    new JSONObject()
                        .put("type", "array")
                        .put("items", agentSchema)
                        .put("description", "子任务列表（最多 " + MAX_AGENTS + " 个）")))
        .put("required", new JSONArray().put("agents"));
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    if (runner == null) {
      return error("当前环境不支持子 agent。");
    }
    if (depth >= AgentTool.MAX_DEPTH) {
      return error(
          "已达子 agent 嵌套上限（" + AgentTool.MAX_DEPTH + " 层）。请直接完成这个任务，不要再委派。");
    }

    PipelineDependencyResolver resolver = new PipelineDependencyResolver();
    List<PipelineAgent> agents = resolver.parsePipelineAgents(input.optJSONArray("agents"));
    if (agents.isEmpty()) {
      return error("agents 不能为空，或存在重复/缺失的 id。");
    }
    if (agents.size() > MAX_AGENTS) {
      return error("子任务过多（" + agents.size() + "），最多 " + MAX_AGENTS + " 个。");
    }
    String dependencyError = resolver.validatePipelineDependencies(agents);
    if (!dependencyError.isEmpty()) {
      return error(dependencyError);
    }
    List<List<PipelineAgent>> levels = resolver.dependencyLevels(agents);
    if (levels.isEmpty()) {
      return error("流水线存在循环依赖或悬空依赖（depends_on 引用了不存在的 id），无法执行。");
    }
    for (List<PipelineAgent> level : levels) {
      String scopeError = resolver.validateWriteScopes(level);
      if (!scopeError.isEmpty()) {
        return error(scopeError);
      }
    }

    Map<String, SubAgentRunner.Result> results = new LinkedHashMap<>();
    StringBuilder summary = new StringBuilder();
    summary.append("流水线完成，共 ").append(agents.size()).append(" 个子任务：");

    boolean hasError = false;
    int totalToolCalls = 0;

    for (List<PipelineAgent> level : levels) {
      List<SubAgentRunner.Result> levelResults =
          runLevel(level, results, resolver, context);
      for (int i = 0; i < level.size(); i++) {
        PipelineAgent agent = level.get(i);
        SubAgentRunner.Result result = levelResults.get(i);
        results.put(agent.getId(), result);
        totalToolCalls += result.getToolCallCount();
        boolean failed = !result.isSuccess();
        hasError = hasError || failed;
        summary
            .append("\n\n## ")
            .append(agent.getId())
            .append(" · ")
            .append(agent.getDescription())
            .append('\n')
            .append("状态: ")
            .append(failed ? "error" : "done")
            .append("，工具调用: ")
            .append(result.getToolCallCount())
            .append('\n')
            .append(result.getOutput());
      }
    }
    summary.append("\n\n总工具调用: ").append(totalToolCalls);
    // 有失败时以 error 返回，让主 agent 知道流水线未全部成功（而非只看汇总文本）。
    return hasError ? error(summary.toString()) : ok(summary.toString());
  }

  /** 执行一层：并行跑该层所有子任务，返回顺序与入参一致。 */
  private List<SubAgentRunner.Result> runLevel(
      List<PipelineAgent> level,
      Map<String, SubAgentRunner.Result> upstream,
      PipelineDependencyResolver resolver,
      ToolContext context) {
    List<SubAgentRunner.Result> out = new ArrayList<>();
    if (level.size() == 1) {
      out.add(runOne(level.get(0), upstream, resolver, context));
      return out;
    }

    int size = level.size();
    ExecutorService pool = Executors.newFixedThreadPool(size);
    try {
      List<Future<SubAgentRunner.Result>> futures = new ArrayList<>(size);
      for (PipelineAgent agent : level) {
        futures.add(pool.submit(() -> runOne(agent, upstream, resolver, context)));
      }
      for (Future<SubAgentRunner.Result> future : futures) {
        try {
          out.add(future.get());
        } catch (Exception e) {
          ExceptionUtils.restoreInterrupt(e);
          out.add(
              new SubAgentRunner.Result(
                  false, "子任务执行异常: " + ExceptionUtils.describeException(e), 0, 0));
        }
      }
    } finally {
      pool.shutdownNow();
      try {
        pool.awaitTermination(1, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    return out;
  }

  private SubAgentRunner.Result runOne(
      PipelineAgent agent,
      Map<String, SubAgentRunner.Result> upstream,
      PipelineDependencyResolver resolver,
      ToolContext context) {
    String prompt = agent.getPrompt();
    if (prompt.isEmpty()) {
      prompt = agent.getDescription();
    }
    if (prompt.length() > MAX_PROMPT_CHARS) {
      prompt = prompt.substring(0, MAX_PROMPT_CHARS);
    }
    // 上游输出注入：下游据此接着做，而不是重复上游的调查。
    String task = prompt + resolver.dependencyOutputContext(agent, upstream);

    SubAgentRunner.Mode mode = SubAgentRunner.Mode.fromName(agent.getType());
    if (context != null) {
      context.reportProgress("流水线 [" + agent.getId() + "]: " + abbreviate(agent.getDescription(), 60));
    }
    SubAgentRunner.ProgressListener forward = context == null ? null : context::reportProgress;

    try {
      SubAgentRunner.Result result =
          runner.run(
              new SubAgentRunner.Request(
                  task, mode, depth, null, ToolNameFilter.unrestricted(), forward));
      if (result == null) {
        return new SubAgentRunner.Result(false, "子任务没有返回结果。", 0, 0);
      }
      return result;
    } catch (Exception e) {
      ExceptionUtils.restoreInterrupt(e);
      return new SubAgentRunner.Result(
          false, "子任务失败: " + ExceptionUtils.describeException(e), 0, 0);
    }
  }

  private static String abbreviate(String value, int max) {
    if (value == null) {
      return "";
    }
    return value.length() <= max ? value : value.substring(0, max) + "…";
  }
}
