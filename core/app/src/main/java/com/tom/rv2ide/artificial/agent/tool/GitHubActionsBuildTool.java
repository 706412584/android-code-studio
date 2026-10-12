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

import com.tom.rv2ide.ai.tool.BaseTool;
import com.tom.rv2ide.ai.tool.ToolContext;
import com.tom.rv2ide.ai.tool.api.ToolCategory;
import com.tom.rv2ide.ai.tool.api.ToolDisplayCategory;
import com.tom.rv2ide.ai.tool.api.ToolNames;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import com.tom.rv2ide.cloudbuild.CloudBuildService;
import com.tom.rv2ide.cloudbuild.RunLogExtractor;
import com.tom.rv2ide.cloudbuild.WorkflowArtifact;
import com.tom.rv2ide.cloudbuild.WorkflowRunPoller;
import java.util.List;
import org.json.JSONObject;

/**
 * 触发 GitHub Actions 云编译，等待到终态并报告结果。
 *
 * <p><b>为什么需要它</b>：手机本地构建受限于性能与内存（大型项目动辄十几分钟甚至
 * 打不出来），而 GitHub Actions 是免费的云端构建。用户说「帮我云编译」时，AI 应当
 * 能直接触发、跟踪、拿回结果，而不是让用户自己去网页点。
 *
 * <p><b>为什么 needsConfirmation=true</b>：触发会真的起一次云编译，消耗用户的
 * Actions 分钟数（私有仓库计费），与 {@code gradle_build} 同级的资源消耗动作。
 *
 * <p><b>为什么与状态查询拆成两个工具</b>：见 {@link GitHubActionsStatusTool}——
 * 权限层按工具分级，查询应当免确认、只读放行。
 *
 * <p><b>超时语义</b>：本工具的 timeoutMs 是「agent 愿意阻塞等待多久」，不是构建的
 * 最大时长。超时后构建在 GitHub 侧**仍在继续**（服务层不做远端取消），因此超时
 * 返回的是「仍在运行 + 链接」，让模型据此决定是再查还是告诉用户稍后看——
 * 而不是谎报失败。
 */
public final class GitHubActionsBuildTool extends BaseTool {

  /** 默认等待上限：10 分钟。云编译通常 5-15 分钟，与 gradle_build 同值。 */
  private static final long DEFAULT_TIMEOUT_MS = 10 * 60 * 1000L;

  /** 等待上限的封顶：30 分钟。比这更久的多是卡住而非真在构建。 */
  private static final long MAX_TIMEOUT_MS = 30 * 60 * 1000L;

  /**
   * GitHub PAT 的提供者。
   *
   * <p>用接口注入而非直接依赖 {@code CloudBuildTokenStore}：store 的构造需要
   * {@code Context}（且会做 keystore I/O），而工具层保持纯逻辑才可 JVM 单测。
   * 两个云编译工具共用同一个提供者实例（装配处一次构造）。
   */
  public interface TokenProvider {
    /** 当前 token；未配置时返回空串或 null。 */
    String get();
  }

  private final TokenProvider tokenProvider;
  /** 日志 zip 的临时目录（app 的 cacheDir）；null 时退回工作区。 */
  private final java.io.File cacheDir;

  public GitHubActionsBuildTool(TokenProvider tokenProvider) {
    this(tokenProvider, null);
  }

  /**
   * @param cacheDir 下载失败日志 zip 的临时目录（通常传 app 的 cacheDir）；
   *     null 时退回工作区目录
   */
  public GitHubActionsBuildTool(TokenProvider tokenProvider, java.io.File cacheDir) {
    this.tokenProvider = tokenProvider;
    this.cacheDir = cacheDir;
  }

  @Override
  public String getName() {
    return ToolNames.GITHUB_ACTIONS_BUILD;
  }

  @Override
  public String getDescription() {
    return "触发当前项目 GitHub 仓库的 Actions 云编译（workflow_dispatch），并等待到结束。"
        + "成功时返回产物（artifact）列表；失败时返回日志里的错误片段。"
        + "手机上本地构建慢/内存不足时，云编译是更快的替代路径。"
        + "注意：会消耗 GitHub Actions 分钟数，且等待可能长达数分钟。"
        + "只查最近一次运行的状态用 github_actions_status（更快、不消耗资源）。";
  }

  @Override
  public ToolCategory getCategory() {
    return ToolCategory.SYSTEM;
  }

  @Override
  public ToolDisplayCategory getDisplayCategory() {
    return ToolDisplayCategory.GENERIC;
  }

  /** 触发真实云编译（消耗 CI 资源），与 gradle_build 同级，需用户确认。 */
  @Override
  public boolean needsConfirmation() {
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
                    "workflow",
                    new JSONObject()
                        .put("type", "string")
                        .put("description",
                            "workflow 文件名（如 build.yml，只写文件名不要路径）。"
                                + "省略时自动使用 .github/workflows/ 下第一个 yml/yaml"))
                .put(
                    "branch",
                    new JSONObject()
                        .put("type", "string")
                        .put("description", "分支名，默认当前分支"))
                .put(
                    "timeoutMs",
                    new JSONObject()
                        .put("type", "number")
                        .put("description",
                            "等待上限毫秒数，默认 " + DEFAULT_TIMEOUT_MS + "，最大 " + MAX_TIMEOUT_MS
                                + "。超时后构建仍在 GitHub 侧继续")))
        .put("required", new org.json.JSONArray());
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    // ---- 前置校验（token / 工作区 / GitHub 仓库识别，全部无网络调用）----
    String token = tokenProvider == null ? "" : tokenProvider.get();
    CloudBuildSupport.Setup setup = CloudBuildSupport.prepare(getName(), token, context);
    if (setup.error != null) {
      return setup.error;
    }

    // ---- workflow 解析 ----
    String workflow = input.optString("workflow", "").trim();
    if (!workflow.isEmpty() && !CloudBuildSupport.isSafeWorkflowName(workflow)) {
      return error("workflow 参数只能是文件名（如 build.yml），不能包含路径。");
    }
    if (workflow.isEmpty()) {
      workflow = CloudBuildSupport.findDefaultWorkflow(setup.projectPath);
      if (workflow == null) {
        return error("项目里找不到 .github/workflows/*.yml。"
            + "请确认项目包含 GitHub Actions workflow（模板项目自带），或显式指定 workflow 参数。");
      }
    }

    // ---- 分支解析 ----
    String branch = input.optString("branch", "").trim();
    if (branch.isEmpty()) {
      branch = CloudBuildService.detectBranch(setup.projectPath);
      if (branch == null || branch.isEmpty()) {
        // 空仓库 / detached HEAD 时拿不到分支名。GitHub 的 dispatch 必须带 ref，
        // 提前报错比让 API 返回 422 更可读。
        return error("无法识别当前分支（仓库尚无提交或处于 detached HEAD）。请显式指定 branch 参数。");
      }
    }

    long timeoutMs = CloudBuildSupport.resolveTimeoutMs(input, DEFAULT_TIMEOUT_MS, MAX_TIMEOUT_MS);

    // ---- 取消检查器：ToolContext 的信号适配成 poller 的 CancelCheck ----
    ToolContext.CancellationSignal signal = context == null ? null : context.getCancellation();
    WorkflowRunPoller.CancelCheck cancel = () -> signal != null && signal.isCancelled();

    if (cancel.isCancelled()) {
      return error("操作已被用户停止。");
    }
    if (context != null) {
      context.reportProgress("触发云编译: " + workflow + " @ " + branch);
    }

    // ---- 触发并等待新 run 出现 ----
    CloudBuildService.BuildHandle handle;
    try {
      handle = setup.service.triggerAndAwaitRun(workflow, branch, null, cancel);
    } catch (CloudBuildService.CancelledException e) {
      return error("操作已被用户停止。");
    } catch (Exception e) {
      return error("触发云编译失败：" + CloudBuildSupport.describeApiError(e));
    }

    if (cancel.isCancelled()) {
      return error("操作已被用户停止。远端构建（#" + handle.run.id + "）可能仍在运行，"
          + "可用 github_actions_status 查看。");
    }

    // ---- 等待到终态（可取消，期间上报进度）----
    WorkflowRunPoller.Snapshot snapshot;
    try {
      snapshot = setup.service.awaitCompletion(handle, timeoutMs,
          s -> {
            if (context != null && s.run != null) {
              context.reportProgress("云编译 " + s.run.statusText() + "…");
            }
          },
          cancel);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return error("云编译等待被中断。");
    } catch (Exception e) {
      return error("等待云编译结果失败：" + CloudBuildSupport.describeApiError(e));
    }

    if (cancel.isCancelled()) {
      return error("操作已被用户停止。远端构建（#" + handle.run.id + "）可能仍在运行，"
          + "可用 github_actions_status 查看。");
    }

    if (snapshot == null) {
      // 理论上不会发生（run id 已知，poll 至少返回一次）；兜底而不是 NPE。
      return error("未能获取云编译状态。运行链接：" + handle.run.htmlUrl);
    }

    // ---- 超时未到终态：如实报告「仍在跑」，不是失败 ----
    if (!snapshot.isFinished()) {
      StringBuilder sb = new StringBuilder();
      sb.append("等待超时（").append(timeoutMs / 1000).append(" 秒），云编译仍在进行中。\n");
      sb.append("当前状态：").append(snapshot.run.statusText()).append('\n');
      appendJobs(sb, snapshot);
      sb.append("链接：").append(snapshot.run.htmlUrl).append('\n');
      sb.append("构建在 GitHub 侧会继续运行，稍后可用 github_actions_status 查询结果。");
      return ok(sb.toString());
    }

    // ---- 终态 ----
    if (snapshot.run.isSuccess()) {
      return ok(describeSuccess(snapshot, setup.service, workflow));
    }
    return error(describeFailure(snapshot, setup.service, context, workflow));
  }

  /** 成功结果：状态 + 产物列表（下载引导）。 */
  private String describeSuccess(WorkflowRunPoller.Snapshot snapshot, CloudBuildService service,
                                 String workflow) {
    StringBuilder sb = new StringBuilder();
    sb.append("云编译成功。\n");
    sb.append("workflow: ").append(workflow).append('\n');
    sb.append("分支: ").append(snapshot.run.headBranch).append('\n');
    appendJobs(sb, snapshot);

    // 产物列表：构建的价值就是产物，拿不到清单等于白跑一次。
    // 列 artifact 是一次额外 API 调用，失败不影响「构建成功」这一事实。
    try {
      List<WorkflowArtifact> artifacts = service.getClient().listArtifacts(snapshot.run.id);
      if (artifacts.isEmpty()) {
        sb.append("产物：无（该 workflow 未上传 artifact）。\n");
      } else {
        sb.append("产物：\n");
        for (WorkflowArtifact artifact : artifacts) {
          sb.append("  - ").append(artifact.name).append("（").append(artifact.sizeText()).append("）");
          if (artifact.expired) {
            sb.append("（已过期）");
          }
          sb.append('\n');
        }
      }
    } catch (Exception e) {
      sb.append("产物：查询失败（").append(CloudBuildSupport.describeApiError(e)).append("）\n");
    }
    sb.append("链接：").append(snapshot.run.htmlUrl);
    return sb.toString();
  }

  /** 失败结果：状态 + 失败日志片段（模型据此定位原因）。 */
  private String describeFailure(WorkflowRunPoller.Snapshot snapshot, CloudBuildService service,
                                 ToolContext context, String workflow) {
    StringBuilder sb = new StringBuilder();
    sb.append("云编译失败。\n");
    sb.append("workflow: ").append(workflow).append('\n');
    sb.append("分支: ").append(snapshot.run.headBranch).append('\n');
    sb.append("状态: ").append(snapshot.run.statusText()).append('\n');
    appendJobs(sb, snapshot);
    sb.append("链接：").append(snapshot.run.htmlUrl).append('\n');

    // 日志片段：没有它模型只知道「失败了」，只能反复试错（同 GradleBuildTool 的教训）。
    sb.append("\n失败日志片段：\n");
    try {
      RunLogExtractor.ExtractResult result =
          service.fetchFailureLog(snapshot.run.id, CloudBuildSupport.cacheDir(cacheDir, context));
      if (result.text == null || result.text.trim().isEmpty()) {
        sb.append("(日志为空，可打开上方链接查看)");
      } else {
        sb.append(result.text);
      }
    } catch (Exception e) {
      // 日志提取失败不掩盖构建失败本身：附上原因，让模型知道是「拿不到日志」而非「没日志」。
      sb.append("(获取失败日志出错：").append(CloudBuildSupport.describeApiError(e)).append(")");
    }
    return sb.toString();
  }

  /** 追加 job 级状态行（run 级只有一个整体状态，job 级才看得出卡在哪）。 */
  private static void appendJobs(StringBuilder sb, WorkflowRunPoller.Snapshot snapshot) {
    if (snapshot.jobs == null || snapshot.jobs.isEmpty()) {
      return;
    }
    sb.append("Jobs：\n");
    for (com.tom.rv2ide.cloudbuild.WorkflowJob job : snapshot.jobs) {
      sb.append("  - ").append(job.name).append(": ").append(job.statusText()).append('\n');
    }
  }
}
