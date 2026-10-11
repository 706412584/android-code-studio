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

import com.tom.rv2ide.git.GitManager;
import com.tom.rv2ide.git.RemoteInfo;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 云打包门面：把「识别仓库 → 触发 → 跟踪 → 取失败日志 / 产物」串成可复用的流程。
 *
 * <p>UI 与 AI 工具都通过本类操作，避免两处各自拼装
 * {@link GitHubActionsClient} + {@link WorkflowRunPoller} + {@link RunLogExtractor}
 * 的逻辑（这正是项目「共享适配层先行」的教训：多个消费者需要的同一段胶水代码
 * 必须只有一份）。
 *
 * <p><b>同步 vs 异步</b>：本类的方法全部是同步阻塞的（含网络与 sleep）。
 * UI 调用时须置于后台线程 / 协程 IO 调度器；AI 工具调用时置于工具执行线程。
 * 门面不替调用方管理线程——两边的生命周期管理方式不同（UI 随页面销毁取消、
 * AI 随工具调用超时取消），统一接管反而会互相牵制。
 */
public final class CloudBuildService {

  /** 用户取消长等待时抛出（与「超时未出现」区分，两者给用户的处置不同）。 */
  public static final class CancelledException extends Exception {
    CancelledException() {
      super("操作已被用户取消。");
    }
  }

  /** 触发前的默认等待：GitHub 出现新 run 通常需要几秒。 */
  private static final long DEFAULT_AWAIT_RUN_TIMEOUT_MS = 60_000;

  /** 轮询间隔：GitHub API 有速率限制（认证后 5000 次/小时），10 秒间隔足够。 */
  private static final long DEFAULT_POLL_INTERVAL_MS = 10_000;

  private final String token;
  private final String owner;
  private final String repo;
  private final GitHubActionsClient client;

  public CloudBuildService(String token, String owner, String repo) {
    this.token = token == null ? "" : token;
    this.owner = owner == null ? "" : owner;
    this.repo = repo == null ? "" : repo;
    this.client = new GitHubActionsClient(this.token, this.owner, this.repo);
  }

  public String getOwner() {
    return owner;
  }

  public String getRepo() {
    return repo;
  }

  public GitHubActionsClient getClient() {
    return client;
  }

  // ---------------------------------------------------------------------------
  // 仓库识别
  // ---------------------------------------------------------------------------

  /**
   * 从项目目录识别 GitHub 仓库。
   *
   * <p>优先用 {@code origin} remote，回退到第一个可解析的 remote——有些项目把
   * 主仓库叫别的名字（upstream / github）。
   *
   * @return owner/repo；无法识别时返回 {@code null}
   */
  public static GitRemoteParser.RepoSlug detectRepository(String projectPath) {
    if (projectPath == null || projectPath.trim().isEmpty()) {
      return null;
    }
    try {
      GitManager gitManager = new GitManager(projectPath);
      if (!gitManager.openRepository()) {
        return null;
      }
      List<RemoteInfo> remotes = gitManager.getRemotes();
      if (remotes.isEmpty()) {
        return null;
      }
      // origin 优先。
      for (RemoteInfo remote : remotes) {
        if ("origin".equals(remote.getName())) {
          GitRemoteParser.RepoSlug slug = GitRemoteParser.parse(remote.getFetchUrl());
          if (slug.isValid()) {
            return slug;
          }
        }
      }
      // 回退：第一个能解析出 GitHub owner/repo 的 remote。
      for (RemoteInfo remote : remotes) {
        GitRemoteParser.RepoSlug slug = GitRemoteParser.parse(remote.getFetchUrl());
        if (slug.isValid()) {
          return slug;
        }
      }
      return null;
    } catch (Throwable e) {
      return null;
    }
  }

  /** 读当前分支（触发 workflow 的 ref）。 */
  public static String detectBranch(String projectPath) {
    try {
      GitManager gitManager = new GitManager(projectPath);
      if (!gitManager.openRepository()) {
        return null;
      }
      return gitManager.getCurrentBranch();
    } catch (Throwable e) {
      return null;
    }
  }

  // ---------------------------------------------------------------------------
  // 触发与跟踪
  // ---------------------------------------------------------------------------

  /** 一次触发的完整上下文。 */
  public static final class BuildHandle {
    public final String workflowFile;
    public final String branch;
    public WorkflowRun run;
    /** 触发前的 run id（用于识别新 run）。 */
    public long previousRunId;

    BuildHandle(String workflowFile, String branch) {
      this.workflowFile = workflowFile;
      this.branch = branch;
    }
  }

  /**
   * 触发 workflow 并等待新 run 出现。
   *
   * <p>这是「一键触发」的核心：记下当前最新 run → dispatch → 等新 run 出现。
   *
   * @param workflowFile workflow 文件名（如 {@code build.yml}）
   * @param branch 分支；null 表示当前分支（由调用方提供）
   * @param inputs workflow_dispatch 输入（可为空）
   * @return 已出现的新 run 句柄
   * @throws Exception 触发失败或超时未见新 run
   */
  public BuildHandle triggerAndAwaitRun(String workflowFile, String branch,
                                        java.util.Map<String, String> inputs)
      throws Exception {
    return triggerAndAwaitRun(workflowFile, branch, inputs, null);
  }

  /**
   * 触发 workflow 并等待新 run 出现（可取消）。
   *
   * @param cancel 取消检查（可为 null）；取消时抛 {@link CancelledException}
   */
  public BuildHandle triggerAndAwaitRun(String workflowFile, String branch,
                                        java.util.Map<String, String> inputs,
                                        WorkflowRunPoller.CancelCheck cancel)
      throws Exception {
    BuildHandle handle = new BuildHandle(workflowFile, branch);

    // 记录触发前的最新 run id（没有历史时为 0）。
    WorkflowRun previous = client.getLatestRun(workflowFile, branch);
    handle.previousRunId = previous == null ? 0 : previous.id;

    client.dispatchWorkflow(workflowFile, branch, inputs);

    WorkflowRun newRun = new WorkflowRunPoller(client)
        .awaitNewRun(handle.previousRunId, workflowFile, branch,
            DEFAULT_AWAIT_RUN_TIMEOUT_MS, DEFAULT_POLL_INTERVAL_MS, cancel);
    if (newRun == null) {
      if (cancel != null && cancel.isCancelled()) {
        throw new CancelledException();
      }
      throw new Exception("已触发 workflow，但等待 " + (DEFAULT_AWAIT_RUN_TIMEOUT_MS / 1000)
          + " 秒后仍未看到新的运行记录。请到 GitHub 网页确认 workflow 是否真的被触发。");
    }
    handle.run = newRun;
    return handle;
  }

  /**
   * 等待 run 到终态，期间回调进度。
   *
   * @param handle {@link #triggerAndAwaitRun} 返回的句柄
   * @param timeoutMs 等待上限
   * @param listener 进度回调（可为 null）
   * @return 终态快照
   */
  public WorkflowRunPoller.Snapshot awaitCompletion(BuildHandle handle, long timeoutMs,
                                                    WorkflowRunPoller.ProgressListener listener)
      throws Exception {
    return awaitCompletion(handle, timeoutMs, listener, null);
  }

  /**
   * 等待 run 到终态（可取消）。
   *
   * @param cancel 取消检查（可为 null）；取消时返回最后一次快照（调用方据
   *     {@code snapshot.isFinished()} 判断是否真的到终态）
   */
  public WorkflowRunPoller.Snapshot awaitCompletion(BuildHandle handle, long timeoutMs,
                                                    WorkflowRunPoller.ProgressListener listener,
                                                    WorkflowRunPoller.CancelCheck cancel)
      throws Exception {
    return new WorkflowRunPoller(client).awaitCompletion(
        handle.run.id, handle.workflowFile, handle.branch,
        timeoutMs, DEFAULT_POLL_INTERVAL_MS, listener, cancel);
  }

  /** 查一次进度（供 UI 刷新 / AI 查询状态）。 */
  public WorkflowRunPoller.Snapshot poll(long runId, String workflowFile, String branch)
      throws Exception {
    return new WorkflowRunPoller(client).poll(runId, workflowFile, branch);
  }

  // ---------------------------------------------------------------------------
  // 失败日志与产物
  // ---------------------------------------------------------------------------

  /**
   * 取失败 run 的日志片段。
   *
   * @param runId run id
   * @param cacheDir 下载日志 zip 的临时目录
   * @return 可读的失败片段（见 {@link RunLogExtractor}）
   */
  public RunLogExtractor.ExtractResult fetchFailureLog(long runId, File cacheDir)
      throws Exception {
    File logZip = new File(cacheDir, "run-" + runId + "-logs.zip");
    try {
      client.downloadRunLogs(runId, logZip);
      return RunLogExtractor.extract(logZip);
    } finally {
      // 日志 zip 是中间产物：提取完成即删除，避免在用户设备上积累大文件。
      //noinspection ResultOfMethodCallIgnored
      logZip.delete();
    }
  }

  /**
   * 下载 run 的 artifact。
   *
   * @param runId run id
   * @param artifactName 目标 artifact 名；null 表示下载第一个未过期的
   * @param targetDir 保存目录
   * @return 保存的文件
   */
  public File downloadArtifact(long runId, String artifactName, File targetDir)
      throws Exception {
    List<WorkflowArtifact> artifacts = client.listArtifacts(runId);
    if (artifacts.isEmpty()) {
      throw new Exception("该次运行没有产物（artifact）。");
    }
    WorkflowArtifact target = null;
    for (WorkflowArtifact artifact : artifacts) {
      if (artifact.expired) {
        continue;
      }
      if (artifactName == null || artifactName.isEmpty()
          || artifact.name.equals(artifactName)) {
        target = artifact;
        break;
      }
    }
    if (target == null) {
      List<String> names = new ArrayList<>();
      for (WorkflowArtifact artifact : artifacts) {
        if (!artifact.expired) {
          names.add(artifact.name);
        }
      }
      throw new Exception("找不到可下载的产物"
          + (artifactName == null || artifactName.isEmpty() ? "" : "：" + artifactName)
          + "。可用产物：" + names);
    }

    if (!targetDir.exists() && !targetDir.mkdirs()) {
      throw new Exception("无法创建保存目录：" + targetDir.getAbsolutePath());
    }
    // GitHub 的 artifact 名常含空格/斜杠，落盘文件名要清洗。
    String safeName = target.name.replaceAll("[\\\\/:*?\"<>|]", "_");
    File out = new File(targetDir, safeName + ".zip");
    client.downloadArtifact(target, out);
    return out;
  }

  // ---------------------------------------------------------------------------
  // 状态查询（只读，供 AI 的免确认路径）
  // ---------------------------------------------------------------------------

  /**
   * 查询最近一次运行的状态摘要（不触发任何构建，只读）。
   *
   * @param workflowFile 可选：限定 workflow
   * @param branch 可选：限定分支
   * @return 人类可读的状态摘要
   */
  public String describeLatestRun(String workflowFile, String branch) throws Exception {
    WorkflowRun run = client.getLatestRun(workflowFile, branch);
    if (run == null) {
      return formatLatestRun(owner, repo, null, null);
    }
    List<WorkflowJob> jobs = run.isFinished() ? client.listJobs(run.id) : null;
    return formatLatestRun(owner, repo, run, jobs);
  }

  /**
   * 状态摘要的格式化（纯函数，供 UI 与 AI 工具共用，可 JVM 单测）。
   *
   * <p>与网络调用分离是刻意的：文案是「用户/模型看到的最终输出」，改错一个字
   * 比改错一行逻辑更难被发现（同 {@code GradleBuildTool.describeFailure} 的教训）。
   *
   * @param run 最新 run；null 表示没有运行记录
   * @param jobs job 列表（可为 null，表示未查询或没有）
   */
  static String formatLatestRun(String owner, String repo, WorkflowRun run,
                                List<WorkflowJob> jobs) {
    if (run == null) {
      return "仓库 " + owner + "/" + repo + " 没有运行记录。";
    }
    StringBuilder builder = new StringBuilder();
    builder.append("最近一次运行：").append(run.name.isEmpty() ? "(未命名)" : run.name)
        .append('\n');
    builder.append("状态：").append(run.statusText()).append('\n');
    builder.append("分支：").append(run.headBranch).append('\n');
    builder.append("创建时间：").append(run.createdAt).append('\n');
    if (jobs != null && !jobs.isEmpty()) {
      builder.append("Jobs：\n");
      for (WorkflowJob job : jobs) {
        builder.append("  - ").append(job.name).append(": ").append(job.statusText())
            .append('\n');
      }
    }
    builder.append("链接：").append(run.htmlUrl);
    return builder.toString();
  }
}
