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

package com.tom.rv2ide.ai.agent.prompt;

import java.util.Arrays;
import java.util.List;

/**
 * 对话模式：决定系统提示词里给出多少行动授权。
 *
 * <p><b>为什么需要模式而不是一个开关</b>：用户对「AI 能做什么」的期待在不同场景下差别很大。
 * 想讨论设计方案时，不希望它顺手改文件；想让它干活时，又不想每步都被问。一个「是否允许
 * 写文件」的开关无法表达这种差别，而模式可以：
 *
 * <ul>
 *   <li>{@link #CHAT}——纯对话。不调用工具，只回答与讨论。适合「这个报错是什么意思」。
 *   <li>{@link #PLAN}——只读调查 + 给出计划。可以读文件、搜代码、查文档，但不改任何东西。
 *       适合「我该怎么重构这块」——用户想先看方案再决定。
 *   <li>{@link #AGENT}——完整执行。默认值，可读写文件、执行命令。
 *   <li>{@link #CONTROL}——执行 + 额外强调先确认危险操作。适合在陌生项目上放手让它跑。
 * </ul>
 *
 * <p>模式只影响**提示词**，不改变工具注册表。真正的强制拦截在权限层
 * （{@code ToolPermissionService} 的只读模式），两者是互补的：提示词引导模型不做，
 * 权限层保证它做不了。
 */
public enum ChatMode {

  /** 纯对话，不调用工具。 */
  CHAT("chat", "对话", "不调用工具，只回答与讨论。"),

  /** 只读调查并给出计划，不做任何修改。 */
  PLAN("plan", "计划", "可以读取文件与搜索，但不做任何修改，最后给出计划。"),

  /** 完整执行：读写文件、执行命令。 */
  AGENT("agent", "执行", "可以读写文件、执行命令，直到任务完成。"),

  /** 完整执行，但对危险操作格外谨慎。 */
  CONTROL("control", "受控执行", "可以执行任务，但涉及删除、安装、启动等危险操作前必须先说明并等待确认。");

  /** 默认模式。 */
  public static final ChatMode DEFAULT = AGENT;

  private final String id;
  private final String label;
  private final String description;

  ChatMode(String id, String label, String description) {
    this.id = id;
    this.label = label;
    this.description = description;
  }

  /** 稳定标识，写入偏好。 */
  public String getId() {
    return id;
  }

  /** 界面显示名。 */
  public String getLabel() {
    return label;
  }

  /** 一句话说明，供设置界面展示。 */
  public String getDescription() {
    return description;
  }

  /** 该模式是否允许模型调用工具。 */
  public boolean allowsTools() {
    return this != CHAT;
  }

  /**
   * 该模式是否允许修改工作区（写文件、删除、执行有副作用的命令）。
   *
   * <p>供 UI 在切换模式时提示用户「当前模式不会改动你的代码」。
   */
  public boolean allowsModification() {
    return this == AGENT || this == CONTROL;
  }

  /** 按 id 解析；未知或 null 回退到 {@link #DEFAULT}。 */
  public static ChatMode fromId(String id) {
    if (id == null) {
      return DEFAULT;
    }
    String value = id.trim().toLowerCase(java.util.Locale.ROOT);
    for (ChatMode mode : values()) {
      if (mode.id.equals(value)) {
        return mode;
      }
    }
    return DEFAULT;
  }

  /** 全部模式，按界面展示顺序。 */
  public static List<ChatMode> all() {
    return Arrays.asList(values());
  }

  /** 全部模式 id。 */
  public static List<String> allIds() {
    return Arrays.asList(CHAT.id, PLAN.id, AGENT.id, CONTROL.id);
  }
}
