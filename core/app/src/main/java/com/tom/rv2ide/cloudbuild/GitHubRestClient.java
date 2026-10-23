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

import com.tom.rv2ide.ai.protocol.SimpleHttpClient;
import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONObject;

/**
 * GitHub REST API 的公共底座：拼请求、加鉴权头、把非 2xx 转成 {@link GitHubApiException}。
 *
 * <p><b>为什么抽成基类而不是各客户端各写一份</b>：项目的 HTTP 出口只有
 * {@link SimpleHttpClient} 一处（内含逐跳 {@code UrlPolicy} 校验与全局代理）。
 * 每个 GitHub 客户端各自 new 连接就等于复制出第二处安全边界；而鉴权头漏加、
 * 错误体解析漏判这类差异，表现是「某个工具莫名 401」，很难定位。
 *
 * <p><b>不引入 OkHttp/Retrofit</b>：理由同 {@link GitHubActionsClient}——GitHub API
 * 的调用形态简单（JSON in / JSON out），现有客户端足够。
 *
 * <p><b>线程模型</b>：所有方法同步阻塞，调用方负责放到后台线程。
 */
public abstract class GitHubRestClient {

  protected static final String API_BASE = "https://api.github.com";
  private static final String API_VERSION = "2022-11-28";

  protected static final int CONNECT_TIMEOUT_MS = 15_000;
  protected static final int READ_TIMEOUT_MS = 30_000;

  protected final String token;
  protected final String owner;
  protected final String repo;

  protected GitHubRestClient(String token, String owner, String repo) {
    this.token = token == null ? "" : token.trim();
    this.owner = owner == null ? "" : owner.trim();
    this.repo = repo == null ? "" : repo.trim();
  }

  public String getOwner() {
    return owner;
  }

  public String getRepo() {
    return repo;
  }

  /** 是否配置了 token。未配置时私有仓库全部失败、公开仓库受匿名配额限制。 */
  public boolean hasToken() {
    return !token.isEmpty();
  }

  /** 仓库级 API 的路径前缀：{@code /repos/{owner}/{repo}}。 */
  protected String repoPath() {
    return API_BASE + "/repos/" + encodePathSegment(owner) + "/" + encodePathSegment(repo);
  }

  /** 构造带鉴权与 API 版本头的请求并执行。 */
  protected SimpleHttpClient.Response execute(String method, String url, String body)
      throws Exception {
    SimpleHttpClient.Request request = new SimpleHttpClient.Request(url, method, body);
    request.connectTimeoutMs = CONNECT_TIMEOUT_MS;
    request.readTimeoutMs = READ_TIMEOUT_MS;
    if (body != null) {
      request.headers.put("Content-Type", "application/json");
    }
    request.headers.putAll(authHeaders());
    return SimpleHttpClient.execute(request);
  }

  /** 执行并校验成功；失败抛 {@link GitHubApiException}。 */
  protected JSONObject executeJson(String method, String url, String body) throws Exception {
    SimpleHttpClient.Response response = execute(method, url, body);
    checkSuccess(response);
    return new JSONObject(response.body);
  }

  /**
   * 把非 2xx 响应转成带状态码的 {@link GitHubApiException}。
   *
   * <p>不抛裸 Exception：调用方需要按状态码分流处置（401/403 引导配 token、
   * 404 提示资源不存在、422 提示参数非法、429 提示限流）。
   */
  protected void checkSuccess(SimpleHttpClient.Response response) throws GitHubApiException {
    if (response.code >= 200 && response.code < 300) {
      return;
    }
    String message = "HTTP " + response.code;
    try {
      JSONObject json = new JSONObject(response.body);
      String apiMessage = json.optString("message", "");
      if (!apiMessage.isEmpty()) {
        message = apiMessage;
      }
    } catch (Throwable ignored) {
      // 错误响应体不是 JSON（例如网关返回的 HTML）——保留 HTTP 状态码即可。
    }
    throw new GitHubApiException(response.code, message, response.body);
  }

  protected Map<String, String> authHeaders() {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("Accept", "application/vnd.github+json");
    headers.put("X-GitHub-Api-Version", API_VERSION);
    if (!token.isEmpty()) {
      headers.put("Authorization", "Bearer " + token);
    }
    return headers;
  }

  protected static String encodePathSegment(String value) {
    try {
      // URLEncoder 是 form 编码（空格变 +），路径段里不出现空格，够用；
      // 这里对常见特殊字符做最小转义。
      return URLEncoder.encode(value == null ? "" : value, "UTF-8").replace("+", "%20");
    } catch (java.io.UnsupportedEncodingException e) {
      return value == null ? "" : value;
    }
  }

  protected static String encodeQueryParam(String value) {
    try {
      return URLEncoder.encode(value == null ? "" : value, "UTF-8");
    } catch (java.io.UnsupportedEncodingException e) {
      return value == null ? "" : value;
    }
  }
}
