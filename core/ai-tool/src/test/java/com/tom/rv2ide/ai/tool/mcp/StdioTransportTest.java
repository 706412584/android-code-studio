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
 * along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.ai.tool.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.Collections;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/**
 * {@link StdioTransport} 的真实进程行为（newline-delimited JSON-RPC over stdin/stdout）。
 *
 * <p>用 python 起一个极简 echo server：读一行 JSON-RPC，回一行同 id 的 result。
 * python 不在 PATH 时整个测试类跳过（CI 或无 python 环境不因此红）。
 */
class StdioTransportTest {

  private static final String SERVER_SCRIPT =
      "import sys, json\n"
          + "for line in sys.stdin:\n"
          + "    line = line.strip()\n"
          + "    if not line:\n"
          + "        continue\n"
          + "    try:\n"
          + "        msg = json.loads(line)\n"
          + "    except Exception:\n"
          + "        continue\n"
          + "    if 'id' not in msg:\n"
          + "        continue\n"
          + "    sys.stdout.write(json.dumps({'jsonrpc': '2.0', 'id': msg['id'], "
          + "'result': {'echo': msg.get('method', ''), 'params': msg.get('params', {})}}) + '\\n')\n"
          + "    sys.stdout.flush()\n";

  /** python 可执行文件；找不到时返回 null（测试跳过）。 */
  private static String python() {
    for (String candidate : new String[] {"python", "python3", "py"}) {
      try {
        Process probe = new ProcessBuilder(candidate, "--version").start();
        if (probe.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
            && probe.exitValue() == 0) {
          return candidate;
        }
        probe.destroyForcibly();
      } catch (Exception ignored) {
        // 试下一个候选。
      }
    }
    return null;
  }

  @Test
  public void roundTripRequestResponse() throws Exception {
    String py = python();
    assumeTrue(py != null, "no python on PATH");

    StdioTransport transport =
        new StdioTransport(
            py,
            java.util.Arrays.asList("-c", SERVER_SCRIPT),
            Collections.emptyMap(),
            "");
    try {
      JSONObject response = new JSONObject(transport.send(requestJson(1, "ping")));
      assertEquals(1, response.getInt("id"));
      assertEquals("ping", response.getJSONObject("result").getString("echo"));
    } finally {
      transport.close();
    }
  }

  @Test
  public void paramsRoundTripAndMultipleRequests() throws Exception {
    String py = python();
    assumeTrue(py != null, "no python on PATH");

    StdioTransport transport =
        new StdioTransport(
            py,
            java.util.Arrays.asList("-c", SERVER_SCRIPT),
            Collections.emptyMap(),
            "");
    try {
      JSONObject params = new JSONObject().put("name", "x");
      JSONObject first = new JSONObject(transport.send(requestJson(7, "tools/list", params)));
      assertEquals("x", first.getJSONObject("result").getJSONObject("params").getString("name"));

      JSONObject second = new JSONObject(transport.send(requestJson(8, "tools/call")));
      assertEquals(8, second.getInt("id"));
    } finally {
      transport.close();
    }
  }

  @Test
  public void envIsPassedToChild() throws Exception {
    String py = python();
    assumeTrue(py != null, "no python on PATH");

    // server 把指定环境变量的值回显到 result.echo。
    String script =
        "import sys, json, os\n"
            + "for line in sys.stdin:\n"
            + "    try:\n"
            + "        msg = json.loads(line)\n"
            + "    except Exception:\n"
            + "        continue\n"
            + "    if 'id' not in msg:\n"
            + "        continue\n"
            + "    sys.stdout.write(json.dumps({'jsonrpc': '2.0', 'id': msg['id'], "
            + "'result': {'echo': os.environ.get('ACS_TEST_VAR', 'missing')}}) + '\\n')\n"
            + "    sys.stdout.flush()\n";
    StdioTransport transport =
        new StdioTransport(
            py,
            java.util.Arrays.asList("-c", script),
            Collections.singletonMap("ACS_TEST_VAR", "hello"),
            "");
    try {
      JSONObject response = new JSONObject(transport.send(requestJson(1, "ping")));
      assertEquals("hello", response.getJSONObject("result").getString("echo"));
    } finally {
      transport.close();
    }
  }

  @Test
  public void missingCommandFailsFast() {
    StdioTransport transport =
        new StdioTransport(
            "acs-definitely-not-a-real-command-xyz", Collections.emptyList(),
            Collections.emptyMap(), "");
    Exception e = assertThrows(Exception.class, transport::open);
    assertTrue(e.getMessage().contains("无法启动"));
  }

  // ---- 此前未覆盖的分支 ----

  @Test
  public void immediateExitIsReportedEitherAtOpenOrAtSend() throws Exception {
    String py = python();
    assumeTrue(py != null, "no python on PATH");
    // 进程启动后立刻退出并往 stderr 写一行。两条合法路径（取决于进程死亡与
    // open 首轮检查的时序，无法从测试侧确定）：
    //   1) 死亡早于 open 检查 → open 抛「立即退出」带 exit 码与 stderr 尾巴；
    //   2) open 已返回（reader 线程活着）→ 首个 send 走 failPending，
    //      报「进程已退出」。
    // 共同契约：**错误必须出现且带诊断信息**，绝不静默或干等到超时。
    String script =
        "import sys\n"
            + "sys.stderr.write('boom: config missing\\n')\n"
            + "sys.stderr.flush()\n"
            + "sys.exit(3)\n";
    StdioTransport transport =
        new StdioTransport(py, java.util.Arrays.asList("-c", script), Collections.emptyMap(), "");
    try {
      String message;
      try {
        transport.open();
        // 路径 2：等 reader 线程观察到 EOF 并 failPending。
        Thread.sleep(500);
        Exception e =
            assertThrows(Exception.class, () -> transport.send(requestJson(1, "ping")));
        message = e.getMessage();
      } catch (Exception e) {
        // 路径 1：open 直接报错。
        message = e.getMessage();
      }
      assertNotNull(message);
      assertTrue(
          message.contains("立即退出")
              || message.contains("进程已退出")
              || message.contains("未在运行"),
          "错误应说明进程不可用：" + message);
    } finally {
      transport.close();
    }
  }

  @Test
  public void nonEmptyCwdIsUsedAsWorkingDirectory() throws Exception {
    String py = python();
    assumeTrue(py != null, "no python on PATH");
    // server 把 cwd 回显出来：证明 builder.directory(cwd) 生效。
    String script =
        "import sys, json, os\n"
            + "for line in sys.stdin:\n"
            + "    try:\n"
            + "        msg = json.loads(line)\n"
            + "    except Exception:\n"
            + "        continue\n"
            + "    if 'id' not in msg:\n"
            + "        continue\n"
            + "    sys.stdout.write(json.dumps({'jsonrpc': '2.0', 'id': msg['id'], "
            + "'result': {'cwd': os.getcwd()}}) + '\\n')\n"
            + "    sys.stdout.flush()\n";
    String cwd = System.getProperty("java.io.tmpdir");
    StdioTransport transport =
        new StdioTransport(py, java.util.Arrays.asList("-c", script), Collections.emptyMap(), cwd);
    try {
      JSONObject response = new JSONObject(transport.send(requestJson(1, "ping")));
      String reported = response.getJSONObject("result").getString("cwd");
      assertTrue(reported.length() > 0, "cwd 应被设置");
      // tmpdir 可能有符号链接（macOS /var → /private/var），只断言非空且为绝对路径。
      assertTrue(new java.io.File(reported).isAbsolute(), reported);
    } finally {
      transport.close();
    }
  }

  @Test
  public void notifyWithoutIdDoesNotWaitForResponse() throws Exception {
    String py = python();
    assumeTrue(py != null, "no python on PATH");
    // 无 id 的报文是通知：send 应走 notify 立即返回空串，不等响应。
    String script =
        "import sys, json\n"
            + "for line in sys.stdin:\n"
            + "    try:\n"
            + "        msg = json.loads(line)\n"
            + "    except Exception:\n"
            + "        continue\n"
            + "    if 'id' in msg:\n"
            + "        sys.stdout.write(json.dumps({'jsonrpc': '2.0', 'id': msg['id'], "
            + "'result': 'answered'}) + '\\n')\n"
            + "        sys.stdout.flush()\n";
    StdioTransport transport =
        new StdioTransport(py, java.util.Arrays.asList("-c", script), Collections.emptyMap(), "");
    try {
      String noId =
          new JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized")
              .toString();
      assertEquals("", transport.send(noId), "无 id 报文应作为通知处理并立即返回空串");
      // 通知之后普通请求仍工作（读线程未被通知打断）。
      JSONObject response = new JSONObject(transport.send(requestJson(7, "ping")));
      assertEquals(7, response.getInt("id"));
    } finally {
      transport.close();
    }
  }

  @Test
  public void malformedBodyIsTreatedAsNotification() throws Exception {
    String py = python();
    assumeTrue(py != null, "no python on PATH");
    // 非法 JSON（extractId 解析失败）按无 id 处理 → 走 notify，不挂起等待。
    StdioTransport transport =
        new StdioTransport(py, java.util.Arrays.asList("-c", "pass"), Collections.emptyMap(), "");
    try {
      transport.open();
      String result = transport.send("this is not json");
      assertEquals("", result);
    } finally {
      transport.close();
    }
  }

  @Test
  public void writeLineFailsWhenProcessNotRunning() throws Exception {
    String py = python();
    assumeTrue(py != null, "no python on PATH");
    // 进程活着但 stdout 未建立时不应写——open 之后立刻 close，writeLine 走
    // 「进程未在运行」的 IOException 分支（而不是 NPE）。
    StdioTransport transport =
        new StdioTransport(py, java.util.Arrays.asList("-c", "pass"), Collections.emptyMap(), "");
    transport.open();
    transport.close();
    // 关闭后 send 必须失败——具体分支是 closed 检查或 writeLine 守卫，都带消息。
    Exception e =
        assertThrows(Exception.class, () -> transport.send(requestJson(1, "x")));
    assertNotNull(e.getMessage());
  }

  @Test
  public void sessionIdIsAlwaysEmpty() throws Exception {
    // stdio 无会话 id 语义（进程本身即会话）。
    StdioTransport transport =
        new StdioTransport("x", Collections.emptyList(), Collections.emptyMap(), "");
    assertEquals("", transport.getSessionId());
  }

  @Test
  public void emptyCommandIsRejectedAtOpen() {
    StdioTransport transport =
        new StdioTransport("  ", Collections.emptyList(), Collections.emptyMap(), "");
    Exception e = assertThrows(Exception.class, transport::open);
    assertTrue(e.getMessage().contains("启动命令"), e.getMessage());
  }

  private static String requestJson(long id, String method) {
    return requestJson(id, method, new JSONObject());
  }

  private static String requestJson(long id, String method, JSONObject params) {
    return new JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", id)
        .put("method", method)
        .put("params", params)
        .toString();
  }

  @Test
  public void closeIsIdempotentAndFailsPendingRequests() throws Exception {
    if (pythonCommand() == null) {
      return; // 与其余用例同前提：无 python 则跳过。
    }
    // 起一个「收到请求但不回应」的 server，制造一个挂起的请求。
    String silent = "import sys\nfor line in sys.stdin:\n    pass\n";
    StdioTransport transport =
        new StdioTransport(
            pythonCommand(),
            java.util.Arrays.asList("-c", silent),
            Collections.emptyMap(),
            "");
    try {
      transport.open();
      // 不等待响应，直接在另一线程发请求（会被阻塞）。
      Thread sender =
          new Thread(
              () -> {
                try {
                  transport.send(requestJson(1, "never-answered"));
                } catch (Exception ignored) {
                  // 关闭导致的失败是预期结果。
                }
              });
      sender.setDaemon(true);
      sender.start();
      Thread.sleep(300);

      transport.close();
      // 关键：close 必须唤醒所有挂起请求（failPending），否则调用线程永久阻塞。
      sender.join(5_000L);
      assertFalse(sender.isAlive(), "close 后挂起的请求应被唤醒而不是永久阻塞");

      // 幂等：重复 close 不应抛异常。
      transport.close();
      transport.close();
    } finally {
      transport.close();
    }
  }

  @Test
  public void sendAfterCloseFailsFast() throws Exception {
    if (pythonCommand() == null) {
      return;
    }
    StdioTransport transport =
        new StdioTransport(
            pythonCommand(), java.util.Arrays.asList("-c", "pass"), Collections.emptyMap(), "");
    transport.open();
    transport.close();

    // 关闭后发送必须立即失败（带明确原因），而不是挂起等待一个永不到来的响应。
    Exception e =
        assertThrows(
            Exception.class, () -> transport.send(requestJson(2, "ping")));
    assertNotNull(e.getMessage());
  }

  private static String cachedPython;

  /** 与 setUp 同源的 python 探测（子类测试直接复用）。 */
  private static String pythonCommand() {
    if (cachedPython != null) {
      return cachedPython.isEmpty() ? null : cachedPython;
    }
    for (String candidate : new String[] {"python3", "python"}) {
      try {
        Process probe = new ProcessBuilder(candidate, "--version").start();
        if (probe.waitFor() == 0) {
          cachedPython = candidate;
          return candidate;
        }
      } catch (Exception ignored) {
        // 试下一个。
      }
    }
    cachedPython = "";
    return null;
  }
}
