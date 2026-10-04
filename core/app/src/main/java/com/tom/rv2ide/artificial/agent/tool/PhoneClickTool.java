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

package com.tom.rv2ide.artificial.agent.tool;

import com.tom.rv2ide.ai.tool.BaseTool;
import com.tom.rv2ide.ai.tool.ShellBackendRegistry;
import com.tom.rv2ide.ai.tool.ToolContext;
import com.tom.rv2ide.ai.tool.api.ToolCategory;
import com.tom.rv2ide.ai.tool.api.ToolDisplayCategory;
import com.tom.rv2ide.ai.tool.api.ToolNames;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import org.json.JSONObject;

/**
 * 按屏幕坐标点击。
 *
 * <p>真机测试闭环里的「操作」步：构建 → 安装 → 启动 → <b>操作</b> → 观察 → 验证。
 * 用 {@code input tap x y} 以 adb 级权限（Shizuku，uid 2000）模拟一次触摸，
 * 不依赖无障碍服务，也不要求被测应用做任何集成。
 *
 * <p><b>坐标来源</b>：x/y 通常来自 {@code phone_view_hierarchy} / {@code phone_click_view}
 * 返回的节点 bounds 中心。坐标是<b>物理像素</b>（与 uiautomator 的 bounds 同一坐标系），
 * 不是 dp——用 dp 会点偏。
 *
 * <p><b>为什么把「取坐标」和「点击」分开</b>：{@code phone_click_view} 已经覆盖了
 * 「按选择器定位后点击」的常见路径；本工具保留纯坐标入口，用于选择器够不到的场景
 * （自绘控件、Canvas 游戏画面、坐标已知的回归脚本）。
 */
public final class PhoneClickTool extends BaseTool {

  /** input 命令超时。tap 是即时操作，给 10 秒足够覆盖 Shizuku 首次启动进程的开销。 */
  private static final long TIMEOUT_MS = 10_000L;

  private final ShellBackendRegistry registry;

  public PhoneClickTool(ShellBackendRegistry registry) {
    this.registry = registry;
  }

  @Override
  public String getName() {
    return ToolNames.PHONE_CLICK;
  }

  @Override
  public String getDescription() {
    return "按屏幕坐标点击（物理像素）。"
        + "x/y 通常取自 phone_view_hierarchy 或 phone_click_view 返回的节点 bounds 中心。"
        + "需要 adb 级权限（Shizuku 后端）；未启用时返回明确错误。"
        + "若要点击已知 resource-id/text/content-desc 的控件，优先用 phone_click_view，"
        + "它不需要你手算坐标。";
  }

  @Override
  public ToolCategory getCategory() {
    return ToolCategory.SYSTEM;
  }

  @Override
  public ToolDisplayCategory getDisplayCategory() {
    return ToolDisplayCategory.PHONE_CONTROL;
  }

  @Override
  public boolean needsConfirmation() {
    // 与 shell_execute 同级：它执行的就是 adb 级命令（input tap）。
    // 若免确认，用户配了「shell 需确认」后仍能靠本工具静默点屏幕，等于绕过确认门。
    return true;
  }

  @Override
  public JSONObject getParameters() throws org.json.JSONException {
    return new JSONObject()
        .put("type", "object")
        .put(
            "properties",
            new JSONObject()
                .put(
                    "x",
                    new JSONObject()
                        .put("type", "number")
                        .put("description", "点击位置的横坐标（物理像素）"))
                .put(
                    "y",
                    new JSONObject()
                        .put("type", "number")
                        .put("description", "点击位置的纵坐标（物理像素）")))
        .put("required", new org.json.JSONArray().put("x").put("y"));
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    if (!input.has("x") || !input.has("y")) {
      return error("必须提供 x 与 y 坐标（物理像素）。");
    }
    double rawX = input.optDouble("x", Double.NaN);
    double rawY = input.optDouble("y", Double.NaN);
    if (Double.isNaN(rawX) || Double.isNaN(rawY)) {
      return error("x / y 必须是数字。");
    }

    int x = (int) Math.round(rawX);
    int y = (int) Math.round(rawY);
    if (x < 0 || y < 0) {
      return error("坐标不能为负: x=" + x + " y=" + y);
    }

    if (context != null) {
      context.reportProgress("点击 (" + x + ", " + y + ")");
    }

    PhoneUiSelector.Exec exec =
        PhoneUiSelector.exec(registry, "input tap " + x + " " + y, TIMEOUT_MS);
    if (!exec.ok) {
      return error(exec.error);
    }
    return ok("已点击 (" + x + ", " + y + ")。\n提示：用 phone_view_hierarchy 或 phone_screenshot 确认结果。");
  }
}
