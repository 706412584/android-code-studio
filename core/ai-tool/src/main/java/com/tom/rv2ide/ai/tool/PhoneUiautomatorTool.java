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

import com.tom.rv2ide.ai.tool.api.ToolCategory;
import com.tom.rv2ide.ai.tool.api.ToolDisplayCategory;
import com.tom.rv2ide.ai.tool.api.ToolNames;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * phone_uiautomator：uiautomator2 server 代理，补齐现有 phone_* 工具的四个缺口。
 *
 * <p>与既有工具的分工（并存而非替代）：
 * <ul>
 *   <li>{@code setText} 支持任意 Unicode（现有 {@code phone_input_text} 只支持 ASCII）；
 *   <li>{@code waitGone} 等待节点消失（现有 {@code phone_wait_for} 只能等出现）；
 *   <li>{@code gesture} / {@code pinch} / {@code swipePoints} 多点触控
 *       （现有 {@code phone_swipe} 是单指 {@code input swipe}）；
 *   <li>{@code scrollTo} 滚动到元素（现有工具没有）。
 * </ul>
 *
 * <p>实现完全经 {@link U2ProxyPort} 端口：本类只做「参数校验 → 构造 jsonrpc params →
 * 解析结果文本」，HTTP 与 server 生命周期在 app 层（{@code U2ServerManager}）。
 *
 * <p><b>selector 的 mask 编码</b>：u2 server 用 {@code BySelector} 的位掩码协议，
 * 每个字段一个 bit；mask 为各字段位值 OR。字段名与位值的映射照抄 u2 客户端
 * {@code _selector.py:17-43}（见 {@link #buildSelector}）。
 */
public final class PhoneUiautomatorTool extends BaseTool {

  // ---- action 常量 ----
  static final String ACTION_SET_TEXT = "setText";
  static final String ACTION_WAIT_GONE = "waitGone";
  static final String ACTION_GESTURE = "gesture";
  static final String ACTION_PINCH = "pinch";
  static final String ACTION_SWIPE_POINTS = "swipePoints";
  static final String ACTION_SCROLL_TO = "scrollTo";
  static final String ACTION_CLICK = "click";
  static final String ACTION_DUMP = "dump";
  static final String ACTION_STOP = "stop";
  static final String ACTION_RESTART = "restart";
  static final String ACTION_CALL = "call";

  // ---- 超时（计划 §6.1 复审 M2：由工具层按动作推导，port 只透传）----
  /** 普通动作的 HTTP 超时。 */
  static final long HTTP_TIMEOUT_DEFAULT_MS = 15_000L;
  /** dump / scrollTo 的 HTTP 超时（dump 可能很大，scrollTo 内部多次滚动）。 */
  static final long HTTP_TIMEOUT_HEAVY_MS = 30_000L;
  /** waitGone 的 HTTP 超时在用户 timeout 之上的裕量（对齐 u2 客户端 timeout+10s）。 */
  static final long WAIT_GONE_HTTP_MARGIN_MS = 10_000L;

  // ---- waitGone 的 timeout 范围 ----
  static final long WAIT_GONE_DEFAULT_MS = 10_000L;
  static final long WAIT_GONE_MIN_MS = 1_000L;
  static final long WAIT_GONE_MAX_MS = 120_000L;

  // ---- 其它上限 ----
  /** swipePoints 的 durationMs 上限。 */
  static final long SWIPE_DURATION_MAX_MS = 30_000L;
  /** 手势默认步数（每步约 5ms）。 */
  static final int DEFAULT_STEPS = 100;
  /** pinch 默认步数（与 u2 客户端 pinch_in(percent, steps=50) 一致）。 */
  static final int PINCH_DEFAULT_STEPS = 50;
  /** dump 默认层级深度。 */
  static final int DEFAULT_MAX_DEPTH = 50;
  /** 结果文本里的结果 JSON 展示上限（dumpWindowHierarchy 可达 54KB+）。 */
  private static final int RESULT_PREVIEW_MAX = 8_000;

  /** selector 字段 → BySelector mask 位值（u2 客户端 _selector.py:17-43 原文）。 */
  private static final long MASK_TEXT = 0x01L;
  private static final long MASK_TEXT_CONTAINS = 0x02L;
  private static final long MASK_CLASS_NAME = 0x10L;
  private static final long MASK_DESCRIPTION = 0x40L;
  private static final long MASK_DESCRIPTION_CONTAINS = 0x80L;
  private static final long MASK_CLICKABLE = 0x1000L;
  private static final long MASK_SCROLLABLE = 0x4000L;
  private static final long MASK_RESOURCE_ID = 0x200000L;
  private static final long MASK_INDEX = 0x800000L;

  /**
   * {@code call} 动作的黑名单：这些方法会直接篡改/破坏测试环境，
   * 不应由模型通过逃生舱触达（与「测试工具」定位不符）。
   */
  private static final java.util.Set<String> CALL_BLACKLIST =
      new java.util.HashSet<>(java.util.Arrays.asList("shell", "injectInputEvent"));

  public PhoneUiautomatorTool() {}

  @Override
  public String getName() {
    return ToolNames.PHONE_UIAUTOMATOR;
  }

  @Override
  public String getDescription() {
    return "uiautomator2 代理：Unicode 文本输入 / 等待节点消失 / 多点触控（双指手势、捏合缩放、"
        + "多段路径滑动）/ 滚动到元素 / 读节点树 / 通用 jsonrpc 逃生舱。"
        + "action：setText=向匹配节点输入任意 Unicode 文本（中文/emoji；空文本=清空）；"
        + "waitGone=等待节点消失；gesture=双指两点手势（points2 八个坐标）；"
        + "pinch=捏合缩放（percent 正=缩小，负=放大）；swipePoints=多段路径滑动（points 扁平坐标 + durationMs）；"
        + "scrollTo=滚动到元素（selector 为目标，容器默认 {scrollable:true}，可用 scrollableSelector 指定）；click=按条件点击；"
        + "dump=读节点树 XML；stop=停掉 u2 server（解除对传统 uiautomator dump 的独占，幂等）；"
        + "restart=重启 server（卡死恢复用）；call=按 uiautomator2 jsonrpc 协议直调任意方法（逃生舱）。"
        + "selector 支持 resourceId/text/textContains/description/descriptionContains/className/"
        + "clickable/scrollable/index。"
        + "首次调用会自动经 Shizuku 启动设备上的 u2 server（需已授权 Shizuku）。"
        + "注意：server 运行期间会独占 UiAutomation，phone_view_hierarchy / phone_click_view / "
        + "phone_wait_for 的节点条件会失败——用 stop 动作停掉 server 后恢复；"
        + "单指滑动优先用 phone_swipe（更稳），普通 ASCII 输入优先用 phone_input_text。";
  }

  @Override
  public ToolCategory getCategory() {
    // 含输入/点击/手势，与 phone_input_text 同级。
    return ToolCategory.SYSTEM;
  }

  @Override
  public ToolDisplayCategory getDisplayCategory() {
    return ToolDisplayCategory.PHONE_CONTROL;
  }

  @Override
  public boolean needsConfirmation() {
    // 能驱动任意应用（点击/输入/手势），与 phone_input_text / phone_swipe 一致。
    return true;
  }

  @Override
  public boolean isConcurrencySafe() {
    // server 单实例，串行最稳。
    return false;
  }

  @Override
  public JSONObject getParameters() throws org.json.JSONException {
    return new JSONObject()
        .put("type", "object")
        .put(
            "properties",
            new JSONObject()
                .put("action", new JSONObject()
                    .put("type", "string")
                    .put("description", "动作名。setText/waitGone/gesture/pinch/swipePoints/scrollTo/click/dump/stop/restart/call"))
                .put("selector", new JSONObject()
                    .put("type", "object")
                    .put("description", "节点定位条件。setText/waitGone/scrollTo/click 必需；gesture/pinch 可省略（省略时作用于全屏）。字段：resourceId/text/textContains/description/descriptionContains/className/clickable/scrollable/index。"))
                .put("scrollableSelector", new JSONObject()
                    .put("type", "object")
                    .put("description", "scrollTo 用：滚动容器条件（字段同 selector）。省略时默认 {scrollable:true}（第一个可滚动节点，通常就是主滚动容器）。"))
                .put("text", new JSONObject()
                    .put("type", "string")
                    .put("description", "setText 的文本（支持任意 Unicode；空字符串=清空输入框）；click 的快捷文本（等价 selector.text）"))
                .put("points", new JSONObject()
                    .put("type", "array")
                    .put("items", new JSONObject().put("type", "number"))
                    .put("description", "swipePoints 用：扁平坐标序列 [x1,y1,x2,y2,...]，至少 4 个数。单位物理像素。"))
                .put("points2", new JSONObject()
                    .put("type", "array")
                    .put("items", new JSONObject().put("type", "number"))
                    .put("description", "gesture 用：两个手指的起终点 [s1x,s1y,s2x,s2y,e1x,e1y,e2x,e2y]，8 个数。物理像素。"))
                .put("percent", new JSONObject()
                    .put("type", "number")
                    .put("description", "pinch 用：捏合百分比，默认 100。正数=捏合（pinchIn，缩小），负数=张开（pinchOut，放大）。"))
                .put("steps", new JSONObject()
                    .put("type", "number")
                    .put("description", "滑动步数（每步约 5ms）。默认 100；swipePoints 由 durationMs 换算（steps=durationMs/5）；pinch 默认 50。"))
                .put("durationMs", new JSONObject()
                    .put("type", "number")
                    .put("description", "swipePoints 用：总时长毫秒，默认 500，上限 30000。"))
                .put("direction", new JSONObject()
                    .put("type", "string")
                    .put("description", "scrollTo 用：vertical|horizontal，默认 vertical。"))
                .put("timeoutMs", new JSONObject()
                    .put("type", "number")
                    .put("description", "waitGone 用：最长等待毫秒，默认 10000，范围 1000-120000。"))
                .put("method", new JSONObject()
                    .put("type", "string")
                    .put("description", "call 用：jsonrpc 方法名，如 getText / scrollForward / pressKey / openNotification。"))
                .put("params", new JSONObject()
                    .put("type", "array")
                    .put("description", "call 用：jsonrpc params 数组，原样透传。"))
                .put("maxDepth", new JSONObject()
                    .put("type", "number")
                    .put("description", "dump 用：最大层级深度，默认 50。"))
                .put("compressed", new JSONObject()
                    .put("type", "boolean")
                    .put("description", "dump 用：true 时省略无文本/无 id 的节点，默认 true。")))
        // required 必须是顶层键（与 properties 平级）——嵌套会生成非法 schema，
        // 被严格校验的服务商 400 拒绝整个请求（CodeActionsTool 事故）。
        .put("required", new JSONArray().put("action"));
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    U2ProxyPort port = context == null ? null : context.getU2Proxy();
    if (port == null) {
      return error("uiautomator2 代理端口未接入（ShizukuU2Proxy 未注入）。");
    }
    String action = input.optString("action", "").trim();
    if (action.isEmpty()) {
      return error("action 不能为空。可用动作：" + actionList());
    }
    if (ACTION_STOP.equals(action)) {
      port.stop();
      return ok("u2 server 已停止（若本来就没在运行，此操作无副作用）。\n"
          + "现在传统 uiautomator dump（phone_view_hierarchy / phone_click_view / phone_wait_for）已恢复可用。");
    }
    if (ACTION_RESTART.equals(action)) {
      String restartError = port.restart();
      if (restartError != null) {
        return error("u2 server 重启失败：" + restartError);
      }
      return ok("u2 server 已重启。");
    }
    try {
      java.util.function.BooleanSupplier cancelled = cancellationSignal(context);
      ToolResult result;
      switch (action) {
        case ACTION_SET_TEXT:
          result = runSetText(input, port, cancelled);
          break;
        case ACTION_WAIT_GONE:
          result = runWaitGone(input, port, cancelled);
          break;
        case ACTION_GESTURE:
          result = runGesture(input, port, cancelled);
          break;
        case ACTION_PINCH:
          result = runPinch(input, port, cancelled);
          break;
        case ACTION_SWIPE_POINTS:
          result = runSwipePoints(input, port, cancelled);
          break;
        case ACTION_SCROLL_TO:
          result = runScrollTo(input, port, cancelled);
          break;
        case ACTION_CLICK:
          result = runClick(input, port, cancelled);
          break;
        case ACTION_DUMP:
          result = runDump(input, port, cancelled);
          break;
        case ACTION_CALL:
          result = runCall(input, port, cancelled);
          break;
        default:
          return error("未知 action：" + action + "。可用动作：" + actionList());
      }
      // 动作粒度收尾（供「用完即停」偏好）：必须在这里而不是每次 HTTP call 后——
      // click 是 objInfo + click 两次 call，中途停 server 会让第二次调用为
      // 失效会话重启。stop 动作本身不触发（已经停了）。
      port.onActionFinished();
      return result;
    } catch (org.json.JSONException e) {
      return error("参数 JSON 构造失败：" + e.getMessage());
    } catch (Throwable e) {
      // 工具约定：失败返回 error 变体，不抛异常。
      return error("phone_uiautomator 失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
    }
  }

  // ---------------------------------------------------------------------
  // action 实现
  // ---------------------------------------------------------------------

  private ToolResult runSetText(JSONObject input, U2ProxyPort port,
      java.util.function.BooleanSupplier cancelled) throws org.json.JSONException {
    SelectorResult sel = requireSelector(input, ACTION_SET_TEXT);
    if (sel.error != null) {
      return error(sel.error);
    }
    String text = input.optString("text", "");
    String method;
    String params;
    if (text.isEmpty()) {
      // 空文本 = 清空输入框（u2 客户端 setText("") 的语义，走 clearTextField 更稳）。
      method = "clearTextField";
      params = new JSONArray().put(sel.json).toString();
    } else {
      method = "setText";
      params = new JSONArray().put(sel.json).put(text).toString();
    }
    U2ProxyPort.Result result = port.call(method, params, HTTP_TIMEOUT_DEFAULT_MS, cancelled);
    return toResult(result, ACTION_SET_TEXT,
        text.isEmpty() ? "已清空匹配的输入框。" : "已输入文本（Unicode）。");
  }

  private ToolResult runWaitGone(JSONObject input, U2ProxyPort port,
      java.util.function.BooleanSupplier cancelled) throws org.json.JSONException {
    SelectorResult sel = requireSelector(input, ACTION_WAIT_GONE);
    if (sel.error != null) {
      return error(sel.error);
    }
    long timeout = clampLong(input.optDouble("timeoutMs", WAIT_GONE_DEFAULT_MS),
        WAIT_GONE_MIN_MS, WAIT_GONE_MAX_MS);
    String params = new JSONArray().put(sel.json).put(timeout).toString();
    U2ProxyPort.Result result = port.call("waitUntilGone", params,
        timeout + WAIT_GONE_HTTP_MARGIN_MS, cancelled);
    if (!result.isOk()) {
      return error(formatFailure(result, ACTION_WAIT_GONE));
    }
    boolean gone = "true".equalsIgnoreCase(trim(result.getResultJson()));
    if (gone) {
      return ok("节点已消失（在 " + timeout + "ms 内）。");
    }
    return error("等待超时：节点在 " + timeout + "ms 内仍然存在。\n"
        + "可检查 selector 是否匹配到了别的常驻节点，或增大 timeoutMs（上限 "
        + WAIT_GONE_MAX_MS + "）。");
  }

  private ToolResult runGesture(JSONObject input, U2ProxyPort port,
      java.util.function.BooleanSupplier cancelled) throws org.json.JSONException {
    JSONArray points = input.optJSONArray("points2");
    if (points == null || points.length() != 8) {
      return error("gesture 需要 points2 为 8 个数字 [s1x,s1y,s2x,s2y,e1x,e1y,e2x,e2y]，收到："
          + (points == null ? "缺失" : points.length() + " 个数"));
    }
    SelectorResult sel = optionalSelector(input);
    if (sel.error != null) {
      return error(sel.error);
    }
    int steps = clampInt(input.optDouble("steps", DEFAULT_STEPS), 1, 1000);
    JSONArray params = new JSONArray();
    params.put(sel.json);
    params.put(point(points, 0));
    params.put(point(points, 2));
    params.put(point(points, 4));
    params.put(point(points, 6));
    params.put(steps);
    U2ProxyPort.Result result = port.call("gesture", params.toString(),
        HTTP_TIMEOUT_DEFAULT_MS, cancelled);
    return toResult(result, ACTION_GESTURE, "双指手势已执行（steps=" + steps + "）。");
  }

  private ToolResult runPinch(JSONObject input, U2ProxyPort port,
      java.util.function.BooleanSupplier cancelled) throws org.json.JSONException {
    SelectorResult sel = optionalSelector(input);
    if (sel.error != null) {
      return error(sel.error);
    }
    double percentRaw = input.optDouble("percent", 100.0);
    if (percentRaw == 0) {
      return error("pinch 的 percent 不能为 0（正数=捏合缩小，负数=张开放大）。");
    }
    boolean pinchingIn = percentRaw > 0;
    int percent = (int) Math.round(Math.abs(percentRaw));
    int steps = clampInt(input.optDouble("steps", PINCH_DEFAULT_STEPS), 1, 1000);
    String method = pinchingIn ? "pinchIn" : "pinchOut";
    String params = new JSONArray().put(sel.json).put(percent).put(steps).toString();
    U2ProxyPort.Result result = port.call(method, params, HTTP_TIMEOUT_DEFAULT_MS, cancelled);
    return toResult(result, ACTION_PINCH,
        (pinchingIn ? "捏合（缩小）" : "张开（放大）") + "已执行（percent=" + percent + ", steps=" + steps + "）。");
  }

  private ToolResult runSwipePoints(JSONObject input, U2ProxyPort port,
      java.util.function.BooleanSupplier cancelled) throws org.json.JSONException {
    JSONArray points = input.optJSONArray("points");
    if (points == null || points.length() < 4 || points.length() % 2 != 0) {
      return error("swipePoints 需要 points 为扁平坐标序列 [x1,y1,x2,y2,...]（至少 4 个数，偶数个），收到："
          + (points == null ? "缺失" : points.length() + " 个数"));
    }
    JSONArray flat = new JSONArray();
    for (int i = 0; i < points.length(); i++) {
      flat.put((int) Math.round(points.optDouble(i, 0)));
    }
    long duration = clampLong(input.optDouble("durationMs", 500), 1, SWIPE_DURATION_MAX_MS);
    // steps 由 durationMs 换算（u2 客户端算法：每步约 5ms；不做最小 clamp——
    // "最小 2" 只存在于 u2 客户端的 swipe()，swipePoints 没有）。
    int steps = (int) Math.max(1, duration / 5);
    if (input.has("steps")) {
      steps = clampInt(input.optDouble("steps", steps), 1, 10_000);
    }
    // params[0] 必须是扁平 int 数组（嵌套数组 → Jackson 反序列化错误，实测）。
    String params = new JSONArray().put(flat).put(steps).toString();
    U2ProxyPort.Result result = port.call("swipePoints", params, HTTP_TIMEOUT_DEFAULT_MS, cancelled);
    return toResult(result, ACTION_SWIPE_POINTS,
        "多段路径滑动已执行（" + (points.length() / 2) + " 个点，steps=" + steps + "）。");
  }

  private ToolResult runScrollTo(JSONObject input, U2ProxyPort port,
      java.util.function.BooleanSupplier cancelled) throws org.json.JSONException {
    SelectorResult sel = requireSelector(input, ACTION_SCROLL_TO);
    if (sel.error != null) {
      return error(sel.error);
    }
    // u2 客户端语义（_selector.py:577）：scrollTo(containerSelector, targetSelector, vertical)。
    // 两个 selector 都必须非空——实测空壳会让 server 挂起（>20s 无响应，随后整体 wedged）。
    // 容器默认 {scrollable:true}（第一个可滚动节点，绝大多数界面就是主滚动容器）。
    JSONObject containerRaw = input.optJSONObject("scrollableSelector");
    if (containerRaw == null) {
      containerRaw = new JSONObject().put("scrollable", true);
    }
    SelectorResult container = buildSelector(containerRaw);
    if (container.error != null) {
      return error("scrollableSelector 无效：" + container.error);
    }
    if (container.json.length() <= 3) {
      return error("scrollableSelector 不能为空（会挂起 u2 server）。省略该参数即默认 {scrollable:true}。");
    }
    boolean vertical = !"horizontal".equalsIgnoreCase(input.optString("direction", "vertical").trim());
    String params = new JSONArray().put(container.json).put(sel.json).put(vertical).toString();
    U2ProxyPort.Result result = port.call("scrollTo", params, HTTP_TIMEOUT_HEAVY_MS, cancelled);
    return toResult(result, ACTION_SCROLL_TO,
        "已滚动到目标元素（" + (vertical ? "vertical" : "horizontal") + "）。");
  }

  private ToolResult runClick(JSONObject input, U2ProxyPort port,
      java.util.function.BooleanSupplier cancelled) throws org.json.JSONException {
    SelectorResult sel = requireSelector(input, ACTION_CLICK);
    if (sel.error != null) {
      return error(sel.error);
    }
    // 两步：objInfo 拿 bounds → 本地算中心 → click(x, y)。
    // 不走 u2 的 click(selector)——那需要 uiautomator 的 UiObject 语义，jsonrpc 层没有。
    U2ProxyPort.Result info = port.call("objInfo",
        new JSONArray().put(sel.json).toString(), HTTP_TIMEOUT_DEFAULT_MS, cancelled);
    if (!info.isOk()) {
      return error("查找节点失败。\n" + formatFailure(info, ACTION_CLICK));
    }
    int[] center = extractCenter(info.getResultJson());
    if (center == null) {
      return error("objInfo 返回里没有可用的 bounds：\n" + preview(info.getResultJson())
          + "\n（节点可能不存在或不可见；可用 dump 动作查看当前层级。）");
    }
    U2ProxyPort.Result click = port.call("click",
        new JSONArray().put(center[0]).put(center[1]).toString(), HTTP_TIMEOUT_DEFAULT_MS, cancelled);
    return toResult(click, ACTION_CLICK,
        "已点击节点中心 (" + center[0] + ", " + center[1] + ")。");
  }

  private ToolResult runDump(JSONObject input, U2ProxyPort port,
      java.util.function.BooleanSupplier cancelled) throws org.json.JSONException {
    boolean compressed = input.optBoolean("compressed", true);
    int maxDepth = clampInt(input.optDouble("maxDepth", DEFAULT_MAX_DEPTH), 1, 500);
    String params = new JSONArray().put(compressed).put(maxDepth).toString();
    U2ProxyPort.Result result = port.call("dumpWindowHierarchy", params, HTTP_TIMEOUT_HEAVY_MS, cancelled);
    if (!result.isOk()) {
      return error(formatFailure(result, ACTION_DUMP));
    }
    String xml = unquote(result.getResultJson());
    StringBuilder sb = new StringBuilder();
    if (result.wasServerRestarted()) {
      sb.append("（u2 server 曾断开，本次调用已自动重启。）\n");
    }
    sb.append("节点树 XML（compressed=").append(compressed).append(", maxDepth=").append(maxDepth).append("）：\n");
    // dump 的 XML 实测可达 54KB——用 ToolResult 的 50K 总上限，不走 8K 通用预览，
    // 否则模型只能看到极小一部分、dump 失去意义。
    sb.append(xml);
    return ok(ToolResult.truncateContent(sb.toString()));
  }

  private ToolResult runCall(JSONObject input, U2ProxyPort port,
      java.util.function.BooleanSupplier cancelled) throws org.json.JSONException {
    String method = input.optString("method", "").trim();
    if (method.isEmpty()) {
      return error("call 需要 method（jsonrpc 方法名，如 getText / scrollForward / pressKey）。");
    }
    if (CALL_BLACKLIST.contains(method)) {
      return error("call 不允许调用 " + method + "（会绕过权限门直接改设备/执行命令）。"
          + "需要执行命令请用 shell_execute；需要原始输入注入请用 phone_click / phone_swipe。");
    }
    JSONArray params = input.optJSONArray("params");
    String paramsJson = params == null ? "[]" : params.toString();
    U2ProxyPort.Result result = port.call(method, paramsJson, HTTP_TIMEOUT_DEFAULT_MS, cancelled);
    if (!result.isOk()) {
      return error(formatFailure(result, "call " + method));
    }
    return ok("jsonrpc " + method + " 成功，result：\n" + preview(unquote(result.getResultJson())));
  }

  // ---------------------------------------------------------------------
  // selector 构造
  // ---------------------------------------------------------------------

  /** selector 构造结果：json 为下发的对象；error 非空时 json 无意义。 */
  static final class SelectorResult {
    final JSONObject json;
    final String error;

    SelectorResult(JSONObject json, String error) {
      this.json = json;
      this.error = error;
    }
  }

  /** 必需 selector 的动作：缺失或为空时报错。 */
  private static SelectorResult requireSelector(JSONObject input, String action) {
    SelectorResult sel = optionalSelector(input);
    if (sel.error != null) {
      return sel;
    }
    if (sel.json.length() <= 3) { // 只有 mask/childOrSibling/childOrSiblingSelector = 空壳
      return new SelectorResult(null, action + " 需要一个 selector（resourceId / text / description / "
          + "className 至少给一个）。当前界面可用 dump 动作查看。");
    }
    return sel;
  }

  /** 可选 selector 的动作（gesture/pinch）：缺失时用空壳（实测可用，勿传 null——NPE）。 */
  private static SelectorResult optionalSelector(JSONObject input) {
    JSONObject raw = input.optJSONObject("selector");
    return buildSelector(raw);
  }

  /**
   * 把工具层的 selector 字段映射成 u2 的 BySelector 对象。
   *
   * <p>字段映射（复审 M4 修正，按字段名改键，不按原写法直接塞 text）：
   * {@code textContains:true} + {@code text:"<子串>"} → {@code {"mask":0x02,"textContains":"<子串>"}}；
   * {@code descriptionContains} 同理。字段名与 mask 位值来自 u2 客户端 {@code _selector.py:17-43}。
   */
  static SelectorResult buildSelector(JSONObject raw) {
    try {
      long mask = 0L;
      JSONObject out = new JSONObject();
      if (raw != null) {
        String resourceId = raw.optString("resourceId", "").trim();
        if (!resourceId.isEmpty()) {
          mask |= MASK_RESOURCE_ID;
          out.put("resourceId", resourceId);
        }
        String text = raw.optString("text", "").trim();
        boolean textContains = raw.optBoolean("textContains", false);
        if (!text.isEmpty()) {
          if (textContains) {
            mask |= MASK_TEXT_CONTAINS;
            out.put("textContains", text);
          } else {
            mask |= MASK_TEXT;
            out.put("text", text);
          }
        }
        String description = raw.optString("description", "").trim();
        boolean descContains = raw.optBoolean("descriptionContains", false);
        if (!description.isEmpty()) {
          if (descContains) {
            mask |= MASK_DESCRIPTION_CONTAINS;
            out.put("descriptionContains", description);
          } else {
            mask |= MASK_DESCRIPTION;
            out.put("description", description);
          }
        }
        String className = raw.optString("className", "").trim();
        if (!className.isEmpty()) {
          mask |= MASK_CLASS_NAME;
          out.put("className", className);
        }
        if (raw.has("clickable")) {
          mask |= MASK_CLICKABLE;
          out.put("clickable", raw.optBoolean("clickable"));
        }
        if (raw.has("scrollable")) {
          mask |= MASK_SCROLLABLE;
          out.put("scrollable", raw.optBoolean("scrollable"));
        }
        int index = raw.optInt("index", -1);
        if (index >= 0) {
          mask |= MASK_INDEX;
          out.put("index", index);
        }
      }
      // mask/childOrSibling/childOrSiblingSelector 是服务端反序列化必需的三件套。
      JSONObject result = new JSONObject();
      result.put("mask", mask);
      result.put("childOrSibling", new JSONArray());
      result.put("childOrSiblingSelector", new JSONArray());
      java.util.Iterator<String> keys = out.keys();
      while (keys.hasNext()) {
        String key = keys.next();
        result.put(key, out.get(key));
      }
      return new SelectorResult(result, null);
    } catch (org.json.JSONException e) {
      return new SelectorResult(null, "selector 构造失败：" + e.getMessage());
    }
  }

  // ---------------------------------------------------------------------
  // 结果解析
  // ---------------------------------------------------------------------

  /** 通用成功/失败转换：成功时在结果文本里带 result 摘要与重启提示。 */
  private ToolResult toResult(U2ProxyPort.Result result, String action, String successNote) {
    if (!result.isOk()) {
      return error(formatFailure(result, action));
    }
    StringBuilder sb = new StringBuilder();
    if (result.wasServerRestarted()) {
      sb.append("（u2 server 曾断开，本次调用已自动重启。）\n");
    }
    sb.append(successNote);
    String body = trim(result.getResultJson());
    if (!body.isEmpty() && !"true".equalsIgnoreCase(body)) {
      sb.append("\nresult: ").append(preview(body));
    }
    return ok(ToolResult.truncateContent(sb.toString()));
  }

  /** 失败信息统一带超时/取消的下一步建议。 */
  private static String formatFailure(U2ProxyPort.Result result, String action) {
    StringBuilder sb = new StringBuilder();
    if (result.wasServerRestarted()) {
      sb.append("（u2 server 曾断开，本次调用已自动重启。）\n");
    }
    sb.append(action).append(" 失败");
    if (result.isTimedOut()) {
      sb.append("（超时）");
    }
    sb.append("：").append(result.getError());
    if (result.isTimedOut()) {
      sb.append("\n提示：server 可能已卡死——可用 restart 动作重启；"
          + "scrollTo 尤其容易因 selector 不当卡住 server。");
    }
    return sb.toString();
  }

  /** 从 objInfo 结果里抽 bounds 中心。支持 {left,top,right,bottom} 与 {visibleBounds:{...}} 两种形态。 */
  static int[] extractCenter(String resultJson) {
    if (resultJson == null || resultJson.trim().isEmpty()) {
      return null;
    }
    try {
      JSONObject obj = new JSONObject(resultJson.trim());
      JSONObject bounds = obj.optJSONObject("visibleBounds");
      if (bounds == null) {
        bounds = obj.optJSONObject("bounds");
      }
      if (bounds == null) {
        return null;
      }
      int left = bounds.optInt("left", Integer.MIN_VALUE);
      int top = bounds.optInt("top", Integer.MIN_VALUE);
      int right = bounds.optInt("right", Integer.MIN_VALUE);
      int bottom = bounds.optInt("bottom", Integer.MIN_VALUE);
      if (left == Integer.MIN_VALUE || top == Integer.MIN_VALUE
          || right == Integer.MIN_VALUE || bottom == Integer.MIN_VALUE) {
        return null;
      }
      return new int[] {(left + right) / 2, (top + bottom) / 2};
    } catch (org.json.JSONException e) {
      return null;
    }
  }

  // ---------------------------------------------------------------------
  // 小工具
  // ---------------------------------------------------------------------

  private static JSONObject point(JSONArray flat, int offset) throws org.json.JSONException {
    return new JSONObject()
        .put("x", (int) Math.round(flat.optDouble(offset, 0)))
        .put("y", (int) Math.round(flat.optDouble(offset + 1, 0)));
  }

  private static String actionList() {
    return "setText / waitGone / gesture / pinch / swipePoints / scrollTo / click / dump / stop / restart / call";
  }

  static long clampLong(double value, long min, long max) {
    long v = (long) value;
    return v < min ? min : (v > max ? max : v);
  }

  static int clampInt(double value, int min, int max) {
    int v = (int) value;
    return v < min ? min : (v > max ? max : v);
  }

  /** 把 result 的原始 JSON 文本解成展示用字符串（字符串结果做反转义，其余原样）。 */
  private static String unquote(String value) {
    String v = trim(value);
    if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
      try {
        // 借 JSONArray 做真正的字符串反转义（\\n / \\" 等），而不是裸切引号。
        return new JSONArray("[" + v + "]").getString(0);
      } catch (org.json.JSONException e) {
        return v.substring(1, v.length() - 1);
      }
    }
    return v;
  }

  private static String trim(String value) {
    return value == null ? "" : value.trim();
  }

  private static String preview(String value) {
    String v = value == null ? "" : value;
    return v.length() <= RESULT_PREVIEW_MAX
        ? v
        : v.substring(0, RESULT_PREVIEW_MAX) + "\n…（已截断，共 " + v.length() + " 字符）";
  }

  /** 从 ToolContext 取取消信号；null 安全的适配。 */
  private static java.util.function.BooleanSupplier cancellationSignal(ToolContext context) {
    if (context == null || context.getCancellation() == null) {
      return null;
    }
    return context.getCancellation()::isCancelled;
  }
}
