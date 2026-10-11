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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/**
 * 模型类 JSON 解析与状态判定的回归测试。
 *
 * <p>用 GitHub API 的真实响应形态构造 JSON——特别是 {@code conclusion} 字段：
 * 运行中的 run 它的值是 **JSON null**，{@code optString} 会返回字符串 "null"，
 * 若不处理会把「运行中」误判为「已结束（null）」。
 */
public class WorkflowModelsTest {

  @Test
  public void parsesRunningRun() throws Exception {
    JSONObject json = new JSONObject(
        "{\"id\":123,\"name\":\"Build\",\"head_branch\":\"dev\",\"head_sha\":\"abc\","
            + "\"status\":\"in_progress\",\"conclusion\":null,"
            + "\"html_url\":\"https://github.com/o/r/actions/runs/123\","
            + "\"created_at\":\"2026-10-12T01:00:00Z\",\"updated_at\":\"2026-10-12T01:01:00Z\"}");
    WorkflowRun run = WorkflowRun.fromJson(json);

    assertEquals(123, run.id);
    assertEquals("dev", run.headBranch);
    assertTrue(run.isRunning());
    assertFalse(run.isFinished());
    assertFalse(run.isSuccess());
    // conclusion 是 JSON null：必须归一化为空串而不是字符串 "null"。
    assertEquals("", run.conclusion);
    assertEquals("运行中", run.statusText());
  }

  @Test
  public void parsesSuccessfulRun() throws Exception {
    JSONObject json = new JSONObject(
        "{\"id\":1,\"status\":\"completed\",\"conclusion\":\"success\"}");
    WorkflowRun run = WorkflowRun.fromJson(json);

    assertTrue(run.isFinished());
    assertTrue(run.isSuccess());
    assertEquals("成功", run.statusText());
  }

  @Test
  public void parsesFailedRun() throws Exception {
    JSONObject json = new JSONObject(
        "{\"id\":1,\"status\":\"completed\",\"conclusion\":\"failure\"}");
    WorkflowRun run = WorkflowRun.fromJson(json);

    assertTrue(run.isFinished());
    assertFalse(run.isSuccess());
    assertEquals("失败", run.statusText());
  }

  @Test
  public void parsesQueuedRun() throws Exception {
    JSONObject json = new JSONObject("{\"id\":1,\"status\":\"queued\"}");
    WorkflowRun run = WorkflowRun.fromJson(json);
    assertEquals("排队中", run.statusText());
    assertTrue(run.isRunning());
  }

  @Test
  public void parsesJobWithSteps() throws Exception {
    JSONObject json = new JSONObject(
        "{\"id\":42,\"name\":\"build\",\"status\":\"completed\",\"conclusion\":\"failure\","
            + "\"steps\":["
            + "{\"name\":\"Set up job\",\"status\":\"completed\",\"conclusion\":\"success\"},"
            + "{\"name\":\"Build\",\"status\":\"completed\",\"conclusion\":\"failure\"}]}");
    WorkflowJob job = WorkflowJob.fromJson(json);

    assertEquals(42, job.id);
    assertEquals("build", job.name);
    assertTrue(job.isFailed());
    assertEquals("失败", job.statusText());
    assertTrue(job.stepsSummary.contains("Set up job: completed (success)"));
    assertTrue(job.stepsSummary.contains("Build: completed (failure)"));
  }

  @Test
  public void parsesJobWithoutSteps() throws Exception {
    JSONObject json = new JSONObject("{\"id\":1,\"name\":\"j\",\"status\":\"in_progress\"}");
    WorkflowJob job = WorkflowJob.fromJson(json);
    assertEquals("", job.stepsSummary);
    assertEquals("运行中", job.statusText());
    assertFalse(job.isFailed());
  }

  @Test
  public void parsesArtifact() throws Exception {
    JSONObject json = new JSONObject(
        "{\"id\":7,\"name\":\"app-debug\",\"size_in_bytes\":3145728,\"expired\":false,"
            + "\"archive_download_url\":\"https://api.github.com/repos/o/r/actions/artifacts/7/zip\"}");
    WorkflowArtifact artifact = WorkflowArtifact.fromJson(json);

    assertEquals(7, artifact.id);
    assertEquals("app-debug", artifact.name);
    assertFalse(artifact.expired);
    assertEquals("3.0 MB", artifact.sizeText());
  }

  @Test
  public void artifactSizeTextHandlesUnknown() throws Exception {
    JSONObject json = new JSONObject("{\"id\":1,\"name\":\"x\",\"size_in_bytes\":0}");
    WorkflowArtifact artifact = WorkflowArtifact.fromJson(json);
    assertEquals("未知大小", artifact.sizeText());
  }

  @Test
  public void exceptionTranslatesStatusCodes() {
    assertTrue(new GitHubApiException(401, "x", "").isAuthProblem());
    assertTrue(new GitHubApiException(403, "x", "").isAuthProblem());
    assertTrue(new GitHubApiException(404, "x", "").isNotFound());
    assertTrue(new GitHubApiException(429, "x", "").isRateLimited());
    assertFalse(new GitHubApiException(500, "x", "").isAuthProblem());

    // userMessage 必须给出可操作的话，而不是裸状态码。
    assertTrue(new GitHubApiException(401, "x", "").userMessage().contains("Personal Access Token"));
    assertTrue(new GitHubApiException(403, "x", "").userMessage().contains("workflow"));
    assertTrue(new GitHubApiException(404, "x", "").userMessage().contains("workflow"));
  }
}
