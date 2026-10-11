/*
 * This file is part of AndroidCodeStudio.
 *
 * Adapted from LineCode Pro (https://github.com/LangLang03/LineCodePro),
 * licensed under the GNU General Public License v3.0 or later.
 * Modifications for AndroidCodeStudio are licensed under the same terms.
 */

package com.tom.rv2ide.ai.tool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * agent 流水线中的一个子任务节点。
 *
 * <p>由 {@code agent_pipeline} 工具从模型的 JSON 输入解析而来。相比单个 {@code agent}
 * 调用，它额外携带**依赖关系**（{@code dependsOn}）与**读写范围**（{@code readScope} /
 * {@code writeScope}），使多个子 agent 之间能表达「B 等 A 完成」以及「两个 agent 不许
 * 写同一个文件」。
 */
public final class PipelineAgent {

  private final String id;
  private final String type;
  private final String description;
  private final String prompt;
  private final List<String> readScope;
  private final List<String> writeScope;
  private final List<String> dependencies;

  public PipelineAgent(
      String id,
      String type,
      String description,
      String prompt,
      List<String> readScope,
      List<String> writeScope,
      List<String> dependencies) {
    this.id = id == null ? "" : id;
    this.type = type == null ? "" : type;
    this.description = description == null ? "" : description;
    this.prompt = prompt == null ? "" : prompt;
    this.readScope = copy(readScope);
    this.writeScope = copy(writeScope);
    this.dependencies = copy(dependencies);
  }

  private static List<String> copy(List<String> values) {
    if (values == null) {
      return Collections.emptyList();
    }
    return Collections.unmodifiableList(new ArrayList<>(values));
  }

  public String getId() {
    return id;
  }

  /** 子 agent 模式（explore / code），已归一。 */
  public String getType() {
    return type;
  }

  public String getDescription() {
    return description;
  }

  public String getPrompt() {
    return prompt;
  }

  /** 只读范围（供提示词描述，不强制）。 */
  public List<String> getReadScope() {
    return readScope;
  }

  /** 写入范围；同层两个 agent 的写入范围不得重叠（见 {@link AgentPipelineTool}）。 */
  public List<String> getWriteScope() {
    return writeScope;
  }

  /** 依赖的上游 agent id。 */
  public List<String> getDependencies() {
    return dependencies;
  }
}
