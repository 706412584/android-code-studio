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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tom.rv2ide.ai.tool.api.ToolCategory;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * {@link PhoneUiautomatorTool}：参数构造 / 校验 / 结果解析的回归测试。
 *
 * <p>用 FakePort 记录下发的 method 与 paramsJson（计划 §9.1），真实 HTTP 与 server
 * 生命周期在 app 层（设备验证覆盖）。
 */
class PhoneUiautomatorToolTest {

  private static final class FakePort implements U2ProxyPort {
    final List<String> methods = new ArrayList<>();
    final List<String> params = new ArrayList<>();
    final List<Long> timeouts = new ArrayList<>();
    final List<BooleanSupplier> cancellations = new ArrayList<>();
    String resultJson = "true";
    String error = "";
    boolean ok = true;
    boolean timedOut = false;
    boolean restarted = false;
    int stopCalls = 0;
    int restartCalls = 0;

    @Override
    public Result call(String method, String paramsJson, long timeoutMs, BooleanSupplier cancelled) {
      methods.add(method);
      params.add(paramsJson);
      timeouts.add(timeoutMs);
      cancellations.add(cancelled);
      return ok ? Result.ok(resultJson, restarted)
          : Result.fail(error, timedOut, restarted);
    }

    @Override
    public boolean isAvailable() {
      return true;
    }

    @Override
    public String unavailableReason() {
      return "";
    }

    @Override
    public void stop() {
      stopCalls++;
    }

    @Override
    public void restart() {
      restartCalls++;
    }
  }

  private static ToolContext ctx(U2ProxyPort port) {
    return ToolContext.builder().u2Proxy(port).build();
  }

  private static JSONObject args(String action) throws Exception {
    return new JSONObject().put("action", action);
  }

  // ---------------------------------------------------------------------
  // 端口缺失 / schema / 元数据
  // ---------------------------------------------------------------------

  @Test
  void reportsErrorWhenPortMissing() throws Exception {
    ToolResult r = new PhoneUiautomatorTool()
        .execute(args("dump"), ToolContext.builder().build());
    assertTrue(r.isError());
    assertTrue(r.getContent().contains("未接入"), r.getContent());
  }

  @Test
  void parametersSchemaHasRequiredAtTopLevel() throws Exception {
    JSONObject params = new PhoneUiautomatorTool().getParameters();
    assertEquals("object", params.optString("type"));
    JSONObject props = params.optJSONObject("properties");
    assertNotNull(props);
    JSONArray required = params.optJSONArray("required");
    assertNotNull(required, "required 必须在顶层");
    assertFalse(props.has("required"), "required 不得嵌套在 properties 内");
    assertEquals("action", required.optString(0));
    for (java.util.Iterator<String> it = props.keys(); it.hasNext(); ) {
      String key = it.next();
      assertTrue(props.get(key) instanceof JSONObject, "properties." + key + " 必须是 schema 对象");
    }
  }

  @Test
  void toolMetadata() {
    PhoneUiautomatorTool tool = new PhoneUiautomatorTool();
    assertEquals("phone_uiautomator", tool.getName());
    assertEquals(ToolCategory.SYSTEM, tool.getCategory());
    assertTrue(tool.needsConfirmation());
    assertFalse(tool.isAllowedInReadonlyMode());
    assertFalse(tool.isConcurrencySafe());
  }

  // ---------------------------------------------------------------------
  // setText / clearTextField
  // ---------------------------------------------------------------------

  @Test
  void setTextBuildsSelectorWithTextMask() throws Exception {
    FakePort port = new FakePort();
    JSONObject input = args("setText")
        .put("selector", new JSONObject().put("resourceId", "android:id/input"))
        .put("text", "蓝牙");
    ToolResult r = new PhoneUiautomatorTool().execute(input, ctx(port));
    assertFalse(r.isError(), r.getContent());
    assertEquals(1, port.methods.size());
    assertEquals("setText", port.methods.get(0));
    JSONArray params = new JSONArray(port.params.get(0));
    JSONObject sel = params.getJSONObject(0);
    assertEquals(0x200000, sel.getLong("mask"));
    assertEquals("android:id/input", sel.getString("resourceId"));
    assertEquals("蓝牙", params.getString(1));
    // 三件套齐备
    assertNotNull(sel.optJSONArray("childOrSibling"));
    assertNotNull(sel.optJSONArray("childOrSiblingSelector"));
  }

  @Test
  void setTextEmptyUsesClearTextField() throws Exception {
    FakePort port = new FakePort();
    JSONObject input = args("setText")
        .put("selector", new JSONObject().put("text", "搜索"))
        .put("text", "");
    ToolResult r = new PhoneUiautomatorTool().execute(input, ctx(port));
    assertFalse(r.isError(), r.getContent());
    assertEquals("clearTextField", port.methods.get(0));
    JSONArray params = new JSONArray(port.params.get(0));
    assertEquals(1, params.length(), "clearTextField 只带 selector");
  }

  @Test
  void textContainsMapsToTextContainsKeyNotText() throws Exception {
    // 复审 M4：mask=2 必须配 textContains 键；配 text 键会稳定触发服务器 NPE。
    FakePort port = new FakePort();
    JSONObject input = args("setText")
        .put("selector", new JSONObject().put("text", "搜索").put("textContains", true))
        .put("text", "x");
    ToolResult r = new PhoneUiautomatorTool().execute(input, ctx(port));
    assertFalse(r.isError(), r.getContent());
    JSONObject sel = new JSONArray(port.params.get(0)).getJSONObject(0);
    assertEquals(0x02, sel.getLong("mask"));
    assertEquals("搜索", sel.getString("textContains"));
    assertFalse(sel.has("text"), "textContains 模式不得出现 text 键");
  }

  @Test
  void requireSelectorRejectsEmptyShell() throws Exception {
    FakePort port = new FakePort();
    ToolResult r = new PhoneUiautomatorTool()
        .execute(args("setText").put("text", "x"), ctx(port));
    assertTrue(r.isError());
    assertTrue(r.getContent().contains("selector"), r.getContent());
    assertTrue(port.methods.isEmpty(), "本地校验失败不得下发调用");
  }

  // ---------------------------------------------------------------------
  // gesture / pinch / swipePoints
  // ---------------------------------------------------------------------

  @Test
  void gestureRequiresEightNumbers() throws Exception {
    FakePort port = new FakePort();
    ToolResult r = new PhoneUiautomatorTool().execute(
        args("gesture").put("points2", new JSONArray().put(1).put(2).put(3)),
        ctx(port));
    assertTrue(r.isError());
    assertTrue(r.getContent().contains("8 个数字"), r.getContent());
    assertTrue(port.methods.isEmpty());
  }

  @Test
  void gestureBuildsFourPointsAndShellSelector() throws Exception {
    FakePort port = new FakePort();
    JSONArray pts = new JSONArray();
    for (int v : new int[] {400, 1000, 680, 1000, 300, 1000, 780, 1000}) {
      pts.put(v);
    }
    ToolResult r = new PhoneUiautomatorTool().execute(
        args("gesture").put("points2", pts).put("steps", 50), ctx(port));
    assertFalse(r.isError(), r.getContent());
    assertEquals("gesture", port.methods.get(0));
    JSONArray params = new JSONArray(port.params.get(0));
    assertEquals(6, params.length(), "[selector, start1, start2, end1, end2, steps]");
    JSONObject sel = params.getJSONObject(0);
    assertEquals(0, sel.getLong("mask"), "空壳 selector 用于 gesture");
    assertEquals(400, params.getJSONObject(1).getInt("x"));
    assertEquals(1000, params.getJSONObject(1).getInt("y"));
    assertEquals(300, params.getJSONObject(3).getInt("x"));
    assertEquals(50, params.getInt(5));
  }

  @Test
  void pinchSignChoosesMethodAndAbsValue() throws Exception {
    FakePort port = new FakePort();
    ToolResult r = new PhoneUiautomatorTool().execute(
        args("pinch").put("percent", -50), ctx(port));
    assertFalse(r.isError(), r.getContent());
    assertEquals("pinchOut", port.methods.get(0));
    JSONArray params = new JSONArray(port.params.get(0));
    assertEquals(50, params.getInt(1), "负数取绝对值");
    assertEquals(50, params.getInt(2), "默认 steps=50");
  }

  @Test
  void pinchPositiveUsesPinchIn() throws Exception {
    FakePort port = new FakePort();
    ToolResult r = new PhoneUiautomatorTool().execute(args("pinch"), ctx(port));
    assertFalse(r.isError(), r.getContent());
    assertEquals("pinchIn", port.methods.get(0));
    assertEquals(100, new JSONArray(port.params.get(0)).getInt(1), "默认 percent=100");
  }

  @Test
  void pinchRejectsZeroPercent() throws Exception {
    FakePort port = new FakePort();
    ToolResult r = new PhoneUiautomatorTool().execute(
        args("pinch").put("percent", 0), ctx(port));
    assertTrue(r.isError());
    assertTrue(port.methods.isEmpty());
  }

  @Test
  void swipePointsFlattensAndDerivesStepsFromDuration() throws Exception {
    FakePort port = new FakePort();
    JSONArray pts = new JSONArray().put(1).put(2).put(3).put(4);
    ToolResult r = new PhoneUiautomatorTool().execute(
        args("swipePoints").put("points", pts).put("durationMs", 500), ctx(port));
    assertFalse(r.isError(), r.getContent());
    assertEquals("swipePoints", port.methods.get(0));
    JSONArray params = new JSONArray(port.params.get(0));
    // params[0] 必须是扁平数组（嵌套 → Jackson 报错）
    JSONArray flat = params.getJSONArray(0);
    assertEquals(4, flat.length());
    assertEquals(1, flat.getInt(0));
    assertEquals(4, flat.getInt(3));
    assertEquals(100, params.getInt(1), "steps = durationMs / 5");
  }

  @Test
  void swipePointsRejectsOddCount() throws Exception {
    FakePort port = new FakePort();
    ToolResult r = new PhoneUiautomatorTool().execute(
        args("swipePoints").put("points", new JSONArray().put(1).put(2).put(3)),
        ctx(port));
    assertTrue(r.isError());
    assertTrue(port.methods.isEmpty());
  }

  // ---------------------------------------------------------------------
  // scrollTo
  // ---------------------------------------------------------------------

  @Test
  void scrollToDefaultsContainerToScrollableTrue() throws Exception {
    FakePort port = new FakePort();
    ToolResult r = new PhoneUiautomatorTool().execute(
        args("scrollTo").put("selector", new JSONObject().put("text", "版本号")),
        ctx(port));
    assertFalse(r.isError(), r.getContent());
    assertEquals("scrollTo", port.methods.get(0));
    JSONArray params = new JSONArray(port.params.get(0));
    JSONObject container = params.getJSONObject(0);
    assertTrue(container.getBoolean("scrollable"));
    assertTrue(container.getLong("mask") != 0, "容器 selector 不得为空壳（会挂起 server）");
    assertEquals("版本号", params.getJSONObject(1).getString("text"));
    assertTrue(params.getBoolean(2), "默认 vertical");
  }

  @Test
  void scrollToRejectsEmptyTargetSelector() throws Exception {
    FakePort port = new FakePort();
    ToolResult r = new PhoneUiautomatorTool().execute(args("scrollTo"), ctx(port));
    assertTrue(r.isError());
    assertTrue(port.methods.isEmpty(), "缺 target selector 必须本地拒绝");
  }

  // ---------------------------------------------------------------------
  // click
  // ---------------------------------------------------------------------

  @Test
  void clickDoesObjInfoThenClickCenter() throws Exception {
    FakePort port = new FakePort();
    port.resultJson = "{\"bounds\":{\"left\":100,\"top\":200,\"right\":300,\"bottom\":400}}";
    JSONObject input = args("click").put("selector", new JSONObject().put("text", "确定"));
    ToolResult r = new PhoneUiautomatorTool().execute(input, ctx(port));
    assertFalse(r.isError(), r.getContent());
    assertEquals(2, port.methods.size());
    assertEquals("objInfo", port.methods.get(0));
    assertEquals("click", port.methods.get(1));
    JSONArray clickParams = new JSONArray(port.params.get(1));
    assertEquals(200, clickParams.getInt(0));
    assertEquals(300, clickParams.getInt(1));
    assertTrue(r.getContent().contains("(200, 300)"), r.getContent());
  }

  @Test
  void clickPrefersVisibleBounds() throws Exception {
    int[] center = PhoneUiautomatorTool.extractCenter(
        "{\"visibleBounds\":{\"left\":0,\"top\":0,\"right\":10,\"bottom\":20},"
            + "\"bounds\":{\"left\":0,\"top\":0,\"right\":100,\"bottom\":100}}");
    assertNotNull(center);
    assertEquals(5, center[0]);
    assertEquals(10, center[1]);
  }

  @Test
  void extractCenterReturnsNullWithoutBounds() {
    assertNull(PhoneUiautomatorTool.extractCenter("{}"));
    assertNull(PhoneUiautomatorTool.extractCenter("not json"));
    assertNull(PhoneUiautomatorTool.extractCenter(""));
  }

  // ---------------------------------------------------------------------
  // call 黑名单
  // ---------------------------------------------------------------------

  @Test
  void callBlocksBlacklistedMethods() throws Exception {
    FakePort port = new FakePort();
    ToolResult shell = new PhoneUiautomatorTool().execute(
        args("call").put("method", "shell").put("params", new JSONArray().put("rm -rf /")),
        ctx(port));
    assertTrue(shell.isError());
    ToolResult inject = new PhoneUiautomatorTool().execute(
        args("call").put("method", "injectInputEvent"), ctx(port));
    assertTrue(inject.isError());
    assertTrue(port.methods.isEmpty(), "黑名单方法不得下发");
  }

  @Test
  void callPassesThroughAllowedMethod() throws Exception {
    FakePort port = new FakePort();
    port.resultJson = "\"hello\"";
    ToolResult r = new PhoneUiautomatorTool().execute(
        args("call").put("method", "getText")
            .put("params", new JSONArray().put(new JSONObject().put("mask", 1).put("text", "a"))),
        ctx(port));
    assertFalse(r.isError(), r.getContent());
    assertEquals("getText", port.methods.get(0));
    assertTrue(r.getContent().contains("hello"), r.getContent());
  }

  // ---------------------------------------------------------------------
  // 超时映射 / stop / restart / 取消 / serverRestarted
  // ---------------------------------------------------------------------

  @Test
  void httpTimeoutMappingPerAction() throws Exception {
    FakePort port = new FakePort();
    new PhoneUiautomatorTool().execute(
        args("waitGone").put("selector", new JSONObject().put("text", "x")).put("timeoutMs", 60000),
        ctx(port));
    assertEquals(70_000L, port.timeouts.get(0), "waitGone = timeout + 10s 裕量");

    FakePort port2 = new FakePort();
    new PhoneUiautomatorTool().execute(args("dump"), ctx(port2));
    assertEquals(30_000L, port2.timeouts.get(0), "dump 用重超时");

    FakePort port3 = new FakePort();
    new PhoneUiautomatorTool().execute(
        args("setText").put("selector", new JSONObject().put("text", "a")).put("text", "b"),
        ctx(port3));
    assertEquals(15_000L, port3.timeouts.get(0), "普通动作 15s");
  }

  @Test
  void waitGoneClampsTimeout() throws Exception {
    FakePort port = new FakePort();
    new PhoneUiautomatorTool().execute(
        args("waitGone").put("selector", new JSONObject().put("text", "x")).put("timeoutMs", 999999),
        ctx(port));
    JSONArray params = new JSONArray(port.params.get(0));
    assertEquals(120_000L, params.getLong(1), "上限 120s");

    FakePort port2 = new FakePort();
    new PhoneUiautomatorTool().execute(
        args("waitGone").put("selector", new JSONObject().put("text", "x")).put("timeoutMs", 1),
        ctx(port2));
    assertEquals(1_000L, new JSONArray(port2.params.get(0)).getLong(1), "下限 1s");
  }

  @Test
  void waitGoneFalseIsErrorWithGuidance() throws Exception {
    FakePort port = new FakePort();
    port.resultJson = "false";
    ToolResult r = new PhoneUiautomatorTool().execute(
        args("waitGone").put("selector", new JSONObject().put("text", "x")), ctx(port));
    assertTrue(r.isError());
    assertTrue(r.getContent().contains("仍然存在"), r.getContent());
  }

  @Test
  void stopCallsPortWithoutJsonrpc() throws Exception {
    FakePort port = new FakePort();
    ToolResult r = new PhoneUiautomatorTool().execute(args("stop"), ctx(port));
    assertFalse(r.isError(), r.getContent());
    assertEquals(1, port.stopCalls);
    assertTrue(port.methods.isEmpty(), "stop 不走 jsonrpc");
    assertTrue(r.getContent().contains("phone_view_hierarchy"), r.getContent());
  }

  @Test
  void restartCallsPortWithoutJsonrpc() throws Exception {
    FakePort port = new FakePort();
    ToolResult r = new PhoneUiautomatorTool().execute(args("restart"), ctx(port));
    assertFalse(r.isError(), r.getContent());
    assertEquals(1, port.restartCalls);
    assertTrue(port.methods.isEmpty());
  }

  @Test
  void cancellationSignalIsPassedThrough() throws Exception {
    FakePort port = new FakePort();
    ToolContext context = ToolContext.builder()
        .u2Proxy(port)
        .cancellation(() -> true)
        .build();
    new PhoneUiautomatorTool().execute(args("dump"), context);
    assertNotNull(port.cancellations.get(0), "取消信号必须透传给端口");
    assertTrue(port.cancellations.get(0).getAsBoolean());
  }

  @Test
  void serverRestartedFlagSurfacesInResult() throws Exception {
    FakePort port = new FakePort();
    port.restarted = true;
    ToolResult r = new PhoneUiautomatorTool().execute(args("dump"), ctx(port));
    assertFalse(r.isError(), r.getContent());
    assertTrue(r.getContent().contains("自动重启"), r.getContent());
  }

  @Test
  void timeoutFailureSuggestsRestart() throws Exception {
    FakePort port = new FakePort();
    port.ok = false;
    port.timedOut = true;
    port.error = "读取超时";
    ToolResult r = new PhoneUiautomatorTool().execute(args("scrollTo")
        .put("selector", new JSONObject().put("text", "x")), ctx(port));
    assertTrue(r.isError());
    assertTrue(r.getContent().contains("restart"), r.getContent());
  }

  @Test
  void unknownActionListsAvailable() throws Exception {
    FakePort port = new FakePort();
    ToolResult r = new PhoneUiautomatorTool().execute(args("bogus"), ctx(port));
    assertTrue(r.isError());
    assertTrue(r.getContent().contains("setText"), r.getContent());
    assertTrue(r.getContent().contains("call"), r.getContent());
  }

  @Test
  void dumpUnquotesResultAndPreviews() throws Exception {
    FakePort port = new FakePort();
    port.resultJson = "\"<hierarchy rotation=\\\"0\\\"><node/></hierarchy>\"";
    ToolResult r = new PhoneUiautomatorTool().execute(args("dump"), ctx(port));
    assertFalse(r.isError(), r.getContent());
    assertTrue(r.getContent().contains("<hierarchy"), r.getContent());
    assertFalse(r.getContent().contains("\\\""), "外层引号与转义应被去掉");
  }

  @Test
  void indexAndFlagsProduceCorrectMask() throws Exception {
    FakePort port = new FakePort();
    JSONObject sel = new JSONObject()
        .put("className", "android.widget.Button")
        .put("clickable", true)
        .put("index", 2);
    new PhoneUiautomatorTool().execute(
        args("setText").put("selector", sel).put("text", "x"), ctx(port));
    JSONObject sent = new JSONArray(port.params.get(0)).getJSONObject(0);
    long expected = 0x10L | 0x1000L | 0x800000L;
    assertEquals(expected, sent.getLong("mask"));
    assertEquals(2, sent.getInt("index"));
    assertTrue(sent.getBoolean("clickable"));
  }
}
