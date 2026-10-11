/*
 * This file is part of AndroidCodeStudio.
 *
 * This program is free software: you can implement it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY DIRECT WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.ai.tool.mcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/**
 * MCP 的 **stdio 传输**：经 {@link ProcessBuilder} 起子进程（Termux 环境），在
 * stdin/stdout 上收发 newline-delimited JSON-RPC。
 *
 * <p><b>分帧语义照 cc-haha（MCP SDK shared/stdio.js）</b>：收端按 {@code \n} 切行
 * （{@code BufferedReader.readLine} 天然 strip 行尾 {@code \r}，CRLF 兼容），
 * 发端 {@code JSON + '\n'}。server 主动请求/通知（如 {@code notifications/*}）直接丢弃
 * ——本客户端只发起请求，server 的主动调用没有应答路径。
 *
 * <p><b>stderr 必须 drain</b>：子进程往 stderr 写而无人读时，管道缓冲区（约 64KB）
 * 写满即把进程卡死——这是 stdio server 最常见的死法。另起守护线程持续丢弃
 * （累计 64MB 上限防内存膨胀，cc-haha 同语义）。
 *
 * <p><b>关闭阶梯</b>（cc-haha SIGINT→SIGTERM→SIGKILL 的 Java 对应）：先写 stdin EOF
 * 让协作式 server 自行退出 → {@code destroy()} → {@code destroyForcibly()}，
 * 每步 {@code isAlive()} 探测。Java 的 {@code destroy()} 在部分平台即强杀，
 * 所以 EOF 这步不能省。
 *
 * <p>本类不依赖 Android，可在 JVM 上单测（用脚本模拟 server）。
 */
final class StdioTransport implements McpTransport {

  /** 等待一条响应的上限。工具执行可能很慢，与 SSE 传输同宽。 */
  private static final long RESPONSE_TIMEOUT_MS = 120_000;

  /** open() 等待进程起 + 首条响应窗口的握手余量。 */
  private static final long STARTUP_TIMEOUT_MS = 30_000;

  /** stderr 累计保留量上限，超过即丢弃后续（防内存膨胀）。 */
  private static final long STDERR_KEEP_LIMIT = 64 * 1024 * 1024L;

  /** 等待进程退出的阶梯间隔（ms）。 */
  private static final long TERM_STEP_MS = 150;
  private static final long KILL_STEP_MS = 400;

  private final String command;
  private final java.util.List<String> args;
  private final Map<String, String> env;
  private final String cwd;

  private final AtomicBoolean closed = new AtomicBoolean(false);
  private final Object connectLock = new Object();

  private Process process;
  private OutputStream stdin;
  private BufferedReader stdout;
  private Thread readerThread;
  private Thread stderrThread;
  /** stderr 尾部（诊断用），环形丢弃。 */
  private volatile String lastStderr = "";

  /** 请求 id → 等待中的响应信箱。读线程按响应里的 id 配对唤醒。 */
  private final ConcurrentHashMap<String, ResponseBox> pending = new ConcurrentHashMap<>();

  StdioTransport(
      String command,
      java.util.List<String> args,
      Map<String, String> env,
      String cwd) {
    this.command = command == null ? "" : command.trim();
    this.args = args == null ? Collections.<String>emptyList() : args;
    this.env = env == null ? Collections.<String, String>emptyMap() : env;
    this.cwd = cwd == null ? "" : cwd.trim();
  }

  @Override
  public void open() throws Exception {
    if (isOpen()) {
      return;
    }
    if (closed.get()) {
      throw new Exception("MCP stdio 连接已关闭。");
    }
    synchronized (connectLock) {
      if (isOpen()) {
        return;
      }
      if (command.isEmpty()) {
        throw new Exception("未配置 MCP stdio server 的启动命令。");
      }

      ProcessBuilder builder = new ProcessBuilder(buildCommandline());
      // shell:false 等价：命令与参数逐个传给 exec，不经 shell 解释，杜绝注入。
      builder.redirectErrorStream(false);
      if (!cwd.isEmpty()) {
        builder.directory(new java.io.File(cwd));
      }
      // 继承父环境再叠加用户 env（用户优先）。Termux 下 PATH 已含 node/python。
      Map<String, String> environment = builder.environment();
      environment.putAll(env);

      try {
        process = builder.start();
      } catch (IOException e) {
        throw new Exception(
            "无法启动 MCP server（" + command + "）：" + e.getMessage()
                + "。请确认命令存在于 Termux PATH 中。");
      }
      stdin = process.getOutputStream();
      stdout =
          new BufferedReader(
              new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));

      // stderr drain 守护线程：不读会把进程卡死，见类注释。
      stderrThread =
          new Thread(this::drainStderr, "mcp-stdio-stderr");
      stderrThread.setDaemon(true);
      stderrThread.start();

      readerThread = new Thread(this::readLoop, "mcp-stdio-reader");
      readerThread.setDaemon(true);
      readerThread.start();

      // 进程秒退（命令不存在/立即崩溃）在这里就暴露：wait 启动窗口，
      // 退出即报错并带上 stderr 尾巴，而不是让首个 send 干等到超时。
      long deadline = System.currentTimeMillis() + STARTUP_TIMEOUT_MS;
      while (System.currentTimeMillis() < deadline) {
        if (!process.isAlive()) {
          throw new Exception(
              "MCP server 启动后立即退出（exit="
                  + process.exitValue()
                  + "）。stderr: "
                  + abbreviate(lastStderr, 400));
        }
        if (readerThread.isAlive()) {
          return;
        }
        Thread.sleep(50);
      }
    }
    // 超时路径：进程活着但迟迟无输出，交给 send 的超时处理。
  }

  private java.util.List<String> buildCommandline() {
    java.util.List<String> argv = new java.util.ArrayList<>();
    argv.add(command);
    for (String arg : args) {
      if (arg != null && !arg.trim().isEmpty()) {
        argv.add(arg);
      }
    }
    return argv;
  }

  @Override
  public String send(String jsonRpcBody) throws Exception {
    open();

    String id = extractId(jsonRpcBody);
    if (id.isEmpty()) {
      // 无 id 的报文是通知，走 notify；否则会永远等不到响应。
      notify(jsonRpcBody);
      return "";
    }

    ResponseBox box = new ResponseBox();
    pending.put(id, box);
    try {
      writeLine(jsonRpcBody);
    } catch (Exception e) {
      pending.remove(id);
      throw e;
    }

    if (!box.await(RESPONSE_TIMEOUT_MS)) {
      pending.remove(id);
      String reason = lastStderr;
      throw new Exception(
          "MCP stdio 等待响应超时（id="
              + id
              + "）"
              + (reason.isEmpty() ? "" : "，stderr: " + abbreviate(reason, 300)));
    }
    return box.body;
  }

  @Override
  public void notify(String jsonRpcBody) throws Exception {
    open();
    writeLine(jsonRpcBody);
  }

  @Override
  public String getSessionId() {
    // stdio 无会话 id 语义（进程本身即会话），恒空——与 StreamableHttpTransport
    // 的「无状态 server 不下发会话 id」路径行为一致。
    return "";
  }

  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    failPending("MCP stdio 连接已关闭。");
    // 优雅关闭阶梯：EOF → destroy → destroyForcibly。
    try {
      if (stdin != null) {
        stdin.close(); // EOF：协作式 server 自行退出。
      }
    } catch (IOException ignored) {
      // 管道可能已断，无补救。
    }
    Process proc = process;
    if (proc == null) {
      return;
    }
    awaitExit(proc, TERM_STEP_MS);
    if (proc.isAlive()) {
      proc.destroy();
      awaitExit(proc, TERM_STEP_MS);
    }
    if (proc.isAlive()) {
      proc.destroyForcibly();
      awaitExit(proc, KILL_STEP_MS);
    }
  }

  private static void awaitExit(Process proc, long stepMs) {
    try {
      proc.waitFor(stepMs, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  // ─── 读线程 / stderr 线程 ───────────────────────────────────────────────────

  private void readLoop() {
    try {
      String line;
      while ((line = stdout.readLine()) != null) {
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
          continue;
        }
        JSONObject message;
        try {
          message = new JSONObject(trimmed);
        } catch (org.json.JSONException e) {
          // 非 JSON 行（启动横幅等）丢弃，不打断读循环。
          continue;
        }
        dispatch(message);
      }
    } catch (IOException ignored) {
      // 进程退出/流关闭：走 fail 路径。
    }
    failPending(lastStderr.isEmpty() ? "MCP server 进程已退出。" : "MCP server 进程已退出：" + abbreviate(lastStderr, 300));
  }

  private void dispatch(JSONObject message) {
    String id = message.has("id") ? String.valueOf(message.opt("id")) : "";
    if (id.isEmpty()) {
      // server 主动推送的通知/请求（logging、progress 等）没有等待者，丢弃。
      return;
    }
    ResponseBox box = pending.remove(id);
    if (box == null) {
      return;
    }
    box.complete(message.toString());
  }

  private void drainStderr() {
    try (BufferedReader err =
        new BufferedReader(
            new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
      StringBuilder keep = new StringBuilder();
      String line;
      while ((line = err.readLine()) != null) {
        if (keep.length() < STDERR_KEEP_LIMIT) {
          keep.append(line).append('\n');
          lastStderr = keep.toString();
        }
      }
    } catch (IOException ignored) {
      // 流关闭即结束。
    }
  }

  // ─── 内部工具 ───────────────────────────────────────────────────────────────

  private boolean isOpen() {
    return process != null && process.isAlive() && stdout != null;
  }

  private void writeLine(String jsonRpcBody) throws IOException {
    if (!isOpen()) {
      throw new IOException("MCP stdio 进程未在运行。");
    }
    synchronized (this) {
      stdin.write((jsonRpcBody + "\n").getBytes(StandardCharsets.UTF_8));
      stdin.flush();
    }
    // 写失败（管道断）时抛 IOException，send 的 catch 会清理信箱。
  }

  private void failPending(String reason) {
    for (Map.Entry<String, ResponseBox> entry : pending.entrySet()) {
      ResponseBox box = pending.remove(entry.getKey());
      if (box != null) {
        box.fail(reason);
      }
    }
  }

  private static String extractId(String jsonRpcBody) {
    if (jsonRpcBody == null) {
      return "";
    }
    try {
      JSONObject json = new JSONObject(jsonRpcBody);
      if (json.has("id") && !json.isNull("id")) {
        return String.valueOf(json.get("id"));
      }
    } catch (org.json.JSONException ignored) {
      // 调用方保证是合法 JSON。
    }
    return "";
  }

  private static String abbreviate(String value, int max) {
    if (value == null || value.length() <= max) {
      return value == null ? "" : value;
    }
    return value.substring(value.length() - max);
  }

  /** 一次请求的投递信箱：读线程与发起线程之间的交接点。 */
  private static final class ResponseBox {
    private final java.util.concurrent.CountDownLatch latch =
        new java.util.concurrent.CountDownLatch(1);
    private volatile String body = "";
    private volatile String failure = "";

    void complete(String responseBody) {
      this.body = responseBody;
      latch.countDown();
    }

    void fail(String reason) {
      this.failure = reason;
      latch.countDown();
    }

    boolean await(long timeoutMs) throws InterruptedException {
      return latch.await(timeoutMs, TimeUnit.MILLISECONDS);
    }
  }
}
