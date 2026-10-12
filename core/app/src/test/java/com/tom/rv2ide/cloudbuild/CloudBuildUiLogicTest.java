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
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * 云编译界面纯逻辑的回归测试。
 *
 * <p><b>为什么这些用例值得覆盖</b>：界面文案与 workflow 选择是「错了用户就看不懂、
 * 或选错目标」的地方——错误提示说错方向（例如把「没推」说成「没建」）会让用户
 * 白白折腾一轮；默认 workflow 选错则会触发错误的构建目标。把这些判断从
 * Fragment 里抽出来（{@link CloudBuildUiLogic}）就是为了能在这里固定住。
 */
public class CloudBuildUiLogicTest {

  @Rule public TemporaryFolder tempFolder = new TemporaryFolder();

  // ---------------------------------------------------------------------------
  // errorMessage
  // ---------------------------------------------------------------------------

  @Test
  public void translatesAuthException() {
    String message = CloudBuildUiLogic.errorMessage(new GitHubApiException(401, "Bad credentials", ""));
    assertTrue(message, message.contains("Personal Access Token"));
  }

  @Test
  public void translatesScopeForbidden() {
    String message = CloudBuildUiLogic.errorMessage(new GitHubApiException(403, "Forbidden", ""));
    assertTrue(message, message.contains("workflow"));
  }

  @Test
  public void translatesNotFound() {
    String message = CloudBuildUiLogic.errorMessage(new GitHubApiException(404, "Not Found", ""));
    assertTrue(message, message.contains("找不到目标"));
  }

  @Test
  public void translatesRateLimit() {
    String message = CloudBuildUiLogic.errorMessage(new GitHubApiException(429, "rate limited", ""));
    assertTrue(message, message.contains("速率限制"));
  }

  /**
   * 状态码 0 表示失败发生在本地（没走到 HTTP），此时不能套用「HTTP 0」的通用文案，
   * 必须原样透传消息——例如「artifact 没有下载地址（可能已过期）」。
   */
  @Test
  public void keepsLocalFailureMessageForStatusZero() {
    String message =
        CloudBuildUiLogic.errorMessage(new GitHubApiException(0, "artifact 没有下载地址（可能已过期）", ""));
    assertEquals("artifact 没有下载地址（可能已过期）", message);
  }

  @Test
  public void translatesDnsFailure() {
    String message = CloudBuildUiLogic.errorMessage(new UnknownHostException("api.github.com"));
    assertTrue(message, message.contains("网络不可用"));
  }

  @Test
  public void translatesTimeout() {
    String message = CloudBuildUiLogic.errorMessage(new SocketTimeoutException("timeout"));
    assertTrue(message, message.contains("超时"));
  }

  @Test
  public void translatesConnectFailure() {
    String message = CloudBuildUiLogic.errorMessage(new ConnectException("refused"));
    assertTrue(message, message.contains("无法连接"));
  }

  @Test
  public void translatesGenericIoFailure() {
    String message = CloudBuildUiLogic.errorMessage(new IOException("broken pipe"));
    assertTrue(message, message.contains("网络请求失败"));
    assertTrue(message, message.contains("broken pipe"));
  }

  /** 非网络异常也要有可读输出，不能是空白或 null。 */
  @Test
  public void fallsBackToMessageForUnknownThrowable() {
    assertEquals("boom", CloudBuildUiLogic.errorMessage(new IllegalStateException("boom")));
  }

  @Test
  public void fallsBackToClassNameWhenMessageMissing() {
    String message = CloudBuildUiLogic.errorMessage(new IllegalStateException());
    assertTrue(message, message.contains("IllegalStateException"));
  }

  // ---------------------------------------------------------------------------
  // 文案拼装
  // ---------------------------------------------------------------------------

  @Test
  public void formatsJobLineWithChineseStatus() {
    WorkflowJob job = new WorkflowJob(1L, "android-apk", "in_progress", "", "");
    assertEquals("android-apk：运行中", CloudBuildUiLogic.jobLine(job));
  }

  @Test
  public void formatsFailedJobLine() {
    WorkflowJob job = new WorkflowJob(1L, "desktop", "completed", "failure", "");
    assertEquals("desktop：失败", CloudBuildUiLogic.jobLine(job));
  }

  /** run.name 可能为空（GitHub 允许未命名 workflow）——不能渲染出空标题。 */
  @Test
  public void usesPlaceholderForUnnamedRun() {
    WorkflowRun run = newRun("", "main", "in_progress", "");
    assertEquals("未命名运行", CloudBuildUiLogic.runTitle(run));
  }

  @Test
  public void formatsRunStatusLine() {
    WorkflowRun run = newRun("Build", "dev", "in_progress", "");
    assertEquals("状态：运行中 · 分支：dev", CloudBuildUiLogic.runStatusLine(run));
  }

  @Test
  public void marksUnknownBranch() {
    WorkflowRun run = newRun("Build", "", "completed", "success");
    assertEquals("状态：成功 · 分支：未知", CloudBuildUiLogic.runStatusLine(run));
  }

  // ---------------------------------------------------------------------------
  // workflow 选择
  // ---------------------------------------------------------------------------

  @Test
  public void prefersBuildYmlAsDefault() {
    List<String> workflows = Arrays.asList("ci.yml", "build.yml", "release.yml");
    assertEquals("build.yml", CloudBuildUiLogic.pickDefaultWorkflow(workflows));
  }

  @Test
  public void fallsBackToBuildYaml() {
    List<String> workflows = Arrays.asList("ci.yml", "build.yaml");
    assertEquals("build.yaml", CloudBuildUiLogic.pickDefaultWorkflow(workflows));
  }

  @Test
  public void fallsBackToFirstWhenNoBuildWorkflow() {
    List<String> workflows = Arrays.asList("ci.yml", "release.yml");
    assertEquals("ci.yml", CloudBuildUiLogic.pickDefaultWorkflow(workflows));
  }

  @Test
  public void returnsEmptyForNoWorkflows() {
    assertEquals("", CloudBuildUiLogic.pickDefaultWorkflow(Collections.<String>emptyList()));
  }

  // ---------------------------------------------------------------------------
  // 本地 workflow 探测
  // ---------------------------------------------------------------------------

  /** 目录不存在时必须返回空列表而不是抛异常——无项目/未初始化的项目都会走到这。 */
  @Test
  public void returnsEmptyWhenWorkflowDirMissing() throws Exception {
    File project = tempFolder.newFolder("plain-project");
    assertTrue(CloudBuildUiLogic.localWorkflowFiles(project).isEmpty());
  }

  @Test
  public void listsOnlyYamlFilesSorted() throws Exception {
    File project = tempFolder.newFolder("project");
    File workflows = new File(project, ".github/workflows");
    assertTrue(workflows.mkdirs());
    writeFile(new File(workflows, "release.yml"), "name: release");
    writeFile(new File(workflows, "build.yml"), "name: build");
    writeFile(new File(workflows, "notes.txt"), "not a workflow");

    List<String> files = CloudBuildUiLogic.localWorkflowFiles(project);
    assertEquals(Arrays.asList("build.yml", "release.yml"), files);
  }

  @Test
  public void acceptsYamlExtension() throws Exception {
    File project = tempFolder.newFolder("project2");
    File workflows = new File(project, ".github/workflows");
    assertTrue(workflows.mkdirs());
    writeFile(new File(workflows, "build.yaml"), "name: build");

    assertEquals(Collections.singletonList("build.yaml"), CloudBuildUiLogic.localWorkflowFiles(project));
  }

  // ---------------------------------------------------------------------------

  private static WorkflowRun newRun(String name, String branch, String status, String conclusion) {
    return new WorkflowRun(1L, name, branch, "abc123", status, conclusion,
        "https://github.com/o/r/actions/runs/1", "2026-10-12T00:00:00Z", "2026-10-12T00:00:00Z");
  }

  private static void writeFile(File file, String content) throws IOException {
    Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
  }
}
