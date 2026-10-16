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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * 钉住 {@link AgentPipelineTool} 的执行侧：失败不中断、同层并行、上游结论注入下游、
 * prompt 截断、深度上限与空列表。这些决定「多子任务编排」在真实失败下是否可用。
 */
final class AgentPipelineExecutionTest {

  /** 记录每次请求、按脚本返回结果的假 runner。 */
  private static final class FakeRunner implements SubAgentRunner {
    final List<Request> requests = Collections.synchronizedList(new ArrayList<>());
    final java.util.Map<String, Result> byTask = new java.util.concurrent.ConcurrentHashMap<>();
    Result defaultResult = new Result(true, "done", 1, 2);
    RuntimeException toThrow;
    boolean returnNull;
    final AtomicInteger concurrent = new AtomicInteger();
    final AtomicInteger maxConcurrent = new AtomicInteger();
    long delayMs;

    @Override
    public Result run(Request request) {
      requests.add(request);
      int now = concurrent.incrementAndGet();
      maxConcurrent.accumulateAndGet(now, Math::max);
      try {
        if (delayMs > 0) {
          Thread.sleep(delayMs);
        }
        if (toThrow != null) {
          throw toThrow;
        }
        if (returnNull) {
          return null;
        }
        for (java.util.Map.Entry<String, Result> entry : byTask.entrySet()) {
          if (request.getTask().contains(entry.getKey())) {
            return entry.getValue();
          }
        }
        return defaultResult;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return new Result(false, "interrupted", 0, 0);
      } finally {
        concurrent.decrementAndGet();
      }
    }
  }

  private static JSONObject agent(String id, String prompt, String... deps) {
    JSONObject object = new JSONObject().put("id", id).put("prompt", prompt);
    if (deps.length > 0) {
      JSONArray array = new JSONArray();
      for (String dep : deps) {
        array.put(dep);
      }
      object.put("depends_on", array);
    }
    return object;
  }

  private static JSONArray agents(JSONObject... items) {
    JSONArray array = new JSONArray();
    for (JSONObject item : items) {
      array.put(item);
    }
    return array;
  }

  private static ToolContext context() {
    return ToolContext.builder().homePath(".").build();
  }

  @Test
  void nullRunnerExplains() {
    ToolResult result = new AgentPipelineTool(null).execute(
        new JSONObject().put("agents", agents(agent("a", "x"))), context());
    assertTrue(result.isError());
    assertTrue(result.getContent().contains("不支持子 agent"));
  }

  @Test
  void depthLimitBlocksNestedDelegation() {
    ToolResult result = new AgentPipelineTool(new FakeRunner(), AgentTool.MAX_DEPTH).execute(
        new JSONObject().put("agents", agents(agent("a", "x"))), context());
    assertTrue(result.isError());
    assertTrue(result.getContent().contains("嵌套上限"));
  }

  @Test
  void emptyAgentsIsRejected() {
    ToolResult result = new AgentPipelineTool(new FakeRunner())
        .execute(new JSONObject(), context());
    assertTrue(result.isError());
    assertTrue(result.getContent().contains("不能为空"));
  }

  @Test
  void tooManyAgentsIsRejected() throws Exception {
    JSONArray array = new JSONArray();
    for (int i = 0; i < 13; i++) {
      array.put(agent("a" + i, "p"));
    }
    ToolResult result = new AgentPipelineTool(new FakeRunner())
        .execute(new JSONObject().put("agents", array), context());
    assertTrue(result.isError());
    assertTrue(result.getContent().contains("子任务过多"));
  }

  @Test
  void successfulPipelineSummarizesEachAgent() {
    FakeRunner runner = new FakeRunner();
    runner.byTask.put("do a", new SubAgentRunner.Result(true, "结论 A", 1, 3));
    ToolResult result = new AgentPipelineTool(runner).execute(
        new JSONObject().put("agents", agents(agent("a", "do a"))), context());
    assertFalse(result.isError(), result.getContent());
    assertTrue(result.getContent().contains("## a"));
    assertTrue(result.getContent().contains("结论 A"));
    assertTrue(result.getContent().contains("总工具调用: 3"));
  }

  @Test
  void singleFailureDoesNotAbortPipelineButMarksError() {
    // 上游失败 → 整体以 error 返回（让主 agent 知道没全成功），但下游仍会执行。
    FakeRunner runner = new FakeRunner();
    runner.byTask.put("fail-task", new SubAgentRunner.Result(false, "爆了", 1, 0));
    ToolResult result = new AgentPipelineTool(runner).execute(
        new JSONObject().put("agents", agents(agent("a", "fail-task"))), context());
    assertTrue(result.isError());
    assertTrue(result.getContent().contains("状态: error"));
    assertEquals(1, runner.requests.size(), "失败节点仍被执行且流水线未中断");
  }

  @Test
  void upstreamOutputIsInjectedIntoDownstreamTask() {
    // 下游 prompt 必须带上上游结论，否则会重复上游的调查。
    FakeRunner runner = new FakeRunner();
    runner.byTask.put("investigate", new SubAgentRunner.Result(true, "上游发现的关键结论", 1, 1));
    new AgentPipelineTool(runner).execute(
        new JSONObject().put("agents", agents(
            agent("a", "investigate"),
            agent("b", "implement", "a"))),
        context());
    SubAgentRunner.Request downstream = runner.requests.get(1);
    assertTrue(downstream.getTask().contains("上游发现的关键结论"),
        "下游任务描述应注入上游输出：" + downstream.getTask());
  }

  @Test
  void sameLevelAgentsRunAndProduceAllResults() {
    FakeRunner runner = new FakeRunner();
    runner.delayMs = 5;
    ToolResult result = new AgentPipelineTool(runner).execute(
        new JSONObject().put("agents", agents(agent("a", "pa"), agent("b", "pb"))), context());
    assertFalse(result.isError(), result.getContent());
    assertEquals(2, runner.requests.size());
    assertTrue(runner.maxConcurrent.get() >= 2, "同层应并行执行");
  }

  @Test
  void missingPromptFallsBackToDescription() throws Exception {
    FakeRunner runner = new FakeRunner();
    JSONObject item = new JSONObject().put("id", "a").put("description", "描述兜底");
    new AgentPipelineTool(runner).execute(
        new JSONObject().put("agents", agents(item)), context());
    assertTrue(runner.requests.get(0).getTask().contains("描述兜底"));
  }

  @Test
  void oversizedPromptIsTruncated() {
    FakeRunner runner = new FakeRunner();
    String huge = "x".repeat(5000);
    new AgentPipelineTool(runner).execute(
        new JSONObject().put("agents", agents(agent("a", huge))), context());
    String task = runner.requests.get(0).getTask();
    // 注入的上游上下文为空时任务恰为截断后的 prompt（4000 字符）
    assertEquals(4000, task.length());
  }

  @Test
  void runnerExceptionBecomesErrorResultForThatAgent() {
    FakeRunner runner = new FakeRunner();
    runner.toThrow = new RuntimeException("runner 崩了");
    ToolResult result = new AgentPipelineTool(runner).execute(
        new JSONObject().put("agents", agents(agent("a", "pa"))), context());
    assertTrue(result.isError());
    assertTrue(result.getContent().contains("runner 崩了"), result.getContent());
  }

  @Test
  void nullRunnerResultBecomesErrorResult() {
    FakeRunner runner = new FakeRunner();
    runner.returnNull = true;
    ToolResult result = new AgentPipelineTool(runner).execute(
        new JSONObject().put("agents", agents(agent("a", "pa"))), context());
    assertTrue(result.isError());
    assertTrue(result.getContent().contains("没有返回结果"));
  }

  @Test
  void modeIsResolvedFromAgentType() {
    FakeRunner runner = new FakeRunner();
    JSONObject item = agent("a", "pa").put("type", "code");
    new AgentPipelineTool(runner).execute(
        new JSONObject().put("agents", agents(item)), context());
    assertEquals(SubAgentRunner.Mode.CODE, runner.requests.get(0).getMode());
  }

  @Test
  void writeScopeConflictIsRejected() {
    // 同层两个 agent 声明重叠写入范围 → 必须拒绝，否则并发写同一文件
    FakeRunner runner = new FakeRunner();
    JSONObject a = agent("a", "pa").put("write_scope", new JSONArray().put("src/Foo.java"));
    JSONObject b = agent("b", "pb").put("write_scope", new JSONArray().put("src/Foo.java"));
    ToolResult result = new AgentPipelineTool(runner).execute(
        new JSONObject().put("agents", agents(a, b)), context());
    assertTrue(result.isError(), result.getContent());
    assertTrue(runner.requests.isEmpty(), "冲突应在执行前拦下");
  }

  @Test
  void cyclicDependenciesAreRejected() {
    ToolResult result = new AgentPipelineTool(new FakeRunner()).execute(
        new JSONObject().put("agents", agents(agent("a", "pa", "b"), agent("b", "pb", "a"))),
        context());
    assertTrue(result.isError());
    assertTrue(result.getContent().contains("循环依赖") || result.getContent().contains("悬空"),
        result.getContent());
  }

  @Test
  void metadataAccessorsAreExposed() throws Exception {
    AgentPipelineTool tool = new AgentPipelineTool(new FakeRunner());
    assertEquals(com.tom.rv2ide.ai.tool.api.ToolNames.AGENT_PIPELINE, tool.getName());
    assertEquals(com.tom.rv2ide.ai.tool.api.ToolCategory.WRITE, tool.getCategory());
    assertEquals(com.tom.rv2ide.ai.tool.api.ToolDisplayCategory.AGENT_PIPELINE, tool.getDisplayCategory());
    assertFalse(tool.getDescription().isEmpty());
    assertEquals("array", tool.getParameters().getJSONObject("properties")
        .getJSONObject("agents").getString("type"));
    assertEquals("agents", tool.getParameters().getJSONArray("required").getString(0));
  }
}
