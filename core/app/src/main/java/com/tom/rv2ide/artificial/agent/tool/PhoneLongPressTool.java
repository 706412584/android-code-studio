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
 * 长按屏幕坐标。
 *
 * <p>Android 没有独立的「长按」input 子命令，长按等价于<b>在同一位置按住一段时间</b>，
 * 因此用 {@code input swipe x y x y duration}（起点=终点）实现——这正是
 * {@code adb shell input} 的惯用做法。默认 {@value #DEFAULT_DURATION_MS}ms，
 * 足以越过系统约 500ms 的长按判定阈值。
 *
 * <p>与 {@link PhoneSwipeTool} 的区别：swipe 明确拒绝「起点=终点」，long_press
 * 则专门表达这件事。分开两个工具是为了让模型的选择语义清晰（要长按就调 long_press，
 * 而不是自己拼一个零位移 swipe）。
 *
 * <p>需要 adb 级权限（Shizuku 后端）；未启用时返回明确错误。
 */
public final class PhoneLongPressTool extends BaseTool {

  private static final long TIMEOUT_MS = 30_000L;

  /** 默认按压时长。长于系统约 500ms 的长按阈值，留足余量。 */
  private static final int DEFAULT_DURATION_MS = 1000;

  /** 下限：低于系统长按阈值不会触发长按。 */
  private static final int MIN_DURATION_MS = 500;

  /** 上限：再长没有实际用途。 */
  private static final int MAX_DURATION_MS = 10_000;

  private final ShellBackendRegistry registry;

  public PhoneLongPressTool(ShellBackendRegistry registry) {
    this.registry = registry;
  }

  @Override
  public String getName() {
    return ToolNames.PHONE_LONG_PRESS;
  }

  @Override
  public String getDescription() {
    return "长按屏幕坐标（物理像素）。实现为在原位置按住 durationMs 毫秒。"
        + "durationMs 范围 "
        + MIN_DURATION_MS
        + "-"
        + MAX_DURATION_MS
        + "，默认 "
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
    // 与 shell_execute 同级：执行 adb 级命令（input swipe 零位移），需经确认门。
    return true;
  }

  @Override
  public JSONObject getParameters() throws org.json.JSONException {
    return new JSONObject()
        .put("type", "object")
        .put(
            "properties",
            new JSONObject()
                .put("x", new JSONObject().put("type", "number").put("description", "横坐标（物理像素）"))
                .put("y", new JSONObject().put("type", "number").put("description", "纵坐标（物理像素）"))
                .put(
                    "durationMs",
                    new JSONObject()
                        .put("type", "number")
                        .put(
                            "description",
                            "按住时长（毫秒）。范围 "
                                + MIN_DURATION_MS
                                + "-"
                                + MAX_DURATION_MS
                                + "，默认 "
                                + DEFAULT_DURATION_MS)))
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

    int duration = clampDuration(input.optDouble("durationMs", DEFAULT_DURATION_MS));

    if (context != null) {
      context.reportProgress("长按 (" + x + ", " + y + ") " + duration + "ms");
    }

    PhoneUiSelector.Exec exec =
        PhoneUiSelector.exec(
            registry, "input swipe " + x + " " + y + " " + x + " " + y + " " + duration, TIMEOUT_MS);
    if (!exec.ok) {
      return error(exec.error);
    }
    return ok("已长按 (" + x + ", " + y + ")，时长 " + duration + "ms。");
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
