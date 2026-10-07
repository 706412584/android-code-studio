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
import com.tom.rv2ide.git.CommitInfo;
import com.tom.rv2ide.git.FetchResult;
import com.tom.rv2ide.git.FileChange;
import com.tom.rv2ide.git.GitManager;
import com.tom.rv2ide.git.PullResult;
import com.tom.rv2ide.git.PushResult;
import com.tom.rv2ide.git.RemoteInfo;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;

/**
 * 项目 git 仓库的**写操作**工具（JGit，经 {@link GitManager}）。
 *
 * <p><b>为什么与只读的 {@link GitTool} 分开</b>：权限层按**工具**决定分级——
 * {@code needsConfirmation()} 无参数，无法对部分 action 收紧；只读模式的放行也只看
 * 工具分类。混在一个工具里只能整包按「写」处理，连 status/diff 都要弹确认框。
 * 拆分后查询免打扰、写操作逐次确认（授权粒度见 {@code ToolPermissionRule} 的
 * {@code git_write} 分支：按 action + 分支/远端/路径区分，「始终允许」不会被放大）。
 *
 * <p><b>为什么写操作走 GitManager 而不是直连 JGit</b>：Git 面板（ChangesFragment /
 * RemotesFragment）用的就是 GitManager，其 stage 处理了 ignore 规则、push 装配了
 * credentials provider。AI 与用户共用同一实现，行为不会漂移成两套语义。
 *
 * <p><b>为什么要注入身份信息</b>：提交需要作者名/邮箱、推送需要凭据，这些存在
 * PreferencesManager（app 层）。本工具不直接依赖它，而是经 {@link GitIdentityProvider}
 * 注入，使工具本身可在 JVM 上单测（假实现即可，无需 Android Context）。
 */
public final class GitWriteTool extends BaseTool {

  /** 各 action 白名单与说明。 */
  private static final Map<String, String> ACTIONS = new LinkedHashMap<>();

  static {
    ACTIONS.put("stage", "暂存指定路径的改动（file_path=\".\" 暂存全部）");
    ACTIONS.put("unstage", "取消暂存指定路径");
    ACTIONS.put("commit", "提交已暂存的改动（需 message）");
    ACTIONS.put("checkout", "切换到指定分支（需 branch）");
    ACTIONS.put("branch_create", "创建分支（需 branch）");
    ACTIONS.put("branch_delete", "删除分支（需 branch；未合并的分支会被拒绝）");
    ACTIONS.put("discard", "丢弃指定文件的未提交改动（不可逆！仅限已跟踪文件）");
    ACTIONS.put("remote_add", "添加远端（需 remote 与 url）");
    ACTIONS.put("remote_remove", "移除远端（需 remote）");
    ACTIONS.put("fetch", "抓取远端更新（默认 origin），不合并");
    ACTIONS.put("pull", "拉取并合并远端分支（默认 origin 的当前分支）");
    ACTIONS.put("push", "推送本地分支到远端（默认 origin 的当前分支）");
  }

  /** 失败时的按动作提示：GitManager 吞掉了异常细节，这里至少给出最常见的排查方向。 */
  private static final Map<String, String> FAILURE_HINTS = new LinkedHashMap<>();

  static {
    FAILURE_HINTS.put("stage", "路径不存在、写错了，或文件被 .gitignore 排除。");
    FAILURE_HINTS.put("unstage", "路径不在暂存区，或写错了。");
    FAILURE_HINTS.put("commit", "没有已暂存的改动，或提交被钩子拒绝。先 stage 再 commit。");
    FAILURE_HINTS.put("checkout", "分支不存在，或工作区有未提交改动会与之冲突（先 commit 或 discard）。");
    FAILURE_HINTS.put("branch_create", "同名分支已存在，或分支名非法。");
    FAILURE_HINTS.put("branch_delete", "分支不存在、未合并（git 拒绝删除），或是当前所在分支。");
    FAILURE_HINTS.put("discard", "文件未被跟踪（untracked 无法丢弃），或路径写错了。");
    FAILURE_HINTS.put("remote_add", "同名远端已存在，或 URL 非法。");
    FAILURE_HINTS.put("remote_remove", "远端不存在。");
    FAILURE_HINTS.put("fetch", "远端不可达、凭据缺失，或网络问题。");
    FAILURE_HINTS.put("pull", "本地有未提交改动与远端冲突，或远端/凭据问题。");
    FAILURE_HINTS.put("push", "远端不可达、凭据缺失，或本地落后于远端（先 pull）。");
  }

  /**
   * 提交身份与远端凭据的提供者。由 app 层用 PreferencesManager 实现；
   * 单测注入假实现。任何字段都允许返回 null（凭据未配置是常态）。
   */
  public interface GitIdentityProvider {
    /** 提交作者名；null 时用 "User"。 */
    String authorName();

    /** 提交作者邮箱；null 时用 "user@example.com"。 */
    String authorEmail();

    /** 远端用户名；null 表示未配置。 */
    String username();

    /** 远端密码/令牌；null 表示未配置。 */
    String password();
  }

  private final GitIdentityProvider identity;

  public GitWriteTool(GitIdentityProvider identity) {
    this.identity = identity;
  }

  @Override
  public String getName() {
    return ToolNames.GIT_WRITE;
  }

  @Override
  public String getDescription() {
    return "对当前项目 git 仓库执行写操作：stage/unstage/commit（暂存与提交）、"
        + "checkout/branch_create/branch_delete（分支）、discard（丢弃改动，不可逆）、"
        + "remote_add/remote_remove/fetch/pull/push（远端同步）。"
        + "每次调用需要用户确认（AUTO 模式除外）。"
        + "建议流程：git status 看改动 → git diff 自查 → stage → commit。"
        + "推送前确认用户意图，推送不可撤回。";
  }

  @Override
  public ToolCategory getCategory() {
    return ToolCategory.SYSTEM;
  }

  @Override
  public ToolDisplayCategory getDisplayCategory() {
    return ToolDisplayCategory.SHELL;
  }

  @Override
  public boolean needsConfirmation() {
    return true;
  }

  /** 写操作，只读模式下不放行。 */
  @Override
  public boolean isAllowedInReadonlyMode() {
    return false;
  }

  /** 刻意保持串行（默认）：stage/commit/push 的调用顺序不能被打乱。 */

  @Override
  public JSONObject getParameters() throws org.json.JSONException {
    JSONObject action = new JSONObject().put("type", "string").put("description",
        "操作类型，见下");
    StringBuilder hint = new StringBuilder("可用值：");
    for (Map.Entry<String, String> e : ACTIONS.entrySet()) {
      hint.append("\n- ").append(e.getKey()).append(": ").append(e.getValue());
    }
    action.put("description", action.getString("description") + "\n" + hint);
    action.put("enum", new org.json.JSONArray(ACTIONS.keySet()));
    return new JSONObject()
        .put("type", "object")
        .put(
            "properties",
            new JSONObject()
                .put("action", action)
                .put(
                    "file_path",
                    new JSONObject()
                        .put("type", "string")
                        .put("description",
                            "stage/unstage/discard 用：目标路径（仓库相对或绝对）。"
                                + "stage 传 \".\" 暂存全部改动"))
                .put(
                    "message",
                    new JSONObject()
                        .put("type", "string")
                        .put("description", "commit 用：提交信息"))
                .put(
                    "branch",
                    new JSONObject()
                        .put("type", "string")
                        .put("description",
                            "checkout/branch_create/branch_delete 用：分支名；"
                                + "pull/push 可选，默认当前分支"))
                .put(
                    "remote",
                    new JSONObject()
                        .put("type", "string")
                        .put("description",
                            "remote_* / fetch / pull / push 用：远端名，默认 origin"))
                .put(
                    "url",
                    new JSONObject()
                        .put("type", "string")
                        .put("description", "remote_add 用：远端 URL")))
        .put("required", new org.json.JSONArray());
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    String action = input.optString("action", "").trim().toLowerCase();
    if (action.isEmpty()) {
      // 模型可能经别名（git_commit / git_push，见 ToolRegistry.ALIASES）调到本工具
      // 却没带 action——别名只能归一工具名，无法代为补参数。给出明确的下一步，
      // 而不是「不支持的 action：」这种带空值的费解文案。
      return GitRepoSupport.err(getName(),
          "缺少 action 参数。可用：" + String.join(", ", ACTIONS.keySet()));
    }
    if (!ACTIONS.containsKey(action)) {
      return GitRepoSupport.err(getName(),
          "不支持的 action：" + action + "。可用：" + String.join(", ", ACTIONS.keySet()));
    }

    ToolResult[] openError = new ToolResult[1];
    String root = GitRepoSupport.requireRepoRoot(getName(), context, openError);
    if (root == null) {
      return openError[0];
    }

    GitManager manager = new GitManager(root);
    if (!manager.openRepository()) {
      return GitRepoSupport.err(getName(),
          "打开 git 仓库失败：" + root + "/.git 无法解析。仓库可能损坏，请用命令行 git fsck 检查。");
    }
    try {
      return dispatch(action, input, context, manager, root);
    } catch (RuntimeException e) {
      // GitManager 内部吞异常返回 false；但构造命令阶段仍可能抛（如 URIish 解析）。
      return GitRepoSupport.err(getName(),
          "git " + action + " 异常：" + e.getClass().getSimpleName()
              + (e.getMessage() == null ? "" : ": " + e.getMessage()));
    } finally {
      manager.close();
    }
  }

  private ToolResult dispatch(
      String action, JSONObject input, ToolContext context, GitManager manager, String root) {
    switch (action) {
      case "stage":
        return stage(input, manager, root);
      case "unstage":
        return unstage(input, manager, root);
      case "commit":
        return commit(input, manager);
      case "checkout":
        return checkout(input, manager);
      case "branch_create":
        return branchCreate(input, manager);
      case "branch_delete":
        return branchDelete(input, manager);
      case "discard":
        return discard(input, manager, root);
      case "remote_add":
        return remoteAdd(input, manager);
      case "remote_remove":
        return remoteRemove(input, manager);
      case "fetch":
        return fetch(input, context, manager);
      case "pull":
        return pull(input, context, manager);
      case "push":
        return push(input, context, manager);
      default:
        return GitRepoSupport.err(getName(), "未实现的 action：" + action);
    }
  }

  // ---- 暂存区 ----

  private ToolResult stage(JSONObject input, GitManager manager, String root) {
    String path = requirePath(input, root);
    if (path == null) {
      return GitRepoSupport.err(getName(), "stage 需要 file_path（暂存全部用 \".\"）。");
    }
    // "." 走 stageAllFiles（与面板的「全部暂存」同一实现），不经过 stageFile——
    // 后者对 "." 会先做一次 isIgnored(".") 探测，语义含混。
    if (".".equals(path)) {
      if (!manager.stageAllFiles()) {
        return failure("stage");
      }
      return GitRepoSupport.ok(getName(), "已暂存全部改动。");
    }
    if (!manager.stageFile(path)) {
      return failure("stage");
    }
    return GitRepoSupport.ok(getName(), "已暂存：" + path);
  }

  private ToolResult unstage(JSONObject input, GitManager manager, String root) {
    String path = requirePath(input, root);
    if (path == null) {
      return GitRepoSupport.err(getName(), "unstage 需要 file_path。");
    }
    if (!manager.unstageFile(path)) {
      return failure("unstage");
    }
    return GitRepoSupport.ok(getName(), "已取消暂存：" + path);
  }

  private ToolResult discard(JSONObject input, GitManager manager, String root) {
    String path = requirePath(input, root);
    if (path == null) {
      return GitRepoSupport.err(getName(), "discard 需要 file_path。");
    }
    // 预检：JGit 对**未跟踪**路径的 checkout(path) 静默无操作——不抛异常也不改文件，
    // 而 GitManager.discardChanges 返回 true，直接调用会让工具谎报「已丢弃」
    // （真机同版 JGit 6.8.0 实测确认）。未跟踪文件本就没有可回退的历史版本，
    // 这里给出替代做法，而不是假装成功。
    for (FileChange change : manager.getChangedFiles()) {
      if (change.getPath().equals(path)
          && change.getChangeType() == com.tom.rv2ide.git.ChangeType.UNTRACKED) {
        return GitRepoSupport.err(getName(),
            "文件未被跟踪（untracked），没有可回退的版本，无法 discard：" + path
                + "。要删除请用 file_delete；要保留请先 action=stage。");
      }
    }
    if (!manager.discardChanges(path)) {
      return failure("discard");
    }
    return GitRepoSupport.ok(getName(),
        "已丢弃未提交改动：" + path + "（工作区内容已回退到暂存区/HEAD 版本）");
  }

  // ---- 提交 ----

  private ToolResult commit(JSONObject input, GitManager manager) {
    String message = input.optString("message", "").trim();
    if (message.isEmpty()) {
      return GitRepoSupport.err(getName(), "commit 需要 message（提交信息）。");
    }
    // 预检：没有已暂存的改动时 JGit 抛 EmptyCommit/NoFilesToCommit，错误信息晦涩；
    // 这里直接给出可执行的下一步。
    boolean hasStaged = false;
    for (FileChange change : manager.getChangedFiles()) {
      if (change.isStaged()) {
        hasStaged = true;
        break;
      }
    }
    if (!hasStaged) {
      return GitRepoSupport.err(getName(),
          "没有已暂存的改动，无法提交。请先用 action=stage 暂存要提交的文件。");
    }

    String author = identity == null ? null : identity.authorName();
    String email = identity == null ? null : identity.authorEmail();
    if (author == null || author.trim().isEmpty()) {
      author = "User";
    }
    if (email == null || email.trim().isEmpty()) {
      email = "user@example.com";
    }

    if (!manager.commit(message, author, email)) {
      return failure("commit");
    }

    // 成功后回读最新提交，让模型（与用户）确认提交真的落地及其 hash。
    StringBuilder sb = new StringBuilder("已提交（作者 ").append(author).append("）\n");
    List<CommitInfo> latest = manager.getCommitHistory(1);
    if (!latest.isEmpty()) {
      CommitInfo c = latest.get(0);
      sb.append(c.getShortHash()).append("  ").append(c.getMessage().split("\n", 2)[0]);
    }
    return GitRepoSupport.ok(getName(), sb.toString());
  }

  // ---- 分支 ----

  private ToolResult checkout(JSONObject input, GitManager manager) {
    String branch = requireBranch(input);
    if (branch == null) {
      return GitRepoSupport.err(getName(), "checkout 需要 branch（分支名）。");
    }
    if (!manager.getAllBranches().contains(branch)) {
      return GitRepoSupport.err(getName(),
          "分支不存在：" + branch + "。现有分支：" + join(manager.getAllBranches())
              + "。如需新建请用 action=branch_create。");
    }
    if (!manager.checkoutBranch(branch)) {
      return failure("checkout");
    }
    return GitRepoSupport.ok(getName(), "已切换到分支：" + branch);
  }

  private ToolResult branchCreate(JSONObject input, GitManager manager) {
    String branch = requireBranch(input);
    if (branch == null) {
      return GitRepoSupport.err(getName(), "branch_create 需要 branch（分支名）。");
    }
    if (manager.getAllBranches().contains(branch)) {
      return GitRepoSupport.err(getName(), "分支已存在：" + branch + "。可直接 action=checkout 切换。");
    }
    if (!manager.createBranch(branch)) {
      return failure("branch_create");
    }
    return GitRepoSupport.ok(getName(),
        "已创建分支：" + branch + "（当前仍在 " + manager.getCurrentBranch() + "，切换用 action=checkout）");
  }

  private ToolResult branchDelete(JSONObject input, GitManager manager) {
    String branch = requireBranch(input);
    if (branch == null) {
      return GitRepoSupport.err(getName(), "branch_delete 需要 branch（分支名）。");
    }
    String current = manager.getCurrentBranch();
    if (branch.equals(current)) {
      return GitRepoSupport.err(getName(),
          "不能删除当前所在的分支：" + branch + "。先 checkout 到其它分支再删除。");
    }
    if (!manager.getAllBranches().contains(branch)) {
      return GitRepoSupport.err(getName(),
          "分支不存在：" + branch + "。现有分支：" + join(manager.getAllBranches()));
    }
    if (!manager.deleteBranch(branch)) {
      return failure("branch_delete");
    }
    return GitRepoSupport.ok(getName(),
        "已删除分支：" + branch + "。剩余：" + join(manager.getAllBranches()));
  }

  // ---- 远端 ----

  private ToolResult remoteAdd(JSONObject input, GitManager manager) {
    String remote = input.optString("remote", "").trim();
    String url = input.optString("url", "").trim();
    if (remote.isEmpty()) {
      return GitRepoSupport.err(getName(), "remote_add 需要 remote（远端名，如 origin）。");
    }
    if (url.isEmpty()) {
      return GitRepoSupport.err(getName(), "remote_add 需要 url（远端地址）。");
    }
    for (RemoteInfo existing : manager.getRemotes()) {
      if (existing.getName().equals(remote)) {
        return GitRepoSupport.err(getName(),
            "远端已存在：" + remote + " -> " + existing.getFetchUrl()
                + "。如需更换请先 action=remote_remove。");
      }
    }
    if (!manager.addRemote(remote, url)) {
      return failure("remote_add");
    }
    return GitRepoSupport.ok(getName(), "已添加远端：" + remote + " -> " + url);
  }

  private ToolResult remoteRemove(JSONObject input, GitManager manager) {
    String remote = input.optString("remote", "").trim();
    if (remote.isEmpty()) {
      return GitRepoSupport.err(getName(), "remote_remove 需要 remote（远端名）。");
    }
    if (!manager.removeRemote(remote)) {
      return failure("remote_remove");
    }
    return GitRepoSupport.ok(getName(), "已移除远端：" + remote);
  }

  // ---- 远端同步 ----

  private ToolResult fetch(JSONObject input, ToolContext context, GitManager manager) {
    String remote = remoteOrDefault(input);
    ToolResult guard = guardRemote(manager, remote);
    if (guard != null) {
      return guard;
    }
    context.reportProgress("git fetch " + remote);
    FetchResult result = manager.fetch(remote, username(), password());
    if (!result.getSuccess()) {
      return remoteFailure("fetch", result.getMessage());
    }
    return GitRepoSupport.ok(getName(), "fetch 完成（" + remote + "）。");
  }

  private ToolResult pull(JSONObject input, ToolContext context, GitManager manager) {
    String remote = remoteOrDefault(input);
    ToolResult guard = guardRemote(manager, remote);
    if (guard != null) {
      return guard;
    }
    String branch = input.optString("branch", "").trim();
    context.reportProgress("git pull " + remote + (branch.isEmpty() ? "" : " " + branch));
    PullResult result = manager.pull(remote, branch.isEmpty() ? null : branch, username(), password());
    if (!result.getSuccess()) {
      return remoteFailure("pull", result.getMessage());
    }
    return GitRepoSupport.ok(getName(),
        "pull 完成（" + remote + "）。当前分支：" + manager.getCurrentBranch());
  }

  private ToolResult push(JSONObject input, ToolContext context, GitManager manager) {
    String remote = remoteOrDefault(input);
    ToolResult guard = guardRemote(manager, remote);
    if (guard != null) {
      return guard;
    }
    String branch = input.optString("branch", "").trim();
    if (branch.isEmpty()) {
      String current = manager.getCurrentBranch();
      if (current == null || current.isEmpty()) {
        return GitRepoSupport.err(getName(),
            "无法确定当前分支（HEAD 未出生？）。请显式指定 branch。");
      }
      branch = current;
    }
    context.reportProgress("git push " + remote + " " + branch);
    PushResult result = manager.push(remote, branch, username(), password());
    if (!result.getSuccess()) {
      return remoteFailure("push", result.getMessage());
    }
    return GitRepoSupport.ok(getName(), "已推送：" + branch + " -> " + remote);
  }

  /** 远端不存在时给出可操作错误；存在返回 null。 */
  private ToolResult guardRemote(GitManager manager, String remote) {
    List<RemoteInfo> remotes = manager.getRemotes();
    if (remotes.isEmpty()) {
      return GitRepoSupport.err(getName(),
          "没有配置远端仓库。先用 action=remote_add 添加（如 origin）。");
    }
    for (RemoteInfo info : remotes) {
      if (info.getName().equals(remote)) {
        return null;
      }
    }
    return GitRepoSupport.err(getName(),
        "远端不存在：" + remote + "。已配置：" + joinRemotes(remotes) + "。");
  }

  // ---- 公共小件 ----

  /** 取 file_path 并归一为仓库相对路径；空则返回 null。 */
  private static String requirePath(JSONObject input, String root) {
    String path = GitRepoSupport.toRepoRelative(root, input.optString("file_path", "").trim());
    return path.isEmpty() ? null : path;
  }

  private static String requireBranch(JSONObject input) {
    String branch = input.optString("branch", "").trim();
    return branch.isEmpty() ? null : branch;
  }

  private static String remoteOrDefault(JSONObject input) {
    String remote = input.optString("remote", "").trim();
    return remote.isEmpty() ? "origin" : remote;
  }

  private String username() {
    return identity == null ? null : identity.username();
  }

  private String password() {
    return identity == null ? null : identity.password();
  }

  /** 动作失败：附加该动作最常见的排查方向。 */
  private ToolResult failure(String action) {
    String hint = FAILURE_HINTS.get(action);
    return GitRepoSupport.err(getName(),
        "git " + action + " 失败。" + (hint == null ? "" : hint));
  }

  /**
   * 远端操作失败：附加凭据缺失提示（若确实没配）。
   *
   * <p>只在未配置凭据时提示：公开仓库的 fetch、本地路径远端都不需要凭据，
   * 无条件提示会把「网络不通」误导成「去配密码」。
   */
  private ToolResult remoteFailure(String action, String message) {
    StringBuilder sb = new StringBuilder("git ").append(action).append(" 失败");
    if (message != null && !message.trim().isEmpty()) {
      sb.append("：").append(message.trim());
    }
    sb.append('。');
    String hint = FAILURE_HINTS.get(action);
    if (hint != null) {
      sb.append(hint);
    }
    if (username() == null || password() == null) {
      sb.append("\n当前未配置远端凭据。如需认证：到「设置 → Git」填写用户名与密码（或访问令牌），"
          + "并勾选「记住凭据」。");
    }
    return GitRepoSupport.err(getName(), sb.toString());
  }

  private static String join(List<String> items) {
    return items.isEmpty() ? "（无）" : String.join(", ", items);
  }

  private static String joinRemotes(List<RemoteInfo> remotes) {
    StringBuilder sb = new StringBuilder();
    for (RemoteInfo info : remotes) {
      if (sb.length() > 0) {
        sb.append(", ");
      }
      sb.append(info.getName());
    }
    return sb.toString();
  }
}
