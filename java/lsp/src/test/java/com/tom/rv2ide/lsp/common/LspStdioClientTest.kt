/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidCodeStudio is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.lsp.common

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LspStdioClient] 的协议行为测试。
 *
 * <p>用管道把客户端的两端接起来：测试线程扮演「服务器」，读请求、回响应。
 * 这能覆盖真实链路里最容易错的几件事——id 匹配、通知分发、超时唤醒、
 * 服务器主动请求的应答。
 */
class LspStdioClientTest {

  /** 从管道读一帧（测试侧扮演服务器）。 */
  private fun readFrame(input: java.io.InputStream): JsonObject {
    val json = LspFraming.readMessage(input) ?: error("EOF")
    return com.google.gson.Gson().fromJson(json, JsonObject::class.java)
  }

  private fun writeFrame(output: java.io.OutputStream, json: String) {
    output.write(LspFraming.encode(json))
    output.flush()
  }

  @Test
  fun request_matchesResponseById() {
    val toClient = PipedInputStream(64 * 1024)
    val clientOutput = PipedOutputStream(toClient)
    val toServer = PipedInputStream(64 * 1024)
    val serverOutput = PipedOutputStream(toServer)

    val client = LspStdioClient(toClient, serverOutput)

    val serverThread =
        Thread {
          val req = readFrame(toServer)
          val id = req.get("id").asInt
          // 先发一个错配 id 的响应（模拟乱序/陈旧响应）：客户端必须忽略它，
          // 否则会把陈旧结果当成本次请求的答案。
          writeFrame(
              clientOutput,
              "{\"jsonrpc\":\"2.0\",\"id\":${id + 999},\"result\":{\"stale\":true}}")
          // 再发正确 id 的响应
          writeFrame(
              clientOutput,
              "{\"jsonrpc\":\"2.0\",\"id\":$id,\"result\":{\"value\":\"ok\"}}")
        }
    serverThread.start()

    val response = client.request("test/echo", JsonObject(), 5000)
    assertNotNull(response)
    assertEquals("ok", response!!.getAsJsonObject("result").get("value").asString)

    client.close()
  }

  @Test
  fun request_returnsNullOnTimeout() {
    val toClient = PipedInputStream(64 * 1024)
    val serverOutput = PipedOutputStream(toClient)
    val toServer = PipedInputStream(64 * 1024)
    val clientOutput = PipedOutputStream(toServer)

    val client = LspStdioClient(toClient, clientOutput)
    // 服务器不回应 → 必须超时返回 null，而不是永久挂起
    val start = System.currentTimeMillis()
    val response = client.request("test/no-reply", JsonObject(), 300)
    val elapsed = System.currentTimeMillis() - start
    assertNull(response)
    assertTrue("应在超时附近返回（实际 ${elapsed}ms）", elapsed >= 250 && elapsed < 3000)
    client.close()
  }

  @Test
  fun request_wakesUpWhenStreamCloses() {
    val toClient = PipedInputStream(64 * 1024)
    val serverOutput = PipedOutputStream(toClient)
    val toServer = PipedInputStream(64 * 1024)
    val clientOutput = PipedOutputStream(toServer)

    val client = LspStdioClient(toClient, clientOutput)
    // 关掉服务器侧输出 → 客户端 reader 收到 EOF → 所有等待者必须被唤醒
    val resultRef = AtomicReference<JsonObject?>()
    val done = CountDownLatch(1)
    Thread {
          resultRef.set(client.request("test/never", JsonObject(), 30_000))
          done.countDown()
        }
        .start()

    Thread.sleep(200)
    serverOutput.close()

    assertTrue("流关闭后请求应在 5 秒内返回", done.await(5, TimeUnit.SECONDS))
    assertNull(resultRef.get())
    client.close()
  }

  @Test
  fun notification_dispatchedToHandler() {
    val toClient = PipedInputStream(64 * 1024)
    val serverOutput = PipedOutputStream(toClient)
    val toServer = PipedInputStream(64 * 1024)
    val clientOutput = PipedOutputStream(toServer)

    val received = AtomicReference<JsonObject>()
    val latch = CountDownLatch(1)
    val client =
        LspStdioClient(
            toClient,
            clientOutput,
            notificationHandler = { obj ->
              received.set(obj)
              latch.countDown()
            })

    writeFrame(
        serverOutput,
        "{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/publishDiagnostics\"," +
            "\"params\":{\"uri\":\"file:///a.ts\",\"diagnostics\":[]}}")

    assertTrue(latch.await(5, TimeUnit.SECONDS))
    assertEquals(
        "textDocument/publishDiagnostics", received.get().get("method").asString)
    client.close()
  }

  @Test
  fun serverRequest_answeredThroughHandler() {
    val toClient = PipedInputStream(64 * 1024)
    val serverOutput = PipedOutputStream(toClient)
    val toServer = PipedInputStream(64 * 1024)
    val clientOutput = PipedOutputStream(toServer)

    val client =
        LspStdioClient(
            toClient,
            clientOutput,
            serverRequestHandler = { method, params ->
              if (method == "workspace/configuration") {
                JsonArray().apply { add(JsonObject()) }
              } else {
                null
              }
            })

    // 服务器发请求：客户端必须回一个带 id 的响应，否则服务器会阻塞
    writeFrame(
        serverOutput,
        "{\"jsonrpc\":\"2.0\",\"id\":77,\"method\":\"workspace/configuration\"," +
            "\"params\":{\"items\":[{\"section\":\"typescript\"}]}}")

    val reply = readFrame(toServer)
    assertEquals(77, reply.get("id").asInt)
    assertTrue(reply.get("result").isJsonArray)
    client.close()
  }

  @Test
  fun serverRequest_unknownMethod_getsErrorReply() {
    val toClient = PipedInputStream(64 * 1024)
    val serverOutput = PipedOutputStream(toClient)
    val toServer = PipedInputStream(64 * 1024)
    val clientOutput = PipedOutputStream(toServer)

    val client = LspStdioClient(toClient, clientOutput)

    writeFrame(
        serverOutput,
        "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"unknown/method\",\"params\":{}}")

    val reply = readFrame(toServer)
    assertEquals(5, reply.get("id").asInt)
    assertTrue(reply.get("error").isJsonObject)
    client.close()
  }

  @Test
  fun notify_sendsWithoutId() {
    val toClient = PipedInputStream(64 * 1024)
    val serverOutput = PipedOutputStream(toClient)
    val toServer = PipedInputStream(64 * 1024)
    val clientOutput = PipedOutputStream(toServer)

    val client = LspStdioClient(toClient, clientOutput)
    client.notify("textDocument/didOpen", JsonObject().apply { addProperty("x", 1) })

    val frame = readFrame(toServer)
    assertFalse("通知不应带 id", frame.has("id"))
    assertEquals("textDocument/didOpen", frame.get("method").asString)
    client.close()
  }

  @Test
  fun errorResponse_reportedThroughHelpers() {
    val toClient = PipedInputStream(64 * 1024)
    val clientOutput = PipedOutputStream(toClient)
    val toServer = PipedInputStream(64 * 1024)
    val serverOutput = PipedOutputStream(toServer)

    // 接线方向：client 的输入是 toClient、输出是 serverOutput（写进 toServer
    // 供测试线程读）；测试线程的响应写进 clientOutput（喂给 client 的输入）。
    val client = LspStdioClient(toClient, serverOutput)
    Thread {
          val req = readFrame(toServer)
          val id = req.get("id").asInt
          writeFrame(
              clientOutput,
              "{\"jsonrpc\":\"2.0\",\"id\":$id,\"error\":{\"code\":-32601,\"message\":\"not found\"}}")
        }
        .start()

    val response = client.request("bad/method", JsonObject(), 5000)
    assertNotNull(response)
    assertTrue(response!!.isErrorResponse())
    assertEquals("not found", response.errorMessage())
    client.close()
  }

  @Test
  fun close_releasesPendingRequests() {
    val toClient = PipedInputStream(64 * 1024)
    val serverOutput = PipedOutputStream(toClient)
    val toServer = PipedInputStream(64 * 1024)
    val clientOutput = PipedOutputStream(toServer)

    val client = LspStdioClient(toClient, clientOutput)
    val done = CountDownLatch(1)
    val resultRef = AtomicReference<JsonObject?>()
    Thread {
          resultRef.set(client.request("test/never", JsonObject(), 30_000))
          done.countDown()
        }
        .start()

    Thread.sleep(200)
    client.close()

    assertTrue("close 后等待中的请求必须立即返回", done.await(5, TimeUnit.SECONDS))
    assertNull(resultRef.get())
  }

  @Test
  fun malformedMessage_doesNotKillReader() {
    val toClient = PipedInputStream(64 * 1024)
    val serverOutput = PipedOutputStream(toClient)
    val toServer = PipedInputStream(64 * 1024)
    val clientOutput = PipedOutputStream(toServer)

    val latch = CountDownLatch(1)
    val client =
        LspStdioClient(
            toClient,
            clientOutput,
            notificationHandler = { latch.countDown() })

    // 先发一条非法 JSON，再发一条合法通知——reader 不能因前者退出
    writeFrame(serverOutput, "this is not json")
    writeFrame(serverOutput, "{\"jsonrpc\":\"2.0\",\"method\":\"test/ping\"}")

    assertTrue("非法消息后 reader 必须继续工作", latch.await(5, TimeUnit.SECONDS))
    client.close()
  }
}
