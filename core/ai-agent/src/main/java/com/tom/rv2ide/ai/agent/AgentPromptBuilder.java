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

package com.tom.rv2ide.ai.agent;

import com.tom.rv2ide.ai.agent.prompt.ChatMode;
import com.tom.rv2ide.ai.agent.prompt.PromptRenderer;
import com.tom.rv2ide.ai.agent.prompt.PromptTemplateStore;
import com.tom.rv2ide.ai.agent.prompt.PromptTemplates;
import com.tom.rv2ide.ai.tool.api.ToolInfo;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 生成 agent 的系统提示词。
 *
 * <p><b>从硬编码改为模板 + 占位符</b>：此前提示词的每一句话都写在代码里，改一个措辞
 * 要重新编译。现在文本是数据（{@link PromptTemplates}），由
 * {@link PromptTemplateStore} 覆盖，因此用户可以在设置界面里改提示词并立刻生效。
 *
 * <p>渲染语义见 {@link PromptRenderer}：单趟替换、未提供的占位符变空串。
 *
 * <p>本类不引用 Android 类型，可单测。
 */
public final class AgentPromptBuilder {

  private final String identity;
  private final PromptTemplateStore templates;

  public AgentPromptBuilder() {
    this("ACS AI Agent", PromptTemplateStore.defaults());
  }

  public AgentPromptBuilder(String identity) {
    this(identity, PromptTemplateStore.defaults());
  }

  /**
   * @param identity 助手身份，填入 {@code {{MODEL_IDENTITY}}}
   * @param templates 模板存储；null 表示始终使用默认模板
   */
  public AgentPromptBuilder(String identity, PromptTemplateStore templates) {
    this.identity = identity == null || identity.isEmpty() ? "ACS AI Agent" : identity;
    this.templates = templates == null ? PromptTemplateStore.defaults() : templates;
  }

  /**
   * 生成系统提示词（默认模式）。
   *
   * @param homePath 工作区根目录，模型据此构造相对路径
   * @param tools 可用工具列表
   */
  public String build(String homePath, List<ToolInfo> tools) {
    return build(homePath, tools, false);
  }

  /**
   * 生成系统提示词。
   *
   * <p><b>为什么需要 {@code nativeTools} 开关</b>：实测发现，对支持原生工具调用的端点
   * 也注入 XML 兜底格式说明，会显著诱导模型放弃原生 tool_calls 去写 XML——而免费/小型
   * 模型写出的 XML 常常是畸形的（如 {@code </parameter name="content">}、标签不闭合），
   * 结果原生与文本两条路径都解析不出调用，表现为「模型什么都没做」。
   * 同一提示词去掉该段落后，原生 tool_calls 成功率从 1/6 升到 8/8。
   *
   * <p>因此 XML 说明只在模型确实拿不到 tools 字段时才给出。
   *
   * @param homePath 工作区根目录
   * @param tools 可用工具列表
   * @param nativeTools 协议是否支持原生工具调用；true 时不注入 XML 兜底格式说明
   */
  public String build(String homePath, List<ToolInfo> tools, boolean nativeTools) {
    return build(homePath, tools, nativeTools, null);
  }

  /**
   * 生成系统提示词（含待办状态）。
   *
   * <p><b>为什么待办要进提示词</b>：{@code todo_update} 把计划外化成列表，但模型只有在
   * 提示词里看到它，才能在几十轮调用之后仍知道「我做到哪一步、还剩什么」。
   * 只存不读等于没记。
   *
   * @param todoState 已渲染的待办文本；null 或空表示无待办，此时不注入该段落
   */
  public String build(
      String homePath, List<ToolInfo> tools, boolean nativeTools, String todoState) {
    return build(homePath, tools, nativeTools, todoState, ChatMode.DEFAULT, null);
  }

  /**
   * 生成系统提示词（完整参数）。
   *
   * @param homePath 工作区根目录
   * @param tools 可用工具列表
   * @param nativeTools 协议是否支持原生工具调用
   * @param todoState 待办文本；null 或空表示无待办
   * @param chatMode 对话模式；null 视作默认模式
   * @param modelInfo 模型信息（服务商 / 模型名 / 协议），用于填充对应占位符；可为 null
   */
  public String build(
      String homePath,
      List<ToolInfo> tools,
      boolean nativeTools,
      String todoState,
      ChatMode chatMode,
      ModelInfo modelInfo) {

    ChatMode mode = chatMode == null ? ChatMode.DEFAULT : chatMode;
    Map<String, String> values = new HashMap<>();

    // 身份与模型
    values.put("MODEL_IDENTITY", identity);
    values.put("MODEL_PROVIDER", modelInfo == null ? "" : modelInfo.providerId);
    values.put("MODEL_NAME", modelInfo == null ? "" : modelInfo.modelId);
    values.put("MODEL_PROTOCOL", modelInfo == null ? "" : modelInfo.protocolLabel);

    // 环境
    values.put("HOME_PATH", homePath == null ? "" : homePath);
    values.put(
        "WORKSPACE_CONTEXT",
        homePath == null || homePath.isEmpty()
            ? ""
            : PromptRenderer.render(templates.resolve(PromptTemplates.WORKSPACE_CONTEXT), values));

    // 模式
    values.put("CHAT_MODE", mode.getId());
    values.put(
        "CHAT_MODE_CONTEXT",
        PromptRenderer.render(
            templates.resolve(PromptTemplates.chatModeTemplateId(mode)), values));

    // 工具
    // 空列表时仍渲染该段落：{@code renderToolList} 会给出「当前没有可用工具」，
    // 这比整段消失更有信息量——模型能据此知道「不是我不该用工具，而是确实没有」。
    values.put("TOOL_LIST", renderToolList(tools));
    // CHAT 模式明确不给工具清单：模型看不到工具名，就不会去「顺手调用」。
    values.put(
        "TOOLS_CONTEXT",
        mode.allowsTools()
            ? PromptRenderer.render(templates.resolve(PromptTemplates.TOOLS_CONTEXT), values)
            : "");

    // 文本工具调用格式：仅在不支持原生工具调用且当前模式允许工具时给出。
    // 对支持原生工具的端点注入这段会诱导模型改用 XML（见方法注释）。
    values.put(
        "TOOL_CALL_FORMAT",
        !nativeTools && mode.allowsTools()
            ? PromptRenderer.render(templates.resolve(PromptTemplates.TOOL_CALL_FORMAT), values)
            : "");

    // 任务状态：清单正文与「标题 + 清单 + 提示」的完整段落是两个不同的占位符。
    // 若两者同名，段落模板里的 {{TODO_LIST}} 会解析到段落自身，清单永远注入不进去。
    values.put("TODO_LIST", todoState == null ? "" : todoState.trim());
    values.put(
        "TODO_SECTION",
        todoState == null || todoState.trim().isEmpty()
            ? ""
            : PromptRenderer.render(templates.resolve(PromptTemplates.TODO_SECTION), values));

    // 收尾注意事项
    values.put(
        "NOTES", PromptRenderer.render(templates.resolve(PromptTemplates.NOTES), values));

    // 当前未使用的占位符（角色、语气、历史、摘要、任务描述）显式置空：
    // 模板里若引用了它们，渲染结果为空串而不是留下字面量。
    values.put("ROLE_PROMPT", "");
    values.put("TONE_CONTEXT", "");
    values.put("HISTORY_SECTION", "");
    values.put("SUMMARY", "");
    values.put("TASK_DESCRIPTION", "");

    String template = templates.resolve(PromptTemplates.SYSTEM_PROMPT);
    return PromptRenderer.renderAndTidy(template, values);
  }

  /** 工具清单正文：每行 {@code - name: description}，附补充说明。 */
  private static String renderToolList(List<ToolInfo> tools) {
    if (tools == null || tools.isEmpty()) {
      return "(当前没有可用工具)";
    }
    StringBuilder sb = new StringBuilder();
    for (ToolInfo tool : tools) {
      sb.append("- ").append(tool.getName()).append(": ").append(tool.getDescription()).append('\n');
      String supplement = safePromptSupplement(tool);
      if (supplement != null && !supplement.isEmpty()) {
        sb.append("  ").append(supplement.replace("\n", "\n  ")).append('\n');
      }
    }
    return sb.toString().trim();
  }

  private static String safePromptSupplement(ToolInfo tool) {
    try {
      return tool.promptSupplement("");
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** 模型信息，用于填充 {@code MODEL_*} 占位符。 */
  public static final class ModelInfo {
    public final String providerId;
    public final String modelId;
    public final String protocolLabel;

    public ModelInfo(String providerId, String modelId, String protocolLabel) {
      this.providerId = providerId == null ? "" : providerId;
      this.modelId = modelId == null ? "" : modelId;
      this.protocolLabel = protocolLabel == null ? "" : protocolLabel;
    }
  }
}
