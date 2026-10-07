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

import com.tom.rv2ide.ai.tool.BaseTool;
import com.tom.rv2ide.ai.tool.ToolContext;
import com.tom.rv2ide.ai.tool.api.ToolCategory;
import com.tom.rv2ide.ai.tool.api.ToolDisplayCategory;
import com.tom.rv2ide.ai.tool.api.ToolNames;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.dircache.DirCacheIterator;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.FileTreeIterator;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.json.JSONObject;

/**
 * 项目 git 仓库的**只读查询**工具（JGit 实现）。
 *
 * <p><b>为什么需要它</b>：AI 助手此前对 git 一无所知——它改完文件后无法查看
 * 「哪些文件变了」「diff 长什么样」「最近提交了什么」，只能靠 shell_execute 手敲
 * git 命令（Termux 后端下未必装了 git，Shizuku 后端下更无 git），或者干脆盲改。
 * 本工具把 GitManager 已有的仓库能力正式暴露给模型，形成「改代码 → 看 diff →
 * 提交」的闭环。
 *
 * <p><b>为什么读写分成两个工具</b>：权限层按**工具**决定分级——
 * {@code needsConfirmation()} 无参数（无法对部分 action 收紧），只读模式的放行
 * 也只看工具分类。混在一个工具里，所有查询都会连带背上「写」的确认门；
 * 而反过来把写操作标成只读则是安全漏洞。因此按 file_read/file_write 的先例拆开：
 * 本类只读（READ 分类、免确认、只读模式放行），写操作在 {@link GitWriteTool}。
 *
 * <p><b>为什么直连 JGit 而不经 GitManager</b>：见 {@link GitRepoSupport} 类注释——
 * GitManager 的 {@code getFileDiff} 不是真 diff（只返回 changeType 一行），
 * 而真 diff 需要 {@code DiffFormatter} + {@code Repository}。
 */
public final class GitTool extends BaseTool {

  /** 各 action 白名单与说明。顺序即提示词里的展示顺序。 */
  private static final Map<String, String> ACTIONS = new LinkedHashMap<>();

  static {
    ACTIONS.put("status", "工作区状态：暂存/未暂存/未跟踪/冲突文件清单");
    ACTIONS.put("diff", "真实 unified diff。默认工作区 vs HEAD；staged=true 看暂存区 vs HEAD");
    ACTIONS.put("log", "最近提交历史（hash/作者/时间/首行信息）");
    ACTIONS.put("branches", "本地分支列表与当前分支");
    ACTIONS.put("remotes", "已配置的远端仓库（名称与 URL）");
  }

  /** log 默认条数。 */
  private static final int DEFAULT_LOG_LIMIT = 20;

  /** log 上限，防止模型把整个历史拉进上下文。 */
  private static final int MAX_LOG_LIMIT = 200;

  /** diff 输出上限的提示（实际截断在 {@link GitRepoSupport#truncate}）。 */
  private static final String DIFF_TRUNCATION_HINT = "\n（diff 过大已截断，可用 path 参数只看某个文件）";

  public GitTool() {}

  @Override
  public String getName() {
    return ToolNames.GIT;
  }

  @Override
  public String getDescription() {
    return "查询当前项目 git 仓库的只读信息：status（改了哪些文件）、diff（真实 unified diff）、"
        + "log（提交历史）、branches（分支）、remotes（远端）。"
        + "改完代码后建议先 diff 自查改动，再决定是否 git_write 提交。"
        + "写操作（暂存/提交/分支/推送）用 git_write 工具。";
  }

  @Override
  public ToolCategory getCategory() {
    return ToolCategory.READ;
  }

  @Override
  public ToolDisplayCategory getDisplayCategory() {
    return ToolDisplayCategory.READ;
  }

  /** 只读查询，与 file_read 等同级，只读模式下放行。 */
  @Override
  public boolean isAllowedInReadonlyMode() {
    return true;
  }

  /**
   * 多个查询互不依赖且仓库操作是纯读，可并发。
   *
   * <p>注意只对**本工具**成立：写工具（GitWriteTool）刻意保持串行，
   * 避免 stage/commit 的调用顺序被打乱。
   */
  @Override
  public boolean isConcurrencySafe() {
    return true;
  }

  @Override
  public String promptSupplement(String executionMode) {
    return "查看改动用本工具（diff/status），比 shell_execute 手敲 git 命令可靠"
        + "（不依赖设备装了 git）。提交前先用 diff 自查。";
  }

  @Override
  public JSONObject getParameters() throws org.json.JSONException {
    JSONObject action = new JSONObject().put("type", "string").put("description",
        "查询类型，见下。默认 status");
    StringBuilder hint = new StringBuilder("可用值：");
    for (Map.Entry<String, String> e : ACTIONS.entrySet()) {
      hint.append("\n- ").append(e.getKey()).append(": ").append(e.getValue());
    }
    action.put("description", action.getString("description") + "\n" + hint);
    action.put("enum", new org.json.JSONArray(ACTIONS.keySet()));
    return new JSONObject()
        .put("type", "object")
        .put(
            "properties",
            new JSONObject()
                .put("action", action)
                .put(
                    "path",
                    new JSONObject()
                        .put("type", "string")
                        .put("description",
                            "可选：只看该路径（仓库相对路径或绝对路径）。"
                                + "diff 时限定单个文件，status 时过滤清单"))
                .put(
                    "staged",
                    new JSONObject()
                        .put("type", "boolean")
                        .put("description",
                            "仅 diff 用：true 看暂存区 vs HEAD（即将提交的内容）；"
                                + "false/省略看工作区 vs HEAD"))
                .put(
                    "limit",
                    new JSONObject()
                        .put("type", "number")
                        .put("description",
                            "仅 log 用：返回条数，默认 " + DEFAULT_LOG_LIMIT + "，最大 " + MAX_LOG_LIMIT)))
        .put("required", new org.json.JSONArray());
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    String action = input.optString("action", "status").trim().toLowerCase();
    if (!ACTIONS.containsKey(action)) {
      return GitRepoSupport.err(getName(),
          "不支持的 action：" + action + "。可用：" + String.join(", ", ACTIONS.keySet()));
    }

    ToolResult[] openError = new ToolResult[1];
    String root = GitRepoSupport.requireRepoRoot(getName(), context, openError);
    if (root == null) {
      return openError[0];
    }

    try (Git git = GitRepoSupport.openGit(getName(), root, openError)) {
      if (git == null) {
        return openError[0];
      }
      try {
        switch (action) {
          case "status":
            return status(git, root, input);
          case "diff":
            return diff(git, root, input);
          case "log":
            return log(git, input);
          case "branches":
            return branches(git);
          case "remotes":
            return remotes(git);
          default:
            // 白名单已挡在前面，走到这里说明 ACTIONS 与 switch 不同步（开发期错误）。
            return GitRepoSupport.err(getName(), "未实现的 action：" + action);
        }
      } catch (Exception e) {
        // 工具约定不得抛异常：JGit 的 API 异常（NoHeadException、CorruptObjectException 等）
        // 必须翻译成错误结果回灌给模型，否则会炸掉整轮对话。
        return GitRepoSupport.err(getName(),
            "git " + action + " 失败：" + describeGitError(e));
      }
    }
  }

  /** JGit 异常的简短可读描述：取异常类型 + 消息，空消息时只留类型。 */
  private static String describeGitError(Exception e) {
    String message = e.getMessage();
    String type = e.getClass().getSimpleName();
    if (message == null || message.trim().isEmpty()) {
      return type;
    }
    return type + ": " + message.trim();
  }

  // ---- status ----

  private ToolResult status(Git git, String root, JSONObject input) throws Exception {
    String filter = GitRepoSupport.toRepoRelative(root, input.optString("path", "").trim());
    Status st = git.status().call();

    StringBuilder sb = new StringBuilder();
    String branch = safeBranch(git.getRepository());
    sb.append("分支：").append(branch).append('\n');
    if (!filter.isEmpty()) {
      sb.append("（已按路径过滤：").append(filter).append("）\n");
    }

    int total = 0;
    total += appendList(sb, "已暂存-新增", st.getAdded(), filter);
    total += appendList(sb, "已暂存-修改", st.getChanged(), filter);
    total += appendList(sb, "已暂存-删除", st.getRemoved(), filter);
    // modified 里可能同时出现在 changed（既暂存又有后续修改），GitManager 的展示
    // 刻意跳过这些；这里同样跳过，避免同一文件在两组里重复出现造成误读。
    java.util.Set<String> changed = st.getChanged();
    total += appendList(sb, "未暂存-修改", minus(st.getModified(), changed), filter);
    total += appendList(sb, "未暂存-删除", minus(st.getMissing(), st.getRemoved()), filter);
    total += appendList(sb, "未跟踪", st.getUntracked(), filter);
    total += appendList(sb, "冲突", st.getConflicting(), filter);

    if (total == 0) {
      sb.append(filter.isEmpty() ? "工作区干净，没有改动。" : "该路径没有改动。");
    } else {
      sb.append("共 ").append(total).append(" 项改动。");
    }
    return GitRepoSupport.ok(getName(), sb.toString());
  }

  /** 追加一组文件；返回实际追加的数量（过滤后）。 */
  private static int appendList(
      StringBuilder sb, String label, java.util.Set<String> files, String filter) {
    int count = 0;
    StringBuilder lines = new StringBuilder();
    for (String f : files) {
      if (!filter.isEmpty() && !f.equals(filter) && !f.startsWith(filter + "/")) {
        continue;
      }
      lines.append("  ").append(f).append('\n');
      count++;
    }
    if (count > 0) {
      sb.append(label).append("（").append(count).append("）：\n").append(lines);
    }
    return count;
  }

  private static java.util.Set<String> minus(java.util.Set<String> from, java.util.Set<String> exclude) {
    java.util.Set<String> result = new java.util.LinkedHashSet<>(from);
    result.removeAll(exclude);
    return result;
  }

  // ---- diff ----

  private ToolResult diff(Git git, String root, JSONObject input) throws Exception {
    Repository repo = git.getRepository();
    boolean staged = input.optBoolean("staged", false);
    String path = GitRepoSupport.toRepoRelative(root, input.optString("path", "").trim());

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (DiffFormatter fmt = new DiffFormatter(out)) {
      fmt.setRepository(repo);
      fmt.setContext(3);
      if (!path.isEmpty()) {
        fmt.setPathFilter(PathFilter.create(path));
      }
      AbstractTreeIterator oldTree = headTreeIterator(repo);
      AbstractTreeIterator newTree =
          staged ? new DirCacheIterator(repo.readDirCache()) : new FileTreeIterator(repo);
      List<DiffEntry> entries = fmt.scan(oldTree, newTree);
      if (entries.isEmpty()) {
        String what = staged ? "暂存区" : "工作区";
        return GitRepoSupport.ok(getName(),
            path.isEmpty() ? what + "相对 HEAD 没有差异。" : path + " 在" + what + "相对 HEAD 没有差异。");
      }
      fmt.format(entries);
      fmt.flush();
    }
    String text = out.toString(StandardCharsets.UTF_8.name());
    if (text.length() > GitRepoSupport.MAX_OUTPUT_CHARS) {
      return GitRepoSupport.ok(getName(),
          GitRepoSupport.truncate(text) + DIFF_TRUNCATION_HINT);
    }
    return GitRepoSupport.ok(getName(), text);
  }

  /**
   * HEAD 的树迭代器；未出生 HEAD（尚无提交）时用空树。
   *
   * <p>{@code resolve(Constants.HEAD)} 返回的是**提交** id 而非树 id——直接交给
   * {@code CanonicalTreeParser.reset} 会抛 {@code IncorrectObjectTypeException}
   * （JGit spike 实测确认）。必须先 {@code RevWalk.parseCommit} 再取 tree。
   */
  private static AbstractTreeIterator headTreeIterator(Repository repo) throws Exception {
    ObjectId head = repo.resolve(Constants.HEAD);
    if (head == null) {
      return new EmptyTreeIterator();
    }
    try (RevWalk rw = new RevWalk(repo);
        org.eclipse.jgit.lib.ObjectReader reader = repo.newObjectReader()) {
      RevCommit commit = rw.parseCommit(head);
      CanonicalTreeParser parser = new CanonicalTreeParser();
      parser.reset(reader, commit.getTree().getId());
      return parser;
    }
  }

  // ---- log ----

  private ToolResult log(Git git, JSONObject input) throws Exception {
    int limit = (int) input.optDouble("limit", DEFAULT_LOG_LIMIT);
    if (limit <= 0) {
      limit = DEFAULT_LOG_LIMIT;
    }
    limit = Math.min(limit, MAX_LOG_LIMIT);

    Iterable<RevCommit> commits;
    try {
      commits = git.log().setMaxCount(limit).call();
    } catch (org.eclipse.jgit.api.errors.NoHeadException e) {
      // 空仓库（尚无提交）时 JGit 抛 NoHeadException——这是正常状态而非失败
      // （同版 JGit 实测确认）。按错误返回会让模型以为仓库坏了，只能反复试错。
      return GitRepoSupport.ok(getName(), "尚无提交（仓库刚初始化，或 HEAD 未出生）。");
    }
    StringBuilder sb = new StringBuilder();
    int count = 0;
    for (RevCommit c : commits) {
      String firstLine = c.getShortMessage();
      sb.append(c.getName(), 0, 7)
          .append("  ")
          .append(c.getAuthorIdent().getName())
          .append("  ")
          .append(c.getAuthorIdent().getWhenAsInstant())
          .append('\n')
          .append("  ")
          .append(firstLine)
          .append('\n');
      count++;
    }
    if (count == 0) {
      return GitRepoSupport.ok(getName(), "尚无提交（仓库刚初始化，或 HEAD 未出生）。");
    }
    return GitRepoSupport.ok(getName(), sb.toString());
  }

  // ---- branches ----

  private ToolResult branches(Git git) throws Exception {
    String current = safeBranch(git.getRepository());
    StringBuilder sb = new StringBuilder();
    sb.append("当前分支：").append(current).append('\n').append("本地分支：\n");
    List<Ref> refs = git.branchList().call();
    if (refs.isEmpty()) {
      sb.append("  （无）");
    }
    for (Ref ref : refs) {
      String name = Repository.shortenRefName(ref.getName());
      sb.append("  ").append(name);
      if (name.equals(current)) {
        sb.append("  <- 当前");
      }
      sb.append('\n');
    }
    return GitRepoSupport.ok(getName(), sb.toString());
  }

  // ---- remotes ----

  private ToolResult remotes(Git git) throws Exception {
    List<org.eclipse.jgit.transport.RemoteConfig> list = git.remoteList().call();
    if (list.isEmpty()) {
      return GitRepoSupport.ok(getName(), "没有配置远端仓库。可用 git_write action=remote_add 添加。");
    }
    StringBuilder sb = new StringBuilder();
    for (org.eclipse.jgit.transport.RemoteConfig rc : list) {
      String url = rc.getURIs().isEmpty() ? "" : rc.getURIs().get(0).toString();
      sb.append(rc.getName()).append("  ").append(url).append('\n');
    }
    return GitRepoSupport.ok(getName(), sb.toString());
  }

  /** 当前分支名；仓库无分支（未出生 HEAD）时返回 "(未出生)"。 */
  private static String safeBranch(Repository repo) {
    try {
      String branch = repo.getBranch();
      return branch == null || branch.isEmpty() ? "(未出生)" : branch;
    } catch (Exception e) {
      return "(未知)";
    }
  }
}
