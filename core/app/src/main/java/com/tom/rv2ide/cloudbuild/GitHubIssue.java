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

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 一个 GitHub Issue 的只读快照。
 *
 * <p>字段只保留列表/详情真正要展示的部分：GitHub 的 issue 对象有 30+ 字段，
 * 全量映射只会带来维护负担（同 {@link WorkflowRun} 的取舍）。
 *
 * <p><b>注意 issue 与 PR 共用同一套 REST 端点</b>：{@code GET /repos/{o}/{r}/issues}
 * 的返回里**混有 PR**（带 {@code pull_request} 字段）。{@link #isPullRequest} 就是
 * 用来把它们分出去的——不区分的话，用户问「有哪些 issue」会看到一堆 PR。
 */
public final class GitHubIssue {

  public final int number;
  public final String title;
  public final String state;
  public final String author;
  public final String body;
  public final String htmlUrl;
  public final String createdAt;
  public final String updatedAt;
  public final int comments;
  public final java.util.List<String> labels;
  /** true = 这条其实是一个 PR（GitHub 的 issues 端点会混入）。 */
  public final boolean isPullRequest;

  public GitHubIssue(int number, String title, String state, String author, String body,
                     String htmlUrl, String createdAt, String updatedAt, int comments,
                     java.util.List<String> labels, boolean isPullRequest) {
    this.number = number;
    this.title = title == null ? "" : title;
    this.state = state == null ? "" : state;
    this.author = author == null ? "" : author;
    this.body = body == null ? "" : body;
    this.htmlUrl = htmlUrl == null ? "" : htmlUrl;
    this.createdAt = createdAt == null ? "" : createdAt;
    this.updatedAt = updatedAt == null ? "" : updatedAt;
    this.comments = comments;
    this.labels = labels == null ? java.util.Collections.emptyList() : labels;
    this.isPullRequest = isPullRequest;
  }

  /** 面向用户的开关状态文案（界面与 AI 工具共用）。 */
  public String stateText() {
    return "open".equalsIgnoreCase(state) ? "开启" : "已关闭";
  }

  static GitHubIssue fromJson(JSONObject json) {
    return new GitHubIssue(
        json.optInt("number"),
        json.optString("title", ""),
        json.optString("state", ""),
        json.optJSONObject("user") == null ? "" : json.optJSONObject("user").optString("login", ""),
        json.optString("body", ""),
        json.optString("html_url", ""),
        json.optString("created_at", ""),
        json.optString("updated_at", ""),
        json.optInt("comments"),
        labels(json.optJSONArray("labels")),
        // PR 在 issues 端点里带这个对象；普通 issue 没有。
        json.optJSONObject("pull_request") != null);
  }

  private static java.util.List<String> labels(JSONArray array) {
    if (array == null) {
      return java.util.Collections.emptyList();
    }
    java.util.List<String> names = new java.util.ArrayList<>();
    for (int i = 0; i < array.length(); i++) {
      JSONObject label = array.optJSONObject(i);
      if (label == null) {
        continue;
      }
      String name = label.optString("name", "");
      if (!name.isEmpty()) {
        names.add(name);
      }
    }
    return names;
  }
}
