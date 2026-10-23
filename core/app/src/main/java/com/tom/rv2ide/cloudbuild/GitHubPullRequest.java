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
 * 一个 GitHub Pull Request 的只读快照。
 *
 * <p>与 {@link GitHubIssue} 分开：PR 有 merge 状态、head/base 分支、是否 draft、
 * 变更文件数这些 issue 没有的字段，混用一个类只会让两边字段常年为空。
 */
public final class GitHubPullRequest {

  public final int number;
  public final String title;
  public final String state;
  public final String author;
  public final String body;
  public final String htmlUrl;
  public final String createdAt;
  public final String updatedAt;
  public final String headRef;
  public final String baseRef;
  public final boolean draft;
  public final boolean merged;
  public final String mergeableState;
  public final int changedFiles;
  public final int additions;
  public final int deletions;

  public GitHubPullRequest(int number, String title, String state, String author, String body,
                           String htmlUrl, String createdAt, String updatedAt, String headRef,
                           String baseRef, boolean draft, boolean merged, String mergeableState,
                           int changedFiles, int additions, int deletions) {
    this.number = number;
    this.title = title == null ? "" : title;
    this.state = state == null ? "" : state;
    this.author = author == null ? "" : author;
    this.body = body == null ? "" : body;
    this.htmlUrl = htmlUrl == null ? "" : htmlUrl;
    this.createdAt = createdAt == null ? "" : createdAt;
    this.updatedAt = updatedAt == null ? "" : updatedAt;
    this.headRef = headRef == null ? "" : headRef;
    this.baseRef = baseRef == null ? "" : baseRef;
    this.draft = draft;
    this.merged = merged;
    this.mergeableState = mergeableState == null ? "" : mergeableState;
    this.changedFiles = changedFiles;
    this.additions = additions;
    this.deletions = deletions;
  }

  /** 面向用户的状态文案（界面与 AI 工具共用）。 */
  public String stateText() {
    if (merged) {
      return "已合并";
    }
    if ("closed".equalsIgnoreCase(state)) {
      return "已关闭";
    }
    if (draft) {
      return "草稿";
    }
    return "开启";
  }

  static GitHubPullRequest fromJson(JSONObject json) {
    JSONObject head = json.optJSONObject("head");
    JSONObject base = json.optJSONObject("base");
    return new GitHubPullRequest(
        json.optInt("number"),
        json.optString("title", ""),
        json.optString("state", ""),
        json.optJSONObject("user") == null ? "" : json.optJSONObject("user").optString("login", ""),
        json.optString("body", ""),
        json.optString("html_url", ""),
        json.optString("created_at", ""),
        json.optString("updated_at", ""),
        head == null ? "" : head.optString("ref", ""),
        base == null ? "" : base.optString("ref", ""),
        json.optBoolean("draft", false),
        // merged 只有 GET 单个 PR 才有；列表接口给的是 merged_at。
        json.optBoolean("merged", false) || !json.isNull("merged_at"),
        json.optString("mergeable_state", ""),
        json.optInt("changed_files"),
        json.optInt("additions"),
        json.optInt("deletions"));
  }

  /** 从数组里挑出合法对象（容错：GitHub 偶尔在分页末尾返回 null）。 */
  static java.util.List<GitHubPullRequest> listFrom(JSONArray array) {
    java.util.List<GitHubPullRequest> list = new java.util.ArrayList<>();
    if (array == null) {
      return list;
    }
    for (int i = 0; i < array.length(); i++) {
      JSONObject item = array.optJSONObject(i);
      if (item != null) {
        list.add(fromJson(item));
      }
    }
    return list;
  }
}
