/*
 * This file is part of AndroidCodeStudio.
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

import com.tom.rv2ide.ai.tool.BaseTool;
import com.tom.rv2ide.ai.tool.ToolContext;
import com.tom.rv2ide.ai.tool.api.ToolCategory;
import com.tom.rv2ide.ai.tool.api.ToolDisplayCategory;
import com.tom.rv2ide.ai.tool.api.ToolNames;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import com.tom.rv2ide.preferences.internal.DevOpsPreferences;
import org.json.JSONObject;

/**
 * 开关 Logsender（开发者选项里的日志通道总开关）。
 *
 * <p><b>为什么单独成工具</b>：权限层按工具分级（{@code needsConfirmation()} 无参数），
 * 与只读查询混在一起会让每次状态查询都弹确认框。同 {@code git}/{@code git_write} 的拆分。
 *
 * <p><b>为什么这个开关需要确认</b>：它改变的是**后续所有构建的产物**——开启后
 * 每个用户项目都会被注入 `logger-runtime.aar` 与 desugaring 配置
 * （见 GradleBuildService 的 init-script 注入）。这是对用户工程行为的持续影响，
 * 不只是「读一次日志」的临时动作，因此不能静默写入。
 *
 * <p><b>变更只对后续构建生效</b>：注入发生在构建时，已安装的应用不会自动获得
 * 日志库——必须重新构建并安装。结果里必须说清这一点，否则用户会以为「开了开关
 * 却还是看不到日志」是功能坏了。
 */
public final class LogWireSetEnabledTool extends BaseTool {

  public LogWireSetEnabledTool() {}

  @Override
  public String getName() {
    return ToolNames.LOGWIRE_SET_ENABLED;
  }

  @Override
  public String getDescription() {
    return "开启或关闭 Logsender（「设置 → 开发者选项 → Logsender」）。"
        + "开启后，本 IDE 构建的应用会自动注入日志库，可用 logwire_read 读取其日志"
        + "（无需 Shizuku/adb 权限）。**变更只对之后重新构建安装的应用生效**，"
        + "已安装的应用不受影响。用 logwire_status 查看当前状态。";
  }

  @Override
  public ToolCategory getCategory() {
    return ToolCategory.WRITE;
  }

  @Override
  public ToolDisplayCategory getDisplayCategory() {
    return ToolDisplayCategory.READ;
  }

  @Override
  public boolean needsConfirmation() {
    // 改变后续构建产物，属持续影响，需逐次确认。
    return true;
  }

  @Override
  public boolean isAllowedInReadonlyMode() {
    // 写设置，只读模式不放行。
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
                    "enabled",
                    new JSONObject()
                        .put("type", "boolean")
                        .put("description", "true 开启、false 关闭")))
        .put("required", new org.json.JSONArray().put("enabled"));
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    if (!input.has("enabled")) {
      return error("缺少 enabled 参数（true 开启 / false 关闭）。");
    }
    boolean enabled = input.optBoolean("enabled", false);

    if (context != null) {
      context.reportProgress((enabled ? "开启" : "关闭") + " Logsender");
    }

    boolean previous = DevOpsPreferences.INSTANCE.getLogsenderEnabled();
    DevOpsPreferences.INSTANCE.setLogsenderEnabled(enabled);

    StringBuilder sb = new StringBuilder();
    if (previous == enabled) {
      sb.append("Logsender 已是").append(enabled ? "开启" : "关闭").append("状态，无需变更。\n");
      return ok(sb.toString());
    }

    sb.append("Logsender 已").append(enabled ? "开启" : "关闭").append("。\n");
    if (enabled) {
      sb.append("生效范围：**之后重新构建安装**的应用。已安装的应用不会自动获得日志库——")
          .append("如需读取某个应用的日志，请重新构建并安装它。\n");
      sb.append("之后可用 logwire_read 读取其日志（无需 shell 权限）。\n");
    } else {
      sb.append("之后构建的应用不再注入日志库；已安装应用中的日志库不受影响"
          + "（仍会继续上报，直到应用被重新构建替换）。\n");
    }
    return ok(sb.toString());
  }
}
