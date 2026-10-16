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
