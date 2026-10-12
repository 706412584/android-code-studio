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

package com.tom.rv2ide.ai.tool;

/**
 * uiautomator2 server 代理端口：把设备上 127.0.0.1:9008 的 JSON-RPC 暴露给工具层。
 *
 * <p>为什么是端口：core/ai-tool 是纯 JVM、零 Android 依赖，而「确保 server 已启动」
 * 需要 Shizuku（Android 侧），HTTP 客户端也放在 app 层（见计划 §1.2）。
 * 本接口只用中性类型（String / long / boolean），因此可被 JVM 单测直接实现。
 */
public interface U2ProxyPort {

  /** 一次 jsonrpc 调用的结果。 */
  final class Result {
    private final boolean ok;
    private final String resultJson;
    private final String error;
    private final boolean timedOut;
    private final boolean serverRestarted;

    public Result(boolean ok, String resultJson, String error,
        boolean timedOut, boolean serverRestarted) {
      this.ok = ok;
      this.resultJson = resultJson == null ? "" : resultJson;
      this.error = error == null ? "" : error;
      this.timedOut = timedOut;
      this.serverRestarted = serverRestarted;
    }

    public boolean isOk() { return ok; }
    public String getResultJson() { return resultJson; }
    public String getError() { return error; }
    public boolean isTimedOut() { return timedOut; }
    public boolean wasServerRestarted() { return serverRestarted; }

    public static Result ok(String resultJson, boolean restarted) {
      return new Result(true, resultJson, "", false, restarted);
    }

    public static Result fail(String error, boolean timedOut, boolean restarted) {
      return new Result(false, "", error, timedOut, restarted);
    }
  }

  /**
   * 发起一次 jsonrpc 调用。实现方负责：
   * <ol>
   *   <li>确保 server 已启动（未启动则启动并等待就绪；启动失败返回带原因的 fail）</li>
   *   <li>POST http://127.0.0.1:9008/jsonrpc/0，body {"jsonrpc":"2.0","id":1,"method":m,"params":p}</li>
   *   <li>解析 {"result":...} / {"error":{...}}；网络错误时探活并按需重启后重试一次</li>
   * </ol>
   *
   * @param method jsonrpc 方法名
   * @param paramsJson params 数组的原始 JSON 文本（如 "[{\"mask\":0,...},\"文本\"]"），
   *                   由工具层构造——因为 selector 的字段结构只有工具层知道
   * @param timeoutMs HTTP 超时
   * @param cancelled 取消信号（工具层传 {@code context.getCancellation()::isCancelled}）；
   *                  为 null 视为永不取消。实现方在**等待循环**里按 250ms 分片查询，
   *                  命中时断开连接（{@code HttpURLConnection.disconnect()}）并返回
   *                  fail("已取消")——与 {@code ShellRequest.CancellationSignal} 的
   *                  既有惯例一致。JVM 单测传 null。
   */
  Result call(String method, String paramsJson, long timeoutMs,
      java.util.function.BooleanSupplier cancelled);

  /** 端口是否可用（Shizuku 可用 + jar 可提取 + 启动命令可执行）。 */
  boolean isAvailable();

  /** 不可用原因；可用时为空串。 */
  String unavailableReason();

  /** 停掉 server（幂等；供 `stop` 动作与互斥门解除途径使用）。 */
  void stop();

  /** 重启 server（stop + ensureRunning；供 `restart` 动作与卡死恢复使用）。 */
  void restart();
}
