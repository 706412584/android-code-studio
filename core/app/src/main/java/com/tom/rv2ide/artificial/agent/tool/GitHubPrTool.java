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
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONObject;

/**
 * GitHub Pull Request 的**只读**查询工具：列表 / 详情。
 *
 * <p>写操作在 {@link GitHubPrWriteTool}——拆分理由同 {@link GitHubIssueTool}
 * 与 {@code GitHubIssueWriteTool}：权限层按工具分级。
 */
public final class GitHubPrTool extends BaseTool {

  private static final Map<String, String> ACTIONS = new LinkedHashMap<>();

  static {
    ACTIONS.put("list", "列出 PR（默认只列开启的）");
    ACTIONS.put("get", "查看单个 PR 的分支、变更统计与正文（需 number）");
  }

  private final GitHubActionsBuildTool.TokenProvider tokenProvider;

  public GitHubPrTool(GitHubActionsBuildTool.TokenProvider tokenProvider) {
    this.tokenProvider = tokenProvider;
  }

  @Override
  public String getName() {
    return ToolNames.GITHUB_PR;
  }

  @Override
  public String getDescription() {
    return "查询当前项目 GitHub 仓库的 Pull Request：list（列表，默认只列开启的）、"
        + "get（详情：分支、变更文件数、增删行、正文，需 number）。只读，不修改任何内容。"
        + "要创建/评论/关闭/合并 PR 用 github_pr_write。";
  }

  @Override
  public ToolCategory getCategory() {
    return ToolCategory.READ;
  }

  @Override
  public ToolDisplayCategory getDisplayCategory() {
    return ToolDisplayCategory.READ;
  }

  @Override
  public boolean isAllowedInReadonlyMode() {
    return true;
  }

  @Override
  public boolean isConcurrencySafe() {
    return true;
  }

  @Override
  public JSONObject getParameters() throws org.json.JSONException {
    JSONObject action = new JSONObject().put("type", "string").put("description", "查询类型");
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
                    "number",
                    new JSONObject()
                        .put("type", "integer")
                        .put("description", "action=get 必填：PR 编号"))
                .put(
                    "state",
                    new JSONObject()
                        .put("type", "string")
                        .put("description", "action=list 用：open / closed / all，默认 open")
                        .put("enum", new org.json.JSONArray().put("open").put("closed").put("all")))
                .put(
                    "limit",
                    new JSONObject()
                        .put("type", "integer")
                        .put("description",
                            "action=list 用：最多返回条数，默认 " + GitHubIssueSupport.DEFAULT_LIMIT
                                + "，上限 " + GitHubIssueSupport.MAX_LIMIT)))
        .put("required", new org.json.JSONArray().put("action"));
  }

  @Override
  public ToolResult execute(JSONObject input, ToolContext context) {
    GitHubIssueSupport.Setup setup =
        GitHubIssueSupport.prepare(getName(), tokenProvider, context);
    if (setup.error != null) {
      return setup.error;
    }

    String action = input.optString("action", "").trim().toLowerCase(java.util.Locale.ROOT);
    if (!ACTIONS.containsKey(action)) {
      return error("不支持的 action：" + action + "。可用值：" + String.join(" / ", ACTIONS.keySet()));
    }

    if ("get".equals(action)) {
      int number = GitHubIssueSupport.parseNumber(input);
      if (number < 0) {
        return error("action=get 需要 PR 编号：请传 number。");
      }
      if (context != null) {
        context.reportProgress("读取 PR #" + number + "…");
      }
      try {
        return ok(setup.service.describePullRequest(number));
      } catch (Exception e) {
        return error("读取 PR #" + number + " 失败：" + GitHubIssueSupport.describeError(e));
      }
    }

    if (context != null) {
      context.reportProgress("查询 GitHub PR 列表…");
    }
    try {
      return ok(setup.service.describePullRequests(
          input.optString("state", ""),
          GitHubIssueSupport.resolveLimit(input, GitHubIssueSupport.DEFAULT_LIMIT)));
    } catch (Exception e) {
      return error("查询 PR 列表失败：" + GitHubIssueSupport.describeError(e));
    }
  }
}
