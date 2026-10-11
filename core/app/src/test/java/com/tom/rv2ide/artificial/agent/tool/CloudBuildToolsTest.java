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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.tom.rv2ide.ai.tool.ToolContext;
import com.tom.rv2ide.ai.tool.api.ToolCategory;
import com.tom.rv2ide.ai.tool.api.ToolNames;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.eclipse.jgit.api.Git;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * {@link GitHubActionsBuildTool} / {@link GitHubActionsStatusTool} 的行为测试。
 *
 * <p><b>为什么能在 JVM 上跑</b>：两个工具类除 {@code JSONObject} 外不引用 Android
 * 类型，{@code ToolContext} 亦然；token 经接口注入、项目路径来自
 * {@code getHomePath()}——因此所有前置校验路径（无 token / 无工作区 / 非 GitHub
 * 仓库 / 非法参数）都能在单测里覆盖，不需要网络也不需要设备。
 *
 * <p><b>刻意不做真实网络调用</b>：服务层的 HTTP 行为（GitHub API 的请求与解析）
 * 已由 {@code GitRemoteParserTest} / {@code RunLogExtractorTest} / 真实 API 冒烟
 * 覆盖。这里钉住的是「模型看到什么」——错误文案必须指向下一步，而不是光秃秃的
 * 「失败了」。
 */
public final class CloudBuildToolsTest {

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private static final String TOKEN = "ghp_test_token";

  private final GitHubActionsBuildTool buildTool =
      new GitHubActionsBuildTool(() -> TOKEN);
  private final GitHubActionsStatusTool statusTool =
      new GitHubActionsStatusTool(() -> TOKEN);

  private final GitHubActionsBuildTool noTokenBuildTool =
      new GitHubActionsBuildTool(() -> "");
  private final GitHubActionsStatusTool noTokenStatusTool =
      new GitHubActionsStatusTool(() -> null);

  // ---- 测试基建 ----

  private static ToolContext contextAt(String root) {
    return ToolContext.builder().homePath(root).build();
  }

  private static JSONObject args(String... kv) throws Exception {
    JSONObject json = new JSONObject();
    for (int i = 0; i < kv.length; i += 2) {
      json.put(kv[i], kv[i + 1]);
    }
    return json;
  }

  /** 建一个带 GitHub remote 的仓库（不推送，只要 remote 配置存在）。 */
  private String initRepoWithGitHubRemote() throws Exception {
    File dir = tmp.newFolder();
    try (Git git = Git.init().setDirectory(dir).call()) {
      git.getRepository().getConfig().setBoolean("core", null, "autocrlf", false);
      git.getRepository().getConfig().save();
      git.remoteAdd()
          .setName("origin")
          .setUri(new org.eclipse.jgit.transport.URIish(
              "https://github.com/example/cloudbuild-demo.git"))
          .call();
    }
    return dir.getAbsolutePath();
  }

  /** 在项目里创建 .github/workflows 下的 workflow 文件。 */
  private static void writeWorkflow(String root, String name) throws Exception {
    File dir = new File(root, ".github/workflows");
    assertTrue(dir.mkdirs() || dir.isDirectory());
    Files.write(new File(dir, name).toPath(), "name: test\n".getBytes(StandardCharsets.UTF_8));
  }

  // ---- 权限元数据 ----

  @Test
  public void buildToolRequiresConfirmationAndStatusToolIsReadonly() {
    // 权限层按工具分级：触发消耗 CI 资源必须确认，查询必须免打扰。
    // 两侧任一被改错都是「误扣用户额度」或「查询也要点确认」的事故。
    assertEquals(ToolNames.GITHUB_ACTIONS_BUILD, buildTool.getName());
    assertEquals(ToolCategory.SYSTEM, buildTool.getCategory());
    assertTrue(buildTool.needsConfirmation());
    assertFalse(buildTool.isAllowedInReadonlyMode());

    assertEquals(ToolNames.GITHUB_ACTIONS_STATUS, statusTool.getName());
    assertEquals(ToolCategory.READ, statusTool.getCategory());
    assertFalse(statusTool.needsConfirmation());
    assertTrue(statusTool.isAllowedInReadonlyMode());
    assertTrue(statusTool.isConcurrencySafe());
  }

  // ---- 前置校验：token ----

  @Test
  public void missingTokenGivesActionableErrorBeforeAnyNetworkCall() throws Exception {
    // 无 token 是首次使用最常见的失败。文案必须告诉用户「去配置」而不是
    // 「HTTP 401」——模型据此能直接引导用户，而不是反复重试。
    String root = initRepoWithGitHubRemote();
    ToolResult build = noTokenBuildTool.execute(args(), contextAt(root));
    ToolResult status = noTokenStatusTool.execute(args(), contextAt(root));

    assertTrue(build.isError());
    assertTrue(build.getContent(), build.getContent().contains("Token"));
    assertTrue(build.getContent(), build.getContent().contains("配置"));
    assertTrue(status.isError());
    assertTrue(status.getContent(), status.getContent().contains("Token"));
  }

  // ---- 前置校验：工作区 ----

  @Test
  public void missingWorkspaceGivesActionableError() throws Exception {
    ToolResult build = buildTool.execute(args(), ToolContext.builder().build());
    ToolResult status = statusTool.execute(args(), ToolContext.builder().build());

    assertTrue(build.isError());
    assertTrue(build.getContent(), build.getContent().contains("工作区"));
    assertTrue(status.isError());
    assertTrue(status.getContent(), status.getContent().contains("工作区"));
  }

  // ---- 前置校验：仓库识别 ----

  @Test
  public void nonRepoDirectoryGivesActionableError() throws Exception {
    File plain = tmp.newFolder();
    ToolResult build = buildTool.execute(args(), contextAt(plain.getAbsolutePath()));
    ToolResult status = statusTool.execute(args(), contextAt(plain.getAbsolutePath()));

    assertTrue(build.isError());
    assertTrue(build.getContent(), build.getContent().contains("remote"));
    assertTrue(status.isError());
    assertTrue(status.getContent(), status.getContent().contains("remote"));
  }

  @Test
  public void nonGitHubRemoteGivesActionableError() throws Exception {
    // Gitee 等项目能识别出 remote 但不是 GitHub：云打包只支持 GitHub Actions，
    // 报「不是 GitHub 仓库」比报「找不到仓库」更能指导用户。
    File dir = tmp.newFolder();
    try (Git git = Git.init().setDirectory(dir).call()) {
      git.remoteAdd()
          .setName("origin")
          .setUri(new org.eclipse.jgit.transport.URIish(
              "https://gitee.com/someone/some-repo.git"))
          .call();
    }
    ToolResult result = buildTool.execute(args(), contextAt(dir.getAbsolutePath()));
    assertTrue(result.isError());
    assertTrue(result.getContent(), result.getContent().contains("github.com"));
  }

  // ---- 参数校验（先于网络调用的部分） ----

  @Test
  public void workflowWithPathSeparatorIsRejectedBeforeNetwork() throws Exception {
    String root = initRepoWithGitHubRemote();
    ToolResult result =
        buildTool.execute(args("workflow", "../evil/build.yml"), contextAt(root));
    assertTrue(result.isError());
    assertTrue(result.getContent(), result.getContent().contains("文件名"));
  }

  @Test
  public void missingWorkflowDirectoryIsReportedWithoutNetwork() throws Exception {
    // 仓库识别通过但项目里没有 workflow：提前报错并给出原因，避免白白 dispatch。
    String root = initRepoWithGitHubRemote();
    ToolResult result = buildTool.execute(args(), contextAt(root));
    assertTrue(result.isError());
    assertTrue(result.getContent(), result.getContent().contains(".github/workflows"));
  }

  @Test
  public void statusToolRejectsWorkflowWithPathSeparator() throws Exception {
    String root = initRepoWithGitHubRemote();
    ToolResult result = statusTool.execute(args("workflow", "a/b.yml"), contextAt(root));
    assertTrue(result.isError());
    assertTrue(result.getContent(), result.getContent().contains("文件名"));
  }

  // ---- workflow 默认解析 ----

  @Test
  public void defaultWorkflowIsAlphabeticallyFirstYml() throws Exception {
    String root = initRepoWithGitHubRemote();
    writeWorkflow(root, "release.yaml");
    writeWorkflow(root, "build.yml");
    writeWorkflow(root, "readme.md");

    assertEquals("build.yml", CloudBuildSupport.findDefaultWorkflow(root));
  }

  @Test
  public void missingWorkflowDirectoryYieldsNull() throws Exception {
    File plain = tmp.newFolder();
    assertNull(CloudBuildSupport.findDefaultWorkflow(plain.getAbsolutePath()));
  }

  // ---- 超时解析 ----

  @Test
  public void timeoutResolutionFollowsGradleBuildRules() throws Exception {
    long def = 600_000;
    long max = 1_800_000;
    // 缺失 → 默认；<=0 → 默认；超上限 → 截断；正常值 → 原样。
    assertEquals(def, CloudBuildSupport.resolveTimeoutMs(new JSONObject(), def, max));
    assertEquals(def, CloudBuildSupport.resolveTimeoutMs(
        new JSONObject().put("timeoutMs", 0), def, max));
    assertEquals(def, CloudBuildSupport.resolveTimeoutMs(
        new JSONObject().put("timeoutMs", -5), def, max));
    assertEquals(max, CloudBuildSupport.resolveTimeoutMs(
        new JSONObject().put("timeoutMs", 99_999_999), def, max));
    assertEquals(120_000, CloudBuildSupport.resolveTimeoutMs(
        new JSONObject().put("timeoutMs", 120_000), def, max));
  }

  @Test
  public void workflowNameValidationRejectsPathsAndAcceptsPlainNames() {
    assertTrue(CloudBuildSupport.isSafeWorkflowName("build.yml"));
    assertTrue(CloudBuildSupport.isSafeWorkflowName("release.yaml"));
    assertFalse(CloudBuildSupport.isSafeWorkflowName("../x.yml"));
    assertFalse(CloudBuildSupport.isSafeWorkflowName("a/b.yml"));
    assertFalse(CloudBuildSupport.isSafeWorkflowName("a\\b.yml"));
    assertFalse(CloudBuildSupport.isSafeWorkflowName(""));
    assertFalse(CloudBuildSupport.isSafeWorkflowName(null));
  }

  // ---- 参数 schema ----

  @Test
  public void schemasDeclareExpectedProperties() throws Exception {
    JSONObject buildParams = buildTool.getParameters();
    JSONObject buildProps = buildParams.getJSONObject("properties");
    assertNotNull(buildProps.optJSONObject("workflow"));
    assertNotNull(buildProps.optJSONObject("branch"));
    assertNotNull(buildProps.optJSONObject("timeoutMs"));

    JSONObject statusParams = statusTool.getParameters();
    JSONObject statusProps = statusParams.getJSONObject("properties");
    assertNotNull(statusProps.optJSONObject("workflow"));
    assertNotNull(statusProps.optJSONObject("branch"));
    // 状态查询不等待，不应有超时参数。
    assertNull(statusProps.optJSONObject("timeoutMs"));
  }
}
