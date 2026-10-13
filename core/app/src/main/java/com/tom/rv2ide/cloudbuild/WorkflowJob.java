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

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 一次 workflow run 中单个 job 的快照。
 *
 * <p>job 级状态是「看到进度」的关键：run 级只有一个整体状态，而一个 run 可能包含
 * 多个 job（例如 CMP 模板的 desktop/wasm/android 三个 job），用户需要知道
 * 具体卡在哪个 job、哪个失败。
 */
public final class WorkflowJob {

  public final long id;
  public final String name;
  public final String status;
  public final String conclusion;
  /** 该 job 的步骤列表（简化为「名字 + 状态」的文本行）。 */
  public final String stepsSummary;

  public WorkflowJob(long id, String name, String status, String conclusion, String stepsSummary) {
    this.id = id;
    this.name = name == null ? "" : name;
    this.status = status == null ? "" : status;
    this.conclusion = conclusion == null || conclusion.equals("null") ? "" : conclusion;
    this.stepsSummary = stepsSummary == null ? "" : stepsSummary;
  }

  public boolean isFinished() {
    return WorkflowRun.STATUS_COMPLETED.equals(status);
  }

  public boolean isFailed() {
    return isFinished()
        && (WorkflowRun.CONCLUSION_FAILURE.equals(conclusion)
            || WorkflowRun.CONCLUSION_CANCELLED.equals(conclusion));
  }

  /** 面向用户的状态文案。 */
  public String statusText() {
    if (WorkflowRun.STATUS_QUEUED.equals(status)) {
      return "排队中";
    }
    if (WorkflowRun.STATUS_IN_PROGRESS.equals(status)) {
      return "运行中";
    }
    if (isFinished()) {
      if (WorkflowRun.CONCLUSION_SUCCESS.equals(conclusion)) {
        return "成功";
      }
      if (WorkflowRun.CONCLUSION_FAILURE.equals(conclusion)) {
        return "失败";
      }
      if (WorkflowRun.CONCLUSION_CANCELLED.equals(conclusion)) {
        return "已取消";
      }
      if (WorkflowRun.CONCLUSION_SKIPPED.equals(conclusion)) {
        return "已跳过";
      }
      return "已结束（" + conclusion + "）";
    }
    return status;
  }

  static WorkflowJob fromJson(JSONObject json) {
    StringBuilder steps = new StringBuilder();
    JSONArray stepsArray = json.optJSONArray("steps");
    if (stepsArray != null) {
      for (int i = 0; i < stepsArray.length(); i++) {
        JSONObject step = stepsArray.optJSONObject(i);
        if (step == null) {
          continue;
        }
        String stepName = step.optString("name", "");
        String stepStatus = step.optString("status", "");
        String stepConclusion = step.optString("conclusion", "");
        if (steps.length() > 0) {
          steps.append('\n');
        }
        steps.append(stepName).append(": ").append(stepStatus);
        if (stepConclusion != null && !stepConclusion.isEmpty() && !stepConclusion.equals("null")) {
          steps.append(" (").append(stepConclusion).append(')');
        }
      }
    }
    return new WorkflowJob(
        json.optLong("id"),
        json.optString("name", ""),
        json.optString("status", ""),
        json.optString("conclusion", ""),
        steps.toString());
  }
}
