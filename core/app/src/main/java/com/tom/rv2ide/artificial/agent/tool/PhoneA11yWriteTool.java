/*
 * This file is part of AndroidCodeStudio.
 *
 * Adapted from Aharou (https://github.com/520huxiangli/Aharou),
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
 * along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.artificial.agent.tool;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Path;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import com.tom.rv2ide.ai.tool.BaseTool;
import com.tom.rv2ide.ai.tool.ToolContext;
import com.tom.rv2ide.ai.tool.api.ToolCategory;
import com.tom.rv2ide.ai.tool.api.ToolDisplayCategory;
import com.tom.rv2ide.ai.tool.api.ToolNames;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import com.tom.rv2ide.artificial.agent.a11y.AcsAccessibilityService;
import com.tom.rv2ide.artificial.agent.tool.vd.ScreenActionSemantics;
import org.json.JSONObject;

/**
 * 用无障碍服务操作屏幕（点击 / 滑动 / 系统键 / 输入文字）。
 *
 * <p><b>不需要 Shizuku/adb 权限</b>——与 {@code phone_click} / {@code phone_swipe} /
 * {@code phone_input_text} 的分工：那些经 shell 后端执行（`input tap` 等）需要 Shizuku；
 * 本工具走无障碍的 `dispatchGesture` / `ACTION_SET_TEXT`，只需用户开启无障碍服务。
 *
 * <p><b>刻意不纳入工具级授权</b>：权限规则落到参数摘要（`ToolPermissionRule.digest`），
 * 而非 `PHONE_TOOL_SCOPED`。因为 tap 的节点 id 每次都不同，工具级授权会让
 * 「允许点 a3f2」放大成「允许点任意位置」——与 `phone_clear_data` 同理。
 *
 * <p><b>为什么允许作用于任意应用</b>：参照实现 Aharou 把写动作限制为「仅自己界面」
 * （前台是第三方 App 时拒绝，要求改用影子屏）。本项目的使用场景是「AI 帮用户调试/操作
 * 自己的应用」，限制会挡住主场景，因此**允许跨应用写**，风险由确认机制承担：
 * {@link #needsConfirmation()} 为 true，每次调用都要用户确认。
 *
 * <p><b>节点 id 的时效</b>：id 来自 {@link PhoneA11yTool} 的 dump/find，60 秒过期。
 * 过期后返回明确提示让模型重新 dump，而不是静默点到错误位置。
 *
 * <p>移植自 Aharou `A11yTool` 的写动作（tap/swipe/key/setText），逻辑照搬。
 */
public final class PhoneA11yWriteTool extends BaseTool {

  /** 滑动默认时长（毫秒），Aharou 同值。 */
  private static final int DEFAULT_SWIPE_MS = 300;

  public PhoneA11yWriteTool() {}

  @Override
  public String getName() {
    return ToolNames.PHONE_A11Y_WRITE;
  }

  @Override
  public String getDescription() {
    return "用无障碍服务操作屏幕（**不需要 Shizuku/adb 权限**，需用户在系统设置里开启本应用的无障碍服务）。"
        + "action 可选："
        + "tap=点按（传 id，或传 x/y 坐标）；"
        + "swipe=滑动（x/y 起点，x2/y2 终点，duration 毫秒）；"
        + "key=系统键（back / home / recents / notifications）；"
        + "setText=给输入框写文字（传 id 与 text）。"
        + "tap/setText 建议带 label（手指下/目标元素的文字）：命中「确认支付」「删除」这类敏感词"
        + "会提级为敏感操作（逐次确认、不可记忆）；不传会收到一条告警。"
        + "id 来自 phone_a11y 的 dump/find，60 秒过期——过期会明确报错，重新 dump 即可。"
        + "**会改变设备状态**，每次调用需用户确认。"
        + "与 phone_click/phone_swipe 的区别：那些需要 Shizuku，两者都能用时优先它们。";
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
    // 写动作会改变设备状态（点击可能触发提交、输入可能发出消息），逐次确认。
    return true;
  }

  @Override
  public boolean isAllowedInReadonlyMode() {
    return false;
  }

  @Override
  public JSONObject getParameters() throws org.json.JSONException {
    return new JSONObject()
        .put("type", "object")
        .put(
            "properties",
            new JSONObject()
                .put(
                    "action",
                    new JSONObject()
                        .put("type", "string")
                        .put("description", "写动作：tap / swipe / key / setText")
                        .put(
                            "enum",
                            new org.json.JSONArray()
                                .put("tap")
                                .put("swipe")
                                .put("key")
                                .put("setText")))
                .put(
                    "id",
                    new JSONObject()
                        .put("type", "string")
                        .put("description", "节点 id（tap / setText 用；来自 phone_a11y 的 dump / find 结果）"))
                .put(
                    "text",
                    new JSONObject()
                        .put("type", "string")
                        .put("description", "setText 专用：要写入的文本"))
                .put(
                    "x",
                    new JSONObject()
                        .put("type", "number")
                        .put("description", "tap / swipe 起点 X 坐标（物理像素）"))
                .put(
                    "y",
                    new JSONObject()
                        .put("type", "number")
                        .put("description", "tap / swipe 起点 Y 坐标（物理像素）"))
                .put(
                    "x2",
                    new JSONObject()
                        .put("type", "number")
                        .put("description", "swipe 终点 X 坐标"))
                .put(
                    "y2",
                    new JSONObject()
                        .put("type", "number")
                        .put("description", "swipe 终点 Y 坐标"))
                .put(
                    "duration",
                    new JSONObject()
                        .put("type", "number")
                        .put("description", "swipe 持续时间（毫秒，默认 " + DEFAULT_SWIPE_MS + "）"))
                .put(
                    "key",
                    new JSONObject()
                        .put("type", "string")
                        .put("description", "key 专用：back / home / recents / notifications")
                        .put(
                            "enum",
                            new org.json.JSONArray()
                                .put("back")
                                .put("home")
                                .put("recents")
                                .put("notifications")))
                .put(
                    "label",
                    new JSONObject()
                        .put("type", "string")
                        .put(
                            "description",
                            "tap / setText 专用：手指下或目标元素的文字（按钮、链接、输入框的标签），"
                                + "如「确认支付」。命中敏感词会把本次调用提级为敏感操作、逐次确认；"
                                + "tap/setText 不传会收到一条告警。")))
        .put("required", new org.json.JSONArray().put("action"));
  }

  /** 带 label 的动作集合：语义风控只看这两个（与 VdTool 的 LABEL_ACTIONS 对应）。 */
  private static final java.util.Set<String> LABEL_ACTIONS =
      java.util.Collections.unmodifiableSet(
          new java.util.LinkedHashSet<>(java.util.Arrays.asList("tap", "settext")));

  /**
   * 敏感档判定（{@link BaseTool#isSensitiveCall} 的覆写）：tap/setText 的 label 命中敏感词
   * 即为敏感——执行器据此走「逐次确认、不可记忆」的加强确认路径。
   *
   * <p>与 {@link VdTool} 的同类判定共用 {@link ScreenActionSemantics} 词表，保证两个
   * 「操作屏幕」工具的保护强度一致（付款/转账/删除这类动作按下后退不回来）。
   */
  @Override
  public boolean isSensitiveCall(JSONObject args) {
    ScreenActionSemantics.Assessment assessment = assessCall(args);
    return assessment != null && assessment.isSensitive();
  }

  /**
   * 仅对带 label 的动作做语义评估；其余动作没有「手指下是什么」这回事，返回 null。
   * （照搬 VdTool {@code assessCall} 的分流逻辑。）
   */
  private static ScreenActionSemantics.Assessment assessCall(JSONObject args) {
    if (args == null) {
      return null;
    }
    String action = args.optString("action", "").trim().toLowerCase(java.util.Locale.ROOT);
    if (!LABEL_ACTIONS.contains(action)) {
      return null;
    }
    return ScreenActionSemantics.assess(args.optString("label", ""));
  }

  /**
   * 给 tap/setText 的结果附上语义告警：命中敏感词给出「退不回来」的说明；
   * 没传 label 给一条提醒（手指下是什么无从得知）。无告警时原样返回。
   */
  private static String withLabelNote(JSONObject input, String base) {
    ScreenActionSemantics.Assessment assessment = assessCall(input);
    if (assessment == null || assessment.warning == null || assessment.warning.isEmpty()) {
      return base;
    }
    return base + "\n\n" + assessment.warning;
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    String action = input.optString("action", "").trim().toLowerCase();
    if (action.isEmpty()) {
      return error("缺少 action（tap / swipe / key / setText）。");
    }

    AcsAccessibilityService service = AcsAccessibilityService.getInstance();
    if (service == null) {
      return error(PhoneA11ySupport.notEnabledMessage());
    }

    if (context != null) {
      context.reportProgress("无障碍操作: " + action);
    }

    try {
      switch (action) {
        case "tap":
          return doTap(service, input);
        case "swipe":
          return doSwipe(service, input);
        case "key":
          return doKey(service, input);
        case "settext":
          return doSetText(service, input);
        default:
          return error("不支持的 action：" + action + "（写动作：tap / swipe / key / setText）。");
      }
    } catch (Throwable t) {
      return error("无障碍操作失败：" + t.getClass().getSimpleName() + ": " + t.getMessage());
    }
  }

  private ToolResult doTap(AcsAccessibilityService service, JSONObject input) {
    String id = input.optString("id", "").trim();
    if (!id.isEmpty()) {
      AccessibilityNodeInfo node = service.getNodeRegistry().get(id);
      if (node == null) {
        return error("节点 id「" + id + "」已过期或不存在——请重新用 phone_a11y dump/find 后再点。");
      }
      if (clickNode(service, node)) {
        return ok(withLabelNote(input, "已点击节点 " + id));
      }
      return error("节点点击失败（不可点击且坐标兜底失败）。");
    }

    if (!input.has("x") || !input.has("y")) {
      return error("tap 需要 id，或 x / y 坐标。");
    }
    // 必须对 double 判 NaN 再取整：(int) Math.round(NaN) 得 0，
    // 取整后再判 NaN 是死代码——x:"abc" 会被静默当作 0 点屏幕左上角
    // （与 PhoneClickTool 的校验顺序一致）。
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
    if (tapAt(service, x, y)) {
      return ok(withLabelNote(input, "已点击 (" + x + ", " + y + ")"));
    }
    return error("点击手势失败。");
  }

  private ToolResult doSwipe(AcsAccessibilityService service, JSONObject input) {
    if (!input.has("x") || !input.has("y") || !input.has("x2") || !input.has("y2")) {
      return error("swipe 需要 x / y / x2 / y2。");
    }
    // 同 doTap：先对 double 判 NaN，再取整。
    double rawX = input.optDouble("x", Double.NaN);
    double rawY = input.optDouble("y", Double.NaN);
    double rawX2 = input.optDouble("x2", Double.NaN);
    double rawY2 = input.optDouble("y2", Double.NaN);
    if (Double.isNaN(rawX)
        || Double.isNaN(rawY)
        || Double.isNaN(rawX2)
        || Double.isNaN(rawY2)) {
      return error("x / y / x2 / y2 必须是数字。");
    }
    int x = (int) Math.round(rawX);
    int y = (int) Math.round(rawY);
    int x2 = (int) Math.round(rawX2);
    int y2 = (int) Math.round(rawY2);
    if (x < 0 || y < 0 || x2 < 0 || y2 < 0) {
      return error("坐标不能为负。");
    }
    int duration = (int) Math.round(input.optDouble("duration", DEFAULT_SWIPE_MS));
    if (swipeAt(service, x, y, x2, y2, duration)) {
      return ok("已滑动 (" + x + "," + y + ") → (" + x2 + "," + y2 + ")");
    }
    return error("滑动手势失败。");
  }

  private ToolResult doKey(AcsAccessibilityService service, JSONObject input) {
    String key = input.optString("key", "").trim().toLowerCase();
    int globalAction;
    switch (key) {
      case "back":
        globalAction = AccessibilityService.GLOBAL_ACTION_BACK;
        break;
      case "home":
        globalAction = AccessibilityService.GLOBAL_ACTION_HOME;
        break;
      case "recents":
        globalAction = AccessibilityService.GLOBAL_ACTION_RECENTS;
        break;
      case "notifications":
        globalAction = AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS;
        break;
      default:
        return error("key 仅支持 back / home / recents / notifications。");
    }
    if (service.performGlobalAction(globalAction)) {
      return ok("已发送系统键 " + key);
    }
    return error("系统键发送失败。");
  }

  private ToolResult doSetText(AcsAccessibilityService service, JSONObject input) {
    String id = input.optString("id", "").trim();
    if (id.isEmpty() || !input.has("text")) {
      return error("setText 需要 id 和 text。");
    }
    AccessibilityNodeInfo node = service.getNodeRegistry().get(id);
    if (node == null) {
      return error("节点 id「" + id + "」已过期或不存在——请重新用 phone_a11y dump/find 后再写。");
    }
    if (service.setNodeText(node, input.optString("text", ""))) {
      return ok(withLabelNote(input, "已写入文本"));
    }
    return error("写入失败（该节点可能不支持设置文本）。");
  }

  /**
   * 优先点可点击祖先；否则用坐标兜底（Aharou clickNode 的等价物）。
   *
   * <p>为什么要往上找祖先：可点击的往往是外层容器，而 dump 里模型挑中的可能是
   * 容器里的文字节点（本身不可点）。只点自己会失败，往上找才能命中。
   */
  private boolean clickNode(AcsAccessibilityService service, AccessibilityNodeInfo node) {
    AccessibilityNodeInfo current = node;
    int depth = 0;
    while (current != null && depth < 12) {
      if (current.isClickable()) {
        if (current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
          return true;
        }
      }
      current = current.getParent();
      depth++;
    }
    Rect rect = new Rect();
    node.getBoundsInScreen(rect);
    if (rect.width() > 0 && rect.height() > 0) {
      // 直接取浮点中心取整（与 Aharou 原版一致）。不要加「<=0 时退回 centerX()」
      // 的兜底：整数除法与浮点中点在奇宽度下差 0.5px，且会让 X/Y 两轴走不同路径。
      return tapAt(service, (int) rect.exactCenterX(), (int) rect.exactCenterY());
    }
    return false;
  }

  /** 坐标点击：极短的移动路径（Aharou tapAt 的等价物）。 */
  private boolean tapAt(AcsAccessibilityService service, int x, int y) {
    Path path = new Path();
    path.moveTo((float) x, (float) y);
    path.lineTo((float) x + 1f, (float) y + 1f);
    // 显式传超时：Kotlin 的默认参数从 Java 不可见（无 @JvmOverloads）。
    return service.dispatchSimpleGesture(path, 0L, 60L, 5_000L);
  }

  private boolean swipeAt(
      AcsAccessibilityService service, int x, int y, int x2, int y2, int durationMs) {
    Path path = new Path();
    path.moveTo((float) x, (float) y);
    path.lineTo((float) x2, (float) y2);
    long duration = Math.max(50L, Math.min(5_000L, durationMs));
    return service.dispatchSimpleGesture(path, 0L, duration, 5_000L);
  }
}
