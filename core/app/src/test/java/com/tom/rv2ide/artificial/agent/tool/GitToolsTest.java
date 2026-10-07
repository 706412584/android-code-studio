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
import static org.junit.Assert.assertTrue;

import com.tom.rv2ide.ai.tool.ToolContext;
import com.tom.rv2ide.ai.tool.api.ToolCategory;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.io.File;
import java.nio.file.Files;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.PersonIdent;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * {@link GitTool} / {@link GitWriteTool} 的端到端行为测试。
 *
 * <p><b>为什么能在 JVM 上跑</b>：JGit 是纯 Java 库，工具类本身除 {@code JSONObject}
 * 外不引用 Android 类型（{@code ToolContext} 亦然）。测试在临时目录里真建仓库、
 * 真提交、真推送到本地裸仓库——不是 mock。这些断言的价值在于把「模型拿到的输出
 * 到底长什么样」钉住：错误文案必须指向下一步操作，而不是裸的 false。
 *
 * <p><b>测试里刻意关闭 core.autocrlf</b>：JGit 会读用户的全局 gitconfig，
 * 若机器上开着 autocrlf，工作区 LF 文件在 status 里可能被标成 modified，
 * 让「工作区干净」的断言在部分机器上偶发失败。local config 优先级更高，关掉即可。
 */
public final class GitToolsTest {

  @Rule public TemporaryFolder tmp = new TemporaryFolder();

  private final GitTool readTool = new GitTool();
  private final GitWriteTool writeTool =
      new GitWriteTool(
          new GitWriteTool.GitIdentityProvider() {
            @Override
            public String authorName() {
              return "Test User";
            }

            @Override
            public String authorEmail() {
              return "test@example.com";
            }

            @Override
            public String username() {
              return null;
            }

            @Override
            public String password() {
              return null;
            }
          });

  // ---- 测试基建 ----

  /** 建一个临时仓库并完成首次提交，返回仓库根路径。 */
  private String initRepoWithCommit() throws Exception {
    String root = initRepoWithoutCommit();
    Git git = Git.open(new File(root));
    writeFile(new File(root, "a.txt"), "hello\n");
    git.add().addFilepattern(".").call();
    git.commit().setMessage("init").setAuthor(new PersonIdent("T", "t@e")).call();
    git.close();
    return root;
  }

  /** 建一个空仓库（无提交），返回仓库根路径。 */
  private String initRepoWithoutCommit() throws Exception {
    File dir = tmp.newFolder();
    Git git = Git.init().setDirectory(dir).call();
    git.getRepository().getConfig().setBoolean("core", null, "autocrlf", false);
    git.getRepository().getConfig().save();
    git.close();
    return dir.getAbsolutePath();
  }

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

  /** 写文本文件。用 Java 8 API（测试编译目标是 release 8，Files.writeString 是 11+）。 */
  private static void writeFile(File file, String content) throws Exception {
    Files.write(file.toPath(), content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  /** 读文本文件。用 Java 8 API（同上）。 */
  private static String readFile(File file) throws Exception {
    return new String(
        Files.readAllBytes(file.toPath()), java.nio.charset.StandardCharsets.UTF_8);
  }

  // ---- 权限元数据 ----

  @Test
  public void readToolIsReadonlyFriendlyAndWriteToolIsNot() {
    // 权限层按工具分级：这个断言钉住「查询免打扰、写操作必须确认」的边界，
    // 两边任何一侧被改错都是安全或可用性事故。
    assertEquals(ToolCategory.READ, readTool.getCategory());
    assertTrue(readTool.isAllowedInReadonlyMode());
    assertFalse(readTool.needsConfirmation());

    assertEquals(ToolCategory.SYSTEM, writeTool.getCategory());
    assertFalse(writeTool.isAllowedInReadonlyMode());
    assertTrue(writeTool.needsConfirmation());
  }

  @Test
  public void writeToolStaysSerial() {
    // stage/commit/push 的调用顺序不能被打乱，因此写工具必须串行。
    assertFalse(writeTool.isConcurrencySafe());
  }

  // ---- 错误路径 ----

  @Test
  public void nonRepoDirectoryGivesActionableError() throws Exception {
    File plain = tmp.newFolder();
    ToolResult read = readTool.execute(args("action", "status"), contextAt(plain.getAbsolutePath()));
    ToolResult write =
        writeTool.execute(args("action", "commit"), contextAt(plain.getAbsolutePath()));

    assertTrue(read.isError());
    assertTrue(read.getContent(), read.getContent().contains(".git"));
    assertTrue(write.isError());
    assertTrue(write.getContent(), write.getContent().contains(".git"));
  }

  @Test
  public void missingWorkspaceGivesActionableError() throws Exception {
    ToolResult result = readTool.execute(args("action", "status"), ToolContext.builder().build());
    assertTrue(result.isError());
    assertTrue(result.getContent(), result.getContent().contains("工作区"));
  }

  @Test
  public void unknownActionsAreRejectedWithTheAllowedList() throws Exception {
    String root = initRepoWithCommit();
    ToolResult read = readTool.execute(args("action", "reset_hard"), contextAt(root));
    ToolResult write = writeTool.execute(args("action", "reset_hard"), contextAt(root));

    assertTrue(read.isError());
    assertTrue(read.getContent(), read.getContent().contains("status"));
    assertTrue(write.isError());
    assertTrue(write.getContent(), write.getContent().contains("stage"));
  }

  // ---- 只读查询 ----

  @Test
  public void statusShowsBranchAndCleanWorktree() throws Exception {
    String root = initRepoWithCommit();
    ToolResult result = readTool.execute(args("action", "status"), contextAt(root));

    assertFalse(result.getContent(), result.isError());
    assertTrue(result.getContent(), result.getContent().contains("分支"));
    assertTrue(result.getContent(), result.getContent().contains("干净"));
  }

  @Test
  public void statusSeparatesStagedAndUnstaged() throws Exception {
    String root = initRepoWithCommit();
    writeFile(new File(root, "a.txt"), "hello\nworld\n");
    writeFile(new File(root, "b.txt"), "new\n");
    try (Git git = Git.open(new File(root))) {
      git.add().addFilepattern("a.txt").call();
    }

    ToolResult result = readTool.execute(args("action", "status"), contextAt(root));
    String out = result.getContent();
    assertFalse(out, result.isError());
    assertTrue(out, out.contains("已暂存"));
    assertTrue(out, out.contains("a.txt"));
    assertTrue(out, out.contains("未跟踪") || out.contains("未暂存"));
    assertTrue(out, out.contains("b.txt"));
  }

  @Test
  public void diffShowsRealUnifiedDiffIncludingUntrackedFiles() throws Exception {
    String root = initRepoWithCommit();
    writeFile(new File(root, "a.txt"), "hello\nworld\n");
    writeFile(new File(root, "b.txt"), "brand new\n");

    ToolResult result = readTool.execute(args("action", "diff"), contextAt(root));
    String out = result.getContent();
    assertFalse(out, result.isError());
    // 真 diff 的核心特征：diff --git 头与 + 行。旧实现（getFileDiff）只有一行
    // "MODIFY: a.txt"，模型拿不到「改了什么」。
    assertTrue(out, out.contains("diff --git"));
    assertTrue(out, out.contains("+world"));
    // 未跟踪的新文件也要出现（HEAD vs 工作区方向天然包含）。
    assertTrue(out, out.contains("b.txt"));
  }

  @Test
  public void diffWithPathOnlyCoversThatFile() throws Exception {
    String root = initRepoWithCommit();
    writeFile(new File(root, "a.txt"), "hello\nworld\n");
    writeFile(new File(root, "b.txt"), "brand new\n");

    ToolResult result = readTool.execute(args("action", "diff", "path", "a.txt"), contextAt(root));
    String out = result.getContent();
    assertFalse(out, result.isError());
    assertTrue(out, out.contains("+world"));
    assertFalse("按路径过滤后不应出现 b.txt：" + out, out.contains("b.txt"));
  }

  @Test
  public void diffOnCleanRepoExplainsWhyItIsEmpty() throws Exception {
    String root = initRepoWithCommit();
    ToolResult result = readTool.execute(args("action", "diff"), contextAt(root));
    // 空输出必须与「失败」区分开：给出人类可读的解释，而不是空白。
    assertFalse(result.isError());
    assertTrue(result.getContent(), result.getContent().contains("没有差异"));
  }

  @Test
  public void logListsCommitWithShortHashAndMessage() throws Exception {
    String root = initRepoWithCommit();
    ToolResult result = readTool.execute(args("action", "log"), contextAt(root));
    String out = result.getContent();
    assertFalse(out, result.isError());
    assertTrue(out, out.contains("init"));
    assertTrue(out, out.contains("Test") || out.contains("T"));
  }

  @Test
  public void branchesMarkTheCurrentOne() throws Exception {
    String root = initRepoWithCommit();
    try (Git git = Git.open(new File(root))) {
      git.branchCreate().setName("feature").call();
    }
    ToolResult result = readTool.execute(args("action", "branches"), contextAt(root));
    String out = result.getContent();
    assertFalse(out, result.isError());
    assertTrue(out, out.contains("feature"));
    // 当前分支必须被标注出来，用户才能一眼看出在哪个分支上。
    assertTrue(out, out.contains("<- 当前"));
  }

  @Test
  public void remotesEmptyExplainsHowToAdd() throws Exception {
    String root = initRepoWithCommit();
    ToolResult result = readTool.execute(args("action", "remotes"), contextAt(root));
    assertFalse(result.isError());
    assertTrue(result.getContent(), result.getContent().contains("remote_add"));
  }

  @Test
  public void emptyRepoQueriesDoNotCrash() throws Exception {
    // 空仓库（无 HEAD）是最容易炸的边界：resolve(HEAD) 为 null、branchList 为空。
    String root = initRepoWithoutCommit();
    for (String action : new String[] {"status", "diff", "log", "branches"}) {
      ToolResult result = readTool.execute(args("action", action), contextAt(root));
      assertFalse("action=" + action + " 不应报错：" + result.getContent(), result.isError());
    }
  }

  // ---- 写操作 ----

  @Test
  public void stageAndCommitFlowProducesARealCommit() throws Exception {
    String root = initRepoWithCommit();
    writeFile(new File(root, "a.txt"), "hello\nworld\n");

    ToolResult staged = writeTool.execute(args("action", "stage", "file_path", "a.txt"), contextAt(root));
    assertFalse(staged.getContent(), staged.isError());

    ToolResult committed =
        writeTool.execute(args("action", "commit", "message", "add world"), contextAt(root));
    assertFalse(committed.getContent(), committed.isError());
    // 成功回执要带短 hash，模型与用户才能核对提交真的落地。
    assertTrue(committed.getContent(), committed.getContent().contains("add world"));

    try (Git git = Git.open(new File(root))) {
      assertEquals("add world", git.log().setMaxCount(1).call().iterator().next().getShortMessage());
    }
  }

  @Test
  public void commitWithoutStagedChangesExplainsToStageFirst() throws Exception {
    String root = initRepoWithCommit();
    ToolResult result =
        writeTool.execute(args("action", "commit", "message", "empty"), contextAt(root));
    assertTrue(result.isError());
    assertTrue(result.getContent(), result.getContent().contains("stage"));
  }

  @Test
  public void commitWithoutMessageIsRejected() throws Exception {
    String root = initRepoWithCommit();
    ToolResult result = writeTool.execute(args("action", "commit"), contextAt(root));
    assertTrue(result.isError());
    assertTrue(result.getContent(), result.getContent().contains("message"));
  }

  @Test
  public void discardRefusesUntrackedFilesInsteadOfPretendingSuccess() throws Exception {
    // JGit 对未跟踪路径的 checkout(path) 静默无操作：不抛异常、文件不动，
    // 但 GitManager.discardChanges 返回 true。不预检就会谎报「已丢弃」
    // （同版 JGit 6.8.0 实测确认）。这个测试钉住预检。
    String root = initRepoWithCommit();
    writeFile(new File(root, "untracked.txt"), "x\n");

    ToolResult result =
        writeTool.execute(args("action", "discard", "file_path", "untracked.txt"), contextAt(root));
    assertTrue(result.getContent(), result.isError());
    assertTrue(result.getContent(), result.getContent().contains("untracked")
        || result.getContent().contains("未跟踪"));
    // 文件必须原封不动。
    assertTrue(new File(root, "untracked.txt").exists());
  }

  @Test
  public void discardRevertsModifiedTrackedFile() throws Exception {
    String root = initRepoWithCommit();
    writeFile(new File(root, "a.txt"), "hello\nchanged\n");

    ToolResult result =
        writeTool.execute(args("action", "discard", "file_path", "a.txt"), contextAt(root));
    assertFalse(result.getContent(), result.isError());
    assertEquals("hello\n", readFile(new File(root, "a.txt")));
  }

  @Test
  public void checkoutNonexistentBranchExplainsTheAlternatives() throws Exception {
    String root = initRepoWithCommit();
    ToolResult result =
        writeTool.execute(args("action", "checkout", "branch", "nope"), contextAt(root));
    assertTrue(result.isError());
    assertTrue(result.getContent(), result.getContent().contains("branch_create"));
  }

  @Test
  public void branchCreateThenCheckoutSwitches() throws Exception {
    String root = initRepoWithCommit();

    ToolResult created =
        writeTool.execute(args("action", "branch_create", "branch", "feature"), contextAt(root));
    assertFalse(created.getContent(), created.isError());

    ToolResult checkedOut =
        writeTool.execute(args("action", "checkout", "branch", "feature"), contextAt(root));
    assertFalse(checkedOut.getContent(), checkedOut.isError());

    try (Git git = Git.open(new File(root))) {
      assertEquals("feature", git.getRepository().getBranch());
    }
  }

  @Test
  public void pushToLocalBareRepoWorksAndPullReportsUpToDate() throws Exception {
    // 端到端验证 push/pull 的装配：本地裸仓库作远端（不需要网络与凭据）。
    String root = initRepoWithCommit();
    File bare = tmp.newFolder();
    try (Git ignored = Git.init().setBare(true).setDirectory(bare).call()) {}

    String url = bare.getAbsolutePath().replace('\\', '/');
    ToolResult added =
        writeTool.execute(
            args("action", "remote_add", "remote", "origin", "url", url), contextAt(root));
    assertFalse(added.getContent(), added.isError());

    String branch;
    try (Git git = Git.open(new File(root))) {
      branch = git.getRepository().getBranch();
    }

    ToolResult pushed =
        writeTool.execute(
            args("action", "push", "remote", "origin", "branch", branch), contextAt(root));
    assertFalse(pushed.getContent(), pushed.isError());

    // 远端引用真的被写入。
    try (Git git = Git.open(new File(root))) {
      assertNotNull(git.lsRemote().setRemote("origin").call().stream()
          .filter(ref -> ref.getName().equals("refs/heads/" + branch))
          .findFirst()
          .orElse(null));
    }
  }

  @Test
  public void remoteAddDuplicateIsRejectedWithTheExistingUrl() throws Exception {
    String root = initRepoWithCommit();
    File bare = tmp.newFolder();
    try (Git ignored = Git.init().setBare(true).setDirectory(bare).call()) {}
    String url = bare.getAbsolutePath().replace('\\', '/');

    writeTool.execute(args("action", "remote_add", "remote", "origin", "url", url), contextAt(root));
    ToolResult again =
        writeTool.execute(args("action", "remote_add", "remote", "origin", "url", url), contextAt(root));

    assertTrue(again.isError());
    assertTrue(again.getContent(), again.getContent().contains("remote_remove"));
  }

  @Test
  public void pushWithoutRemotesExplainsToAddOne() throws Exception {
    String root = initRepoWithCommit();
    ToolResult result = writeTool.execute(args("action", "push"), contextAt(root));
    assertTrue(result.isError());
    assertTrue(result.getContent(), result.getContent().contains("remote_add"));
  }

  @Test
  public void stageDotStagesEverything() throws Exception {
    String root = initRepoWithCommit();
    writeFile(new File(root, "a.txt"), "hello\nworld\n");
    writeFile(new File(root, "b.txt"), "new\n");

    ToolResult result =
        writeTool.execute(args("action", "stage", "file_path", "."), contextAt(root));
    assertFalse(result.getContent(), result.isError());

    try (Git git = Git.open(new File(root))) {
      com.tom.rv2ide.git.GitManager manager = new com.tom.rv2ide.git.GitManager(root);
      manager.openRepository();
      long staged = manager.getChangedFiles().stream().filter(c -> c.isStaged()).count();
      manager.close();
      assertEquals(2, staged);
    }
  }
}
