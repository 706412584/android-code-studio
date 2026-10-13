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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/**
 * 轮询器「瞬时网络错误不中断」行为的回归测试。
 *
 * <p><b>为什么这条测试重要</b>：真实场景（2026-10-12 实测）中，dispatch 已成功
 * 发出后本地网络抖动了一次，旧实现直接把「网络超时」上抛，用户看到的是「触发失败」
 * ——而构建正在云端跑。状态与事实不符是最糟的失败模式，因此「错误后继续轮询直到
 * deadline」必须有测试钉住。
 *
 * <p>用假 {@link RunQueryPort} 控制每次调用的行为（抛错 / 返回指定 run），
 * 配合极短的 sleep 间隔让测试快速完成。
 */
public class WorkflowRunPollerTest {

  /** 可编排的假查询端口。 */
  private static final class ScriptedPort implements RunQueryPort {
    /** 前 N 次 getLatestRun 调用抛异常。 */
    int failFirstN = 0;
    /** 抛错后返回的 run（可为 null 表示「尚无新 run」）。 */
    WorkflowRun latestRun;
    /** getRun 的固定返回值。 */
    WorkflowRun runById;
    /** listJobs 的固定返回值。 */
    List<WorkflowJob> jobs = Collections.emptyList();

    final AtomicInteger latestCalls = new AtomicInteger();
    final AtomicInteger jobsCalls = new AtomicInteger();

    @Override
    public WorkflowRun getLatestRun(String workflowFile, String branch) throws Exception {
      int call = latestCalls.incrementAndGet();
      if (call <= failFirstN) {
        throw new java.net.SocketTimeoutException("Connect timed out");
      }
      return latestRun;
    }

    @Override
    public WorkflowRun getRun(long runId) throws Exception {
      if (failFirstN > 0) {
        // 简化：getRun 也受同一失败计数控制（测试只关注其一）。
        if (latestCalls.get() <= failFirstN) {
          throw new java.net.SocketTimeoutException("Connect timed out");
        }
      }
      return runById;
    }

    @Override
    public List<WorkflowJob> listJobs(long runId) throws Exception {
      jobsCalls.incrementAndGet();
      return jobs;
    }
  }

  private static WorkflowRun run(long id, String status, String conclusion) {
    return new WorkflowRun(id, "Build", "dev", "sha", status, conclusion,
        "https://example.com", "2026-10-12T00:00:00Z", "2026-10-12T00:00:00Z");
  }

  @Test
  public void awaitNewRunSurvivesTransientErrors() throws Exception {
    ScriptedPort port = new ScriptedPort();
    // 前两次调用抛网络错误，第三次返回新 run——旧实现会在第一次就失败。
    port.failFirstN = 2;
    port.latestRun = run(100, "queued", "");

    WorkflowRun found = new WorkflowRunPoller(port)
        .awaitNewRun(50, "build.yml", "dev", 30_000, 1000);

    assertNotNull("瞬时错误后应继续轮询并找到新 run", found);
    assertEquals(100, found.id);
    assertTrue("应实际重试了多次", port.latestCalls.get() >= 3);
  }

  /**
   * 取消（线程中断）时异常必须原样上抛，不能被当作「瞬时网络错误」吞掉。
   *
   * <p><b>为什么这条测试重要</b>：runInterruptible 的取消会**中断工作线程**，
   * 网络读随即抛异常——若被 catch 成「瞬时错误」继续重试，用户关掉界面后循环仍会
   * 跑满超时并持续发请求，且会触碰已销毁的 UI 导致崩溃（2026-07-12 实测：关闭
   * 云编译弹窗崩溃链条的起点）。
   *
   * <p><b>检测语义是「线程中断位」而非异常类型</b>：SocketTimeoutException 是
   * InterruptedIOException 的子类——若按异常类型判断取消，正常网络超时会被误伤
   * （下面的 awaitNewRunSurvivesTransientErrors 正是钉这一点）。测试因此设置
   * 中断位再抛普通超时异常，模拟 runInterruptible 取消时的真实形态。
   */
  @Test
  public void awaitNewRunPropagatesInterruptInsteadOfRetrying() {
    RunQueryPort interruptPort = new RunQueryPort() {
      @Override
      public WorkflowRun getLatestRun(String workflowFile, String branch) throws Exception {
        // runInterruptible 取消时工作线程的中断位已被设置，网络读随即失败。
        Thread.currentThread().interrupt();
        throw new java.net.SocketTimeoutException("interrupted by cancel");
      }

      @Override
      public WorkflowRun getRun(long runId) throws Exception {
        Thread.currentThread().interrupt();
        throw new java.net.SocketTimeoutException("interrupted by cancel");
      }

      @Override
      public List<WorkflowJob> listJobs(long runId) {
        return Collections.emptyList();
      }
    };

    try {
      // 超时给得很长：若实现把中断当成瞬时错误重试，这里会跑满 10 秒才返回——
      // 原样上抛则立即抛出。
      new WorkflowRunPoller(interruptPort).awaitNewRun(50, "build.yml", "dev", 10_000, 1000);
      fail("中断信号应原样上抛，而不是重试到超时");
    } catch (Exception e) {
      assertTrue("应是中断异常本身：" + e.getClass().getName(),
          e instanceof java.io.InterruptedIOException);
    } finally {
      // 清中断位，避免影响同一线程上的后续测试（JUnit 复用线程）。
      Thread.interrupted();
    }
  }

  /** 同样的语义覆盖 awaitCompletion（终态等待路径）。 */
  @Test
  public void awaitCompletionPropagatesInterrupt() {
    RunQueryPort interruptPort = new RunQueryPort() {
      @Override
      public WorkflowRun getLatestRun(String workflowFile, String branch) throws Exception {
        Thread.currentThread().interrupt();
        throw new java.net.SocketTimeoutException("interrupted by cancel");
      }

      @Override
      public WorkflowRun getRun(long runId) throws Exception {
        Thread.currentThread().interrupt();
        throw new java.net.SocketTimeoutException("interrupted by cancel");
      }

      @Override
      public List<WorkflowJob> listJobs(long runId) {
        return Collections.emptyList();
      }
    };

    try {
      new WorkflowRunPoller(interruptPort)
          .awaitCompletion(100, "build.yml", "dev", 10_000, 1000, null);
      fail("中断信号应原样上抛");
    } catch (Exception e) {
      assertTrue("应是中断异常本身：" + e.getClass().getName(),
          e instanceof java.io.InterruptedIOException);
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  public void awaitNewRunReportsNetworkProblemOnDeadline() {
    ScriptedPort port = new ScriptedPort();
    // 始终抛错——deadline 到达后应抛出，且错误信息说明「可能已触发」而不是
    // 一个光秃秃的 SocketTimeoutException。
    port.failFirstN = Integer.MAX_VALUE;

    try {
      new WorkflowRunPoller(port).awaitNewRun(50, "build.yml", "dev", 2500, 1000);
      fail("持续网络失败应在 deadline 后抛出");
    } catch (Exception e) {
      assertTrue("错误信息应说明构建可能已触发：" + e.getMessage(),
          e.getMessage().contains("可能已触发"));
    }
  }

  @Test
  public void awaitNewRunReturnsNullWhenNoRunAppearsWithoutErrors() throws Exception {
    ScriptedPort port = new ScriptedPort();
    port.latestRun = null; // 一直查不到新 run

    WorkflowRun found = new WorkflowRunPoller(port)
        .awaitNewRun(50, "build.yml", "dev", 2500, 1000);

    assertNull("无错误且超时未出现时应返回 null", found);
  }

  @Test
  public void awaitCompletionSurvivesTransientErrors() throws Exception {
    ScriptedPort port = new ScriptedPort();
    // 第一次查询失败一次，第二次返回进行中，第三次返回成功终态。
    final AtomicInteger phase = new AtomicInteger();
    port.runById = run(100, "completed", "success");
    port.latestRun = port.runById;

    RunQueryPort flaky = new RunQueryPort() {
      @Override
      public WorkflowRun getLatestRun(String workflowFile, String branch) throws Exception {
        return port.getLatestRun(workflowFile, branch);
      }

      @Override
      public WorkflowRun getRun(long runId) throws Exception {
        int p = phase.incrementAndGet();
        if (p == 1) {
          throw new java.net.SocketTimeoutException("Connect timed out");
        }
        if (p == 2) {
          return run(100, "in_progress", "");
        }
        return run(100, "completed", "success");
      }

      @Override
      public List<WorkflowJob> listJobs(long runId) throws Exception {
        return Collections.emptyList();
      }
    };

    List<WorkflowRunPoller.Snapshot> progress = new ArrayList<>();
    WorkflowRunPoller.Snapshot result = new WorkflowRunPoller(flaky)
        .awaitCompletion(100, "build.yml", "dev", 30_000, 1000, progress::add);

    assertNotNull(result);
    assertTrue("应到终态", result.isFinished());
    assertTrue("应收到进度回调", !progress.isEmpty());
  }

  @Test
  public void pollReturnsSnapshotWithJobs() throws Exception {
    ScriptedPort port = new ScriptedPort();
    port.runById = run(7, "in_progress", "");
    port.jobs = Collections.singletonList(
        new WorkflowJob(1, "build", "in_progress", "", ""));

    WorkflowRunPoller.Snapshot snapshot = new WorkflowRunPoller(port)
        .poll(7, "build.yml", "dev");

    assertNotNull(snapshot);
    assertEquals(7, snapshot.run.id);
    assertEquals(1, snapshot.jobs.size());
    assertEquals("build", snapshot.jobs.get(0).name);
  }
}
