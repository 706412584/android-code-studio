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

import com.tom.rv2ide.ai.tool.api.ToolCategory;
import com.tom.rv2ide.ai.tool.api.ToolDisplayCategory;
import com.tom.rv2ide.ai.tool.api.ToolInfo;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import org.json.JSONObject;

/**
 * 所有工具的基类。
 *
 * <p><b>与上游的差异</b>：LineCode Pro 的 {@code BaseTool} 依赖 {@code android.content.Context}
 * 和 {@code :tool-ui} 模块（工具层反向依赖 UI 层）。本移植切断了这两处：
 * <ul>
 *   <li>删除 {@code getDisplayLabel(Context, ...)} / {@code getActionName(Context)} /
 *       {@code getActionIcon()} —— UI 展示元数据改由 app 层的 ToolUiDecorator 按工具名映射
 *   <li>删除 {@code getToolCallViewClass()} —— 卡片视图的选择同理上移到 app 层
 *   <li>保留 {@link #getDisplayCategory()}，它只返回纯枚举，不引入 UI 类型
 * </ul>
 *
 * <p>因此本类及其子类不引用任何 Android 类型，可在 JVM 上单元测试。
 */
public abstract class BaseTool implements ToolInfo {

  /** 工具名，需与 {@link com.tom.rv2ide.ai.tool.api.ToolNames} 中的常量一致。 */
  @Override
  public abstract String getName();

  /** 给模型看的工具说明。 */
  @Override
  public abstract String getDescription();

  /** 工具分类，决定它在只读模式下是否被允许。 */
  @Override
  public abstract ToolCategory getCategory();

  /** 是否需要在执行前征求用户确认。默认不危险。 */
  @Override
  public boolean needsConfirmation() {
    return false;
  }

  /** 只读模式下是否放行。默认不放行，由读类工具覆写。 */
  public boolean isAllowedInReadonlyMode() {
    return false;
  }

  /**
   * 追加到系统提示词的工具说明片段。
   *
   * @param executionMode 当前执行模式标识（如 shell 后端 id），供工具输出模式相关的提示
   * @return 追加内容；返回 null 表示无补充
   */
  public String promptSupplement(String executionMode) {
    return null;
  }

  /** 工具的 JSON Schema 参数定义。 */
  @Override
  public abstract JSONObject getParameters() throws org.json.JSONException;

  /**
   * 执行工具。
   *
   * <p><b>约定</b>：实现方不得抛出异常来表达失败，应返回 {@link ToolResult} 的 error 变体。
   * 这是 agent 循环不中断的前提——错误要能回灌给模型，而不是炸掉整轮对话。
   */
  public abstract ToolResult execute(JSONObject input, ToolContext context);

  /** 卡片展示分类，纯枚举，供 app 层选择视图样式。 */
  public ToolDisplayCategory getDisplayCategory() {
    return ToolDisplayCategory.GENERIC;
  }

  /** 是否可与其他工具并发执行。默认串行，保证有副作用的工具按序生效。 */
  public boolean isConcurrencySafe() {
    return false;
  }

  /** 是否记录 diff 供用户审查。写类工具覆写为 true。 */
  public boolean shouldRecordDiff() {
    return false;
  }

  /** 成功后是否在 UI 上折叠该卡片。 */
  public boolean shouldHideOnSuccess() {
    return false;
  }

  /** 构造一个成功结果。 */
  protected ToolResult ok(String content) {
    return ToolResult.of("", getName(), content, false);
  }

  /** 构造一个失败结果。 */
  protected ToolResult error(String message) {
    return ToolResult.of("", getName(), message, true);
  }

  /** 序列化为 OpenAI tools 数组的元素格式。 */
  @Override
  public final JSONObject toJson() throws org.json.JSONException {
    JSONObject function = new JSONObject();
    function.put("name", getName());
    function.put("description", getDescription());
    function.put("parameters", getParameters());
    JSONObject wrapper = new JSONObject();
    wrapper.put("type", "function");
    wrapper.put("function", function);
    return wrapper;
  }
}
