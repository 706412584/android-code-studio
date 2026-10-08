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

import java.util.Collections;
import java.util.Set;

/**
 * 工具子系统所需的配置读取接口。
 *
 * <p><b>与上游的差异</b>：LineCode Pro 的 {@code ToolSettingsStore} 是一个横跨 17 个字段、
 * 依赖 Room 与 Android Context 的具体类，工具层因此无法脱离 Android。这里收窄成只包含
 * 工具执行真正需要的四项配置，由 app 层提供实现（通常是包装偏好设置的几行代码）。
 *
 * <p>把接口放在工具模块内、实现放在 app 层，使工具模块保持纯 Java，单元测试无需 Robolectric。
 */
public interface ToolSettingsPort {

  /** 权限模式：自动放行。 */
  String PERMISSION_AUTO = "auto";

  /** 权限模式：危险操作需确认。 */
  String PERMISSION_CONFIRM = "confirm";

  /** 权限模式：仅允许只读工具。 */
  String PERMISSION_READONLY = "readonly";

  /** 当前权限模式，取值为 {@link #PERMISSION_AUTO} / {@link #PERMISSION_CONFIRM} / {@link #PERMISSION_READONLY}。 */
  String getPermissionMode();

  /** 当前启用的工具名集合。空集合表示全部启用。 */
  default Set<String> getEnabledToolNames() {
    return Collections.emptySet();
  }

  /** 当前 shell 后端 id，例如 {@code "termux"} 或 {@code "shizuku"}。 */
  default String getShellBackendId() {
    return "termux";
  }

  /**
   * 危险工具（shell 执行、安装、启动等）是否已被用户**全局**放行。
   *
   * <p>这是粗粒度开关，为 true 时确认类工具全部直接放行。精细授权请用
   * {@link #hasDangerousToolRule(String)}——两者是「全部放行」与「按规则放行」的关系。
   */
  default boolean areDangerousToolsConfirmed() {
    return false;
  }

  /**
   * 是否已存在匹配该调用的持久化授权规则。
   *
   * <p>匹配粒度由 {@link ToolPermissionRule#keyFor(String, String)} 决定
   * （shell 按命令首词、文件写按路径）。实现方负责持久化与比对。
   *
   * <p>默认返回 false，即未接入规则存储的实现方行为不变（仍逐次询问）。
   *
   * @param ruleKey {@link ToolPermissionRule#keyFor} 产出的规则键
   */
  default boolean hasDangerousToolRule(String ruleKey) {
    return false;
  }

  /**
   * 持久化一条授权规则，使后续同粒度的调用不再询问。
   *
   * <p>默认空实现，未接入存储的实现方调用后无副作用。
   *
   * @param ruleKey {@link ToolPermissionRule#keyFor} 产出的规则键
   */
  default void rememberDangerousToolRule(String ruleKey) {
    // 默认不持久化。
  }

  /**
   * 针对一次具体的危险工具调用询问用户。
   *
   * <p>与 {@link #areDangerousToolsConfirmed()} 的区别：这里带上工具名与参数，
   * 使用户能看清「要执行什么」再决定——只显示「某个危险工具」无法让人做出有效判断。
   *
   * <p>默认实现退回到无参数的持久确认位，因此未接入 UI 的实现方行为不变。
   *
   * @param toolName 将要执行的工具名
   * @param arguments 工具参数原始 JSON 文本，可为 null
   * @return true 表示放行本次调用
   */
  default boolean confirmDangerousTool(String toolName, String arguments) {
    return areDangerousToolsConfirmed();
  }

  /**
   * 询问用户是否放行一次危险调用（按会话）。
   *
   * <p>默认委托到无会话版本。实现方若维护「本次运行内已批准」的记忆，应按
   * {@code conversationId} 隔离存储。
   *
   * @param conversationId 产生该调用的会话 id；空串表示无会话
   */
  default boolean confirmDangerousTool(String conversationId, String toolName, String arguments) {
    return confirmDangerousTool(toolName, arguments);
  }

  /**
   * 丢弃某个会话的运行内授权记忆。会话运行结束时调用，避免条目随会话数累积。
   *
   * <p>默认空实现。
   */
  default void clearRunApprovedRules(String conversationId) {
    // 默认无按会话状态，无需清理。
  }

  /**
   * 就若干道选择题询问用户，返回用户的选择。
   *
   * <p><b>为什么是阻塞式</b>：与 {@link #confirmDangerousTool} 同理——agent 循环需要的
   * 是一个能据以继续的答案，而不是「已弹出，稍后告诉你」。异步化会把「等用户」这件事
   * 扩散到整个循环（每轮都要判「上一条问题答了没」），而阻塞只需在这一处等。
   * 实现方负责切到主线程弹窗、并在用户作答后唤醒调用线程。
   *
   * <p><b>为什么返回可空列表而不是单个字符串</b>：一次可问多道题（对齐 cc-haha 的
   * 1-4 道语义），且存在「用户取消」这一真实结果。返回 null 表示未作答，工具据此如实
   * 告诉模型「用户没回答」——伪造一个空答案会让模型以为用户默认同意。
   *
   * @param questions 待问的题目，已由工具校验（1-4 道，每道 2-4 个选项）
   * @return 与 questions 等长的答案（多选以逗号连接）；null 表示用户取消或没有 UI 可问
   */
  default java.util.List<String> askUserQuestion(java.util.List<Question> questions) {
    // 默认没有 UI 可问。返回 null 而非空列表：空列表会被误读成「用户全选了空」。
    return null;
  }

  /**
   * 一道选择题。
   *
   * <p>放在端口层而非工具层：实现方（app 的弹窗）要用它渲染，工具层只负责解析 JSON。
   * 两边共用同一类型，避免「工具传 JSON、UI 再解析一遍」的重复与不一致。
   */
  final class Question {
    /** 完整问题文本。 */
    public final String question;

    /** 极短标签（如「认证方式」「实现方案」），用作弹窗上的分组标题。 */
    public final String header;

    /** 选项标签，2-4 个。 */
    public final java.util.List<String> options;

    /** 每个选项的说明，与 {@link #options} 等长。 */
    public final java.util.List<String> optionDescriptions;

    /** 是否允许多选。 */
    public final boolean multiSelect;

    public Question(
        String question,
        String header,
        java.util.List<String> options,
        java.util.List<String> optionDescriptions,
        boolean multiSelect) {
      this.question = question == null ? "" : question;
      this.header = header == null ? "" : header;
      this.options = options == null ? java.util.Collections.<String>emptyList() : options;
      this.optionDescriptions =
          optionDescriptions == null
              ? java.util.Collections.<String>emptyList()
              : optionDescriptions;
      this.multiSelect = multiSelect;
    }
  }

  /**
   * 默认实现：自动模式、全部工具启用、shell 走 termux。
   *
   * <p>供单元测试与尚未接入配置的调用方使用，避免到处判空。
   */
  static ToolSettingsPort defaults() {
    return new ToolSettingsPort() {
      @Override
      public String getPermissionMode() {
        return PERMISSION_AUTO;
      }
    };
  }
}
