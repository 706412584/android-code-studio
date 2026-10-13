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

/**
 * GitHub API 调用失败，带 HTTP 状态码与可读中文说明。
 *
 * <p><b>为什么需要专门的异常类型</b>：调用方（UI、AI 工具）需要对不同的失败做
 * 不同处置——401/403 要引导用户去配置 token（或提示 scope 不足），404 要提示
 * 仓库或 workflow 不存在，429 要提示稍后再试。只抛 {@code Exception("HTTP 404")}
 * 的话这些判断只能靠解析字符串，脆弱且易错。
 */
public class GitHubApiException extends Exception {

  /** GitHub 的错误响应体（通常是 {"message": "..."} 的 JSON）。 */
  public final String responseBody;
  public final int statusCode;

  public GitHubApiException(int statusCode, String message, String responseBody) {
    super(message);
    this.statusCode = statusCode;
    this.responseBody = responseBody == null ? "" : responseBody;
  }

  /** 是否是鉴权问题（未配置 token、token 无效、scope 不足）。 */
  public boolean isAuthProblem() {
    return statusCode == 401 || statusCode == 403;
  }

  /** 是否是「找不到」（仓库 / workflow / run 不存在，或 token 无权访问）。 */
  public boolean isNotFound() {
    return statusCode == 404;
  }

  /** 是否触发了 GitHub 的速率限制。 */
  public boolean isRateLimited() {
    return statusCode == 429;
  }

  /**
   * 面向用户的中文说明。
   *
   * <p>把常见状态码翻译成可操作的话——用户看到「HTTP 403」不知道要做什么，
   * 看到「GitHub 拒绝了这次请求：token 可能缺少 workflow 权限」才知道下一步。
   */
  public String userMessage() {
    if (statusCode == 401) {
      return "GitHub 鉴权失败：请在云编译设置中配置有效的 Personal Access Token。";
    }
    if (statusCode == 403) {
      return "GitHub 拒绝了请求：token 可能缺少 repo / workflow 权限，或触发了速率限制。";
    }
    if (statusCode == 404) {
      return "找不到目标：仓库不存在、workflow 文件未推送，或 token 无权访问该仓库。";
    }
    if (statusCode == 429) {
      return "GitHub 速率限制：请稍后再试。";
    }
    return "GitHub API 请求失败（HTTP " + statusCode + "）：" + getMessage();
  }
}
