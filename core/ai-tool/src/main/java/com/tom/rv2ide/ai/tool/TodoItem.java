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

/**
 * 待办列表的一项。
 *
 * <p>不可变：状态推进通过 {@link #withStatus} 派生新实例，使「哪一步改了什么」在代码里
 * 一眼可见，也避免存储层与调用方共享可变对象。
 */
public final class TodoItem {

  /** 未开始。 */
  public static final String STATUS_PENDING = "pending";

  /** 进行中。同一时刻应当只有一项处于此状态。 */
  public static final String STATUS_IN_PROGRESS = "in_progress";

  /** 已完成。 */
  public static final String STATUS_COMPLETED = "completed";

  private final String content;
  private final String status;

  public TodoItem(String content, String status) {
    this.content = content == null ? "" : content;
    this.status = normalizeStatus(status);
  }

  public String getContent() {
    return content;
  }

  public String getStatus() {
    return status;
  }

  public TodoItem withStatus(String nextStatus) {
    return new TodoItem(content, nextStatus);
  }

  /** 该状态是否是合法取值；非法值会被归一为 {@link #STATUS_PENDING}。 */
  public static boolean isValidStatus(String status) {
    return STATUS_PENDING.equals(status)
        || STATUS_IN_PROGRESS.equals(status)
        || STATUS_COMPLETED.equals(status);
  }

  /**
   * 归一化状态。
   *
   * <p>模型经常写 {@code done}、{@code completed}、{@code finished} 或中文「完成」。
   * 严格拒绝会让它反复重试同一次调用，因此这里做同义词映射；无法识别时退回
   * {@link #STATUS_PENDING}（而不是报错），因为待办列表是辅助手段，不值得为它中断任务。
   */
  public static String normalizeStatus(String status) {
    if (status == null) {
      return STATUS_PENDING;
    }
    String value = status.trim().toLowerCase(java.util.Locale.ROOT);
    if (value.isEmpty()) {
      return STATUS_PENDING;
    }
    if (isValidStatus(value)) {
      return value;
    }
    switch (value) {
      case "done":
      case "complete":
      case "finished":
      case "completed":
      case "完成":
      case "已完成":
        return STATUS_COMPLETED;
      case "doing":
      case "active":
      case "current":
      case "in-progress":
      case "inprogress":
      case "进行中":
        return STATUS_IN_PROGRESS;
      case "todo":
      case "open":
      case "not_started":
      case "未开始":
      case "待办":
        return STATUS_PENDING;
      default:
        return STATUS_PENDING;
    }
  }

  /** 面向模型的单行表示，例如 {@code [x] 读取 build.gradle}。 */
  public String toLine() {
    return "[" + marker() + "] " + content;
  }

  private String marker() {
    if (STATUS_COMPLETED.equals(status)) {
      return "x";
    }
    if (STATUS_IN_PROGRESS.equals(status)) {
      return ">";
    }
    return " ";
  }
}
