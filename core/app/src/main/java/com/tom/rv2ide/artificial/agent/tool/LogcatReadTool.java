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

import android.content.Context;
import com.tom.rv2ide.ai.tool.BaseTool;
import com.tom.rv2ide.ai.tool.ToolContext;
import com.tom.rv2ide.ai.tool.api.ToolCategory;
import com.tom.rv2ide.ai.tool.api.ToolDisplayCategory;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 读取应用日志。
 *
 * <p>「运行 app 测试」闭环的第四步：改代码 → 构建 → 安装 → 启动 → <b>读日志</b> → 修。
 *
 * <p><b>为什么不用 IDE 已有的日志设施</b>：
 * <ul>
 *   <li>{@code IDELogcatReader} 只读 IDE 自身进程（{@code --pid=self}），看不到被测应用
 *   <li>{@code LogReceiverService} 是流式推送且单消费者互斥，会和日志面板抢通道，
 *       还要求用户开启开关并让被测应用集成 logwire 库
 * </ul>
 * 因此这里直接调用系统 {@code logcat -d}：dump 后即退出，天然是「取一段」语义，
 * 且不侵入用户项目、不与任何 UI 竞争。
 *
 * <p><b>权限说明</b>：Android 4.1+ 起普通应用只能读取自身进程的日志。
 * 要读取其它应用的日志需要 adb 级权限（uid 2000 或 root），因此本工具需要
 * 走 {@code shell_execute} 的 Shizuku 后端；无该权限时 logcat 会返回空。
 * 结果里会说明这一点，避免模型误判「应用没有输出日志」。
 */
public final class LogcatReadTool extends BaseTool {

  private static final Logger log = LoggerFactory.getLogger(LogcatReadTool.class);

  /** 默认读取行数。 */
  private static final int DEFAULT_LINES = 200;

  /** 最大读取行数，避免一次拉取过多内容撑爆上下文。 */
  private static final int MAX_LINES = 2000;

  /** 命令执行超时。 */
  private static final long TIMEOUT_MS = 15_000L;

  private final Context appContext;

  public LogcatReadTool(Context context) {
    this.appContext = context.getApplicationContext();
  }

  @Override
  public String getName() {
    return "logcat_read";
  }

  @Override
  public String getDescription() {
    return "读取本机日志（logcat），可按包名与级别过滤。"
        + "用于查看刚启动的应用输出、排查崩溃。"
        + "注意：读取其它应用的日志需要 adb 级权限（Shizuku 后端）；"
        + "权限不足时会返回空结果并说明原因。";
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
                        .put("description", "按应用包名过滤；省略则读取全部日志"))
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
    String packageName = input.optString("packageName", "").trim();

    if (context != null) {
      context.reportProgress("读取日志" + (packageName.isEmpty() ? "" : ": " + packageName));
    }

    List<String> command = new ArrayList<>();
    command.add("logcat");
    // -d: dump 后退出（不是流式跟随），这正是「取一段」需要的行为
    command.add("-d");
    command.add("-v");
    command.add("threadtime");
    command.add("-t");
    command.add(String.valueOf(lines));
    if (!level.isEmpty() && !"V".equals(level)) {
      command.add("*:" + level);
    }

    String pid = packageName.isEmpty() ? null : findPid(packageName);
    if (packageName != null && !packageName.isEmpty()) {
      if (pid == null) {
        return error("应用 " + packageName + " 没有正在运行的进程，无法按 PID 过滤日志。请先启动它。");
      }
      command.add("--pid=" + pid);
    }

    Process process = null;
    try {
      process = new ProcessBuilder(command).redirectErrorStream(true).start();
      StringBuilder output = new StringBuilder();
      try (InputStream in = process.getInputStream();
          BufferedReader reader =
              new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          if (filter.isEmpty() || line.toLowerCase().contains(filter.toLowerCase())) {
            output.append(line).append('\n');
          }
        }
      }

      if (!process.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
        process.destroyForcibly();
        return error("读取日志超时");
      }

      String text = output.toString();
      if (text.trim().isEmpty()) {
        StringBuilder sb = new StringBuilder();
        sb.append("(没有匹配的日志)\n");
        if (!packageName.isEmpty()) {
          sb.append("提示：读取其它应用的日志需要 adb 级权限（Shizuku）。");
          sb.append("若当前 shell 后端是 Termux，普通应用只能读取自身进程的日志。\n");
        }
        return ok(sb.toString());
      }

      StringBuilder sb = new StringBuilder();
      if (!packageName.isEmpty()) {
        sb.append("[应用: ").append(packageName).append(" (pid ").append(pid).append(")]\n");
      }
      sb.append(text);
      return ok(sb.toString());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return error("读取日志被中断");
    } catch (Exception e) {
      log.warn("读取 logcat 失败", e);
      return error("读取日志失败: " + e.getMessage());
    } finally {
      if (process != null) {
        process.destroy();
      }
    }
  }

  /**
   * 按包名查找进程 PID。
   *
   * <p>Android 没有公开 API 做这个映射，因此调用 {@code pidof}。
   * 找不到时返回 null——调用方据此给出明确提示，而不是返回一个空日志列表。
   */
  private String findPid(String packageName) {
    Process process = null;
    try {
      process = new ProcessBuilder("pidof", packageName).redirectErrorStream(true).start();
      try (BufferedReader reader =
          new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
        String line = reader.readLine();
        if (line != null && !line.trim().isEmpty()) {
          // pidof 可能返回多个 pid（多进程应用），取第一个
          return line.trim().split("\\s+")[0];
        }
      }
      process.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (Exception e) {
      log.debug("查找 pid 失败: {}", packageName, e);
    } finally {
      if (process != null) {
        process.destroy();
      }
    }
    return null;
  }
}
