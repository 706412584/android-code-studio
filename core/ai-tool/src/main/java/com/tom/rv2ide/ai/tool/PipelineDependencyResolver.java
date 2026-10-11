/*
 * This file is part of AndroidCodeStudio.
 *
 * Adapted from LineCode Pro (https://github.com/LangLang03/LineCodePro),
 * licensed under the GNU General Public License v3.0 or later.
 * Modifications for AndroidCodeStudio are licensed under the same terms.
 */

package com.tom.rv2ide.ai.tool;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 解析并校验 {@code agent_pipeline} 的 agents 列表，计算可并行的依赖分层。
 *
 * <p>纯逻辑、零依赖，因此可在 JVM 上单测（依赖解析是流水线里最容易出错的一环，
 * 循环依赖、重复 id、悬空依赖都要在这里挡掉，而不是让它们在运行时炸掉）。
 */
public final class PipelineDependencyResolver {

  /**
   * 解析 agents 数组。
   *
   * <p>任一条目格式错误（非对象、id 为空、id 重复）即整体返回空列表——**不静默跳过**：
   * 跳过一条会让模型以为它编排的任务真的跑了，而实际没有，是比报错更糟的失败。
   */
  public List<PipelineAgent> parsePipelineAgents(JSONArray array) {
    List<PipelineAgent> agents = new ArrayList<>();
    if (array == null) {
      return agents;
    }
    Set<String> ids = new HashSet<>();
    for (int i = 0; i < array.length(); i++) {
      JSONObject object = array.optJSONObject(i);
      if (object == null) {
        return new ArrayList<>();
      }
      String id = object.optString("id", "").trim();
      if (id.isEmpty() || !ids.add(id)) {
        return new ArrayList<>();
      }
      agents.add(
          new PipelineAgent(
              id,
              SubAgentRunner.Mode.fromName(object.optString("type", "")).wireName(),
              object.optString("description", "").trim(),
              object.optString("prompt", "").trim(),
              scopeList(object.optJSONArray("read_scope")),
              scopeList(object.optJSONArray("write_scope")),
              scopeList(object.optJSONArray("depends_on"))));
    }
    return agents;
  }

  /** 校验：返回错误描述，无错误返回空串。 */
  public String validatePipelineDependencies(List<PipelineAgent> agents) {
    if (agents == null || agents.isEmpty()) {
      return "agents 不能为空。";
    }
    for (PipelineAgent agent : agents) {
      for (String dependency : agent.getDependencies()) {
        if (agent.getId().equals(dependency)) {
          return "Agent 不能依赖自身: " + agent.getId();
        }
      }
    }
    return "";
  }

  /**
   * 把 agents 分成可并行的层级：同一层内互不依赖，可同时执行；层与层之间必须按序。
   *
   * <p>存在循环依赖或悬空依赖（依赖一个不存在的 id）时返回空列表——调用方据此报错，
   * 而不是让它变成一个永远跑不完的循环。
   */
  public List<List<PipelineAgent>> dependencyLevels(List<PipelineAgent> agents) {
    List<List<PipelineAgent>> levels = new ArrayList<>();
    if (agents == null || agents.isEmpty()) {
      return levels;
    }
    Set<String> allIds = new HashSet<>();
    for (PipelineAgent agent : agents) {
      allIds.add(agent.getId());
    }
    // 悬空依赖：依赖了一个不存在的 id。
    for (PipelineAgent agent : agents) {
      for (String dependency : agent.getDependencies()) {
        if (!allIds.contains(dependency)) {
          return new ArrayList<>();
        }
      }
    }

    Set<String> completed = new HashSet<>();
    while (completed.size() < agents.size()) {
      List<PipelineAgent> level = new ArrayList<>();
      for (PipelineAgent agent : agents) {
        if (completed.contains(agent.getId())) {
          continue;
        }
        if (completed.containsAll(agent.getDependencies())) {
          level.add(agent);
        }
      }
      if (level.isEmpty()) {
        // 有环：本轮一个都放不进去。
        return new ArrayList<>();
      }
      for (PipelineAgent agent : level) {
        completed.add(agent.getId());
      }
      levels.add(level);
    }
    return levels;
  }

  /**
   * 校验同层内的写入范围是否互斥。
   *
   * <p>同层并行执行的 agent 若写同一个文件，结果是竞态——谁后写谁赢，且不可复现。
   * 这是流水线相对串行子 agent 新增的风险，必须在执行前挡掉。
   *
   * @return 错误描述（含冲突的 id 与路径），无冲突返回空串
   */
  public String validateWriteScopes(List<PipelineAgent> level) {
    if (level == null || level.size() < 2) {
      return "";
    }
    Map<String, String> owner = new LinkedHashMap<>();
    for (PipelineAgent agent : level) {
      for (String path : agent.getWriteScope()) {
        String key = normalizeScope(path);
        if (key.isEmpty()) {
          continue;
        }
        String previous = owner.get(key);
        if (previous != null) {
          return "同层 Agent 的写入范围重叠：" + previous + " 与 " + agent.getId() + " 都要写 " + path;
        }
        owner.put(key, agent.getId());
      }
    }
    return "";
  }

  /** 依赖上游的输出，拼进本 agent 的任务描述。 */
  public String dependencyOutputContext(
      PipelineAgent agent, Map<String, SubAgentRunner.Result> results) {
    if (agent.getDependencies().isEmpty() || results == null) {
      return "";
    }
    StringBuilder builder = new StringBuilder();
    builder.append("\n\n## 上游 Agent 的输出\n");
    boolean any = false;
    for (String dependency : agent.getDependencies()) {
      SubAgentRunner.Result result = results.get(dependency);
      if (result == null) {
        continue;
      }
      any = true;
      builder.append("\n### ").append(dependency).append('\n').append(result.getOutput()).append('\n');
    }
    if (!any) {
      return "";
    }
    builder.append("\n请基于以上结果继续你的任务。");
    return builder.toString();
  }

  private static List<String> scopeList(JSONArray array) {
    List<String> values = new ArrayList<>();
    if (array == null) {
      return values;
    }
    for (int i = 0; i < array.length(); i++) {
      String value = array.optString(i, "").trim();
      if (!value.isEmpty()) {
        values.add(value);
      }
    }
    return values;
  }

  /** 范围归一：去掉结尾的 `/` 与 `/**`，使 `src/` 与 `src` 视为同一处。 */
  private static String normalizeScope(String path) {
    if (path == null) {
      return "";
    }
    String value = path.trim();
    while (value.endsWith("/")) {
      value = value.substring(0, value.length() - 1);
    }
    if (value.endsWith("/**")) {
      value = value.substring(0, value.length() - 3);
    }
    return value;
  }
}
