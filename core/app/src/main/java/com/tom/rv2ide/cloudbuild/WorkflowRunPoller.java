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
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.cloudbuild;

import java.util.List;

/**
 * 轮询一次 workflow run 的进度（job 级），供 UI 进度页与 AI 工具等待使用。
 *
 * <p><b>为什么是「一次」而不是「启动一个轮询线程」</b>：ACS 已有协程/线程调度设施
 * （UI 用 lifecycleScope，AI 工具用 ExecutorService），轮询循环应由调用方按自己的
 * 生命周期驱动——UI 需要在页面销毁时取消，AI 工具需要在超时或用户取消时停止。
 * 本类只提供「查一次 + 判断是否该继续」的纯逻辑，把调度权留给调用方。
 *
 * <p><b>为什么要「先找到 run 再跟踪」</b>：dispatch 接口返回 204 无响应体，
 * 拿不到 run id。触发后必须按 workflow + 分支查最新的 run，且要处理两个边界：
 * <ol>
 *   <li>刚触发时新 run 可能还没出现在列表里（GitHub 有秒级延迟）——此时返回
 *       {@code null}，调用方应继续轮询而不是报错；</li>
 *   <li>列表里可能有**触发之前**的旧 run——因此要用触发时刻作基准，
 *       只认在那之后创建的 run。</li>
 * </ol>
 */
public final class WorkflowRunPoller {

  /** 取消检查：长等待（等新 run / 等终态）据此提前退出。由调用方注入，可为 null。 */
  public interface CancelCheck {
    boolean isCancelled();
  }

  /**
   * 取消检查的分片粒度（ms）。
   *
   * <p>等待循环里的 sleep 是 10 秒级，若整段睡完才检查取消，用户点「停止」后最坏要等
   * 10 秒才响应。切成 250ms 分片后响应延迟与 gradle_build 同量级（见
   * {@code GradleBuildTool.CANCEL_POLL_MS}）。
   */
  private static final long CANCEL_SLICE_MS = 250L;

  private final RunQueryPort client;

  public WorkflowRunPoller(RunQueryPort client) {
    this.client = client;
  }

  /** 一次进度快照。 */
  public static final class Snapshot {
    public final WorkflowRun run;
    public final List<WorkflowJob> jobs;

    public Snapshot(WorkflowRun run, List<WorkflowJob> jobs) {
      this.run = run;
      this.jobs = jobs;
    }

    /** 是否已到终态。 */
    public boolean isFinished() {
      return run != null && run.isFinished();
    }
  }

  /**
   * 查一次进度快照。
   *
   * @param runId 已知的 run id；若为 0 表示「尚未找到 run」，此时按
   *     {@code workflowFile + branch} 查最新 run
   * @return 快照；run 尚未出现时返回 {@code null}
   */
  public Snapshot poll(long runId, String workflowFile, String branch) throws Exception {
    WorkflowRun run;
    if (runId > 0) {
      run = client.getRun(runId);
    } else {
      run = client.getLatestRun(workflowFile, branch);
    }
    if (run == null) {
      return null;
    }
    List<WorkflowJob> jobs = client.listJobs(run.id);
    return new Snapshot(run, jobs);
  }

  /**
   * 在触发 dispatch 之后，等待新 run 出现并返回它。
   *
   * <p>触发前先记下「当前最新 run 的 id」，触发后轮询列表直到出现一个 id 更大的
   * run（或超时）。这是处理「dispatch 无响应体」的标准做法。
   *
   * <p><b>为什么单次网络错误不能中断</b>：dispatch 已经成功发出，此时网络抖动
   * （本项目实测中设备网络与开发机到 api.github.com 都出现过瞬时超时）若直接上抛，
   * 用户会看到「触发失败」而实际上构建正在跑——状态与事实不符比慢几秒更糟。
   * 因此瞬时错误只记入 {@code lastError} 并继续轮询，直到 deadline；deadline 到达
   * 仍未见到新 run 时，若期间有过错误则把错误一并报告（说明可能是网络问题）。
   *
   * @param previousRunId 触发前最新 run 的 id（没有历史 run 时传 0）
   * @param workflowFile workflow 文件（限定查询范围）
   * @param branch 分支（限定查询范围）
   * @param timeoutMs 等待上限
   * @param sleepMs 轮询间隔
   * @return 新出现的 run；超时未出现返回 {@code null}
   */
  public WorkflowRun awaitNewRun(long previousRunId, String workflowFile, String branch,
                                 long timeoutMs, long sleepMs) throws Exception {
    return awaitNewRun(previousRunId, workflowFile, branch, timeoutMs, sleepMs, null);
  }

  /**
   * 等待新 run 出现（可取消）。
   *
   * @param cancel 取消检查（可为 null）；为 null 时行为与四参重载一致
   */
  public WorkflowRun awaitNewRun(long previousRunId, String workflowFile, String branch,
                                 long timeoutMs, long sleepMs, CancelCheck cancel) throws Exception {
    long deadline = System.currentTimeMillis() + timeoutMs;
    Exception lastError = null;
    while (System.currentTimeMillis() < deadline) {
      if (cancel != null && cancel.isCancelled()) {
        return null;
      }
      try {
        WorkflowRun latest = client.getLatestRun(workflowFile, branch);
        if (latest != null && latest.id > previousRunId) {
          return latest;
        }
      } catch (Exception e) {
        // 取消信号不能当「瞬时网络错误」吞掉：runInterruptible 的取消会**中断本线程**，
        // 网络读随即抛异常（InterruptedIOException 或 SocketException）。若继续重试，
        // 用户关掉界面后循环仍会跑满超时并持续发请求（实测：关闭弹窗崩溃链条的起点）。
        //
        // 检测只用线程中断位，不能按异常类型判断：SocketTimeoutException 是
        // InterruptedIOException 的**子类**，按类型判断会把正常网络超时也当成取消
        // （测试 awaitNewRunSurvivesTransientErrors 钉住了这一点）。
        // 本方法运行在 runInterruptible 的工作线程上，取消时中断位已被设置。
        if (Thread.currentThread().isInterrupted()) {
          throw e;
        }
        // 瞬时网络错误：记录下来继续轮询，不中断。
        lastError = e;
      }
      // 还没出现：等待一个间隔再查。分片睡眠而不是整段睡——整段睡 10 秒会让
      // 取消最坏延迟 10 秒才被察觉（见 CANCEL_SLICE_MS）。
      if (!sleepInterruptibly(Math.max(1000, sleepMs), cancel)) {
        return null;
      }
    }
    if (lastError != null) {
      throw new Exception("等待新运行记录时网络请求持续失败（最后一次：" + lastError.getMessage()
          + "）。构建可能已触发，请到 GitHub 网页确认。", lastError);
    }
    return null;
  }

  /**
   * 阻塞等待 run 到终态（或超时），期间周期性回调进度。
   *
   * <p>与 {@link #awaitNewRun} 同理：轮询期间的瞬时网络错误不中断等待
   * （构建在云端跑，本地网络抖一下不该让「等待结果」失败）。
   *
   * @param runId run id
   * @param timeoutMs 等待上限
   * @param sleepMs 轮询间隔
   * @param listener 进度回调（可为 null）
   * @return 终态快照；超时返回最后一次快照
   */
  public Snapshot awaitCompletion(long runId, String workflowFile, String branch,
                                  long timeoutMs, long sleepMs, ProgressListener listener)
      throws Exception {
    return awaitCompletion(runId, workflowFile, branch, timeoutMs, sleepMs, listener, null);
  }

  /**
   * 阻塞等待 run 到终态（可取消）。
   *
   * @param cancel 取消检查（可为 null）；为 null 时行为与六参重载一致
   */
  public Snapshot awaitCompletion(long runId, String workflowFile, String branch,
                                  long timeoutMs, long sleepMs, ProgressListener listener,
                                  CancelCheck cancel) throws Exception {
    long deadline = System.currentTimeMillis() + timeoutMs;
    Snapshot last = null;
    while (System.currentTimeMillis() < deadline) {
      if (cancel != null && cancel.isCancelled()) {
        return last;
      }
      try {
        Snapshot snapshot = poll(runId, workflowFile, branch);
        if (snapshot != null) {
          last = snapshot;
          if (listener != null) {
            listener.onProgress(snapshot);
          }
          if (snapshot.isFinished()) {
            return snapshot;
          }
        }
      } catch (Exception e) {
        // 同 awaitNewRun：取消只按**线程中断位**判断（SocketTimeoutException 是
        // InterruptedIOException 的子类，按类型判断会误伤正常超时）。
        if (Thread.currentThread().isInterrupted()) {
          throw e;
        }
        // 瞬时网络错误：继续等待。已有快照时保留（超时返回它比抛异常更有用）。
      }
      if (!sleepInterruptibly(Math.max(1000, sleepMs), cancel)) {
        return last;
      }
    }
    return last;
  }

  /**
   * 分片睡眠：每片 {@link #CANCEL_SLICE_MS} 检查一次取消。
   *
   * @return true 正常睡完；false 被取消（调用方应提前返回）
   */
  private static boolean sleepInterruptibly(long totalMs, CancelCheck cancel)
      throws InterruptedException {
    long remaining = totalMs;
    while (remaining > 0) {
      if (cancel != null && cancel.isCancelled()) {
        return false;
      }
      long slice = Math.min(CANCEL_SLICE_MS, remaining);
      Thread.sleep(slice);
      remaining -= slice;
    }
    return true;
  }

  /** 进度回调。 */
  public interface ProgressListener {
    void onProgress(Snapshot snapshot);
  }
}
