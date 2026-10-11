/*
 * This file is part of AndroidCodeStudio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.tom.rv2ide.ai.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * agent 流水线的依赖解析与编排。
 *
 * <p><b>为什么需要这组测试</b>：依赖解析的错误在真机上只表现为「AI 说编排好了、实际
 * 没跑或跑错顺序」——极难归因。这里钉住四类必挡的错误：重复 id、自依赖、循环依赖、
 * 悬空依赖，以及同层写范围冲突。另验证同层并行、上游输出注入下游。
 */
final class AgentPipelineToolTest {

  private static final PipelineDependencyResolver RESOLVER = new PipelineDependencyResolver();

  private static JSONArray agents(JSONObject... items) {
    JSONArray array = new JSONArray();
    for (JSONObject item : items) {
      array.put(item);
    }
    return array;
  }

  private static JSONObject agent(String id, String... deps) {
    JSONObject object = new JSONObject().put("id", id).put("prompt", "do " + id);
    if (deps.length > 0) {
      JSONArray array = new JSONArray();
      for (String dep : deps) {
        array.put(dep);
      }
      object.put("depends_on", array);
    }
    return object;
  }

  // ---- 依赖解析 ----

  @Test
  void parseRejectsDuplicateIds() {
    List<PipelineAgent> parsed =
        RESOLVER.parsePipelineAgents(agents(agent("a"), agent("a")));
    assertTrue(parsed.isEmpty(), "重复 id 应整体拒绝");
  }

  @Test
  void validateRejectsSelfDependency() {
    List<PipelineAgent> parsed = RESOLVER.parsePipelineAgents(agents(agent("a", "a")));
    assertEquals(1, parsed.size());
    assertFalse(RESOLVER.validatePipelineDependencies(parsed).isEmpty(), "自依赖应被拒");
  }

  @Test
  void dependencyLevelsGroupsIndependentAgentsTogether() {
    // a、b 无依赖（同层）；c 依赖 a、b（第二层）。
    List<PipelineAgent> parsed =
        RESOLVER.parsePipelineAgents(agents(agent("a"), agent("b"), agent("c", "a", "b")));
    List<List<PipelineAgent>> levels = RESOLVER.dependencyLevels(parsed);
    assertEquals(2, levels.size(), "应分两层");
    assertEquals(2, levels.get(0).size(), "a、b 同层");
    assertEquals("c", levels.get(1).get(0).getId());
  }

  @Test
  void dependencyLevelsRejectsCycle() {
    List<PipelineAgent> parsed =
        RESOLVER.parsePipelineAgents(agents(agent("a", "b"), agent("b", "a")));
    assertTrue(RESOLVER.dependencyLevels(parsed).isEmpty(), "循环依赖应返回空");
  }

  @Test
  void dependencyLevelsRejectsDanglingDependency() {
    List<PipelineAgent> parsed = RESOLVER.parsePipelineAgents(agents(agent("a", "ghost")));
    assertTrue(RESOLVER.dependencyLevels(parsed).isEmpty(), "悬空依赖应返回空");
  }

  // ---- 写范围互斥 ----

  @Test
  void sameLevelWriteScopeOverlapIsRejected() {
    JSONObject a = agent("a").put("write_scope", new JSONArray().put("src/Main.kt"));
    JSONObject b = agent("b").put("write_scope", new JSONArray().put("src/Main.kt"));
    List<PipelineAgent> parsed = RESOLVER.parsePipelineAgents(agents(a, b));
    List<List<PipelineAgent>> levels = RESOLVER.dependencyLevels(parsed);
    assertFalse(
        RESOLVER.validateWriteScopes(levels.get(0)).isEmpty(), "同层写同一文件应被拒");
  }

  @Test
  void disjointWriteScopesAreAllowed() {
    JSONObject a = agent("a").put("write_scope", new JSONArray().put("src/A.kt"));
    JSONObject b = agent("b").put("write_scope", new JSONArray().put("src/B.kt"));
    List<PipelineAgent> parsed = RESOLVER.parsePipelineAgents(agents(a, b));
    List<List<PipelineAgent>> levels = RESOLVER.dependencyLevels(parsed);
    assertTrue(RESOLVER.validateWriteScopes(levels.get(0)).isEmpty(), "不同文件应放行");
  }

  // ---- 编排执行 ----

  /** 记录调用顺序与并发度的假 runner。 */
  private static final class FakeRunner implements SubAgentRunner {
    final List<String> seenTasks = new ArrayList<>();
    final Map<String, String> outputs = new LinkedHashMap<>();
    final AtomicInteger concurrent = new AtomicInteger();
    final AtomicInteger maxConcurrent = new AtomicInteger();
    long delayMs = 0;

    @Override
    public Result run(Request request) {
      int now = concurrent.incrementAndGet();
      maxConcurrent.accumulateAndGet(now, Math::max);
      try {
        String task = request.getTask();
        seenTasks.add(task);
        if (delayMs > 0) {
          Thread.sleep(delayMs);
        }
        // 用任务里的 id 作为输出，便于断言注入。
        String id = task.startsWith("do ") ? task.substring(3).split("\\s")[0] : task;
        String output = "OUT(" + id + ")";
        outputs.put(id, output);
        return new Result(true, output, 1, 1);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return new Result(false, "interrupted", 0, 0);
      } finally {
        concurrent.decrementAndGet();
      }
    }
  }

  private static ToolContext ctx() {
    return ToolContext.builder().homePath(System.getProperty("java.io.tmpdir")).build();
  }

  @Test
  void runsLevelsInOrderAndInjectsUpstreamOutput() {
    FakeRunner runner = new FakeRunner();
    JSONObject a = agent("a");
    JSONObject b = agent("b");
    JSONObject c = agent("c", "a", "b");
    ToolResult result =
        new AgentPipelineTool(runner)
            .execute(new JSONObject().put("agents", agents(a, b, c)), ctx());

    assertFalse(result.isError(), result.getContent());
    assertTrue(result.getContent().contains("OUT(a)"), result.getContent());
    assertTrue(result.getContent().contains("OUT(c)"), result.getContent());
    // c 的任务描述里应注入 a、b 的上游输出。
    String cTask = runner.seenTasks.get(runner.seenTasks.size() - 1);
    assertTrue(cTask.contains("上游 Agent 的输出"), cTask);
    assertTrue(cTask.contains("OUT(a)"), cTask);
    assertTrue(cTask.contains("OUT(b)"), cTask);
  }

  @Test
  void runsSameLevelAgentsConcurrently() throws Exception {
    FakeRunner runner = new FakeRunner();
    runner.delayMs = 300;
    // a、b、c 无依赖 → 同层，应并发。
    ToolResult result =
        new AgentPipelineTool(runner)
            .execute(
                new JSONObject().put("agents", agents(agent("a"), agent("b"), agent("c"))),
                ctx());

    assertFalse(result.isError(), result.getContent());
    assertTrue(
        runner.maxConcurrent.get() >= 2,
        "同层应并发执行，实测最大并发=" + runner.maxConcurrent.get());
  }

  @Test
  void rejectsEmptyAgents() {
    ToolResult result =
        new AgentPipelineTool(new FakeRunner()).execute(new JSONObject(), ctx());
    assertTrue(result.isError(), "空 agents 应报错");
  }

  @Test
  void reportsErrorWhenASubAgentFails() {
    SubAgentRunner failing =
        request -> new SubAgentRunner.Result(false, "boom", 0, 0);
    ToolResult result =
        new AgentPipelineTool(failing)
            .execute(new JSONObject().put("agents", agents(agent("a"))), ctx());
    assertTrue(result.isError(), "子任务失败应使流水线以 error 返回");
    assertTrue(result.getContent().contains("boom"), result.getContent());
  }
}
