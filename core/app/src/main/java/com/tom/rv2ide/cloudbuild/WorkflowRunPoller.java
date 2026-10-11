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

  private final GitHubActionsClient client;

  public WorkflowRunPoller(GitHubActionsClient client) {
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
    while (System.currentTimeMillis() < deadline) {
      if (cancel != null && cancel.isCancelled()) {
        return null;
      }
      WorkflowRun latest = client.getLatestRun(workflowFile, branch);
      if (latest != null && latest.id > previousRunId) {
        return latest;
      }
      // 还没出现：等待一个间隔再查。分片睡眠而不是整段睡——整段睡 10 秒会让
      // 取消最坏延迟 10 秒才被察觉（见 CANCEL_SLICE_MS）。
      if (!sleepInterruptibly(Math.max(1000, sleepMs), cancel)) {
        return null;
      }
    }
    return null;
  }

  /**
   * 阻塞等待 run 到终态（或超时），期间周期性回调进度。
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
