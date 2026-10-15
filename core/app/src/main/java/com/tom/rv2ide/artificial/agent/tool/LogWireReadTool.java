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
import com.tom.rv2ide.ai.tool.ToolContext;
import com.tom.rv2ide.ai.tool.api.ToolCategory;
import com.tom.rv2ide.ai.tool.api.ToolDisplayCategory;
import com.tom.rv2ide.ai.tool.api.ToolNames;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import io.github.mohammedbaqernull.logger.model.LogEntry;
import java.util.List;
import org.json.JSONObject;

/**
 * 读取 LogWire 通道的日志（被构建应用主动上报，无需 shell 权限）。
 *
 * <p><b>与 {@code logcat_read} 的分工</b>：
 * <ul>
 *   <li>本工具**不执行任何 shell 命令**，因此不需要 Shizuku/Termux 权限；
 *       代价是只覆盖「开启了 Logsender 且由本 IDE 构建安装」的应用。
 *   <li>{@code logcat_read} 覆盖任意应用（含未集成日志库的、系统应用），
 *       但需要 shell 后端有足够权限。
 * </ul>
 *
 * <p>应用侧日志由 `logger-runtime.aar` 注入的 LogWire 上报（构建时经
 * {@code --init-script} 自动加依赖，见 GradleBuildService）。开关是
 * 「设置 → 开发者选项 → Logsender」。
 *
 * <p><b>实时性</b>：本工具读的是会话开始后累积的缓冲，不是全量历史。连上服务时
 * 应用侧会补发最近 100 行（`-T 100`），更早的内容拿不到——需要全量时用
 * {@code logcat_read}。
 *
 * <p><b>为什么结果必须标注来源</b>：两条通道的格式与覆盖范围不同，模型若不知道
 * 数据来自哪里，会在「日志里没有」时误判「应用没输出」——实际可能只是这条通道
 * 没覆盖到。
 */
public final class LogWireReadTool extends BaseTool {

  /** 默认返回条数。 */
  private static final int DEFAULT_LINES = 200;

  /** 最大返回条数，避免一次拉满上下文。 */
  private static final int MAX_LINES = 2000;

  private final Context appContext;

  public LogWireReadTool(Context context) {
    this.appContext = context.getApplicationContext();
  }

  @Override
  public String getName() {
    return ToolNames.LOGWIRE_READ;
  }

  @Override
  public String getDescription() {
    return "读取 LogWire 通道的应用日志（由本 IDE 构建的应用主动上报，"
        + "**不需要 Shizuku/adb 权限**）。适合调试「我构建并安装的应用」，"
        + "尤其是当前没有 shell 后端权限时。"
        + "与 logcat_read 的区别：本工具只覆盖开启了 Logsender 且由本 IDE 构建的应用，"
        + "且只含会话开始后累积的日志（连上时补发最近 100 行）；"
        + "需要全量历史或读其它应用时用 logcat_read。"
        + "用 logwire_status 可查看开关与连接状态。";
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
        .put(
            "properties",
            new JSONObject()
                .put(
                    "packageName",
                    new JSONObject()
                        .put("type", "string")
                        .put("description", "目标应用包名；省略则返回所有已上报应用"))
                .put(
                    "lines",
                    new JSONObject()
                        .put("type", "number")
                        .put("description", "读取最近多少行，默认 " + DEFAULT_LINES + "，最大 " + MAX_LINES))
                .put(
                    "level",
                    new JSONObject()
                        .put("type", "string")
                        .put("description", "最低日志级别：V/D/I/W/E，默认 V")
                        .put(
                            "enum",
                            new org.json.JSONArray()
                                .put("V")
                                .put("D")
                                .put("I")
                                .put("W")
                                .put("E")))
                .put(
                    "filter",
                    new JSONObject()
                        .put("type", "string")
                        .put("description", "按关键字过滤（大小写不敏感）")))
        .put("required", new org.json.JSONArray());
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    String packageName = input.optString("packageName", "").trim();
    int lines = (int) input.optDouble("lines", DEFAULT_LINES);
    if (lines <= 0) {
      lines = DEFAULT_LINES;
    }
    lines = Math.min(lines, MAX_LINES);

    String level = input.optString("level", "V").trim().toUpperCase();
    if (level.isEmpty()) {
      level = "V";
    }
    String filter = input.optString("filter", "").trim();

    if (context != null) {
      context.reportProgress("读取 LogWire 日志" + (packageName.isEmpty() ? "" : ": " + packageName));
    }

    // 懒绑定：首次查询时连上服务。已连过则 no-op。
    boolean bindRequested = LogWireSession.ensureBound(appContext);
    LogWireSession.Snapshot snapshot =
        LogWireSession.snapshot(packageName.isEmpty() ? null : packageName, lines);

    // 未绑定：给可操作的排查路径，而不是一句「失败」。
    if (!bindRequested || !snapshot.getBound()) {
      StringBuilder sb = new StringBuilder();
      sb.append("(LogWire 通道未连接)\n");
      sb.append("可能原因：\n");
      sb.append("- 「设置 → 开发者选项 → Logsender」未开启；\n");
      sb.append("- 应用不是由本 IDE 构建安装的（Logsender 只对构建时注入日志库的应用生效）；\n");
      sb.append("- 应用从未启动过（日志库在应用启动时才连上来）。\n");
      sb.append("可先执行 logwire_status 查看详细状态，或改用 logcat_read（需要 shell 后端权限）。\n");
      return ok(sb.toString());
    }

    List<LogEntry> entries = snapshot.getEntries();
    List<LogEntry> afterFilter = applyFilters(entries, level, filter);

    if (afterFilter.isEmpty()) {
      StringBuilder sb = new StringBuilder();
      sb.append("(没有匹配的 LogWire 日志)\n");
      // 区分「真没日志」与「过滤掉了」——后者是最常见的误判来源（同 logcat_read）。
      if (!entries.isEmpty()) {
        sb.append("说明：该通道有 ")
            .append(entries.size())
            .append(" 条日志，但没有条目匹配当前过滤条件");
        if (!filter.isEmpty()) {
          sb.append("（filter=\"").append(filter).append("\"）");
        }
        if (!"V".equals(level)) {
          sb.append("（level=").append(level).append("，低于该级别的日志已被排除）");
        }
        sb.append("。可放宽过滤条件后重试。\n");
      } else {
        sb.append("说明：该通道当前没有")
            .append(packageName.isEmpty() ? "任何应用" : "应用 " + packageName)
            .append("的日志。\n");
        if (!snapshot.getPackages().isEmpty()) {
          sb.append("已上报日志的应用：")
              .append(String.join(", ", snapshot.getPackages()))
              .append("\n");
        }
        sb.append("提示：应用需由本 IDE 构建（开启 Logsender）并已启动过，日志才会出现。\n");
      }
      return ok(sb.toString());
    }

    StringBuilder sb = new StringBuilder();
    sb.append("[来源: LogWire");
    if (!packageName.isEmpty()) {
      sb.append(" / ").append(packageName);
    }
    sb.append(" / ").append(afterFilter.size()).append(" 条]\n");
    for (LogEntry entry : afterFilter) {
      sb.append(formatEntry(entry)).append('\n');
    }
    return ok(sb.toString());
  }

  /**
   * 按级别与关键字过滤（供本工具与 {@link LogcatReadTool} 的 LogWire 优先路径共用）。
   *
   * <p>抽成静态方法而不是各自实现：两条路径的过滤语义必须一致——同一次查询
   * 走 LogWire 还是 logcat 只是数据源不同，过滤结果不该有差异。
   */
  static List<LogEntry> applyFilters(List<LogEntry> entries, String level, String filter) {
    int minLevel = levelValue(level);
    List<LogEntry> out = new java.util.ArrayList<>();
    String needle = filter == null ? "" : filter.toLowerCase();
    for (LogEntry entry : entries) {
      // 级别过滤（LogEntry.LogLevel 的 value 越大越严重：V=2..A=7）。
      if (entry.getLevel().getValue() < minLevel) {
        continue;
      }
      // 关键字过滤：匹配 message 或 tag。
      if (needle.isEmpty()
          || (entry.getMessage() != null && entry.getMessage().toLowerCase().contains(needle))
          || (entry.getTag() != null && entry.getTag().toLowerCase().contains(needle))) {
        out.add(entry);
      }
    }
    return out;
  }

  /** 把一条日志格式化成 `HH:mm:ss.SSS L tag: message`（与面板显示风格一致）。 */
  static String formatEntry(LogEntry entry) {
    StringBuilder sb = new StringBuilder();
    sb.append(entry.getFormattedTime())
        .append(' ')
        .append(entry.getLevel().getLabel())
        .append(' ');
    if (entry.getTag() != null && !entry.getTag().isEmpty()) {
      sb.append(entry.getTag()).append(": ");
    }
    sb.append(entry.getMessage() == null ? "" : entry.getMessage());
    return sb.toString();
  }

  /** 级别字母 → 数值阈值（与 {@link LogEntry.LogLevel} 的 value 对应）。 */
  static int levelValue(String level) {
    switch (level) {
      case "V":
        return 2;
      case "D":
        return 3;
      case "I":
        return 4;
      case "W":
        return 5;
      case "E":
        return 6;
      default:
        return 2;
    }
  }
}
