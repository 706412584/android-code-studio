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

package com.tom.rv2ide.ai.agent.command;

import java.util.Locale;

/**
 * 用户自定义的 agent 扩展。
 *
 * <p><b>与子 agent 的区别</b>：子 agent（{@code agent} 工具）是模型自己决定派发的、
 * 临时的上下文隔离；自定义 agent 是**用户预先定义**的、有固定职责的角色，例如
 * 「代码审查员」「提交信息撰写者」「测试生成器」。模型通过
 * {@code agentx_<名字>} 工具调用它们。
 *
 * <p><b>为什么值得有</b>：内置提示词只能覆盖通用场景。用户对自己的项目有特定要求
 * （「审查时必须检查是否遗漏了 i18n 字符串」），把它固化成一个自定义 agent 后，
 * 不必每次在对话里重复说明。
 *
 * <p>本类不引用 Android 类型，可单测。
 */
public final class CustomAgent {

  /** 工具名前缀，与 {@code ToolNames.CUSTOM_AGENT_PREFIX} 一致。 */
  public static final String TOOL_PREFIX = "agentx_";

  /** 提示词长度上限。 */
  public static final int MAX_PROMPT_CHARS = 8000;

  /** 名字长度上限。 */
  public static final int MAX_NAME_CHARS = 40;

  private final String name;
  private final String description;
  private final String prompt;
  private final boolean enabled;

  public CustomAgent(String name, String description, String prompt, boolean enabled) {
    this.name = sanitizeName(name);
    this.description = description == null ? "" : description.trim();
    String body = prompt == null ? "" : prompt.trim();
    this.prompt = body.length() > MAX_PROMPT_CHARS ? body.substring(0, MAX_PROMPT_CHARS) : body;
    this.enabled = enabled;
  }

  /**
   * 规整名字。
   *
   * <p>名字要能拼进工具名（{@code agentx_<名字>}），而工具名会进入模型的 tools 定义，
   * 因此只保留字母、数字、下划线、连字符与中文——空格、标点、斜杠会让工具名难以被模型
   * 稳定复述。
   */
  static String sanitizeName(String raw) {
    if (raw == null) {
      return "";
    }
    String trimmed = raw.trim();
    StringBuilder sb = new StringBuilder(trimmed.length());
    for (int i = 0; i < trimmed.length(); i++) {
      char c = trimmed.charAt(i);
      if (Character.isLetterOrDigit(c) || c == '_' || c == '-') {
        sb.append(c);
      } else if (c == ' ' || c == '.' || c == '/') {
        // 常见的分隔符转成下划线，而不是直接丢弃——丢弃会把「code reviewer」变成
        // 「codereviewer」，与用户预期不符。
        sb.append('_');
      }
    }
    String result = sb.toString();
    while (result.startsWith("_")) {
      result = result.substring(1);
    }
    while (result.endsWith("_")) {
      result = result.substring(0, result.length() - 1);
    }
    if (result.length() > MAX_NAME_CHARS) {
      result = result.substring(0, MAX_NAME_CHARS);
    }
    return result;
  }

  /** 用户给的名字（已规整）。 */
  public String getName() {
    return name;
  }

  /** 一句话说明，展示在工具描述里让模型知道何时该调用。 */
  public String getDescription() {
    return description;
  }

  /** 系统提示词，定义这个 agent 的职责与要求。 */
  public String getPrompt() {
    return prompt;
  }

  public boolean isEnabled() {
    return enabled;
  }

  /** 注册为工具时使用的名字。 */
  public String toolName() {
    return TOOL_PREFIX + name;
  }

  /** 是否可用：名字与提示词都不能为空，且未被禁用。 */
  public boolean isUsable() {
    return enabled && !name.isEmpty() && !prompt.isEmpty();
  }

  /** 校验失败的原因；可用时返回空串。 */
  public String validationError() {
    if (name.isEmpty()) {
      return "名字不能为空（只能用字母、数字、下划线、连字符）。";
    }
    if (prompt.isEmpty()) {
      return "提示词不能为空。";
    }
    return "";
  }

  /** 派生一个启用/禁用状态不同的副本。 */
  public CustomAgent withEnabled(boolean nextEnabled) {
    return new CustomAgent(name, description, prompt, nextEnabled);
  }

  /** 名字是否合法（用于新建时的即时校验）。 */
  public static boolean isValidName(String raw) {
    return !sanitizeName(raw).isEmpty();
  }

  @Override
  public String toString() {
    return "CustomAgent{"
        + name
        + (enabled ? "" : " (disabled)")
        + '}';
  }

  /** 归一化名字用于查重。 */
  public String normalizedName() {
    return name.toLowerCase(Locale.ROOT);
  }
}
