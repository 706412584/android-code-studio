/*
 * This file is part of AndroidCodeStudio.
 *
 * AndroidCodeStudio is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AndroidCodeStudio is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.ai.tool;

import com.tom.rv2ide.ai.tool.api.ToolCall;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import org.json.JSONObject;

/**
 * 用 {@link ToolExecutor} 承载 {@link ToolInvoker}：编排工具的默认实现。
 *
 * <p>走 {@link ToolExecutor#executeConfirmed} 而不是 {@code execute}：
 * 外层编排工具（场景 / 连拍）本身已经过用户确认，内层再逐级弹窗会让多步场景
 * 每一步都弹一次、实际不可用。{@code executeConfirmed} 只跳过「是否需要确认」这一层，
 * 仍然执行 ALLOW/DENY 判定——只读模式下的写操作会在场景里被拒绝，而不是被绕过。
 */
public final class ExecutorToolInvoker implements ToolInvoker {

  private final ToolExecutor executor;

  public ExecutorToolInvoker(ToolExecutor executor) {
    if (executor == null) {
      throw new IllegalArgumentException("executor 不能为空");
    }
    this.executor = executor;
  }

  @Override
  public ToolResult invoke(String toolName, JSONObject args, ToolContext context) {
    if (toolName == null || toolName.trim().isEmpty()) {
      return ToolResult.error("工具名不能为空");
    }
    // 合成的 call id：编排工具内部调用不是模型发起的一轮工具调用，
    // 但 ToolExecutor 需要它来标记结果来源（日志与错误记录会用到）。
    ToolCall call =
        new ToolCall("nested:" + toolName, toolName, args == null ? "{}" : args.toString());
    return executor.executeConfirmed(call, context);
  }
}
