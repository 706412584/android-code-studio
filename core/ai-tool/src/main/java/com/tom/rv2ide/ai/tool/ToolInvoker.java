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

import com.tom.rv2ide.ai.tool.api.ToolResult;
import org.json.JSONObject;

/**
 * 编排类工具（多步场景 / 动作连拍）在内部调用其它工具时的统一入口。
 *
 * <p><b>为什么需要它</b>：直接调用 {@code BaseTool.execute} 会绕过
 * {@link ToolExecutor}，也就绕过了权限判定（ALLOW/DENY）与确认门。
 * 一个「测试场景」里若嵌了 {@code shell_execute} / {@code file_delete} /
 * {@code phone_clear_data}，那些工具自身的 {@code needsConfirmation()} 不会被复查，
 * 用户只在确认框里看到一大坨 steps JSON，未必意识到其中一步的破坏性。
 *
 * <p>实现方必须走权限判定。内置实现用
 * {@link ToolExecutor#executeConfirmed}：外层工具已由用户确认，内层不再逐级弹窗
 * （否则多步场景每步都弹，不可用），但 ALLOW/DENY 仍然生效——例如只读模式下
 * 场景里的 {@code phone_clear_data} 会被拒绝，而不是悄悄执行。
 */
public interface ToolInvoker {

  /**
   * 调用一个已注册的工具。
   *
   * @param toolName 规范工具名
   * @param args 参数对象；可为 {@code null}（视为空参数）
   * @param context 工具上下文
   * @return 工具结果；工具不存在或权限拒绝时返回 error 变体，不抛异常
   */
  ToolResult invoke(String toolName, JSONObject args, ToolContext context);
}
