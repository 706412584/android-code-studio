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
import static org.junit.Assert.assertTrue;

import com.tom.rv2ide.ai.tool.ToolContext;
import com.tom.rv2ide.ai.tool.api.ToolCategory;
import com.tom.rv2ide.ai.tool.api.ToolNames;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.io.File;
import java.nio.file.Files;
import org.eclipse.jgit.api.Git;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * {@link GitHubIssueTool} / {@link GitHubIssueWriteTool} / {@link GitHubPrTool} /
 * {@link GitHubPrWriteTool} 的行为测试。
 *
 * <p><b>为什么能在 JVM 上跑</b>：四个工具除 {@code JSONObject} 外不引用 Android 类型，
 * token 经接口注入、项目路径来自 {@code getHomePath()}。因此所有前置校验路径
 * （无 token / 无工作区 / 非 GitHub 仓库 / 非法 action / 缺参数）都能覆盖，
 * 不需要网络也不需要设备。
 *
 * <p><b>刻意不做真实网络调用</b>：REST 行为由 {@code GitHubIssueClient} 的分页与
 * 状态码处理保证；这里钉住的是「模型看到什么」——错误文案必须指向下一步。
 */
public final class GitHubIssueToolsTest {

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private static final String TOKEN = "ghp_test_token";

  private final GitHubIssueTool issueTool = new GitHubIssueTool(() -> TOKEN);
  private final GitHubIssueWriteTool issueWriteTool = new GitHubIssueWriteTool(() -> TOKEN);
  private final GitHubPrTool prTool = new GitHubPrTool(() -> TOKEN);
  private final GitHubPrWriteTool prWriteTool = new GitHubPrWriteTool(() -> TOKEN);

  private final GitHubIssueTool noTokenIssueTool = new GitHubIssueTool(() -> "");
  private final GitHubIssueWriteTool noTokenIssueWriteTool =
      new GitHubIssueWriteTool(() -> null);
  private final GitHubPrTool noTokenPrTool = new GitHubPrTool(() -> "");
  private final GitHubPrWriteTool noTokenPrWriteTool = new GitHubPrWriteTool(() -> null);

  /* ───────────────────────── 测试基建 ───────────────────────── */

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

  private String initRepoWithGitHubRemote() throws Exception {
    File dir = tmp.newFolder();
    try (Git git = Git.init().setDirectory(dir).call()) {
      git.getRepository().getConfig().setBoolean("core", null, "autocrlf", false);
      git.getRepository().getConfig().save();
      git.remoteAdd()
          .setName("origin")
          .setUri(new org.eclipse.jgit.transport.URIish(
              "https://github.com/example/issue-demo.git"))
          .call();
    }
    return dir.getAbsolutePath();
  }

  /* ───────────────────────── 权限元数据 ───────────────────────── */

  /**
   * 读工具必须免确认、只读放行；写工具必须确认、只读不放行。
   *
   * <p>任一侧被改错都是事故：读工具要确认 → 每看一次 issue 都弹窗；
   * 写工具免确认 → 模型可以不经用户同意公开评论或合并 PR。
   */
  @Test
  public void readToolsAreReadonlyAndWriteToolsRequireConfirmation() {
    assertEquals(ToolNames.GITHUB_ISSUE, issueTool.getName());
    assertEquals(ToolCategory.READ, issueTool.getCategory());
    assertFalse(issueTool.needsConfirmation());
    assertTrue(issueTool.isAllowedInReadonlyMode());
    assertTrue(issueTool.isConcurrencySafe());

    assertEquals(ToolNames.GITHUB_PR, prTool.getName());
    assertEquals(ToolCategory.READ, prTool.getCategory());
    assertFalse(prTool.needsConfirmation());
    assertTrue(prTool.isAllowedInReadonlyMode());
    assertTrue(prTool.isConcurrencySafe());

    assertEquals(ToolNames.GITHUB_ISSUE_WRITE, issueWriteTool.getName());
    assertEquals(ToolCategory.SYSTEM, issueWriteTool.getCategory());
    assertTrue(issueWriteTool.needsConfirmation());
    assertFalse(issueWriteTool.isAllowedInReadonlyMode());
    assertFalse(issueWriteTool.isConcurrencySafe());

    assertEquals(ToolNames.GITHUB_PR_WRITE, prWriteTool.getName());
    assertEquals(ToolCategory.SYSTEM, prWriteTool.getCategory());
    assertTrue(prWriteTool.needsConfirmation());
    assertFalse(prWriteTool.isAllowedInReadonlyMode());
    assertFalse(prWriteTool.isConcurrencySafe());
  }

  /* ───────────────────────── 前置校验 ───────────────────────── */

  @Test
  public void missingTokenIsReportedWithNextStep() throws Exception {
    ToolContext context = contextAt(initRepoWithGitHubRemote());
    JSONObject input = args("action", "list");

    for (ToolResult result : new ToolResult[] {
        noTokenIssueTool.execute(input, context),
        noTokenIssueWriteTool.execute(input, context),
        noTokenPrTool.execute(input, context),
        noTokenPrWriteTool.execute(input, context)}) {
      assertTrue(result.isError());
      assertTrue("应提示去配置 token，而不是笼统失败",
          result.getContent().contains("Personal Access Token"));
      assertTrue("应说明需要什么权限", result.getContent().contains("repo"));
    }
  }

  @Test
  public void missingWorkspaceIsReported() throws Exception {
    ToolResult result = issueTool.execute(args("action", "list"), contextAt(""));
    assertTrue(result.isError());
    assertTrue(result.getContent().contains("工作区"));
  }

  @Test
  public void nonGithubRepoIsReported() throws Exception {
    File dir = tmp.newFolder();
    try (Git git = Git.init().setDirectory(dir).call()) {
      git.remoteAdd()
          .setName("origin")
          .setUri(new org.eclipse.jgit.transport.URIish("https://gitlab.com/a/b.git"))
          .call();
    }
    ToolResult result = prTool.execute(args("action", "list"), contextAt(dir.getAbsolutePath()));
    assertTrue(result.isError());
    assertTrue("应提示检查 remote", result.getContent().contains("github.com"));
  }

  /* ───────────────────────── 参数校验 ───────────────────────── */

  @Test
  public void unknownActionListsAvailableValues() throws Exception {
    ToolContext context = contextAt(initRepoWithGitHubRemote());
    ToolResult result = issueTool.execute(args("action", "delete"), context);
    assertTrue(result.isError());
    assertTrue(result.getContent().contains("不支持的 action"));
    // 错误文案必须列出可用值，否则模型只能盲猜。
    assertTrue(result.getContent().contains("list"));
    assertTrue(result.getContent().contains("get"));
  }

  @Test
  public void getWithoutNumberIsRejectedBeforeNetwork() throws Exception {
    ToolContext context = contextAt(initRepoWithGitHubRemote());
    for (ToolResult result : new ToolResult[] {
        issueTool.execute(args("action", "get"), context),
        issueTool.execute(args("action", "get", "number", "0"), context),
        issueTool.execute(args("action", "get", "number", "-3"), context)}) {
      assertTrue(result.isError());
      assertTrue(result.getContent().contains("number"));
    }
  }

  @Test
  public void createWithoutTitleIsRejected() throws Exception {
    ToolContext context = contextAt(initRepoWithGitHubRemote());
    ToolResult result = issueWriteTool.execute(args("action", "create"), context);
    assertTrue(result.isError());
    assertTrue(result.getContent().contains("标题"));
  }

  @Test
  public void commentWithoutBodyIsRejected() throws Exception {
    ToolContext context = contextAt(initRepoWithGitHubRemote());
    ToolResult result = issueWriteTool.execute(
        args("action", "comment", "number", "12"), context);
    assertTrue(result.isError());
    assertTrue(result.getContent().contains("评论内容"));
  }

  /** PR 创建缺 head/base 必须在网络前拦住：GitHub 的 422 文案对模型不友好。 */
  @Test
  public void createPrRequiresHeadAndBase() throws Exception {
    ToolContext context = contextAt(initRepoWithGitHubRemote());
    ToolResult onlyTitle = prWriteTool.execute(
        args("action", "create", "title", "t"), context);
    assertTrue(onlyTitle.isError());
    assertTrue(onlyTitle.getContent().contains("head") || onlyTitle.getContent().contains("源分支"));

    ToolResult noBase = prWriteTool.execute(
        args("action", "create", "title", "t", "head", "feat"), context);
    assertTrue(noBase.isError());
    assertTrue(noBase.getContent().contains("base") || noBase.getContent().contains("目标分支"));
  }

  @Test
  public void mergeWithoutNumberIsRejected() throws Exception {
    ToolContext context = contextAt(initRepoWithGitHubRemote());
    ToolResult result = prWriteTool.execute(args("action", "merge"), context);
    assertTrue(result.isError());
    assertTrue(result.getContent().contains("number"));
  }

  /* ───────────────────────── Schema ───────────────────────── */

  /**
   * 参数 schema 里必须带 action 枚举——模型靠它知道能做什么；
   * 缺了枚举时模型会幻觉出不存在的 action（如 issue 的 delete）。
   */
  @Test
  public void parametersExposeActionEnum() throws Exception {
    for (com.tom.rv2ide.ai.tool.BaseTool tool : new com.tom.rv2ide.ai.tool.BaseTool[] {
        issueTool, issueWriteTool, prTool, prWriteTool}) {
      JSONObject schema = tool.getParameters();
      JSONObject properties = schema.optJSONObject("properties");
      assertTrue(tool.getName() + " 缺少 properties", properties != null);
      JSONObject action = properties.optJSONObject("action");
      assertTrue(tool.getName() + " 缺少 action 参数", action != null);
      assertTrue(tool.getName() + " 的 action 缺少 enum",
          action.optJSONArray("enum") != null && action.optJSONArray("enum").length() > 0);
      assertEquals(tool.getName() + " 的 action 应为必填",
          "action", schema.optJSONArray("required").optString(0));
    }
  }
}
