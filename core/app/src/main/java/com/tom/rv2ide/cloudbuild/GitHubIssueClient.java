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

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * GitHub Issue / Pull Request 的 REST 客户端。
 *
 * <p><b>为什么与 {@link GitHubActionsClient} 分开</b>：云编译客户端只关心
 * workflow/run/artifact；issue 与 PR 是另一组端点与另一套对象。合成一个类会让
 * 「只想查 issue」的工具被迫依赖 workflow 相关代码，也让单测需要 mock 更多路径。
 * 两者共用 {@link GitHubRestClient} 的请求与鉴权层，安全边界只有一处。
 *
 * <p><b>issue 与 PR 共用端点</b>：GitHub 的 {@code /issues} 会返回 PR，因此
 * {@link #listIssues} 必须过滤；反过来 PR 的评论也走 {@code /issues/{n}/comments}。
 *
 * <p><b>线程模型</b>：全部同步阻塞，调用方放后台线程。
 */
public final class GitHubIssueClient extends GitHubRestClient {

  /** 单页上限（GitHub 最大 100）。 */
  private static final int MAX_PER_PAGE = 100;

  public GitHubIssueClient(String token, String owner, String repo) {
    super(token, owner, repo);
  }

  /* ───────────────────────── Issue ───────────────────────── */

  /**
   * 列出 issue（**不含 PR**）。
   *
   * @param state open / closed / all，空串按 open
   * @param labels 逗号分隔的标签过滤，可空
   * @param limit 最多返回条数（1..100）
   */
  public List<GitHubIssue> listIssues(String state, String labels, int limit) throws Exception {
    List<String> params = new ArrayList<>();
    params.add("state=" + encodeQueryParam(normalizeState(state)));
    params.add("per_page=" + clampLimit(limit));
    params.add("sort=updated");
    if (labels != null && !labels.trim().isEmpty()) {
      params.add("labels=" + encodeQueryParam(labels.trim()));
    }
    String url = repoPath() + "/issues?" + join(params, "&");
    JSONArray array = executeJsonArray(url);
    List<GitHubIssue> issues = new ArrayList<>();
    for (int i = 0; i < array.length(); i++) {
      JSONObject item = array.optJSONObject(i);
      if (item == null) {
        continue;
      }
      GitHubIssue issue = GitHubIssue.fromJson(item);
      // PR 也走 /issues 端点，不过滤会让用户看到一堆「issue」其实是 PR。
      if (!issue.isPullRequest) {
        issues.add(issue);
      }
    }
    return issues;
  }

  /** 查单个 issue。 */
  public GitHubIssue getIssue(int number) throws Exception {
    return GitHubIssue.fromJson(executeJson("GET", repoPath() + "/issues/" + number, null));
  }

  /**
   * 创建 issue。
   *
   * @param labels 逗号分隔标签，可空
   * @return 创建后的 issue（含 number 与 html_url）
   */
  public GitHubIssue createIssue(String title, String body, String labels) throws Exception {
    JSONObject payload = new JSONObject();
    payload.put("title", title == null ? "" : title.trim());
    if (body != null && !body.isEmpty()) {
      payload.put("body", body);
    }
    if (labels != null && !labels.trim().isEmpty()) {
      JSONArray array = new JSONArray();
      for (String label : labels.split(",")) {
        if (!label.trim().isEmpty()) {
          array.put(label.trim());
        }
      }
      if (array.length() > 0) {
        payload.put("labels", array);
      }
    }
    return GitHubIssue.fromJson(
        executeJson("POST", repoPath() + "/issues", payload.toString()));
  }

  /** 关闭或重新打开 issue（state = open / closed）。 */
  public GitHubIssue setIssueState(int number, String state) throws Exception {
    JSONObject payload = new JSONObject().put("state", normalizeState(state));
    return GitHubIssue.fromJson(
        executeJson("PATCH", repoPath() + "/issues/" + number, payload.toString()));
  }

  /** 给 issue 或 PR 加评论（两者共用同一个评论端点）。 */
  public String addComment(int number, String body) throws Exception {
    JSONObject payload = new JSONObject().put("body", body == null ? "" : body);
    JSONObject json = executeJson(
        "POST", repoPath() + "/issues/" + number + "/comments", payload.toString());
    return json.optString("html_url", "");
  }

  /** 列出 issue / PR 的评论正文（按时间正序，最多 {@code limit} 条）。 */
  public List<String> listComments(int number, int limit) throws Exception {
    String url = repoPath() + "/issues/" + number + "/comments?per_page=" + clampLimit(limit);
    JSONArray array = executeJsonArray(url);
    List<String> comments = new ArrayList<>();
    for (int i = 0; i < array.length(); i++) {
      JSONObject item = array.optJSONObject(i);
      if (item == null) {
        continue;
      }
      String author = item.optJSONObject("user") == null
          ? "" : item.optJSONObject("user").optString("login", "");
      String body = item.optString("body", "");
      comments.add((author.isEmpty() ? "" : "@" + author + "：") + body);
    }
    return comments;
  }

  /* ───────────────────────── Pull Request ───────────────────────── */

  /** 列出 PR。 */
  public List<GitHubPullRequest> listPullRequests(String state, int limit) throws Exception {
    String url = repoPath() + "/pulls?state=" + encodeQueryParam(normalizeState(state))
        + "&per_page=" + clampLimit(limit) + "&sort=updated";
    return GitHubPullRequest.listFrom(executeJsonArray(url));
  }

  /** 查单个 PR。 */
  public GitHubPullRequest getPullRequest(int number) throws Exception {
    return GitHubPullRequest.fromJson(
        executeJson("GET", repoPath() + "/pulls/" + number, null));
  }

  /**
   * 创建 PR。
   *
   * @param head 源分支（同仓库时只写分支名；跨 fork 用 {@code user:branch}）
   * @param base 目标分支
   */
  public GitHubPullRequest createPullRequest(String title, String head, String base,
                                             String body, boolean draft) throws Exception {
    JSONObject payload = new JSONObject();
    payload.put("title", title == null ? "" : title.trim());
    payload.put("head", head == null ? "" : head.trim());
    payload.put("base", base == null ? "" : base.trim());
    if (body != null && !body.isEmpty()) {
      payload.put("body", body);
    }
    if (draft) {
      payload.put("draft", true);
    }
    return GitHubPullRequest.fromJson(
        executeJson("POST", repoPath() + "/pulls", payload.toString()));
  }

  /**
   * 合并 PR。
   *
   * @param mergeMethod merge / squash / rebase，空串按 merge
   * @return GitHub 的合并响应消息
   */
  public String mergePullRequest(int number, String mergeMethod) throws Exception {
    JSONObject payload = new JSONObject()
        .put("merge_method", normalizeMergeMethod(mergeMethod));
    JSONObject json = executeJson(
        "PUT", repoPath() + "/pulls/" + number + "/merge", payload.toString());
    return json.optString("message", "已合并");
  }

  /** 关闭 PR（PR 的关闭走 issues 端点，与 issue 相同）。 */
  public GitHubPullRequest closePullRequest(int number) throws Exception {
    JSONObject payload = new JSONObject().put("state", "closed");
    executeJson("PATCH", repoPath() + "/issues/" + number, payload.toString());
    return getPullRequest(number);
  }

  /* ───────────────────────── 内部 ───────────────────────── */

  private JSONArray executeJsonArray(String url) throws Exception {
    com.tom.rv2ide.ai.protocol.SimpleHttpClient.Response response =
        execute("GET", url, null);
    checkSuccess(response);
    // GitHub 的分页接口一定返回数组；万一返回对象（错误体已被 checkSuccess 拦下），
    // 这里退化成空数组而不是抛 JSONException——模型拿到「空列表」比拿到崩溃好。
    String body = response.body == null ? "" : response.body.trim();
    if (body.startsWith("[")) {
      return new JSONArray(body);
    }
    return new JSONArray();
  }

  private static String normalizeState(String state) {
    String value = state == null ? "" : state.trim().toLowerCase(java.util.Locale.ROOT);
    if ("closed".equals(value) || "all".equals(value)) {
      return value;
    }
    return "open";
  }

  private static String normalizeMergeMethod(String method) {
    String value = method == null ? "" : method.trim().toLowerCase(java.util.Locale.ROOT);
    if ("squash".equals(value) || "rebase".equals(value)) {
      return value;
    }
    return "merge";
  }

  private static int clampLimit(int limit) {
    if (limit <= 0) {
      return 20;
    }
    return Math.min(limit, MAX_PER_PAGE);
  }

  private static String join(List<String> parts, String separator) {
    StringBuilder builder = new StringBuilder();
    for (String part : parts) {
      if (builder.length() > 0) {
        builder.append(separator);
      }
      builder.append(part);
    }
    return builder.toString();
  }
}
