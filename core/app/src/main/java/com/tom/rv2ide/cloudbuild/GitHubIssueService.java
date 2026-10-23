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

package com.tom.rv2ide.cloudbuild;

import java.util.List;

/**
 * Issue / PR 的门面：把「查 → 格式化」串起来，供 AI 工具与 UI 共用。
 *
 * <p><b>为什么格式化是静态纯函数</b>：文案是模型与用户看到的最终输出，改错一个字
 * 比改错一行逻辑更难发现（同 {@link CloudBuildService#formatLatestRun} 的教训）。
 * 与网络调用分离后可以直接 JVM 单测，不需要 mock HTTP。
 *
 * <p><b>为什么输出是文本而不是 JSON</b>：工具结果直接进模型上下文。JSON 会让模型
 * 多花 token 解析一层，而这里的信息结构很简单（列表 + 详情），纯文本更省也更易读。
 */
public final class GitHubIssueService {

  /** 正文截断长度：issue 正文可能几千字，全塞进上下文会挤掉真正需要的信息。 */
  private static final int BODY_PREVIEW_CHARS = 2000;

  private final GitHubIssueClient client;

  public GitHubIssueService(String token, String owner, String repo) {
    this.client = new GitHubIssueClient(token, owner, repo);
  }

  public GitHubIssueClient getClient() {
    return client;
  }

  public String getOwner() {
    return client.getOwner();
  }

  public String getRepo() {
    return client.getRepo();
  }

  /* ───────────────────────── Issue ───────────────────────── */

  /** 查列表并格式化为文本。 */
  public String describeIssues(String state, String labels, int limit) throws Exception {
    List<GitHubIssue> issues = client.listIssues(state, labels, limit);
    return formatIssues(client.getOwner(), client.getRepo(), state, labels, issues);
  }

  /** 查单个 issue 并格式化（含评论）。 */
  public String describeIssue(int number, int commentLimit) throws Exception {
    GitHubIssue issue = client.getIssue(number);
    List<String> comments = commentLimit > 0
        ? client.listComments(number, commentLimit)
        : java.util.Collections.emptyList();
    return formatIssue(issue, comments);
  }

  /* ───────────────────────── Pull Request ───────────────────────── */

  public String describePullRequests(String state, int limit) throws Exception {
    List<GitHubPullRequest> pulls = client.listPullRequests(state, limit);
    return formatPullRequests(client.getOwner(), client.getRepo(), state, pulls);
  }

  public String describePullRequest(int number) throws Exception {
    return formatPullRequest(client.getPullRequest(number));
  }

  /* ───────────────────────── 格式化（纯函数，可单测） ───────────────────────── */

  static String formatIssues(String owner, String repo, String state, String labels,
                             List<GitHubIssue> issues) {
    if (issues == null || issues.isEmpty()) {
      return "仓库 " + owner + "/" + repo + " 没有符合条件的 issue（状态："
          + stateLabel(state)
          + (labels == null || labels.trim().isEmpty() ? "" : "，标签：" + labels.trim())
          + "）。";
    }
    StringBuilder builder = new StringBuilder();
    builder.append("仓库 ").append(owner).append('/').append(repo).append(" 的 issue：")
        .append(issues.size()).append(" 条\n");
    for (GitHubIssue issue : issues) {
      builder.append("#").append(issue.number).append(' ').append(oneLine(issue.title))
          .append(" · ").append(issue.stateText());
      if (!issue.author.isEmpty()) {
        builder.append(" · @").append(issue.author);
      }
      if (!issue.labels.isEmpty()) {
        builder.append(" · [").append(String.join(", ", issue.labels)).append(']');
      }
      if (issue.comments > 0) {
        builder.append(" · ").append(issue.comments).append(" 条评论");
      }
      builder.append('\n');
    }
    builder.append("（用 github_issue action=get 并传 number 查看正文与评论）");
    return builder.toString();
  }

  static String formatIssue(GitHubIssue issue, List<String> comments) {
    StringBuilder builder = new StringBuilder();
    builder.append("#").append(issue.number).append(' ').append(oneLine(issue.title))
        .append('\n');
    builder.append("状态：").append(issue.stateText()).append('\n');
    if (!issue.author.isEmpty()) {
      builder.append("作者：@").append(issue.author).append('\n');
    }
    builder.append("创建时间：").append(issue.createdAt).append('\n');
    if (!issue.labels.isEmpty()) {
      builder.append("标签：").append(String.join(", ", issue.labels)).append('\n');
    }
    builder.append("链接：").append(issue.htmlUrl).append('\n');
    builder.append("\n正文：\n").append(preview(issue.body));
    if (comments != null && !comments.isEmpty()) {
      builder.append("\n\n评论（").append(comments.size()).append(" 条）：\n");
      for (int i = 0; i < comments.size(); i++) {
        builder.append(i + 1).append(". ").append(preview(comments.get(i))).append('\n');
      }
    }
    return builder.toString();
  }

  static String formatPullRequests(String owner, String repo, String state,
                                   List<GitHubPullRequest> pulls) {
    if (pulls == null || pulls.isEmpty()) {
      return "仓库 " + owner + "/" + repo + " 没有符合条件的 PR（状态："
          + stateLabel(state) + "）。";
    }
    StringBuilder builder = new StringBuilder();
    builder.append("仓库 ").append(owner).append('/').append(repo).append(" 的 PR：")
        .append(pulls.size()).append(" 条\n");
    for (GitHubPullRequest pull : pulls) {
      builder.append("#").append(pull.number).append(' ').append(oneLine(pull.title))
          .append(" · ").append(pull.stateText())
          .append(" · ").append(pull.headRef).append(" → ").append(pull.baseRef);
      if (!pull.author.isEmpty()) {
        builder.append(" · @").append(pull.author);
      }
      builder.append('\n');
    }
    builder.append("（用 github_pr action=get 并传 number 查看详情）");
    return builder.toString();
  }

  static String formatPullRequest(GitHubPullRequest pull) {
    StringBuilder builder = new StringBuilder();
    builder.append("#").append(pull.number).append(' ').append(oneLine(pull.title)).append('\n');
    builder.append("状态：").append(pull.stateText()).append('\n');
    builder.append("分支：").append(pull.headRef).append(" → ").append(pull.baseRef).append('\n');
    if (!pull.author.isEmpty()) {
      builder.append("作者：@").append(pull.author).append('\n');
    }
    if (pull.changedFiles > 0) {
      builder.append("变更：").append(pull.changedFiles).append(" 个文件，+")
          .append(pull.additions).append(" / -").append(pull.deletions).append('\n');
    }
    if (!pull.mergeableState.isEmpty()) {
      builder.append("可合并状态：").append(pull.mergeableState).append('\n');
    }
    builder.append("链接：").append(pull.htmlUrl).append('\n');
    if (!pull.body.isEmpty()) {
      builder.append("\n正文：\n").append(preview(pull.body));
    }
    return builder.toString();
  }

  /** state 的中文说明（列表为空时的提示用）。 */
  static String stateLabel(String state) {
    String value = state == null ? "" : state.trim().toLowerCase(java.util.Locale.ROOT);
    if ("closed".equals(value)) {
      return "已关闭";
    }
    if ("all".equals(value)) {
      return "全部";
    }
    return "开启";
  }

  /** 把多行标题压成一行（GitHub 允许标题里换行，列表里会撑坏排版）。 */
  static String oneLine(String text) {
    if (text == null) {
      return "";
    }
    return text.replace('\r', ' ').replace('\n', ' ').trim();
  }

  /** 正文截断：超长时保留开头并注明截断。 */
  static String preview(String text) {
    if (text == null || text.isEmpty()) {
      return "（无）";
    }
    String trimmed = text.trim();
    if (trimmed.length() <= BODY_PREVIEW_CHARS) {
      return trimmed;
    }
    return trimmed.substring(0, BODY_PREVIEW_CHARS)
        + "\n…（正文过长，已截断，共 " + trimmed.length() + " 字；完整内容见链接）";
  }
}
