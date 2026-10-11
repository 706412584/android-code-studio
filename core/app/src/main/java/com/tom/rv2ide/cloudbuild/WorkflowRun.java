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

import org.json.JSONObject;

/**
 * 一次 GitHub Actions workflow run 的只读快照。
 *
 * <p>字段只保留云打包界面与 AI 工具真正会用到的部分——GitHub 的 run 对象有 40+
 * 字段，全量映射只会带来维护负担。需要更多时按需再加。
 */
public final class WorkflowRun {

  /** GitHub 的 run 状态（status 字段）。 */
  public static final String STATUS_QUEUED = "queued";
  public static final String STATUS_IN_PROGRESS = "in_progress";
  public static final String STATUS_COMPLETED = "completed";

  /** GitHub 的 run 结论（conclusion 字段，仅 completed 时有值）。 */
  public static final String CONCLUSION_SUCCESS = "success";
  public static final String CONCLUSION_FAILURE = "failure";
  public static final String CONCLUSION_CANCELLED = "cancelled";
  public static final String CONCLUSION_SKIPPED = "skipped";

  public final long id;
  public final String name;
  public final String headBranch;
  public final String headSha;
  public final String status;
  public final String conclusion;
  public final String htmlUrl;
  public final String createdAt;
  public final String updatedAt;

  public WorkflowRun(long id, String name, String headBranch, String headSha, String status,
                     String conclusion, String htmlUrl, String createdAt, String updatedAt) {
    this.id = id;
    this.name = name == null ? "" : name;
    this.headBranch = headBranch == null ? "" : headBranch;
    this.headSha = headSha == null ? "" : headSha;
    this.status = status == null ? "" : status;
    this.conclusion = conclusion == null || conclusion.equals("null") ? "" : conclusion;
    this.htmlUrl = htmlUrl == null ? "" : htmlUrl;
    this.createdAt = createdAt == null ? "" : createdAt;
    this.updatedAt = updatedAt == null ? "" : updatedAt;
  }

  /** 是否仍在排队或运行中。 */
  public boolean isRunning() {
    return STATUS_QUEUED.equals(status) || STATUS_IN_PROGRESS.equals(status);
  }

  /** 是否已到终态（completed）。 */
  public boolean isFinished() {
    return STATUS_COMPLETED.equals(status);
  }

  /** 是否成功。 */
  public boolean isSuccess() {
    return isFinished() && CONCLUSION_SUCCESS.equals(conclusion);
  }

  /** 面向用户的状态文案（中文，界面与 AI 工具共用）。 */
  public String statusText() {
    if (STATUS_QUEUED.equals(status)) {
      return "排队中";
    }
    if (STATUS_IN_PROGRESS.equals(status)) {
      return "运行中";
    }
    if (isFinished()) {
      if (CONCLUSION_SUCCESS.equals(conclusion)) {
        return "成功";
      }
      if (CONCLUSION_FAILURE.equals(conclusion)) {
        return "失败";
      }
      if (CONCLUSION_CANCELLED.equals(conclusion)) {
        return "已取消";
      }
      if (CONCLUSION_SKIPPED.equals(conclusion)) {
        return "已跳过";
      }
      return "已结束（" + conclusion + "）";
    }
    return status;
  }

  static WorkflowRun fromJson(JSONObject json) {
    return new WorkflowRun(
        json.optLong("id"),
        json.optString("name", ""),
        json.optString("head_branch", ""),
        json.optString("head_sha", ""),
        json.optString("status", ""),
        json.optString("conclusion", ""),
        json.optString("html_url", ""),
        json.optString("created_at", ""),
        json.optString("updated_at", ""));
  }
}
