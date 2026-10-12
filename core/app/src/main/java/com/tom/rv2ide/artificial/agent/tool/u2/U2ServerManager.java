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

package com.tom.rv2ide.artificial.agent.tool.u2;

import android.content.Context;
import com.tom.rv2ide.ai.tool.ShellBackend;
import com.tom.rv2ide.ai.tool.ShellBackendRegistry;
import com.tom.rv2ide.ai.tool.ShellRequest;
import com.tom.rv2ide.ai.tool.U2ProxyPort;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * uiautomator2 server 的生命周期管理 + JSON-RPC 客户端。
 *
 * <p>生命周期：提取 jar（{@link U2JarInstaller}）→ 经 Shizuku 启动
 * （{@code CLASSPATH=<ext>/u2/u2.jar nohup app_process / com.wetest.uia2.Main -p 9008}）
 * → 轮询 {@code GET /ping} 直到 {@code pong} → 之后所有调用走
 * {@code POST http://127.0.0.1:9008/jsonrpc/0}。
 *
 * <p><b>为什么固定 Shizuku 后端</b>：u2 server 要注册 UiAutomation，需要
 * {@code RETRIEVE_WINDOW_CONTENT} 权限——实测 app uid（含 run-as）抛
 * {@code SecurityException}，Termux 路径不可行；只有 Shizuku（adb 级权限）能跑。
 * 因此这里按 id 取 Shizuku 后端，**无视用户的 active 后端选择**——active=Termux
 * 时按 active 解析会拿到没有 adb 权限的后端，命令必然失败。
 *
 * <p><b>为什么 server 默认常驻</b>：冷启动虽快（实测 &lt;1s），但每次调用重启会
 * 丢掉 u2 的会话状态；且重启窗口内 UiAutomation 反复注册/释放。默认常驻，
 * 用户在设置页可开「用完即停」（{@code ai_agent_u2_stop_after_use}）。
 *
 * <p><b>线程模型</b>：全部公开方法 synchronized（工具串行执行，但 HTTP 层可能被
 * 并发测试代码调用）；HTTP 读超时按 250ms 分片轮询取消信号。
 */
public final class U2ServerManager {

  private static final Logger log = LoggerFactory.getLogger(U2ServerManager.class);

  /** u2 server 的固定端口。 */
  public static final int PORT = 9008;

  private static final String SHIZUKU_BACKEND_ID = "shizuku";

  /** server 启动到就绪的上限（实测 <1s，15s 非常宽裕）。 */
  private static final long START_TIMEOUT_MS = 15_000L;
  /** 启动/停止命令本身的超时（nohup 立即返回）。 */
  private static final long LAUNCH_CMD_TIMEOUT_MS = 10_000L;
  /** ping 的超时（连接 + 读取）。 */
  private static final long PING_TIMEOUT_MS = 2_000L;
  /** 停止后确认 ping 不再响应的上限。 */
  private static final long STOP_TIMEOUT_MS = 10_000L;
  /** 就绪轮询间隔。 */
  private static final long POLL_INTERVAL_MS = 250L;
  /** 响应体读取上限（dumpWindowHierarchy 实测 54KB；给 8MB 裕量）。 */
  private static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;
  /** 日志尾部读取行数（启动失败诊断）。 */
  private static final int LOG_TAIL_LINES = 30;

  private static final String TMP_DIR = "/data/local/tmp/u2";
  private static final String LOG_PATH = TMP_DIR + "/server.log";
  private static final String PID_PATH = TMP_DIR + "/server.pid";

  /**
   * HTTP 工作线程池。串行执行（u2 server 单实例），1 个线程足够；
   * 控制线程在主线程分片轮询取消与总预算。
   */
  private static final java.util.concurrent.ExecutorService HTTP_EXECUTOR =
      java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "u2-http");
        thread.setDaemon(true);
        return thread;
      });

  public enum State {
    IDLE,
    RUNNING,
    FAILED
  }

  private final Context appContext;
  private final ShellBackendRegistry registry;
  private volatile State state = State.IDLE;
  /** 最近一次失败的原因（state=FAILED 时非空）。 */
  private volatile String lastError = "";
  /**
   * 用完即停偏好提供者（每次调用时现查，改设置立即生效）。
   * 为 null 时视为 false（常驻）。
   */
  private volatile java.util.function.BooleanSupplier stopAfterUseProvider;

  public U2ServerManager(Context context, ShellBackendRegistry registry) {
    this.appContext = context.getApplicationContext();
    this.registry = registry;
  }

  /** 注入「用完即停」偏好读取器（app 层接 AgentToolSettings）。 */
  public void setStopAfterUseProvider(java.util.function.BooleanSupplier provider) {
    this.stopAfterUseProvider = provider;
  }

  public State state() {
    return state;
  }

  // ---------------------------------------------------------------------
  // 生命周期
  // ---------------------------------------------------------------------

  /**
   * 确保 server 运行；已运行（ping 通）则直接返回。
   *
   * @return null=成功；非 null=失败原因
   */
  public synchronized String ensureRunning() {
    if (ping()) {
      state = State.RUNNING;
      return null;
    }
    // 孤儿对账：pidfile 存在但 9008 未监听 → 清掉陈旧 pidfile（server 已死）。
    // 9008 在监听但 ping 不通的情况前面已排除；ping 通即复用（可能是外部 server）。
    cleanupStalePidFile();

    String installError = installJar();
    if (installError != null) {
      state = State.FAILED;
      lastError = installError;
      return installError;
    }
    String launchError = launchServer();
    if (launchError != null) {
      state = State.FAILED;
      lastError = launchError;
      return launchError;
    }
    if (awaitReady()) {
      state = State.RUNNING;
      lastError = "";
      return null;
    }
    String tail = readLogTail();
    String message = "u2 server 启动后 15s 内未就绪（ping 无响应）。";
    if (tail.contains("BindException")) {
      message += "\n端口 " + PORT + " 已被占用（可能是主机侧 uiautomator2 正在运行）。"
          + "请停掉主机侧的 u2 会话后重试。";
    }
    if (!tail.isEmpty()) {
      message += "\nserver.log 尾部：\n" + tail;
    }
    state = State.FAILED;
    lastError = message;
    return message;
  }

  /** 停掉 server（幂等）。 */
  public synchronized void stop() {
    stopServer();
    state = State.IDLE;
    lastError = "";
  }

  /** 重启 server（stop + ensureRunning）。 */
  public synchronized String restart() {
    stopServer();
    state = State.IDLE;
    return ensureRunning();
  }

  /** ping：GET /ping，期望 body == "pong"。 */
  public synchronized boolean ping() {
    HttpURLConnection conn = null;
    try {
      URL url = new URL("http://127.0.0.1:" + PORT + "/ping");
      conn = (HttpURLConnection) url.openConnection();
      conn.setConnectTimeout((int) PING_TIMEOUT_MS);
      conn.setReadTimeout((int) PING_TIMEOUT_MS);
      conn.setRequestMethod("GET");
      int code = conn.getResponseCode();
      if (code != 200) {
        return false;
      }
      String body = readAll(conn.getInputStream(), 4096);
      return body != null && body.trim().equals("pong");
    } catch (Exception e) {
      return false;
    } finally {
      if (conn != null) {
        conn.disconnect();
      }
    }
  }

  // ---------------------------------------------------------------------
  // jsonrpc
  // ---------------------------------------------------------------------

  /**
   * 一次 jsonrpc 调用（{@link U2ProxyPort#call} 的实现体）。
   *
   * <p>语义：先 ensureRunning（失败带原因返回）；POST；解析 result/error；
   * 连接拒绝（server 已死）时重启并重试一次；读超时**不重试**（实测挂起的
   * 调用重试只会再挂一次），返回错误并建议 restart。
   */
  public synchronized U2ProxyPort.Result call(
      String method, String paramsJson, long timeoutMs,
      java.util.function.BooleanSupplier cancelled) {
    U2ProxyPort.Result result = callInternal(method, paramsJson, timeoutMs, cancelled);
    java.util.function.BooleanSupplier provider = stopAfterUseProvider;
    if (provider != null && provider.getAsBoolean()) {
      // 用完即停偏好（默认关）：u2 server 监听全部网卡且无鉴权，
      // 不可信网络下用户可开此开关把暴露窗口缩到调用期间。
      stopServer();
      state = State.IDLE;
    }
    return result;
  }

  private U2ProxyPort.Result callInternal(
      String method, String paramsJson, long timeoutMs,
      java.util.function.BooleanSupplier cancelled) {
    String ensureError = ensureRunning();
    if (ensureError != null) {
      return U2ProxyPort.Result.fail(ensureError, false, false);
    }
    U2ProxyPort.Result first = httpCall(method, paramsJson, timeoutMs, cancelled, false);
    if (first.isOk() || first.isTimedOut()) {
      return first;
    }
    // 非超时的失败 = 连接拒绝/协议错误 → server 可能已死，重启后重试一次。
    if (cancelled != null && cancelled.getAsBoolean()) {
      return first; // 已取消的调用不触发重启重试
    }
    String restartError = restart();
    if (restartError != null) {
      return U2ProxyPort.Result.fail(
          "server 已断开且重启失败：" + restartError + "\n（原错误：" + first.getError() + "）",
          false, true);
    }
    U2ProxyPort.Result second = httpCall(method, paramsJson, timeoutMs, cancelled, true);
    return second;
  }

  /**
   * POST http://127.0.0.1:9008/jsonrpc/0。
   *
   * <p><b>为什么用工作线程 + 分片轮询</b>：u2 的慢操作（scrollTo/waitUntilGone）在
   * 完成前不响应任何 HTTP 字节，{@code getResponseCode} 会一直阻塞。要同时满足
   * 「等完整操作」与「取消能立即打断」，唯一可靠的模式是：阻塞调用放 worker 线程，
   * 控制线程每 250ms 检查取消与总预算，命中时 {@code disconnect()}（跨线程安全，
   * 会打断 worker 的阻塞读）。socket 读超时不可用于此——它会在慢操作期间误报超时。
   *
   * @param restarted 本次调用前是否重启过（透传给 Result）
   */
  private U2ProxyPort.Result httpCall(
      String method, String paramsJson, long timeoutMs,
      java.util.function.BooleanSupplier cancelled, boolean restarted) {
    String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\",\"params\":"
        + (paramsJson == null || paramsJson.trim().isEmpty() ? "[]" : paramsJson) + "}";
    final java.util.concurrent.atomic.AtomicReference<HttpURLConnection> connRef =
        new java.util.concurrent.atomic.AtomicReference<>();
    java.util.concurrent.Future<String> future = HTTP_EXECUTOR.submit(() -> {
      HttpURLConnection conn = null;
      try {
        URL url = new URL("http://127.0.0.1:" + PORT + "/jsonrpc/0");
        conn = (HttpURLConnection) url.openConnection();
        connRef.set(conn);
        conn.setConnectTimeout(2_000);
        conn.setReadTimeout(0); // 无限：超时由控制线程按总预算裁决
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json");
        // 照 u2 客户端：Accept-Encoding 置空避免 gzip。
        conn.setRequestProperty("Accept-Encoding", "");
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        conn.setFixedLengthStreamingMode(payload.length);
        try (OutputStream out = conn.getOutputStream()) {
          out.write(payload);
          out.flush();
        }
        int code = conn.getResponseCode();
        InputStream in = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        String response = readAll(in, MAX_RESPONSE_BYTES);
        if (code != 200) {
          throw new IOException("HTTP " + code + "：" + abbreviate(response));
        }
        return response;
      } finally {
        if (conn != null) {
          conn.disconnect();
        }
      }
    });

    long deadline = System.currentTimeMillis() + timeoutMs;
    while (true) {
      try {
        String response = future.get(POLL_INTERVAL_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
        return parseJsonRpc(response, restarted);
      } catch (java.util.concurrent.TimeoutException slice) {
        if (cancelled != null && cancelled.getAsBoolean()) {
          abort(future, connRef);
          return U2ProxyPort.Result.fail("已取消", false, restarted);
        }
        if (System.currentTimeMillis() > deadline) {
          abort(future, connRef);
          return U2ProxyPort.Result.fail(
              "读取超时（" + timeoutMs + "ms）。server 可能已卡死。", true, restarted);
        }
      } catch (java.util.concurrent.ExecutionException e) {
        Throwable cause = e.getCause() == null ? e : e.getCause();
        if (cause instanceof java.net.ConnectException) {
          // 连接拒绝：server 已死——交由调用方重启重试（call() 里处理）。
          return U2ProxyPort.Result.fail("连接失败: " + cause.getMessage(), false, restarted);
        }
        return U2ProxyPort.Result.fail(
            "调用异常: " + cause.getClass().getSimpleName() + ": " + cause.getMessage(),
            false, restarted);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        abort(future, connRef);
        return U2ProxyPort.Result.fail("已中断", false, restarted);
      }
    }
  }

  /** 打断 worker：disconnect 会令其阻塞读抛 IOException 而结束；再取消 Future 收尾。 */
  private static void abort(
      java.util.concurrent.Future<String> future,
      java.util.concurrent.atomic.AtomicReference<HttpURLConnection> connRef) {
    HttpURLConnection conn = connRef.get();
    if (conn != null) {
      conn.disconnect();
    }
    future.cancel(true);
  }

  /** 解析 {"result":...} / {"error":{...}}。 */
  private static U2ProxyPort.Result parseJsonRpc(String response, boolean restarted) {
    try {
      JSONObject obj = new JSONObject(response.trim());
      if (obj.has("error") && !obj.isNull("error")) {
        JSONObject error = obj.optJSONObject("error");
        if (error == null) {
          return U2ProxyPort.Result.fail("JSON-RPC error：" + obj.get("error"), false, restarted);
        }
        StringBuilder sb = new StringBuilder();
        sb.append(error.optString("message", "未知错误"));
        if (error.has("code")) {
          sb.append("（code=").append(error.optInt("code")).append("）");
        }
        if (error.has("data") && !error.isNull("data")) {
          sb.append("\n").append(error.get("data"));
        }
        return U2ProxyPort.Result.fail(sb.toString(), false, restarted);
      }
      if (obj.has("result")) {
        Object result = obj.get("result");
        return U2ProxyPort.Result.ok(String.valueOf(result), restarted);
      }
      return U2ProxyPort.Result.fail("响应里既无 result 也无 error：" + abbreviate(response),
          false, restarted);
    } catch (org.json.JSONException e) {
      return U2ProxyPort.Result.fail(
          "响应不是合法 JSON：" + abbreviate(response), false, restarted);
    }
  }

  private static String readAll(InputStream in, int maxBytes) {
    if (in == null) {
      return "";
    }
    try {
      ByteArrayOutputStream bos = new ByteArrayOutputStream();
      byte[] buffer = new byte[4096];
      int read;
      int total = 0;
      while ((read = in.read(buffer)) != -1 && total < maxBytes) {
        bos.write(buffer, 0, read);
        total += read;
      }
      return bos.toString(StandardCharsets.UTF_8.name());
    } catch (IOException e) {
      return "";
    } finally {
      try {
        in.close();
      } catch (IOException ignored) {
        // 忽略
      }
    }
  }

  // ---------------------------------------------------------------------
  // shell 侧（启动/停止/探活）
  // ---------------------------------------------------------------------

  /** 提取 jar；返回 null=成功，非 null=失败原因。 */
  private String installJar() {
    File jar = U2JarInstaller.install(appContext);
    if (jar == null) {
      return "u2.jar 提取失败：" + U2JarInstaller.lastError();
    }
    return null;
  }

  /** 经 Shizuku 启动 server；返回 null=成功。 */
  private String launchServer() {
    ShellBackend shizuku = registry == null ? null : registry.get(SHIZUKU_BACKEND_ID);
    if (shizuku == null || !shizuku.isAvailable()) {
      String reason = shizuku == null
          ? "未配置 Shizuku 后端。"
          : shizuku.unavailableReason();
      return "u2 server 需要 Shizuku（adb 级权限）才能启动：" + reason
          + "\n（u2 server 要注册 UiAutomation，普通应用 uid 会抛 SecurityException；"
          + "请在设备上安装并授权 Shizuku 后重试。）";
    }
    File base = appContext.getExternalFilesDir(null);
    if (base == null) {
      return "外部存储不可用（getExternalFilesDir 为 null），无法定位 u2.jar。";
    }
    String jarPath = new File(new File(base, "u2"), "u2.jar").getAbsolutePath();
    // 启动命令照计划 §5.3 原文：CLASSPATH 指向提取出的 jar，nohup 后台运行，
    // 日志与 pid 落 /data/local/tmp/u2/（shell 可写）。
    String command = "mkdir -p " + TMP_DIR + "\n"
        + "CLASSPATH=" + jarPath + " nohup app_process / com.wetest.uia2.Main -p " + PORT
        + " >" + LOG_PATH + " 2>&1 &\n"
        + "echo $! > " + PID_PATH;
    ShellRequest.ShellResult result = execShell(shizuku, command, LAUNCH_CMD_TIMEOUT_MS);
    if (result == null) {
      return "Shizuku 执行启动命令失败（后端异常）。";
    }
    if (result.isTimedOut()) {
      return "启动命令超时（" + LAUNCH_CMD_TIMEOUT_MS + "ms）。";
    }
    // 注意：nohup ... & 命令本身 rc 可能非 0（后台任务），真正的判据是 awaitReady。
    // 这里只记录 stderr 供诊断，不因 rc 直接失败。
    if (result.getExitCode() != 0 && !result.getStderr().trim().isEmpty()) {
      log.debug("u2 启动命令 rc={}，stderr={}", result.getExitCode(), result.getStderr());
    }
    return null;
  }

  /** 轮询 ping 直到就绪。 */
  private boolean awaitReady() {
    long deadline = System.currentTimeMillis() + START_TIMEOUT_MS;
    while (System.currentTimeMillis() < deadline) {
      if (ping()) {
        return true;
      }
      try {
        Thread.sleep(POLL_INTERVAL_MS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
    return false;
  }

  /** bracket pkill 停 server（幂等）。 */
  private void stopServer() {
    ShellBackend shizuku = registry == null ? null : registry.get(SHIZUKU_BACKEND_ID);
    if (shizuku == null || !shizuku.isAvailable()) {
      // 没有 Shizuku 时也尽力尝试：server 可能本来就没在跑。
      return;
    }
    // bracket 技巧（实测）：pkill -f 'com.wetest.uia2.Main' 会命中执行它的 shell
    // 自身（pattern 在命令行里），[c]om... 让 pattern 不匹配自己的命令行。
    execShell(shizuku, "pkill -f '[c]om.wetest.uia2.Main'", 10_000L);
    // 等待 ping 不再响应。
    long deadline = System.currentTimeMillis() + STOP_TIMEOUT_MS;
    while (System.currentTimeMillis() < deadline) {
      if (!ping()) {
        break;
      }
      try {
        Thread.sleep(POLL_INTERVAL_MS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    // -9 兜底（还在响应说明 SIGTERM 没杀掉）。
    if (ping()) {
      execShell(shizuku, "pkill -9 -f '[c]om.wetest.uia2.Main'", 10_000L);
    }
    // 清 pidfile（对账用，防陈旧）。
    execShell(shizuku, "rm -f " + PID_PATH, 5_000L);
  }

  /** 启动前对账：pidfile 存在但 9008 未监听 → 清理陈旧 pidfile。 */
  private void cleanupStalePidFile() {
    ShellBackend shizuku = registry == null ? null : registry.get(SHIZUKU_BACKEND_ID);
    if (shizuku == null || !shizuku.isAvailable()) {
      return;
    }
    execShell(shizuku, "rm -f " + PID_PATH, 5_000L);
  }

  /** 读 server.log 尾部（启动失败诊断）。 */
  private String readLogTail() {
    ShellBackend shizuku = registry == null ? null : registry.get(SHIZUKU_BACKEND_ID);
    if (shizuku == null || !shizuku.isAvailable()) {
      return "";
    }
    ShellRequest.ShellResult result =
        execShell(shizuku, "tail -n " + LOG_TAIL_LINES + " " + LOG_PATH + " 2>/dev/null", 5_000L);
    if (result == null || !result.isSuccess()) {
      return "";
    }
    return result.getStdout().trim();
  }

  /** 执行一条 shell 命令；后端异常返回 null。 */
  private static ShellRequest.ShellResult execShell(
      ShellBackend backend, String command, long timeoutMs) {
    try {
      return backend.execute(
          new ShellRequest(command, "", timeoutMs, null), ShellRequest.ShellOutputSink.NOOP);
    } catch (RuntimeException e) {
      log.warn("u2 shell 命令执行失败: {}", command, e);
      return null;
    }
  }

  // ---------------------------------------------------------------------
  // 供端口实现查询
  // ---------------------------------------------------------------------

  /** Shizuku 可用 + jar 可提取。 */
  public boolean isAvailable() {
    ShellBackend shizuku = registry == null ? null : registry.get(SHIZUKU_BACKEND_ID);
    if (shizuku == null || !shizuku.isAvailable()) {
      return false;
    }
    return appContext.getExternalFilesDir(null) != null;
  }

  /** 不可用原因；可用时为空串。 */
  public String unavailableReason() {
    ShellBackend shizuku = registry == null ? null : registry.get(SHIZUKU_BACKEND_ID);
    if (shizuku == null) {
      return "未配置 Shizuku 后端。";
    }
    if (!shizuku.isAvailable()) {
      return shizuku.unavailableReason();
    }
    if (appContext.getExternalFilesDir(null) == null) {
      return "外部存储不可用（getExternalFilesDir 为 null）。";
    }
    return "";
  }

  private static String abbreviate(String value) {
    if (value == null) {
      return "";
    }
    String v = value.trim();
    return v.length() <= 300 ? v : v.substring(0, 300) + "…";
  }
}
