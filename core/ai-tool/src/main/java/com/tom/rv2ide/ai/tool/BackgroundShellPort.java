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

/**
 * 后台命令执行端口：让 AI 能启动一条长时间命令、立即返回、稍后取回结果。
 *
 * <p><b>为什么需要它</b>：{@link ShellExecuteTool} 是同步阻塞的——跑一次 8 分钟的构建，
 * agent 循环就干等 8 分钟，期间不能读代码、不能做别的调查。更糟的是日志流 / dev server
 * 这类永不退出的命令，同步路径根本不可能（必然超时）。后台执行把「等待」从循环里摘出去：
 * 启动即返回任务句柄，完成时通知模型，中间循环可以继续干活。
 *
 * <p><b>为什么用端口而不是直接实现</b>：本模块是纯 JVM、零 Android 依赖的
 * （见 {@code core/ai-tool/build.gradle.kts} 只依赖 {@code ai-tool-api}），而后台执行
 * 需要真实的 shell 后端（Termux / Shizuku，都在 app 层且需要 {@code Context}）、
 * 需要线程与文件系统落盘。把这些引进来会让本模块无法在 JVM 上单测。因此这里只定义
 * 中性契约，由 app 层的 {@code BackgroundShellRunner} 实现。
 *
 * <p><b>语义照 cc-haha 的 BashTool {@code run_in_background}</b>：
 * <ul>
 *   <li>启动后立即返回任务 id 与输出文件路径——模型**不需要**轮询，完成时会收到通知；
 *   <li>输出写入文件（而非内存），因此命令可以输出任意长而不撑爆上下文；
 *   <li>取回用 {@code task_output}（block/timeout），停止用 {@code task_stop}。
 * </ul>
 */
public interface BackgroundShellPort {

  /** 任务状态。 */
  enum State {
    /** 正在运行。 */
    RUNNING,
    /** 已结束，退出码为 0。 */
    DONE,
    /** 已结束，退出码非 0。 */
    FAILED,
    /** 被 {@link #stop(String)} 主动停止。 */
    STOPPED,
    /** 任务 id 未知（已淘汰或不属于本进程）。 */
    UNKNOWN
  }

  /**
   * 一次后台任务的快照。
   *
   * <p>不可变值对象：端口在每次查询时生成一份，工具层只读。
   */
  final class TaskStatus {
    private final String taskId;
    private final String command;
    private final State state;
    private final int exitCode;
    private final boolean hasExitCode;
    private final String outputFilePath;
    private final long startedAtMs;
    private final long finishedAtMs;
    private final String error;

    public TaskStatus(
        String taskId,
        String command,
        State state,
        int exitCode,
        boolean hasExitCode,
        String outputFilePath,
        long startedAtMs,
        long finishedAtMs,
        String error) {
      this.taskId = taskId == null ? "" : taskId;
      this.command = command == null ? "" : command;
      this.state = state == null ? State.UNKNOWN : state;
      this.exitCode = exitCode;
      this.hasExitCode = hasExitCode;
      this.outputFilePath = outputFilePath == null ? "" : outputFilePath;
      this.startedAtMs = startedAtMs;
      this.finishedAtMs = finishedAtMs;
      this.error = error == null ? "" : error;
    }

    /** 启动失败（无可用后端等）时构造的状态。 */
    public static TaskStatus startFailed(String command, String error) {
      return new TaskStatus("", command, State.FAILED, -1, false, "", 0L, 0L, error);
    }

    public String getTaskId() {
      return taskId;
    }

    public String getCommand() {
      return command;
    }

    public State getState() {
      return state;
    }

    public int getExitCode() {
      return exitCode;
    }

    /** 是否已拿到退出码（进程刚结束时退出码文件可能尚未落盘）。 */
    public boolean hasExitCode() {
      return hasExitCode;
    }

    /** 输出落盘路径；模型可用 file_read 读取完整输出。 */
    public String getOutputFilePath() {
      return outputFilePath;
    }

    public long getStartedAtMs() {
      return startedAtMs;
    }

    /** 结束时刻；仍在运行时为 0。 */
    public long getFinishedAtMs() {
      return finishedAtMs;
    }

    /** 启动失败或运行异常的原因；正常时为空串。 */
    public String getError() {
      return error;
    }

    public boolean isRunning() {
      return state == State.RUNNING;
    }

    public boolean isFinished() {
      return state == State.DONE || state == State.FAILED || state == State.STOPPED;
    }

    public boolean isSuccess() {
      return state == State.DONE;
    }
  }

  /**
   * 启动一条后台命令并**立即返回**。
   *
   * <p>不阻塞等待命令结束——这正是本端口存在的意义。返回的 {@link TaskStatus}
   * 在成功时 {@code state=RUNNING} 且带 taskId 与输出文件路径；启动失败（无可用后端、
   * 无法创建输出文件等）时 {@code state=FAILED} 且 {@link TaskStatus#getError()} 说明原因。
   *
   * @param command 要执行的命令
   * @param cwd 工作目录；空串表示由后端决定（通常是工作区根）
   * @param backendId 指定后端 id；空串表示用用户配置的后端
   * @param timeoutMs 任务的最长时间上限；超时后由实现终止进程
   */
  TaskStatus start(String command, String cwd, String backendId, long timeoutMs);

  /**
   * 查询任务当前状态（非阻塞）。
   *
   * <p>未知 id 返回 {@code state=UNKNOWN}——调用方据此给出「任务不存在」的提示，
   * 而不是静默返回空。
   */
  TaskStatus status(String taskId);

  /**
   * 等待任务结束，最长等待 {@code timeoutMs}。
   *
   * <p>任务在等待期内结束则返回其终态；超时仍未结束则返回当前（RUNNING）状态。
   * 实现应避免忙等——用条件变量/阻塞队列，而不是轮询循环。
   */
  TaskStatus await(String taskId, long timeoutMs);

  /**
   * 停止一个运行中的任务。
   *
   * @return 是否找到并发出停止；任务已结束或 id 未知时返回 false
   */
  boolean stop(String taskId);

  /** 端口当前是否可用（是否至少有一个可用的 shell 后端）。 */
  boolean isAvailable();

  /** 端口不可用的原因；可用时返回空串。 */
  String unavailableReason();
}
