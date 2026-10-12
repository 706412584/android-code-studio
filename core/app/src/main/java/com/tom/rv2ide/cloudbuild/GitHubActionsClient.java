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

import com.tom.rv2ide.ai.protocol.SimpleHttpClient;
import java.io.File;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * GitHub Actions REST API 客户端：触发 workflow、查询运行状态、拉取日志与产物。
 *
 * <p><b>为什么直接写 REST 调用而不是用 OkHttp/Retrofit</b>：项目的 HTTP 出口只有一处
 * ——{@link SimpleHttpClient}（内含逐跳 UrlPolicy 校验与全局代理）。从这里再建一条
 * 连接就等于复制出第二处安全边界。GitHub API 的调用形态简单（JSON in / JSON out +
 * 两个二进制下载），用现有客户端直接拼 URL 与解析 JSON 足够，不引入新依赖。
 *
 * <p><b>为什么解析用 org.json</b>：它是 Android 框架自带、且在本模块测试中作为
 * testImplementation 可用（见 app 的 build.gradle.kts），与项目既有 JSON 处理一致。
 *
 * <p><b>线程模型</b>：本类的所有方法都是同步阻塞的（网络调用）。调用方负责放到
 * 后台线程执行——Android 主线程不允许网络操作，这是调用方的责任而非本类。
 */
public final class GitHubActionsClient implements RunQueryPort {

  private static final String API_BASE = "https://api.github.com";
  private static final String API_VERSION = "2022-11-28";

  private static final int CONNECT_TIMEOUT_MS = 15_000;
  private static final int READ_TIMEOUT_MS = 30_000;
  /** 二进制下载（日志 zip / artifact）的超时：文件可达数百 MB，给足余量。 */
  private static final int DOWNLOAD_READ_TIMEOUT_MS = 300_000;

  private final String token;
  private final String owner;
  private final String repo;

  public GitHubActionsClient(String token, String owner, String repo) {
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

  /** 是否配置了 token。未配置时所有 API 调用都会失败（私有仓库）或受限于匿名配额。 */
  public boolean hasToken() {
    return !token.isEmpty();
  }

  // ---------------------------------------------------------------------------
  // 仓库创建（一键建仓流程）
  // ---------------------------------------------------------------------------

  /**
   * 在当前 token 的账号下创建一个新仓库。
   *
   * <p><b>为什么默认私有</b>：这是「用户的项目源码」——建公开仓库会把源码发布出去，
   * 而用户点「创建仓库」时想的是「让云编译能跑」，不是「开源我的项目」。
   * 要公开是显式选项。
   *
   * <p>注意 owner 参数不参与：GitHub 的 {@code POST /user/repos} 只能建在
   * **token 持有者**名下（组织仓库要另走 {@code POST /orgs/{org}/repos}，
   * 且需要 org 权限）。本流程面向个人账号。
   *
   * @param repoName 仓库名（会校验合法性）
   * @param isPrivate true 建私有仓库
   * @param description 仓库描述（可为空）
   * @return 创建后的 owner/repo
   * @throws GitHubApiException 失败时（含 422：重名、名字非法）
   */
  public GitRemoteParser.RepoSlug createRepository(
      String repoName, boolean isPrivate, String description) throws Exception {
    if (repoName == null || repoName.trim().isEmpty()) {
      throw new GitHubApiException(0, "仓库名不能为空", "");
    }
    JSONObject body = new JSONObject();
    body.put("name", repoName.trim());
    body.put("private", isPrivate);
    if (description != null && !description.trim().isEmpty()) {
      body.put("description", description.trim());
    }
    // 自动初始化关闭：本地项目已有完整内容，云端初始化 README 会造成推送冲突。
    body.put("auto_init", false);

    SimpleHttpClient.Response response =
        execute("POST", API_BASE + "/user/repos", body.toString());
    checkSuccess(response);

    JSONObject json = new JSONObject(response.body);
    String owner = json.optJSONObject("owner") == null
        ? ""
        : json.optJSONObject("owner").optString("login", "");
    String name = json.optString("name", "");
    if (owner.isEmpty() || name.isEmpty()) {
      throw new GitHubApiException(0, "仓库已创建但响应缺少 owner/name", response.body);
    }
    return GitRemoteParser.parse("https://github.com/" + owner + "/" + name + ".git");
  }

  /**
   * 查当前 token 的身份（用于建仓前显示「将建在哪个账号下」）。
   *
   * @return 登录名；失败时抛异常（调用方据此提示 token 无效）
   */
  public String getAuthenticatedLogin() throws Exception {
    SimpleHttpClient.Response response = execute("GET", API_BASE + "/user", null);
    checkSuccess(response);
    return new JSONObject(response.body).optString("login", "");
  }

  // ---------------------------------------------------------------------------
  // workflow 触发与查询
  // ---------------------------------------------------------------------------

  /**
   * 触发一次 workflow（workflow_dispatch 事件）。
   *
   * <p><b>为什么用 workflow_dispatch 而不是 repository_dispatch</b>：模板生成的
   * workflow 都带 {@code workflow_dispatch:} 触发器（已核实三个模板），而
   * repository_dispatch 需要 workflow 声明 {@code repository_dispatch} 类型，
   * 模板没写。用 dispatch 才能对任意模板的 workflow 生效。
   *
   * @param workflowFile workflow 文件名（如 {@code build.yml}，不含路径）或数字 id
   * @param ref 分支 / tag（默认 {@code null} 表示仓库默认分支）
   * @param inputs workflow_dispatch 的自定义输入（可为空）
   * @throws GitHubApiException 失败时（含鉴权、找不到 workflow）
   */
  public void dispatchWorkflow(String workflowFile, String ref, Map<String, String> inputs)
      throws Exception {
    String url = API_BASE + "/repos/" + owner + "/" + repo + "/actions/workflows/"
        + encodePathSegment(workflowFile) + "/dispatches";

    JSONObject body = new JSONObject();
    if (ref != null && !ref.trim().isEmpty()) {
      body.put("ref", ref.trim());
    }
    if (inputs != null && !inputs.isEmpty()) {
      JSONObject inputsJson = new JSONObject();
      for (Map.Entry<String, String> entry : inputs.entrySet()) {
        inputsJson.put(entry.getKey(), entry.getValue());
      }
      body.put("inputs", inputsJson);
    }

    SimpleHttpClient.Response response = execute("POST", url, body.toString());
    // dispatch 成功返回 204 No Content，没有响应体。
    checkSuccess(response);
  }

  /**
   * 取消一次正在运行（或排队）的 run。
   *
   * <p>GitHub 的语义是「请求取消」：接口返回 202 表示请求已受理，run 会在一小段
   * 时间后变为 {@code completed / cancelled}——因此调用方不需要在这里等状态变化，
   * 交给既有的轮询即可。对已到终态的 run 调用会得到 409，调用方把错误如实呈现。
   *
   * <p>请求体传 {@code null}：cancel 接口不接受也不需要 body（与 dispatch 不同）。
   *
   * @param runId run id
   * @throws GitHubApiException 失败时（含 409：run 已结束，无法取消）
   */
  public void cancelRun(long runId) throws Exception {
    String url = API_BASE + "/repos/" + owner + "/" + repo + "/actions/runs/" + runId + "/cancel";
    SimpleHttpClient.Response response = execute("POST", url, null);
    checkSuccess(response);
  }

  /**
   * 列出仓库的 workflow（文件名与状态）。
   *
   * @return workflow 文件列表（如 {@code build.yml}）
   */
  public List<String> listWorkflows() throws Exception {
    String url = API_BASE + "/repos/" + owner + "/" + repo + "/actions/workflows";
    SimpleHttpClient.Response response = execute("GET", url, null);
    checkSuccess(response);

    List<String> files = new ArrayList<>();
    JSONObject json = new JSONObject(response.body);
    JSONArray workflows = json.optJSONArray("workflows");
    if (workflows != null) {
      for (int i = 0; i < workflows.length(); i++) {
        JSONObject workflow = workflows.optJSONObject(i);
        if (workflow != null) {
          String path = workflow.optString("path", "");
          // path 形如 ".github/workflows/build.yml"，只取文件名。
          int slash = path.lastIndexOf('/');
          files.add(slash >= 0 ? path.substring(slash + 1) : path);
        }
      }
    }
    return files;
  }

  /**
   * 查最新一次 run（可选按分支过滤）。
   *
   * @param workflowFile 可选：限定某个 workflow 文件
   * @param branch 可选：限定分支
   * @return 最新 run；仓库从未运行过该 workflow 时返回 {@code null}
   */
  @Override
  public WorkflowRun getLatestRun(String workflowFile, String branch) throws Exception {
    List<WorkflowRun> runs = listRuns(workflowFile, branch, 1);
    return runs.isEmpty() ? null : runs.get(0);
  }

  /**
   * 列出 run（按创建时间倒序）。
   *
   * @param workflowFile 可选：限定 workflow 文件
   * @param branch 可选：限定分支
   * @param limit 最多返回条数（GitHub 单页上限 100）
   */
  public List<WorkflowRun> listRuns(String workflowFile, String branch, int limit)
      throws Exception {
    StringBuilder url = new StringBuilder(API_BASE + "/repos/" + owner + "/" + repo + "/actions/runs");
    List<String> params = new ArrayList<>();
    if (workflowFile != null && !workflowFile.trim().isEmpty()) {
      params.add("workflow=" + encodePathSegment(workflowFile.trim()));
    }
    if (branch != null && !branch.trim().isEmpty()) {
      params.add("branch=" + encodeQueryParam(branch.trim()));
    }
    params.add("per_page=" + Math.max(1, Math.min(limit, 100)));
    if (!params.isEmpty()) {
      url.append('?').append(join(params, "&"));
    }

    SimpleHttpClient.Response response = execute("GET", url.toString(), null);
    checkSuccess(response);

    List<WorkflowRun> runs = new ArrayList<>();
    JSONObject json = new JSONObject(response.body);
    JSONArray runsArray = json.optJSONArray("workflow_runs");
    if (runsArray != null) {
      for (int i = 0; i < runsArray.length(); i++) {
        JSONObject run = runsArray.optJSONObject(i);
        if (run != null) {
          runs.add(WorkflowRun.fromJson(run));
        }
      }
    }
    return runs;
  }

  /** 按 id 查单个 run。 */
  @Override
  public WorkflowRun getRun(long runId) throws Exception {
    String url = API_BASE + "/repos/" + owner + "/" + repo + "/actions/runs/" + runId;
    SimpleHttpClient.Response response = execute("GET", url, null);
    checkSuccess(response);
    return WorkflowRun.fromJson(new JSONObject(response.body));
  }

  /**
   * 查 run 的 job 列表（含每个 job 的状态与步骤）。
   *
   * <p>这是「看到进度」的数据来源：run 级只有整体状态，job 级才有「哪个卡住了」。
   */
  @Override
  public List<WorkflowJob> listJobs(long runId) throws Exception {
    String url = API_BASE + "/repos/" + owner + "/" + repo + "/actions/runs/" + runId + "/jobs";
    SimpleHttpClient.Response response = execute("GET", url, null);
    checkSuccess(response);

    List<WorkflowJob> jobs = new ArrayList<>();
    JSONObject json = new JSONObject(response.body);
    JSONArray jobsArray = json.optJSONArray("jobs");
    if (jobsArray != null) {
      for (int i = 0; i < jobsArray.length(); i++) {
        JSONObject job = jobsArray.optJSONObject(i);
        if (job != null) {
          jobs.add(WorkflowJob.fromJson(job));
        }
      }
    }
    return jobs;
  }

  /**
   * 列出 run 的 artifact。
   *
   * <p>GitHub 分页，这里最多取前 100 个——单次构建的产物不会超过这个量级。
   */
  public List<WorkflowArtifact> listArtifacts(long runId) throws Exception {
    String url = API_BASE + "/repos/" + owner + "/" + repo + "/actions/runs/" + runId
        + "/artifacts?per_page=100";
    SimpleHttpClient.Response response = execute("GET", url, null);
    checkSuccess(response);

    List<WorkflowArtifact> artifacts = new ArrayList<>();
    JSONObject json = new JSONObject(response.body);
    JSONArray artifactsArray = json.optJSONArray("artifacts");
    if (artifactsArray != null) {
      for (int i = 0; i < artifactsArray.length(); i++) {
        JSONObject artifact = artifactsArray.optJSONObject(i);
        if (artifact != null) {
          artifacts.add(WorkflowArtifact.fromJson(artifact));
        }
      }
    }
    return artifacts;
  }

  // ---------------------------------------------------------------------------
  // 下载
  // ---------------------------------------------------------------------------

  /**
   * 下载 run 的全部日志 zip 到目标文件。
   *
   * <p>日志是 zip 归档（每个 job 一个条目），由 {@link RunLogExtractor} 解出可读片段。
   */
  public void downloadRunLogs(long runId, File target) throws Exception {
    String url = API_BASE + "/repos/" + owner + "/" + repo + "/actions/runs/" + runId + "/logs";
    SimpleHttpClient.downloadToFile(url, authHeaders(), CONNECT_TIMEOUT_MS,
        DOWNLOAD_READ_TIMEOUT_MS, target);
  }

  /**
   * 下载 artifact 到目标文件。
   *
   * <p>走 {@code archive_download_url}（不是网页链接），带 Authorization 头；
   * 302 到对象存储时 {@link SimpleHttpClient#downloadToFile} 会自动剥离鉴权头。
   */
  public void downloadArtifact(WorkflowArtifact artifact, File target) throws Exception {
    if (artifact.archiveDownloadUrl.isEmpty()) {
      throw new GitHubApiException(0, "artifact 没有下载地址（可能已过期）", "");
    }
    SimpleHttpClient.downloadToFile(artifact.archiveDownloadUrl, authHeaders(),
        CONNECT_TIMEOUT_MS, DOWNLOAD_READ_TIMEOUT_MS, target);
  }

  // ---------------------------------------------------------------------------
  // 内部
  // ---------------------------------------------------------------------------

  private Map<String, String> authHeaders() {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("Accept", "application/vnd.github+json");
    headers.put("X-GitHub-Api-Version", API_VERSION);
    if (!token.isEmpty()) {
      headers.put("Authorization", "Bearer " + token);
    }
    return headers;
  }

  private SimpleHttpClient.Response execute(String method, String url, String body)
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

  /**
   * 把非 2xx 响应转成带状态码的 {@link GitHubApiException}。
   *
   * <p>不抛裸 Exception：调用方需要按状态码分流处置（401/403 引导配 token、
   * 404 提示 workflow 未推送）。
   */
  private void checkSuccess(SimpleHttpClient.Response response) throws GitHubApiException {
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

  private static String encodePathSegment(String value) {
    try {
      // URLEncoder 是 form 编码（空格变 +），路径段里不出现空格，够用；
      // 这里对常见特殊字符做最小转义。
      return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
    } catch (java.io.UnsupportedEncodingException e) {
      return value;
    }
  }

  private static String encodeQueryParam(String value) {
    try {
      return URLEncoder.encode(value, "UTF-8");
    } catch (java.io.UnsupportedEncodingException e) {
      return value;
    }
  }

  private static String join(List<String> parts, String separator) {
    StringBuilder builder = new StringBuilder();
    for (String part : parts) {
      if (builder.length() > 0) {
        builder.append(separator);
      }
      builder.append(part);
    }
    return builder.toString();
  }
}
