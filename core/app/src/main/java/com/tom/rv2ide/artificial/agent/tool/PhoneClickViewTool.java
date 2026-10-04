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
 * 按选择器定位节点并点击其 bounds 中心。
 *
 * <p>比 {@code phone_click}（纯坐标）更稳：坐标会随屏幕尺寸/密度/布局变化，而
 * resource-id / text / content-desc 是语义锚点。定位逻辑完全复用
 * {@link PhoneUiSelector}（{@code uiautomator dump} → 解析 XML → 匹配 → 取中心），
 * 本类只负责「拿中心 → 发 {@code input tap}」，不重复实现选择器解析。
 *
 * <p><b>找不到节点时明确报错</b>：{@link PhoneUiSelector#locate} 在未匹配/节点不可见时
 * 返回带选择条件描述与「当前可点击节点」提示的错误，这里原样透传给模型，
 * <b>绝不静默点击 (0,0)</b>——那会把一次「定位失败」变成一次「误触左上角」，
 * 后者更难排查，还可能破坏被测应用状态。
 *
 * <p>需要 adb 级权限（Shizuku 后端）；未启用时 {@link PhoneUiSelector#exec} 返回明确错误。
 */
public final class PhoneClickViewTool extends BaseTool {

  /** dump 的超时（定位本身）。 */
  private static final long DUMP_TIMEOUT_MS = PhoneUiSelector.DEFAULT_DUMP_TIMEOUT_MS;

  /** tap 命令的超时。 */
  private static final long TAP_TIMEOUT_MS = 10_000L;

  private final ShellBackendRegistry registry;

  public PhoneClickViewTool(ShellBackendRegistry registry) {
    this.registry = registry;
  }

  @Override
  public String getName() {
    return ToolNames.PHONE_CLICK_VIEW;
  }

  @Override
  public String getDescription() {
    return "按选择器定位界面节点并点击其中心。"
        + "可用 resourceId（如 com.example:id/btn 或裸名 btn）、text、contentDesc 定位，"
        + "多个条件为 AND。默认精确匹配 text/desc；可用 textContains/descContains 做包含匹配。"
        + "同一条件匹配多个节点时用 index 指定第几个（0 起，按深度优先顺序）。"
        + "定位失败会明确报错并列出当前可点击节点，不会点击任何坐标。"
        + "需要 adb 级权限（Shizuku 后端）。";
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
    // 与 shell_execute 同级：最终执行 adb 级命令（input tap），需经确认门。
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
                    "resourceId",
                    new JSONObject()
                        .put("type", "string")
                        .put(
                            "description",
                            "按 resource-id 匹配。支持完整 id（com.example:id/btn）、"
                                + "短 id（:id/btn）或裸名（btn，按后缀匹配）"))
                .put(
                    "text",
                    new JSONObject()
                        .put("type", "string")
                        .put("description", "按节点 text 匹配。默认精确相等；textContains=true 时为包含（大小写不敏感）"))
                .put(
                    "contentDesc",
                    new JSONObject()
                        .put("type", "string")
                        .put("description", "按节点 content-desc 匹配（图标按钮常用）。默认精确相等"))
                .put(
                    "className",
                    new JSONObject()
                        .put("type", "string")
                        .put("description", "按类名匹配，如 android.widget.Button 或短名 Button"))
                .put(
                    "textContains",
                    new JSONObject().put("type", "boolean").put("description", "text 用包含匹配，默认 false"))
                .put(
                    "descContains",
                    new JSONObject().put("type", "boolean").put("description", "contentDesc 用包含匹配，默认 false"))
                .put(
                    "index",
                    new JSONObject()
                        .put("type", "number")
                        .put("description", "取第几个匹配项（0 起，深度优先顺序），默认 0"))
                .put(
                    "clickable",
                    new JSONObject().put("type", "boolean").put("description", "限定节点的 clickable 属性"))
                .put(
                    "compressed",
                    new JSONObject()
                        .put("type", "boolean")
                        .put(
                            "description",
                            "dump 时用 --compressed 减小体积。默认 false（完整树）——"
                                + "压缩会省略既无 text 又无 id 的节点，可能让 className 等条件误判为「找不到」；"
                                + "仅在界面很大且只用 resourceId/text 定位时可设为 true")))
        .put("required", new org.json.JSONArray());
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    PhoneUiSelector.Selector selector = PhoneUiSelector.Selector.fromJson(input);
    if (selector.isEmpty()) {
      return error("未提供定位条件。resourceId / text / contentDesc / className 至少给一个。");
    }

    boolean compressed = input.optBoolean("compressed", false);

    if (context != null) {
      context.reportProgress("定位节点: " + selector.describe());
    }

    PhoneUiSelector.Locate locate = PhoneUiSelector.locate(registry, selector, compressed, DUMP_TIMEOUT_MS);
    if (!locate.isOk()) {
      // 定位失败必须如实报错，绝不回退到点击 (0,0)。
      return error(locate.error);
    }

    int[] center = locate.center;
    int x = center[0];
    int y = center[1];

    PhoneUiSelector.Exec exec =
        PhoneUiSelector.exec(registry, "input tap " + x + " " + y, TAP_TIMEOUT_MS);
    if (!exec.ok) {
      return error(exec.error);
    }
    return ok(
        "已点击节点 "
            + locate.node.describe()
            + "\n中心坐标 ("
            + x
            + ", "
            + y
            + ")。");
  }
}
