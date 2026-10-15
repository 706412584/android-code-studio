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

import android.content.Context;
import com.tom.rv2ide.ai.tool.BaseTool;
import com.tom.rv2ide.ai.tool.ShellBackendRegistry;
import com.tom.rv2ide.ai.tool.ToolContext;
import com.tom.rv2ide.ai.tool.api.ToolCategory;
import com.tom.rv2ide.ai.tool.api.ToolDisplayCategory;
import com.tom.rv2ide.ai.tool.api.ToolNames;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import com.tom.rv2ide.preferences.internal.DevOpsPreferences;
import java.util.List;
import org.json.JSONObject;

/**
 * 查询 LogWire 通道与日志相关开关的状态（只读，免确认）。
 *
 * <p>报告四件事：
 * <ol>
 *   <li>Logsender 开关是否开启（决定后续构建是否注入日志库）
 *   <li>LogWire 会话是否已连接、已收到哪些应用的日志
 *   <li>shell 后端是否可用（决定 {@code logcat_read} 这条路通不通）
 *   <li>当前该用哪条通道的建议（把「两条路都不通」这种最需要解释的状态说清楚）
 * </ol>
 *
 * <p><b>为什么写开关不在这里</b>：权限层按工具分级（{@code needsConfirmation()}
 * 无参数），混在一个工具里会让只读查询也弹确认框。与 {@code git}/{@code git_write}
 * 同样的拆分理由——写开关见 {@link LogWireSetEnabledTool}。
 */
public final class LogWireStatusTool extends BaseTool {

  private final Context appContext;
  private final ShellBackendRegistry shellBackends;

  public LogWireStatusTool(Context context, ShellBackendRegistry shellBackends) {
    this.appContext = context.getApplicationContext();
    this.shellBackends = shellBackends;
  }

  @Override
  public String getName() {
    return ToolNames.LOGWIRE_STATUS;
  }

  @Override
  public String getDescription() {
    return "查询日志通道状态：Logsender 开关是否开启、LogWire 是否已连接、"
        + "已上报日志的应用列表、shell 后端（logcat_read 所需）是否可用，"
        + "并给出「该用哪条通道」的建议。只读，不改变任何设置。"
        + "要修改 Logsender 开关请用 logwire_set_enabled。";
  }

  @Override
  public ToolCategory getCategory() {
    return ToolCategory.READ;
  }

  @Override
  public ToolDisplayCategory getDisplayCategory() {
    return ToolDisplayCategory.READ;
  }

  @Override
  public boolean isAllowedInReadonlyMode() {
    return true;
  }

  @Override
  public JSONObject getParameters() throws org.json.JSONException {
    return new JSONObject()
        .put("type", "object")
        .put("properties", new JSONObject())
        .put("required", new org.json.JSONArray());
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    if (context != null) {
      context.reportProgress("查询日志通道状态");
    }

    boolean logsenderEnabled = DevOpsPreferences.INSTANCE.getLogsenderEnabled();
    // 顺带触发绑定：让状态查询同时反映「现在能不能连上」，
    // 否则用户看到「未连接」也不知道是没开还是没连。
    LogWireSession.ensureBound(appContext);
    LogWireSession.Snapshot snapshot = LogWireSession.snapshot(null, 0);
    List<String> packages = snapshot.getPackages();
    boolean connected = snapshot.getBound();
    boolean shellAvailable = shellBackends != null && !shellBackends.available().isEmpty();

    StringBuilder sb = new StringBuilder();
    sb.append("== 日志通道状态 ==\n");
    sb.append("Logsender 开关: ").append(logsenderEnabled ? "已开启" : "未开启").append('\n');
    sb.append("LogWire 会话: ").append(connected ? "已连接" : "未连接").append('\n');
    sb.append("已上报日志的应用: ");
    if (packages.isEmpty()) {
      sb.append("(无)\n");
    } else {
      sb.append(String.join(", ", packages)).append('\n');
    }
    sb.append("shell 后端(logcat_read 用): ").append(shellAvailable ? "可用" : "不可用").append('\n');

    sb.append("\n== 建议 ==\n");
    if (connected && !packages.isEmpty()) {
      sb.append("用 logwire_read 读取应用日志（无需权限）。\n");
    }
    if (shellAvailable) {
      sb.append("logcat_read 也可用（覆盖任意应用，含未集成日志库的）。\n");
    }
    if (!logsenderEnabled && !shellAvailable) {
      // 最需要解释的组合：两条路都不通。给出明确的两条修复路径。
      sb.append("两条通道都不可用。可选：\n");
      sb.append("- 用 logwire_set_enabled 开启 Logsender 后**重新构建安装**目标应用；\n");
      sb.append("- 或在「设置 → AI 助手 → Shell 后端」配置 Shizuku/Termux 以启用 logcat_read。\n");
    } else if (!logsenderEnabled) {
      sb.append("如需在无 shell 权限时也能读日志，可用 logwire_set_enabled 开启 Logsender"
          + "（注意：只对之后重新构建安装的应用生效）。\n");
    } else if (packages.isEmpty()) {
      sb.append("Logsender 已开但尚无应用上报：确认目标应用是开启开关后**重新构建安装**的，"
          + "且已启动过。\n");
    }
    return ok(sb.toString());
  }
}
