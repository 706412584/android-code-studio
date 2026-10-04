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

package com.tom.rv2ide.ai.tool;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 工具名白名单。
 *
 * <p><b>为什么需要它</b>：子 agent 的「能力边界」应当由结构决定，而不是靠提示词约束——
 * 提示词可能被模型忽略，缺少工具则不会。此前 {@code SubAgentRunnerImpl} 只能按
 * explore/code 两种模式给固定工具集；有了白名单，一个预设角色（如「只读审查员」）
 * 可以让执行方**只注册**它该有的工具。
 *
 * <p>空集合表示**不限制**（与「限制为零个工具」不同）——调用方据此区分「无约束」与
 * 「一个工具都不给」。用 {@link #restricts()} 判断。
 */
public final class ToolNameFilter {

  private final Set<String> allowed;

  private ToolNameFilter(Set<String> allowed) {
    this.allowed = Collections.unmodifiableSet(allowed);
  }

  /** 不限制：允许全部工具。 */
  public static ToolNameFilter unrestricted() {
    return new ToolNameFilter(Collections.<String>emptySet());
  }

  /**
   * 只允许给定名字。
   *
   * <p>用 {@link LinkedHashSet} 去重并保留顺序，使注册表按调用方给定的顺序构造
   * （工具清单的顺序会影响模型的使用倾向）。
   */
  public static ToolNameFilter of(Set<String> names) {
    if (names == null || names.isEmpty()) {
      return unrestricted();
    }
    Set<String> copy = new LinkedHashSet<>();
    for (String name : names) {
      if (name != null && !name.trim().isEmpty()) {
        copy.add(name.trim());
      }
    }
    return copy.isEmpty() ? unrestricted() : new ToolNameFilter(copy);
  }

  /** 是否限制了工具集。 */
  public boolean restricts() {
    return !allowed.isEmpty();
  }

  /** 某个工具是否被允许；不限制时一律允许。 */
  public boolean allows(String toolName) {
    if (!restricts()) {
      return true;
    }
    return toolName != null && allowed.contains(toolName);
  }

  /** 白名单内容（不可变）；不限制时为空集。 */
  public Set<String> names() {
    return allowed;
  }
}
