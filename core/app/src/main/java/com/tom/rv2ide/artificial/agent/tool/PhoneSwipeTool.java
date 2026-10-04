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
 * 滑动 / 拖拽：{@code input swipe x1 y1 x2 y2 duration}。
 *
 * <p>用于滚动列表、翻页、拖拽手柄。起点与终点都是物理像素坐标。
 *
 * <p><b>duration 的语义与边界</b>：单位毫秒。它同时决定「是滑动还是拖拽」：
 * <ul>
 *   <li>短（~100-300ms）→ 快速滑动（fling），列表会带惯性继续滚
 *   <li>长（~800-1500ms）→ 慢速拖拽，位移精确、无惯性
 * </ul>
 * 因此这里对 duration 设上下限：过短（&lt;50ms）在部分设备上会被识别成一次 tap，
 * 达不到滑动效果；过长（&gt;30000ms）会让一次调用独占 agent 循环几十秒且几乎必然是误填。
 * 省略时用 {@value #DEFAULT_DURATION_MS}ms（可靠的滑动而非 fling）。
 *
 * <p>需要 adb 级权限（Shizuku 后端）；未启用时返回明确错误。
 */
public final class PhoneSwipeTool extends BaseTool {

  private static final long TIMEOUT_MS = 30_000L;

  /** 省略 duration 时使用。偏慢，确保被识别为滑动而不是 fling。 */
  private static final int DEFAULT_DURATION_MS = 300;

  /** 下限：低于此值部分设备会当作一次 tap。 */
  private static final int MIN_DURATION_MS = 50;

  /** 上限：再长没有实际用途，且会长时间占用循环。 */
  private static final int MAX_DURATION_MS = 30_000;

  private final ShellBackendRegistry registry;

  public PhoneSwipeTool(ShellBackendRegistry registry) {
    this.registry = registry;
  }

  @Override
  public String getName() {
    return ToolNames.PHONE_SWIPE;
  }

  @Override
  public String getDescription() {
    return "在屏幕上滑动/拖拽：从 (x1,y1) 滑到 (x2,y2)，坐标均为物理像素。"
        + "durationMs 单位毫秒：短（约 100-300）为快速滑动（带惯性），长（约 800-1500）为慢速精确拖拽。"
        + "范围 "
        + MIN_DURATION_MS
        + "-"
        + MAX_DURATION_MS
        + "，省略时默认 "
        + DEFAULT_DURATION_MS
        + "。需要 adb 级权限（Shizuku 后端）。";
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
    // 与 shell_execute 同级：执行 adb 级命令（input swipe），需经确认门。
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
                    "x1",
                    new JSONObject().put("type", "number").put("description", "起点横坐标（物理像素）"))
                .put(
                    "y1",
                    new JSONObject().put("type", "number").put("description", "起点纵坐标（物理像素）"))
                .put(
                    "x2",
                    new JSONObject().put("type", "number").put("description", "终点横坐标（物理像素）"))
                .put(
                    "y2",
                    new JSONObject().put("type", "number").put("description", "终点纵坐标（物理像素）"))
                .put(
                    "durationMs",
                    new JSONObject()
                        .put("type", "number")
                        .put(
                            "description",
                            "滑动耗时（毫秒）。范围 "
                                + MIN_DURATION_MS
                                + "-"
                                + MAX_DURATION_MS
                                + "，默认 "
                                + DEFAULT_DURATION_MS
                                + "。短=快速滑动带惯性，长=慢速精确拖拽。")))
        .put(
            "required",
            new org.json.JSONArray().put("x1").put("y1").put("x2").put("y2"));
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    for (String key : new String[] {"x1", "y1", "x2", "y2"}) {
      if (!input.has(key)) {
        return error("缺少坐标参数 " + key + "（物理像素）。");
      }
    }
    double rawX1 = input.optDouble("x1", Double.NaN);
    double rawY1 = input.optDouble("y1", Double.NaN);
    double rawX2 = input.optDouble("x2", Double.NaN);
    double rawY2 = input.optDouble("y2", Double.NaN);
    if (Double.isNaN(rawX1) || Double.isNaN(rawY1) || Double.isNaN(rawX2) || Double.isNaN(rawY2)) {
      return error("x1 / y1 / x2 / y2 必须是数字。");
    }

    int x1 = (int) Math.round(rawX1);
    int y1 = (int) Math.round(rawY1);
    int x2 = (int) Math.round(rawX2);
    int y2 = (int) Math.round(rawY2);
    if (x1 < 0 || y1 < 0 || x2 < 0 || y2 < 0) {
      return error("坐标不能为负。");
    }
    if (x1 == x2 && y1 == y2) {
      return error("起点与终点相同，不会产生滑动。若要长按，请用 phone_long_press。");
    }

    int duration = clampDuration(input.optDouble("durationMs", DEFAULT_DURATION_MS));

    if (context != null) {
      context.reportProgress(
          "滑动 (" + x1 + "," + y1 + ") → (" + x2 + "," + y2 + ") " + duration + "ms");
    }

    PhoneUiSelector.Exec exec =
        PhoneUiSelector.exec(
            registry, "input swipe " + x1 + " " + y1 + " " + x2 + " " + y2 + " " + duration, TIMEOUT_MS);
    if (!exec.ok) {
      return error(exec.error);
    }
    return ok("已滑动 (" + x1 + "," + y1 + ") → (" + x2 + "," + y2 + ")，耗时 " + duration + "ms。");
  }

  /** 把 duration 夹到 [MIN, MAX]；非有限值回退到默认。 */
  static int clampDuration(double value) {
    if (Double.isNaN(value) || Double.isInfinite(value)) {
      return DEFAULT_DURATION_MS;
    }
    int rounded = (int) Math.round(value);
    return Math.max(MIN_DURATION_MS, Math.min(rounded, MAX_DURATION_MS));
  }
}
