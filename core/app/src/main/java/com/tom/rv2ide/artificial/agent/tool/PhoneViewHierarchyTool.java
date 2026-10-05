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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * dump 当前界面的 UI 节点树，并把屏幕尺寸/密度一并返回。
 *
 * <p><b>用途</b>：截图只能让模型「看」界面，但看不清控件的 resource-id、精确坐标、
 * 是否可点击/可用。本工具给出结构化的节点树，让模型能：
 * <ul>
 *   <li>确认某个控件是否存在、文本是否正确
 *   <li>拿到可点击控件的 resource-id / text / content-desc，作为
 *       {@code phone_click_view} / {@code phone_wait_for} 的定位条件
 *   <li>结合屏幕尺寸把像素坐标换算成相对位置
 * </ul>
 *
 * <p><b>实现</b>：{@code uiautomator dump}（adb 级 shell，无需无障碍服务）→ 解析 XML →
 * 渲染成缩进文本。解析与渲染复用 {@link PhoneUiSelector}，与 phone-act 的定位逻辑同源。
 *
 * <p><b>权限</b>：需要 adb 级权限（Shizuku）；后端没有该权限时给出明确错误。
 */
public final class PhoneViewHierarchyTool extends BaseTool {

  private static final Logger log = LoggerFactory.getLogger(PhoneViewHierarchyTool.class);

  /** 默认最多输出的节点数，避免大树撑爆上下文。 */
  private static final int DEFAULT_MAX_NODES = 200;

  /** maxNodes 的硬上限。 */
  private static final int MAX_NODES_LIMIT = 2000;

  private static final Pattern PHYSICAL_SIZE = Pattern.compile("Physical size:\\s*(\\d+)x(\\d+)");
  private static final Pattern OVERRIDE_SIZE = Pattern.compile("Override size:\\s*(\\d+)x(\\d+)");
  private static final Pattern PHYSICAL_DENSITY = Pattern.compile("Physical density:\\s*(\\d+)");
  private static final Pattern OVERRIDE_DENSITY = Pattern.compile("Override density:\\s*(\\d+)");

  private final ShellBackendRegistry shellBackends;

  /** 仅用于定位 dump 临时文件目录（app 外部私有目录），避免写公共存储根。 */
  private final android.content.Context appContext;

  public PhoneViewHierarchyTool(
      android.content.Context appContext, ShellBackendRegistry shellBackends) {
    this.appContext = appContext;
    this.shellBackends = shellBackends;
  }

  @Override
  public String getName() {
    return ToolNames.PHONE_VIEW_HIERARCHY;
  }

  @Override
  public String getDescription() {
    return "读取当前界面的 UI 节点树（class / text / resource-id / content-desc / bounds / "
        + "clickable / enabled / focused 等），并返回屏幕尺寸与密度。"
        + "用于确认控件是否存在、文本是否正确，以及获取 phone_click_view / phone_wait_for 的定位条件。"
        + "需要 adb 级权限（Shizuku 后端）；无权限时会明确报错。";
  }

  @Override
  public ToolCategory getCategory() {
    return ToolCategory.READ;
  }

  @Override
  public ToolDisplayCategory getDisplayCategory() {
    return ToolDisplayCategory.PHONE_CONTROL;
  }

  @Override
  public boolean isAllowedInReadonlyMode() {
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
                    "maxNodes",
                    new JSONObject()
                        .put("type", "number")
                        .put(
                            "description",
                            "最多输出的节点数，默认 " + DEFAULT_MAX_NODES + "，上限 " + MAX_NODES_LIMIT))
                .put(
                    "compressed",
                    new JSONObject()
                        .put("type", "boolean")
                        .put(
                            "description",
                            "传给 uiautomator 的 --compressed：省略无文本/无 id 的节点，输出更小但可能丢结构节点，默认 false"))
                .put(
                    "includeScreenInfo",
                    new JSONObject()
                        .put("type", "boolean")
                        .put("description", "是否附带屏幕尺寸/密度查询（wm size / wm density），默认 true")))
        .put("required", new org.json.JSONArray());
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    int maxNodes = (int) input.optDouble("maxNodes", DEFAULT_MAX_NODES);
    if (maxNodes <= 0) {
      maxNodes = DEFAULT_MAX_NODES;
    }
    maxNodes = Math.min(maxNodes, MAX_NODES_LIMIT);
    boolean compressed = input.optBoolean("compressed", false);
    boolean includeScreenInfo = input.optBoolean("includeScreenInfo", true);

    if (context != null) {
      context.reportProgress("读取界面节点树");
    }

    StringBuilder out = new StringBuilder();

    // 1) 屏幕尺寸/密度（并入本工具，省一次往返）。
    if (includeScreenInfo) {
      out.append(renderScreenInfo());
    }

    // 2) dump + 解析 + 渲染。
    PhoneUiSelector.Dump dump = PhoneUiSelector.dump(appContext, shellBackends, compressed);
    if (!dump.isOk()) {
      return error("读取界面节点树失败。\n" + dump.error);
    }

    if (!dump.root.packageName.isEmpty()) {
      out.append("[前台窗口] package=").append(dump.root.packageName).append('\n');
    }
    out.append("[节点树] 共 ")
        .append(dump.nodeCount)
        .append(" 个节点，最多显示 ")
        .append(maxNodes)
        .append(" 个：\n");
    out.append(PhoneUiSelector.render(dump.root, maxNodes));

    String text = ToolResult.truncateContent(out.toString());
    return ok(text);
  }

  /** 查询并渲染屏幕尺寸/密度。查询失败时只提示，不阻断节点树。 */
  private String renderScreenInfo() {
    PhoneUiSelector.Exec exec =
        PhoneUiSelector.exec(shellBackends, "wm size; wm density", 8_000L);
    if (!exec.ok) {
      // 无 adb 权限等：此处不直接返回错误，让后面的 dump 给出统一、完整的错误信息。
      return "";
    }

    String stdout = exec.stdout;
    String size = firstMatch(stdout, OVERRIDE_SIZE);
    String physicalSize = firstMatch(stdout, PHYSICAL_SIZE);
    String density = firstMatch(stdout, OVERRIDE_DENSITY);
    String physicalDensity = firstMatch(stdout, PHYSICAL_DENSITY);

    String effectiveSize = size != null ? size : physicalSize;
    String effectiveDensity = density != null ? density : physicalDensity;

    StringBuilder sb = new StringBuilder("[屏幕] ");
    if (effectiveSize != null) {
      sb.append("size=").append(effectiveSize);
      if (size != null && physicalSize != null && !size.equals(physicalSize)) {
        sb.append("（覆盖，物理 ").append(physicalSize).append("）");
      }
    } else {
      sb.append("size=未知");
    }
    sb.append(' ');
    if (effectiveDensity != null) {
      sb.append("density=").append(effectiveDensity);
      try {
        float scale = Integer.parseInt(effectiveDensity) / 160f;
        sb.append(String.format(java.util.Locale.ROOT, " (%.2fx, 1dp=%.2fpx)", scale, scale));
      } catch (RuntimeException ignored) {
        // density 非数字时跳过换算
      }
      if (density != null && physicalDensity != null && !density.equals(physicalDensity)) {
        sb.append("（覆盖，物理 ").append(physicalDensity).append("）");
      }
    } else {
      sb.append("density=未知");
    }
    sb.append('\n');
    return sb.toString();
  }

  private static String firstMatch(String text, Pattern pattern) {
    Matcher matcher = pattern.matcher(text);
    if (matcher.find()) {
      return matcher.groupCount() >= 2 ? matcher.group(1) + "x" + matcher.group(2) : matcher.group(1);
    }
    return null;
  }
}
