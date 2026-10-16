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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.tom.rv2ide.ai.tool.BackgroundShellPort.State;
import com.tom.rv2ide.ai.tool.BackgroundShellPort.TaskStatus;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * 后台命令执行（run_in_background / task_output / task_stop）的行为。
 *
 * <p><b>为什么需要这组测试</b>：后台执行的价值全在「立即返回、不阻塞循环」这一点上。
 * 如果 run_in_background 退化成同步执行，模型会以为命令在后台跑、实际却卡死整个循环——
 * 这是最有害的失败形态（静默、且与预期完全相反）。这里用假 port 钉住三个契约：
 * <ul>
 *   <li>run_in_background 走端口启动，**不**调用同步后端；
 *   <li>端口未注入时明确报错，而不是退回同步执行；
 *   <li>task_output 区分「还在跑」「跑完了成功」「跑完了失败」，输出从文件读。
 * </ul>
 */
final class BackgroundToolsTest {

  /** 假端口：记录 start 调用，返回可配置的状态。 */
  private static final class FakePort implements BackgroundShellPort {
    final List<String> startedCommands = new ArrayList<>();
    String nextTaskId = "task-1";
    State state = State.RUNNING;
    int exitCode = -1;
    boolean hasExitCode = false;
    String outputFilePath = "";
    String error = "";
    boolean available = true;
    final List<String> stopped = new ArrayList<>();
    long lastAwaitTimeout = -1;

    @Override
    public TaskStatus start(String command, String cwd, String backendId, long timeoutMs) {
      startedCommands.add(command);
      return new TaskStatus(
          nextTaskId, command, state, exitCode, hasExitCode, outputFilePath, 1L,
          state == State.DONE || state == State.FAILED || state == State.STOPPED ? 2L : 0L, error);
    }

    @Override
    public TaskStatus status(String taskId) {
      if (!"task-1".equals(taskId)) {
        return new TaskStatus(taskId, "", State.UNKNOWN, -1, false, "", 0L, 0L, "");
      }
      return new TaskStatus(
          taskId, "cmd", state, exitCode, hasExitCode, outputFilePath, 1L,
          state == State.DONE || state == State.FAILED || state == State.STOPPED ? 2L : 0L, error);
    }

    @Override
    public TaskStatus await(String taskId, long timeoutMs) {
      lastAwaitTimeout = timeoutMs;
      return status(taskId);
    }

    @Override
    public boolean stop(String taskId) {
      stopped.add(taskId);
      return true;
    }

    @Override
    public boolean isAvailable() {
      return available;
    }

    @Override
    public String unavailableReason() {
      return available ? "" : "测试用：无可用后端";
    }
  }

  private static ToolContext contextWith(BackgroundShellPort port) {
    return ToolContext.builder()
        .homePath(System.getProperty("java.io.tmpdir"))
        .backgroundShell(port)
        .build();
  }

  // ---- shell_execute 的 run_in_background ----

  @Test
  void runInBackgroundStartsViaPortAndDoesNotBlockOnSyncBackend() {
    FakePort port = new FakePort();
    port.outputFilePath = "/tmp/out.log";
    // 同步后端注册为「可用但一执行就抛异常」——用它证明后台路径没碰它。
    ShellBackendRegistry registry = new ShellBackendRegistry();
    registry.register(new NeverExecutedBackend());

    JSONObject args =
        new JSONObject().put("command", "sleep 100").put("run_in_background", true);
    ToolResult result = new ShellExecuteTool(registry).execute(args, contextWith(port));

    assertFalse(result.isError(), "后台启动应成功: " + result.getContent());
    assertEquals(1, port.startedCommands.size(), "端口应被调用一次");
    assertEquals("sleep 100", port.startedCommands.get(0));
    assertTrue(result.getContent().contains("task-1"), "应返回任务 id: " + result.getContent());
    assertTrue(
        result.getContent().contains("/tmp/out.log"),
        "应返回输出文件路径: " + result.getContent());
    assertTrue(
        result.getContent().contains("不需要轮询"),
        "应说明完成时会通知、无需轮询: " + result.getContent());
  }

  @Test
  void runInBackgroundWithoutPortFailsLoudlyInsteadOfRunningSynchronously() {
    // 端口未注入时必须明确报错——退化成同步执行是最有害的静默失败。
    ShellBackendRegistry registry = new ShellBackendRegistry();
    registry.register(new NeverExecutedBackend());

    JSONObject args =
        new JSONObject().put("command", "sleep 100").put("run_in_background", true);
    ToolResult result = new ShellExecuteTool(registry).execute(args, contextWith(null));

    assertTrue(result.isError(), "端口缺失应报错");
    assertTrue(
        result.getContent().contains("后台执行未接入"),
        "错误应说明未接入: " + result.getContent());
  }

  @Test
  void runInBackgroundReportsStartFailure() {
    FakePort port = new FakePort();
    port.state = State.FAILED;
    port.error = "无法创建输出文件";
    // 需要一个可用后端，否则会在后端解析处就失败、走不到后台分支。
    ShellBackendRegistry registry = new ShellBackendRegistry();
    registry.register(new NeverExecutedBackend());

    JSONObject args = new JSONObject().put("command", "x").put("run_in_background", true);
    ToolResult result = new ShellExecuteTool(registry).execute(args, contextWith(port));

    assertTrue(result.isError(), "启动失败应报错");
    assertTrue(result.getContent().contains("无法创建输出文件"), result.getContent());
  }

  // ---- task_output ----

  @Test
  void taskOutputReadsOutputFromFileAndReportsCompletion() throws Exception {
    File out = File.createTempFile("bgtask", ".log");
    out.deleteOnExit();
    Files.write(out.toPath(), "build ok\n".getBytes(StandardCharsets.UTF_8));

    FakePort port = new FakePort();
    port.state = State.DONE;
    port.exitCode = 0;
    port.hasExitCode = true;
    port.outputFilePath = out.getAbsolutePath();

    ToolResult result =
        new TaskOutputTool().execute(new JSONObject().put("task_id", "task-1"), contextWith(port));

    assertFalse(result.isError(), result.getContent());
    assertTrue(result.getContent().contains("state: completed"), result.getContent());
    assertTrue(result.getContent().contains("exit_code: 0"), result.getContent());
    assertTrue(result.getContent().contains("build ok"), "应包含文件里的输出: " + result.getContent());
  }

  @Test
  void taskOutputBlockingTimeoutReportsStillRunning() {
    FakePort port = new FakePort();
    port.state = State.RUNNING;

    ToolResult result =
        new TaskOutputTool()
            .execute(
                new JSONObject().put("task_id", "task-1").put("block", true).put("timeout", 5000),
                contextWith(port));

    assertFalse(result.isError(), result.getContent());
    assertTrue(result.getContent().contains("retrieval_status: timeout"), result.getContent());
    assertTrue(result.getContent().contains("state: running"), result.getContent());
    assertEquals(5000L, port.lastAwaitTimeout, "阻塞等待应把 timeout 传给端口");
  }

  @Test
  void taskOutputNonBlockingDoesNotWait() {
    FakePort port = new FakePort();
    port.state = State.RUNNING;

    new TaskOutputTool()
        .execute(new JSONObject().put("task_id", "task-1").put("block", false), contextWith(port));

    assertEquals(-1L, port.lastAwaitTimeout, "block=false 不应调用 await");
  }

  @Test
  void taskOutputUnknownIdIsAnError() {
    ToolResult result =
        new TaskOutputTool()
            .execute(new JSONObject().put("task_id", "nope"), contextWith(new FakePort()));

    assertTrue(result.isError(), "未知任务应报错而不是返回空");
    assertTrue(result.getContent().contains("找不到任务"), result.getContent());
  }

  // ---- task_stop ----

  @Test
  void taskStopStopsARunningTask() {
    FakePort port = new FakePort();
    port.state = State.RUNNING;

    ToolResult result =
        new TaskStopTool().execute(new JSONObject().put("task_id", "task-1"), contextWith(port));

    assertFalse(result.isError(), result.getContent());
    assertEquals(1, port.stopped.size(), "应调用端口停止");
    assertEquals("task-1", port.stopped.get(0));
  }

  @Test
  void taskStopOnFinishedTaskDoesNotCallStop() {
    FakePort port = new FakePort();
    port.state = State.DONE;

    ToolResult result =
        new TaskStopTool().execute(new JSONObject().put("task_id", "task-1"), contextWith(port));

    assertFalse(result.isError(), result.getContent());
    assertTrue(port.stopped.isEmpty(), "已结束的任务不应再调用 stop");
    assertTrue(result.getContent().contains("已经结束"), result.getContent());
  }

  /**
   * 一个「可用但一旦真正执行就抛异常」的后端。
   *
   * <p>用 isAvailable=true 让后端解析能通过（后台路径仍要求存在可用后端），
   * 而 execute 抛异常则证明后台路径**没有**走同步执行。
   */
  private static final class NeverExecutedBackend implements ShellBackend {
    @Override
    public String id() {
      return "never-executed";
    }

    @Override
    public String displayName() {
      return "不执行后端";
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
    public ShellRequest.ShellResult execute(
        ShellRequest request, ShellRequest.ShellOutputSink sink) {
      throw new AssertionError("后台路径不应调用同步后端");
    }
  }

  // ---- 状态映射与输出截断（辅助路径）----

  @Test
  void everyStateMapsToItsStableWireName() {
    // 状态名是给模型看的稳定字符串；改名字等于破坏模型的既有理解，
    // 因此每个枚举值都要有明确的映射（default 分支只留给 UNKNOWN）。
    String[][] cases = {
      {"FAILED", "failed"},
      {"STOPPED", "stopped"},
    };
    for (String[] c : cases) {
      FakePort port = new FakePort();
      port.state = State.valueOf(c[0]);
      port.exitCode = 1;
      port.hasExitCode = true;
      ToolResult r =
          new TaskOutputTool()
              .execute(new JSONObject().put("task_id", "task-1"), contextWith(port));
      assertTrue(r.getContent().contains("state: " + c[1]),
          c[0] + " 应映射为 " + c[1] + "，实际：" + r.getContent());
    }
  }

  @Test
  void hugeOutputIsTruncatedInResult() throws Exception {
    // 后台任务的输出可能极大（构建日志）；结果里必须截断，否则一次 task_output
    // 就能吃掉整个上下文窗口。
    File out = File.createTempFile("bgtask-big", ".log");
    out.deleteOnExit();
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 50000; i++) {
      sb.append("line-").append(i).append(" ").append("x".repeat(20)).append('\n');
    }
    Files.write(out.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));

    FakePort port = new FakePort();
    port.state = State.DONE;
    port.exitCode = 0;
    port.hasExitCode = true;
    port.outputFilePath = out.getAbsolutePath();

    ToolResult r =
        new TaskOutputTool().execute(new JSONObject().put("task_id", "task-1"), contextWith(port));
    assertFalse(r.isError(), r.getContent());
    assertTrue(r.getContent().length() < 60000,
        "超大输出必须截断，实际长度 " + r.getContent().length());
  }

  @Test
  void missingOutputFileIsReportedWithoutCrash() {
    // 输出文件被清理/路径失效：应如实说明而不是抛异常。
    FakePort port = new FakePort();
    port.state = State.DONE;
    port.exitCode = 0;
    port.hasExitCode = true;
    port.outputFilePath = "/definitely/not/here-12345.log";
    ToolResult r =
        new TaskOutputTool().execute(new JSONObject().put("task_id", "task-1"), contextWith(port));
    assertFalse(r.isError(), "文件缺失不应让工具失败：" + r.getContent());
  }
}
