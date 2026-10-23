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
import com.tom.rv2ide.cloudbuild.GitHubIssueService;
import org.json.JSONObject;

/**
 * github_issue* / github_pr* 四个工具的共用支撑。
 *
 * <p><b>为什么拆成四个工具而不是两个</b>：{@code needsConfirmation()} 没有参数，
 * 权限层只能按**工具**分级。若把「列 issue」与「关 issue」放同一个工具，
 * 前者会被后者拖着弹确认框（正是 git / git_write 拆分解决的同一个问题）。
 * 拆成「读」「写」两组后，只读模式能放行查询，写操作逐次确认。
 *
 * <p><b>为什么抽一个支撑类</b>：四个工具的前置校验、服务构造、错误翻译逐行相同，
 * 写四遍就是四份会漂移的文案（{@link CloudBuildSupport} 就是这么来的）。
 */
final class GitHubIssueSupport {

  /** 列表默认条数。 */
  static final int DEFAULT_LIMIT = 20;
  /** 列表上限，防止模型把整个 issue 列表拉进上下文。 */
  static final int MAX_LIMIT = 100;
  /** 详情里评论默认条数。 */
  static final int DEFAULT_COMMENT_LIMIT = 20;

  private GitHubIssueSupport() {}

  /** 前置校验通过后的执行环境。 */
  static final class Setup {
    final ToolResult error;
    final GitHubIssueService service;

    private Setup(ToolResult error, GitHubIssueService service) {
      this.error = error;
      this.service = service;
    }

    static Setup failed(ToolResult error) {
      return new Setup(error, null);
    }

    static Setup ok(GitHubIssueService service) {
      return new Setup(null, service);
    }
  }

  /**
   * token → 工作区 → 仓库识别 → 构造服务。
   *
   * <p>与云编译工具共用 {@link CloudBuildSupport#prepareRepo} 的前三段校验：
   * 两处的错误文案必须一致，否则同一件事在模型看来是两种故障。
   */
  static Setup prepare(String toolName, GitHubActionsBuildTool.TokenProvider tokenProvider,
                       ToolContext context) {
    String token = tokenProvider == null ? "" : tokenProvider.get();
    CloudBuildSupport.RepoSetup repoSetup =
        CloudBuildSupport.prepareRepo(toolName, token, context);
    if (repoSetup.error != null) {
      return Setup.failed(repoSetup.error);
    }
    return Setup.ok(
        new GitHubIssueService(token.trim(), repoSetup.slug.owner, repoSetup.slug.repo));
  }

  /**
   * 解析 number 参数。
   *
   * <p>缺失或非正数时返回 -1，由调用方给出「缺 number」的明确错误——不要让它
   * 拼进 URL 变成 {@code /issues/0} 然后拿到一个 404 去猜。
   */
  static int parseNumber(JSONObject input) {
    int number = input.optInt("number", -1);
    return number > 0 ? number : -1;
  }

  static int resolveLimit(JSONObject input, int fallback) {
    int limit = input.optInt("limit", fallback);
    if (limit <= 0) {
      limit = fallback;
    }
    return Math.min(limit, MAX_LIMIT);
  }

  /** 把 API/网络异常翻译成可读文案（复用云编译那套状态码说明）。 */
  static String describeError(Exception e) {
    return CloudBuildSupport.describeApiError(e);
  }

  /** 必填字符串参数校验：返回 null 表示通过，否则返回错误文案。 */
  static String requireNonBlank(JSONObject input, String key, String label) {
    String value = input.optString(key, "").trim();
    if (value.isEmpty()) {
      return "缺少 " + label + "（参数 " + key + "）。";
    }
    return null;
  }

  /** 把标题压成一行：GitHub 允许标题含换行，直接拼进结果会打乱排版。 */
  static String oneLine(String text) {
    return text == null ? "" : text.replace('\r', ' ').replace('\n', ' ').trim();
  }
}
