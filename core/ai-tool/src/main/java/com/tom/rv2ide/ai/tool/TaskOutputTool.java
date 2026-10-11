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

package com.tom.rv2ide.ai.tool;

import com.tom.rv2ide.ai.tool.BackgroundShellPort.TaskStatus;
import com.tom.rv2ide.ai.tool.api.ToolCategory;
import com.tom.rv2ide.ai.tool.api.ToolDisplayCategory;
import com.tom.rv2ide.ai.tool.api.ToolNames;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import org.json.JSONObject;

/**
 * 取回后台任务（{@code shell_execute} 的 {@code run_in_background}）的输出与状态。
 *
 * <p><b>语义照 cc-haha 的 TaskOutputTool</b>：
 * <ul>
 *   <li>{@code block=true}（默认）等待任务结束，最多 {@code timeout} 毫秒；
 *       等到了返回终态与输出，没等到返回 {@code retrieval_status=timeout} + 当前状态；
 *   <li>{@code block=false} 非阻塞看一眼当前状态与已有输出，不等。
 * </ul>
 *
 * <p><b>输出从文件读，不从内存</b>：后台命令的输出写在文件里（见
 * {@link BackgroundShellPort#start}），因此这里读的是文件尾部而非全量——一条输出
 * 几 MB 的命令不该把上下文撑爆。要完整输出时模型可以用 {@code file_read} 按路径读。
 *
 * <p><b>为什么返回状态而不是只返回文本</b>：模型需要区分「还在跑」「跑完了成功」
 * 「跑完了失败」三种情况，它们的下一步动作完全不同（继续等 / 读结果 / 查错误）。
 * 只给一段输出文本会让它无从判断。
 */
public final class TaskOutputTool extends BaseTool {

  /** 返回给模型的输出上限：16KB。超出时保留首尾。 */
  private static final int MAX_OUTPUT_CHARS = 16 * 1024;

  /** 默认阻塞等待上限：30 秒，与 cc-haha 一致。 */
  private static final long DEFAULT_TIMEOUT_MS = 30_000L;

  /** 阻塞等待上限的最大值：10 分钟。 */
  private static final long MAX_TIMEOUT_MS = 10 * 60 * 1000L;

  /** 非阻塞查看时读取输出文件的最大字节数。 */
  private static final int MAX_READ_BYTES = 64 * 1024;

  @Override
  public String getName() {
    return ToolNames.TASK_OUTPUT;
  }

  @Override
  public String getDescription() {
    return "取回后台任务（shell_execute 带 run_in_background 启动）的输出与状态。"
        + "默认阻塞等待任务结束（最多 30 秒），也可用 block=false 非阻塞查看当前进度。"
        + "通常不需要主动调用——任务完成时会自动收到通知。";
  }

  @Override
  public ToolCategory getCategory() {
    // 只读查询：不产生副作用，只读模式下也应放行。
    return ToolCategory.READ;
  }

  @Override
  public ToolDisplayCategory getDisplayCategory() {
    return ToolDisplayCategory.SHELL;
  }

  @Override
  public boolean isConcurrencySafe() {
    // 只读查询任务状态，不产生副作用，可与其它工具并发。
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
                    "task_id",
                    new JSONObject()
                        .put("type", "string")
                        .put("description", "后台任务的 id（shell_execute 返回的 task id）"))
                .put(
                    "block",
                    new JSONObject()
                        .put("type", "boolean")
                        .put(
                            "description",
                            "是否等待任务结束。默认 true：等到结束或超时才返回；"
                                + "false：立即返回当前状态与已有输出，不等。"))
                .put(
                    "timeout",
                    new JSONObject()
                        .put("type", "number")
                        .put(
                            "description",
                            "block=true 时的最长等待毫秒数，默认 30000，最大 600000。")))
        .put("required", new org.json.JSONArray().put("task_id"));
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    BackgroundShellPort port = context == null ? null : context.getBackgroundShell();
    if (port == null) {
      return error("后台任务功能未接入。");
    }
    String taskId = input.optString("task_id", "").trim();
    if (taskId.isEmpty()) {
      return error("task_id 不能为空");
    }

    boolean block = input.optBoolean("block", true);
    long timeout = (long) input.optDouble("timeout", DEFAULT_TIMEOUT_MS);
    if (timeout <= 0) {
      timeout = DEFAULT_TIMEOUT_MS;
    }
    timeout = Math.min(timeout, MAX_TIMEOUT_MS);

    TaskStatus status = block ? port.await(taskId, timeout) : port.status(taskId);
    if (status == null || status.getState() == BackgroundShellPort.State.UNKNOWN) {
      return error(
          "找不到任务 " + taskId + "。它可能已完成并被清理，或不属于当前进程。"
              + "若你持有任务返回的输出文件路径，可直接用 file_read 读取。");
    }

    String retrieval =
        block && status.isRunning() ? "timeout" : (status.isFinished() ? "success" : "not_ready");

    StringBuilder sb = new StringBuilder();
    sb.append("task_id: ").append(status.getTaskId()).append('\n');
    sb.append("command: ").append(abbreviate(status.getCommand(), 200)).append('\n');
    sb.append("state: ").append(stateName(status.getState())).append('\n');
    sb.append("retrieval_status: ").append(retrieval).append('\n');
    if (status.hasExitCode()) {
      sb.append("exit_code: ").append(status.getExitCode()).append('\n');
    }
    if (!status.getError().isEmpty()) {
      sb.append("error: ").append(status.getError()).append('\n');
    }
    if (!status.getOutputFilePath().isEmpty()) {
      sb.append("output_file: ").append(status.getOutputFilePath()).append('\n');
    }

    String output = readOutputTail(status.getOutputFilePath());
    if (!output.isEmpty()) {
      sb.append("--- output ---\n").append(truncate(output));
    } else if (status.isRunning()) {
      sb.append("(尚无输出)");
    } else {
      sb.append("(无输出)");
    }

    return ok(sb.toString());
  }

  /** 读取输出文件（尾部片段）；文件不存在或读取失败时返回空串。 */
  private static String readOutputTail(String path) {
    if (path == null || path.isEmpty()) {
      return "";
    }
    java.io.File file = new java.io.File(path);
    if (!file.exists() || !file.isFile() || file.length() == 0) {
      return "";
    }
    try {
      long length = file.length();
      long start = Math.max(0, length - MAX_READ_BYTES);
      byte[] buffer = new byte[(int) Math.min(length, MAX_READ_BYTES)];
      try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r")) {
        raf.seek(start);
        int read = raf.read(buffer);
        if (read <= 0) {
          return "";
        }
        String text = new String(buffer, 0, read, java.nio.charset.StandardCharsets.UTF_8);
        // 截断到文件中部时首行可能是半行，丢掉它避免出现乱码行。
        if (start > 0) {
          int newline = text.indexOf('\n');
          if (newline >= 0) {
            text = text.substring(newline + 1);
          }
        }
        return text;
      }
    } catch (Exception e) {
      return "";
    }
  }

  /** 中间截断：保留首尾，中间标注省略量。 */
  static String truncate(String text) {
    if (text == null || text.length() <= MAX_OUTPUT_CHARS) {
      return text == null ? "" : text;
    }
    int half = MAX_OUTPUT_CHARS / 2;
    int omitted = text.length() - MAX_OUTPUT_CHARS;
    return text.substring(0, half)
        + "\n…（省略 " + omitted + " 字符）…\n"
        + text.substring(text.length() - half);
  }

  private static String stateName(BackgroundShellPort.State state) {
    switch (state) {
      case RUNNING:
        return "running";
      case DONE:
        return "completed";
      case FAILED:
        return "failed";
      case STOPPED:
        return "stopped";
      default:
        return "unknown";
    }
  }

  private static String abbreviate(String value, int max) {
    if (value == null) {
      return "";
    }
    return value.length() <= max ? value : value.substring(0, max) + "…";
  }
}
