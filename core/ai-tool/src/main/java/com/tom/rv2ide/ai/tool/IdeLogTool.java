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
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import org.json.JSONObject;

/**
 * 读取 IDE 自身日志（协议层/工具层被吞掉的错误都汇入这里）。
 *
 * <p><b>与 {@code logcat_read} 的分工</b>：{@code logcat_read} 读的是**被测应用**的日志
 * （按包名/crash buffer），用于验证「我改的 app 有没有正常跑」；本工具读的是**ACS 自己**
 * 的日志，用于验证「AI 助手这套工具链内部有没有出错」。两者目标进程不同，不能互相替代。
 *
 * <p>纯 Java：日志内容由 {@link IdeLogSource} 端口提供，本类只做过滤与格式化，
 * 因此可在 JVM 上单测。
 */
public final class IdeLogTool extends BaseTool {

  /** 默认返回条数。 */
  static final int DEFAULT_LIMIT = 100;

  /** 最大返回条数，避免一次拉满上下文。 */
  static final int MAX_LIMIT = 1000;

  /** 单条异常堆栈最多附带的字符数。 */
  static final int MAX_THROWABLE_CHARS = 1200;

  /** 缺省只回 warning 及以上：ERROR/WARN 才是排查关注点，INFO 以下噪音大。 */
  static final int DEFAULT_MIN_LEVEL = IdeLogSource.LEVEL_WARN;

  private static final String TIME_PATTERN = "HH:mm:ss.SSS";

  @Override
  public String getName() {
    return ToolNames.IDE_LOG_READ;
  }

  @Override
  public String getDescription() {
    return "读取 IDE（本应用）自身的运行日志，用于排查 AI 助手工具链内部的失败"
        + "（协议层/工具层异常、语言服务器报错等）。"
        + "参数：level（最低级别 error/warn/info/debug/trace，默认 warn）、"
        + "logger（按 logger 名过滤，如 ai / ProtocolError）、"
        + "filter（按消息文本过滤）、lines（返回条数，默认 " + DEFAULT_LIMIT + "）。"
        + "注意：读被测应用的日志请用 logcat_read，本工具只读本应用。";
  }

  @Override
  public ToolCategory getCategory() {
    return ToolCategory.READ;
  }

  @Override
  public ToolDisplayCategory getDisplayCategory() {
    return ToolDisplayCategory.GENERIC;
  }

  @Override
  public boolean isAllowedInReadonlyMode() {
    return true;
  }

  @Override
  public boolean isConcurrencySafe() {
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
                    "level",
                    new JSONObject()
                        .put("type", "string")
                        .put("description", "最低级别：error / warn / info / debug / trace，默认 warn"))
                .put(
                    "logger",
                    new JSONObject()
                        .put("type", "string")
                        .put("description", "按 logger 名过滤（子串，忽略大小写），如 ai、Protocol"))
                .put(
                    "filter",
                    new JSONObject()
                        .put("type", "string")
                        .put("description", "按消息文本过滤（子串，忽略大小写）"))
                .put(
                    "lines",
                    new JSONObject()
                        .put("type", "integer")
                        .put("description", "返回条数，默认 " + DEFAULT_LIMIT + "，最大 " + MAX_LIMIT)));
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    IdeLogSource source = context == null ? null : context.getIdeLog();
    if (source == null) {
      return error("当前环境未接入 IDE 日志源，无法读取本应用日志。");
    }
    if (!source.isAvailable()) {
      String reason = source.unavailableReason();
      return error("IDE 日志不可用" + (reason.isEmpty() ? "。" : "：" + reason + "。"));
    }

    int minLevel = parseLevel(input.optString("level", "warn"));
    String loggerKeyword = input.optString("logger", "").trim();
    String textKeyword = input.optString("filter", "").trim();
    int limit = clamp(input.optInt("lines", DEFAULT_LIMIT), 1, MAX_LIMIT);

    List<IdeLogSource.Entry> entries =
        source.read(limit, minLevel, loggerKeyword, textKeyword);
    if (entries == null || entries.isEmpty()) {
      StringBuilder sb = new StringBuilder();
      sb.append("没有匹配的日志（level≥").append(levelName(minLevel));
      if (!loggerKeyword.isEmpty()) {
        sb.append(", logger~").append(loggerKeyword);
      }
      if (!textKeyword.isEmpty()) {
        sb.append(", filter~").append(textKeyword);
      }
      sb.append("）。可能是过滤器过严，或该级别以上确实没有日志。");
      return ok(sb.toString());
    }

    SimpleDateFormat format = new SimpleDateFormat(TIME_PATTERN, Locale.US);
    StringBuilder sb = new StringBuilder();
    sb.append("本应用日志，最近 ")
        .append(entries.size())
        .append(" 条（level≥")
        .append(levelName(minLevel))
        .append("）：\n\n");
    for (IdeLogSource.Entry entry : entries) {
      sb.append(format.format(new Date(entry.getTimestampMs())))
          .append(' ')
          .append(String.format(Locale.US, "%5s", entry.levelName()))
          .append(' ')
          .append(entry.getLogger())
          .append(": ")
          .append(entry.getMessage())
          .append('\n');
      if (entry.hasThrowable()) {
        sb.append(indentThrowable(entry.getThrowable()));
      }
    }
    return ok(ToolResult.truncateContent(sb.toString()));
  }

  /** 异常堆栈按 4 空格缩进，超长时截断——堆栈前几帧通常已足够定位。 */
  static String indentThrowable(String throwable) {
    String text = throwable;
    if (text.length() > MAX_THROWABLE_CHARS) {
      text = text.substring(0, MAX_THROWABLE_CHARS) + "\n… (堆栈已截断)";
    }
    StringBuilder sb = new StringBuilder();
    for (String line : text.split("\\r?\\n", -1)) {
      if (line.isEmpty()) {
        continue;
      }
      sb.append("    ").append(line).append('\n');
    }
    return sb.toString();
  }

  /** error=1, warn=2, info=3, debug=4, trace=5；无法识别时回落到 warn。 */
  static int parseLevel(String raw) {
    if (raw == null) {
      return DEFAULT_MIN_LEVEL;
    }
    switch (raw.trim().toLowerCase(Locale.US)) {
      case "error":
      case "e":
        return IdeLogSource.LEVEL_ERROR;
      case "info":
      case "i":
        return IdeLogSource.LEVEL_INFO;
      case "debug":
      case "d":
        return IdeLogSource.LEVEL_DEBUG;
      case "trace":
      case "t":
        return IdeLogSource.LEVEL_TRACE;
      case "warn":
      case "warning":
      case "w":
      default:
        return IdeLogSource.LEVEL_WARN;
    }
  }

  static String levelName(int level) {
    switch (level) {
      case IdeLogSource.LEVEL_ERROR:
        return "ERROR";
      case IdeLogSource.LEVEL_INFO:
        return "INFO";
      case IdeLogSource.LEVEL_DEBUG:
        return "DEBUG";
      case IdeLogSource.LEVEL_TRACE:
        return "TRACE";
      case IdeLogSource.LEVEL_WARN:
      default:
        return "WARN";
    }
  }

  private static int clamp(int value, int min, int max) {
    return Math.max(min, Math.min(max, value));
  }
}
