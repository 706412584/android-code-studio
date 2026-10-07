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

import com.tom.rv2ide.ai.tool.ToolContext;
import com.tom.rv2ide.ai.tool.api.ToolResult;
import java.io.File;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

/**
 * GitTool / GitWriteTool 的共用支撑：仓库前置校验、原生仓库打开、路径归一、输出截断。
 *
 * <p><b>为什么需要「前置校验」而不是直接调 GitManager</b>：GitManager 的每个方法都把
 * 异常吞进 {@code printStackTrace} 后返回 null/false/空列表——调用方无法区分
 * 「仓库没打开」「操作真的失败」。直接把这种含糊结果翻译给模型，它只知道「失败了」
 * 而不知「为什么」，只能反复试错（实测同类含糊失败烧掉十几次工具调用）。
 * 本类的做法：先验证前置条件（工作区存在、.git 存在），失败时给出**指向解决办法**
 * 的文案；之后 GitManager 再返回 false 就是真正的操作失败。
 *
 * <p><b>为什么 GitTool 直连 JGit 而不经 GitManager</b>：只读查询（status/log/branch/
 * remote）在原生 API 上都很直接，而 {@code GitManager.getFileDiff} 返回的只是
 * {@code "changeType: path"} 一行文本、**不是真正的 diff**（无调用点，属半成品）。
 * 真 diff 必须走 {@link org.eclipse.jgit.diff.DiffFormatter}，它需要 {@code Repository}，
 * 而 GitManager 没有把它暴露出来。
 *
 * <p><b>为什么 GitWriteTool 走 GitManager</b>：写操作与 Git 面板共用同一套实现
 * （含 ignore 处理、credentials provider），AI 的 stage/commit 与用户在面板上的操作
 * 行为一致，不会漂移出两套语义。
 *
 * <p>每次调用新建会话、用完即关：Git 对象持有 Repository（内含文件句柄与 pack 缓存），
 * 跨调用复用需要会话级生命周期管理；工具执行天然是「一次调用一个事务」，即用即关最稳。
 */
final class GitRepoSupport {

  /** 返回给模型的输出上限：32KB，超出保留首尾。与 CodeGraphTool 同值。 */
  static final int MAX_OUTPUT_CHARS = 32 * 1024;

  private GitRepoSupport() {}

  /**
   * 前置校验：会话绑定了工作区、目录存在、是 git 仓库。
   *
   * @return 通过时返回工作区根路径；失败返回 null，并把错误结果填入 {@code errorOut[0]}
   */
  static String requireRepoRoot(String toolName, ToolContext context, ToolResult[] errorOut) {
    String home = context == null ? "" : context.getHomePath();
    if (home == null || home.trim().isEmpty()) {
      errorOut[0] = err(toolName,
          "当前会话没有绑定工作区目录（homePath 为空）。git 工具只能作用于已打开的项目。");
      return null;
    }
    File root = new File(home);
    if (!root.isDirectory()) {
      errorOut[0] = err(toolName, "工作区目录不存在：" + home + "。请重新打开项目。");
      return null;
    }
    if (!new File(root, ".git").exists()) {
      errorOut[0] = err(toolName,
          "此项目不是 git 仓库（缺少 .git 目录）：" + home
              + "。可在 Git 面板点「初始化仓库」，或由用户手动 git init。");
      return null;
    }
    return home;
  }

  /**
   * 打开原生 JGit 会话（GitTool 用）。失败返回 null 并填充 {@code errorOut[0]}。
   *
   * <p>打开方式与 {@code GitManager.openRepository()} 逐行一致（FileRepositoryBuilder +
   * setGitDir + readEnvironment + findGitDir），避免出现「面板能开、工具打不开」的差异。
   */
  static Git openGit(String toolName, String root, ToolResult[] errorOut) {
    try {
      Repository repo = new FileRepositoryBuilder()
          .setGitDir(new File(root, ".git"))
          .readEnvironment()
          .findGitDir()
          .build();
      return new Git(repo);
    } catch (Exception e) {
      errorOut[0] = err(toolName,
          "打开 git 仓库失败：" + root + "/.git 无法解析（" + e.getMessage()
              + "）。仓库可能损坏，请用命令行 git fsck 检查。");
      return null;
    }
  }

  /**
   * 把模型给的路径归一为**仓库相对路径**。
   *
   * <p>模型经常给绝对路径（从 file_read 的上下文里抄来），而 JGit 的 add/reset
   * 等命令对绝对路径会静默失败或行为诡异。这里把位于仓库内的绝对路径压回相对路径；
   * 仓库外的路径原样返回（后续操作自然失败，由调用方报错）。
   */
  static String toRepoRelative(String root, String path) {
    if (path == null) {
      return "";
    }
    String trimmed = path.trim().replace('\\', '/');
    if (trimmed.isEmpty()) {
      return "";
    }
    String normalizedRoot = root.replace('\\', '/');
    if (!normalizedRoot.endsWith("/")) {
      normalizedRoot = normalizedRoot + "/";
    }
    if (trimmed.startsWith(normalizedRoot)) {
      return trimmed.substring(normalizedRoot.length());
    }
    return trimmed;
  }

  /** 首尾保留、中间省略，避免超长输出灌爆上下文。与 CodeGraphTool.truncate 同策略。 */
  static String truncate(String s) {
    if (s == null) {
      return "";
    }
    String t = s.trim();
    if (t.length() <= MAX_OUTPUT_CHARS) {
      return t;
    }
    int head = MAX_OUTPUT_CHARS * 2 / 3;
    int tail = MAX_OUTPUT_CHARS - head;
    return t.substring(0, head)
        + "\n\n…（省略 "
        + (t.length() - MAX_OUTPUT_CHARS)
        + " 字符）…\n\n"
        + t.substring(t.length() - tail);
  }

  /** 错误结果构造。 */
  static ToolResult err(String toolName, String message) {
    return ToolResult.of("", toolName, message, true);
  }

  /** 成功结果构造。 */
  static ToolResult ok(String toolName, String content) {
    return ToolResult.of("", toolName, content, false);
  }
}
