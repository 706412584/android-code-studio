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

package com.tom.rv2ide.ai.agent.builtin;

import com.tom.rv2ide.ai.tool.api.ToolNames;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 一个内置子 agent 的定义。
 *
 * <p><b>为什么要有内置预设</b>：{@code agent} 工具让模型能即席委派一个子任务，但「即席」
 * 意味着每次都要现写任务描述与角色约束——对「代码审查」「写测试」这类反复出现、要求固定的
 * 角色，这既费 token 又不稳定（模型每次给的约束都不一样）。内置预设把这类角色的**职责、
 * 提示词、工具集**固定下来，模型只需给出「要处理什么」，用户也可以直接调用。
 *
 * <p><b>与 {@code CustomAgent} 的区别</b>：自定义 agent 由用户从零定义，存在用户文件里；
 * 内置 agent 随应用提供，用户可覆盖（改提示词/工具集/启停）并一键恢复默认。
 *
 * <p><b>工具集是硬边界，不是建议</b>：{@link #getTools()} 会被执行方转成子 agent 的工具
 * 白名单——不在白名单里的工具**根本不会注册**给子 agent。因此「只读审查」不是靠提示词
 * 约束（提示词可能被忽略），而是结构上做不到。
 *
 * <p>本类不引用 Android 类型，可单测。
 */
public final class BuiltinAgent {

  /** 提示词长度上限。 */
  public static final int MAX_PROMPT_CHARS = 8000;

  /** 描述长度上限。 */
  public static final int MAX_DESCRIPTION_CHARS = 2000;

  /** 提示词最小长度：过短的提示词定义不出一个可用角色。 */
  public static final int MIN_PROMPT_CHARS = 20;

  /** 视为「写」的工具：白名单含其一即不是只读 agent。 */
  private static final List<String> WRITE_TOOLS =
      Collections.unmodifiableList(
          Arrays.asList(ToolNames.FILE_WRITE, ToolNames.FILE_EDIT, ToolNames.FILE_DELETE));

  private final String id;
  private final String name;
  private final String description;
  private final String prompt;
  private final List<String> tools;
  private final boolean enabled;

  public BuiltinAgent(
      String id,
      String name,
      String description,
      String prompt,
      List<String> tools,
      boolean enabled) {
    this.id = sanitizeId(id);
    this.name = name == null ? "" : name.trim();
    this.description = trimTo(description, MAX_DESCRIPTION_CHARS);
    this.prompt = trimTo(prompt, MAX_PROMPT_CHARS);
    this.tools = normalizeTools(tools);
    this.enabled = enabled;
  }

  /**
   * 规整 id。
   *
   * <p>id 要拼进工具名（{@code agentb_<id>}）并进入模型的 tools 定义，且是用户覆盖
   * 存储的键——因此限定为小写字母、数字、连字符、下划线。与 cc-haha 的 agentType 规则
   * 一致（小写、连字符分隔），保证模型能稳定复述。
   */
  static String sanitizeId(String raw) {
    if (raw == null) {
      return "";
    }
    String trimmed = raw.trim().toLowerCase(Locale.ROOT);
    StringBuilder sb = new StringBuilder(trimmed.length());
    for (int i = 0; i < trimmed.length(); i++) {
      char c = trimmed.charAt(i);
      if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_') {
        sb.append(c);
      } else if (c == ' ' || c == '.') {
        sb.append('-');
      }
    }
    String result = sb.toString();
    while (result.startsWith("-") || result.startsWith("_")) {
      result = result.substring(1);
    }
    while (result.endsWith("-") || result.endsWith("_")) {
      result = result.substring(0, result.length() - 1);
    }
    return result;
  }

  private static String trimTo(String value, int max) {
    String body = value == null ? "" : value.trim();
    return body.length() > max ? body.substring(0, max) : body;
  }

  /** 归一化工具白名单：去空白、去重、保持顺序。空列表表示不限。 */
  private static List<String> normalizeTools(List<String> raw) {
    if (raw == null || raw.isEmpty()) {
      return Collections.emptyList();
    }
    List<String> result = new ArrayList<>(raw.size());
    for (String tool : raw) {
      if (tool == null) {
        continue;
      }
      String name = tool.trim();
      if (!name.isEmpty() && !result.contains(name)) {
        result.add(name);
      }
    }
    return Collections.unmodifiableList(result);
  }

  /** 稳定标识，如 {@code code-reviewer}。用户覆盖存储以它为键。 */
  public String getId() {
    return id;
  }

  /** 展示名，如「代码审查员」。 */
  public String getName() {
    return name;
  }

  /** 何时该用的说明；进入工具描述，供模型判断该不该派遣。 */
  public String getDescription() {
    return description;
  }

  /** 专用系统提示词，定义这个角色的职责与要求。 */
  public String getPrompt() {
    return prompt;
  }

  /** 工具白名单；空列表表示不限制。 */
  public List<String> getTools() {
    return tools;
  }

  public boolean isEnabled() {
    return enabled;
  }

  /**
   * 是否可被用户编辑。
   *
   * <p>内置 agent 一律可编辑（改提示词/工具集/启停），并可一键恢复默认。保留此方法是为了
   * 让 UI 与未来「锁定预设」有统一的判断点。
   */
  public boolean isEditable() {
    return true;
  }

  /** 是否限制了工具集。 */
  public boolean restrictsTools() {
    return !tools.isEmpty();
  }

  /**
   * 是否是只读角色。
   *
   * <p>由工具白名单推导：限制了工具集、且白名单里没有任何写类工具，才是只读。
   * 用于选择子 agent 的工作模式（explore/code）与工具分类（READ/WRITE）。
   */
  public boolean isReadOnly() {
    if (tools.isEmpty()) {
      return false;
    }
    for (String tool : tools) {
      if (WRITE_TOOLS.contains(tool)) {
        return false;
      }
    }
    return true;
  }

  /** 注册为工具时使用的名字。 */
  public String toolName() {
    return BuiltinAgents.TOOL_PREFIX + id;
  }

  /** 是否可用：启用、id/名字/提示词非空且提示词长度达标。 */
  public boolean isUsable() {
    return enabled
        && !id.isEmpty()
        && !name.isEmpty()
        && prompt.length() >= MIN_PROMPT_CHARS;
  }

  /** 校验失败原因；可用时返回空串。 */
  public String validationError() {
    if (id.isEmpty()) {
      return "id 不能为空（只能用字母、数字、连字符、下划线）。";
    }
    if (name.isEmpty()) {
      return "名字不能为空。";
    }
    if (prompt.length() < MIN_PROMPT_CHARS) {
      return "提示词过短（至少 " + MIN_PROMPT_CHARS + " 字符）。";
    }
    return "";
  }

  // ---- 派生副本（用户覆盖与合并用） ----

  public BuiltinAgent withEnabled(boolean nextEnabled) {
    return new BuiltinAgent(id, name, description, prompt, tools, nextEnabled);
  }

  public BuiltinAgent withDescription(String nextDescription) {
    return new BuiltinAgent(id, name, nextDescription, prompt, tools, enabled);
  }

  public BuiltinAgent withPrompt(String nextPrompt) {
    return new BuiltinAgent(id, name, description, nextPrompt, tools, enabled);
  }

  public BuiltinAgent withTools(List<String> nextTools) {
    return new BuiltinAgent(id, name, description, prompt, nextTools, enabled);
  }

  @Override
  public String toString() {
    return "BuiltinAgent{" + id + (enabled ? "" : " (disabled)") + "}";
  }
}
