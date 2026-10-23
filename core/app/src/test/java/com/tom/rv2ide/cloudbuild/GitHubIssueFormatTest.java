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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import org.json.JSONObject;
import org.junit.Test;

/**
 * Issue / PR 的格式化与 JSON 映射（纯函数，无网络）。
 *
 * <p><b>为什么单独测格式化</b>：这些字符串是模型与用户看到的最终输出。文案改错
 * 比逻辑改错更难发现——逻辑错了有异常，文案错了只是「看起来怪」。同
 * {@code CloudBuildService.formatLatestRun} 的取舍。
 */
public final class GitHubIssueFormatTest {

  /* ───────────────────────── JSON 映射 ───────────────────────── */

  @Test
  public void issueJsonMapsCoreFields() throws Exception {
    JSONObject json = new JSONObject()
        .put("number", 12)
        .put("title", "启动崩溃")
        .put("state", "open")
        .put("user", new JSONObject().put("login", "alice"))
        .put("body", "点开就闪退")
        .put("html_url", "https://github.com/o/r/issues/12")
        .put("created_at", "2026-01-01T00:00:00Z")
        .put("updated_at", "2026-01-02T00:00:00Z")
        .put("comments", 3)
        .put("labels", new org.json.JSONArray()
            .put(new JSONObject().put("name", "bug"))
            .put(new JSONObject().put("name", "P0")));

    GitHubIssue issue = GitHubIssue.fromJson(json);
    assertEquals(12, issue.number);
    assertEquals("启动崩溃", issue.title);
    assertEquals("开启", issue.stateText());
    assertEquals("alice", issue.author);
    assertEquals(3, issue.comments);
    assertEquals(Arrays.asList("bug", "P0"), issue.labels);
    assertFalse("普通 issue 不应被判成 PR", issue.isPullRequest);
  }

  /**
   * issues 端点会混入 PR。{@link GitHubIssue#isPullRequest} 是列表过滤的唯一依据，
   * 判错会让用户问「有哪些 issue」时看到一堆 PR。
   */
  @Test
  public void issueWithPullRequestObjectIsMarkedAsPr() throws Exception {
    JSONObject json = new JSONObject()
        .put("number", 7)
        .put("title", "feat: x")
        .put("state", "open")
        .put("pull_request", new JSONObject().put("url", "https://api.github.com/..."));
    assertTrue(GitHubIssue.fromJson(json).isPullRequest);
  }

  @Test
  public void closedIssueStateText() throws Exception {
    JSONObject json = new JSONObject().put("number", 1).put("state", "closed");
    assertEquals("已关闭", GitHubIssue.fromJson(json).stateText());
  }

  @Test
  public void pullRequestJsonMapsBranchesAndStats() throws Exception {
    JSONObject json = new JSONObject()
        .put("number", 5)
        .put("title", "修复登录")
        .put("state", "open")
        .put("user", new JSONObject().put("login", "bob"))
        .put("head", new JSONObject().put("ref", "fix/login"))
        .put("base", new JSONObject().put("ref", "main"))
        .put("draft", false)
        .put("changed_files", 4)
        .put("additions", 120)
        .put("deletions", 30);

    GitHubPullRequest pull = GitHubPullRequest.fromJson(json);
    assertEquals(5, pull.number);
    assertEquals("fix/login", pull.headRef);
    assertEquals("main", pull.baseRef);
    assertEquals(4, pull.changedFiles);
    assertEquals("开启", pull.stateText());
  }

  /** draft PR 的状态文案要能与普通开启区分，否则用户看不出还没准备好。 */
  @Test
  public void draftPullRequestIsLabelled() throws Exception {
    JSONObject json = new JSONObject()
        .put("number", 6).put("state", "open").put("draft", true);
    assertEquals("草稿", GitHubPullRequest.fromJson(json).stateText());
  }

  /** 列表接口给的是 merged_at（不是 merged），漏判会把已合并的 PR 显示成「已关闭」。 */
  @Test
  public void mergedAtMarksPullRequestMerged() throws Exception {
    JSONObject json = new JSONObject()
        .put("number", 8)
        .put("state", "closed")
        .put("merged_at", "2026-01-01T00:00:00Z");
    GitHubPullRequest pull = GitHubPullRequest.fromJson(json);
    assertTrue(pull.merged);
    assertEquals("已合并", pull.stateText());
  }

  /* ───────────────────────── 列表格式化 ───────────────────────── */

  @Test
  public void emptyIssueListExplainsFilters() {
    String text = GitHubIssueService.formatIssues(
        "o", "r", "closed", "bug", Collections.emptyList());
    assertTrue(text.contains("o/r"));
    assertTrue(text.contains("没有"));
    // 过滤条件要回显，否则用户不知道「为什么没有」。
    assertTrue(text.contains("已关闭"));
    assertTrue(text.contains("bug"));
  }

  @Test
  public void issueListShowsNumberTitleAndHints() {
    GitHubIssue issue = new GitHubIssue(12, "启动崩溃", "open", "alice", "body",
        "https://x/12", "t1", "t2", 3, Arrays.asList("bug"), false);
    String text = GitHubIssueService.formatIssues(
        "o", "r", "open", null, Collections.singletonList(issue));
    assertTrue(text.contains("#12"));
    assertTrue(text.contains("启动崩溃"));
    assertTrue(text.contains("开启"));
    assertTrue(text.contains("@alice"));
    assertTrue(text.contains("bug"));
    assertTrue(text.contains("3 条评论"));
    // 必须告诉模型下一步怎么拿详情，否则它只能猜。
    assertTrue(text.contains("github_issue"));
  }

  /** GitHub 允许标题含换行；不压平会把列表排版撑坏。 */
  @Test
  public void multilineTitleIsFlattenedInList() {
    GitHubIssue issue = new GitHubIssue(1, "第一行\n第二行", "open", "", "",
        "", "", "", 0, Collections.emptyList(), false);
    String text = GitHubIssueService.formatIssues(
        "o", "r", "open", null, Collections.singletonList(issue));
    assertFalse("列表里不应出现换行标题", text.contains("第一行\n第二行"));
    assertTrue(text.contains("第一行 第二行"));
  }

  /* ───────────────────────── 详情格式化 ───────────────────────── */

  @Test
  public void issueDetailIncludesBodyCommentsAndLink() {
    GitHubIssue issue = new GitHubIssue(12, "启动崩溃", "open", "alice", "点开就闪退",
        "https://x/12", "2026-01-01", "2026-01-02", 1,
        Collections.singletonList("bug"), false);
    String text = GitHubIssueService.formatIssue(issue, Arrays.asList("@bob：我也遇到了"));
    assertTrue(text.contains("#12"));
    assertTrue(text.contains("点开就闪退"));
    assertTrue(text.contains("https://x/12"));
    assertTrue(text.contains("@bob：我也遇到了"));
    assertTrue(text.contains("评论"));
  }

  @Test
  public void emptyBodyShowsPlaceholder() {
    GitHubIssue issue = new GitHubIssue(1, "t", "open", "", "",
        "", "", "", 0, Collections.emptyList(), false);
    assertTrue(GitHubIssueService.formatIssue(issue, Collections.emptyList()).contains("（无）"));
  }

  /**
   * 正文可能几千字。不截断会挤掉上下文里真正需要的信息；截断时必须注明，
   * 否则模型以为自己看到了全文。
   */
  @Test
  public void longBodyIsTruncatedWithNotice() {
    StringBuilder builder = new StringBuilder();
    for (int i = 0; i < 3000; i++) {
      builder.append('x');
    }
    String preview = GitHubIssueService.preview(builder.toString());
    assertTrue(preview.length() < 2500);
    assertTrue(preview.contains("已截断"));
    assertTrue(preview.contains("3000"));
  }

  @Test
  public void shortBodyIsNotTruncated() {
    assertEquals("短正文", GitHubIssueService.preview("短正文"));
  }

  /* ───────────────────────── PR 格式化 ───────────────────────── */

  @Test
  public void emptyPrListExplainsState() {
    String text = GitHubIssueService.formatPullRequests(
        "o", "r", "closed", Collections.emptyList());
    assertTrue(text.contains("没有"));
    assertTrue(text.contains("已关闭"));
  }

  @Test
  public void prListShowsBranchDirection() {
    GitHubPullRequest pull = new GitHubPullRequest(5, "修复登录", "open", "bob", "",
        "https://x/5", "", "", "fix/login", "main", false, false, "", 4, 120, 30);
    String text = GitHubIssueService.formatPullRequests(
        "o", "r", "open", Collections.singletonList(pull));
    assertTrue(text.contains("#5"));
    assertTrue(text.contains("fix/login → main"));
    assertTrue(text.contains("@bob"));
    assertTrue(text.contains("github_pr"));
  }

  @Test
  public void prDetailShowsStats() {
    GitHubPullRequest pull = new GitHubPullRequest(5, "修复登录", "open", "bob", "说明",
        "https://x/5", "", "", "fix/login", "main", false, false, "clean", 4, 120, 30);
    String text = GitHubIssueService.formatPullRequest(pull);
    assertTrue(text.contains("fix/login → main"));
    assertTrue(text.contains("4 个文件"));
    assertTrue(text.contains("+120"));
    assertTrue(text.contains("-30"));
    assertTrue(text.contains("clean"));
  }

  /* ───────────────────────── state 文案 ───────────────────────── */

  @Test
  public void stateLabelCoversAllValues() {
    assertEquals("开启", GitHubIssueService.stateLabel(""));
    assertEquals("开启", GitHubIssueService.stateLabel(null));
    assertEquals("开启", GitHubIssueService.stateLabel("open"));
    assertEquals("已关闭", GitHubIssueService.stateLabel("CLOSED"));
    assertEquals("全部", GitHubIssueService.stateLabel("all"));
  }
}
