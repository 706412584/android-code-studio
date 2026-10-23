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
 * GitHub Issue 的**只读**查询工具：列表 / 详情 / 评论。
 *
 * <p><b>为什么与写操作拆成两个工具</b>：权限层按**工具**分级——
 * {@code needsConfirmation()} 无参数，只读模式的放行也只看工具分类。
 * 混在一起会让「看看有哪些 issue」也弹确认框（{@link GitTool} 与
 * {@link GitWriteTool} 拆分时踩过同一个坑）。写操作在 {@link GitHubIssueWriteTool}。
 *
 * <p><b>为什么需要它</b>：此前 AI 只能靠 {@code shell_execute} 敲 {@code gh issue list}
 * ——设备上根本没有 gh，且 token 与仓库识别都在 App 侧。补上这个工具后，
 * 「用户报告了一个 issue，去看看」这类请求才有闭环。
 */
public final class GitHubIssueTool extends BaseTool {

  /** 各 action 白名单与说明。顺序即提示词里的展示顺序。 */
  private static final Map<String, String> ACTIONS = new LinkedHashMap<>();

  static {
    ACTIONS.put("list", "列出 issue（默认只列开启的，不含 PR）");
    ACTIONS.put("get", "查看单个 issue 的正文与最近评论（需 number）");
  }

  private final GitHubActionsBuildTool.TokenProvider tokenProvider;

  public GitHubIssueTool(GitHubActionsBuildTool.TokenProvider tokenProvider) {
    this.tokenProvider = tokenProvider;
  }

  @Override
  public String getName() {
    return ToolNames.GITHUB_ISSUE;
  }

  @Override
  public String getDescription() {
    return "查询当前项目 GitHub 仓库的 issue：list（列表，默认只列开启的）、"
        + "get（详情 + 最近评论，需 number）。只读，不修改任何内容。"
        + "要创建/评论/关闭 issue 用 github_issue_write。";
  }

  @Override
  public ToolCategory getCategory() {
    return ToolCategory.READ;
  }

  @Override
  public ToolDisplayCategory getDisplayCategory() {
    return ToolDisplayCategory.READ;
  }

  /** 只读查询，与 git 查询同级，只读模式下放行。 */
  @Override
  public boolean isAllowedInReadonlyMode() {
    return true;
  }

  /** 查询互不依赖，可并发。 */
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
                        .put("description", "action=get 必填：issue 编号（列表里 # 后面的数字）"))
                .put(
                    "state",
                    new JSONObject()
                        .put("type", "string")
                        .put("description",
                            "action=list 用：open / closed / all，默认 open")
                        .put("enum", new org.json.JSONArray().put("open").put("closed").put("all")))
                .put(
                    "labels",
                    new JSONObject()
                        .put("type", "string")
                        .put("description", "action=list 用：逗号分隔的标签过滤，如 bug,ui"))
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
        return error("action=get 需要 issue 编号：请传 number（列表里 # 后面的数字）。");
      }
      if (context != null) {
        context.reportProgress("读取 issue #" + number + "…");
      }
      try {
        int commentLimit = GitHubIssueSupport.resolveLimit(
            input, GitHubIssueSupport.DEFAULT_COMMENT_LIMIT);
        return ok(setup.service.describeIssue(number, commentLimit));
      } catch (Exception e) {
        return error("读取 issue #" + number + " 失败：" + GitHubIssueSupport.describeError(e));
      }
    }

    if (context != null) {
      context.reportProgress("查询 GitHub issue 列表…");
    }
    try {
      return ok(setup.service.describeIssues(
          input.optString("state", ""),
          input.optString("labels", ""),
          GitHubIssueSupport.resolveLimit(input, GitHubIssueSupport.DEFAULT_LIMIT)));
    } catch (Exception e) {
      return error("查询 issue 列表失败：" + GitHubIssueSupport.describeError(e));
    }
  }
}
