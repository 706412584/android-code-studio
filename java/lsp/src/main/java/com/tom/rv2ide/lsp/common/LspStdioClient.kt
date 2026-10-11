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

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.slf4j.LoggerFactory

/**
 * 外部进程语言服务器的 JSON-RPC over stdio 客户端。
 *
 * <p><b>与 KotlinServerProcessManager 的关系</b>：那个类承担了 KLS 特有的环境注入
 * （JAVA_HOME/CLASSPATH/ktfmt 配置），与本类不是同一抽象层。本类只做「进程已启动后」
 * 的协议收发，可注入 [InputStream]/[OutputStream]，因此能在 JVM 单测里用管道对跑，
 * 无需真实进程。进程的启动（路径探测、环境变量）由各语言服务器自己负责。
 *
 * <p><b>线程模型</b>：单个 reader 线程消费 stdout，把「带 id 的响应」交给回调、
 * 「notification」交给 [notificationHandler]；写入由调用线程直接持锁完成（单条消息
 * 极小，不引入写队列）。回调在 reader 线程执行——调用方负责切到自己的线程。
 *
 * <p><b>为什么用读超时（waitFor）而不是轮询</b>：`complete()` 是同步接口，需要等待
 * 响应；轮询会在高延迟设备上浪费 CPU，`waitFor` 由 JVM 调度，代价为零。
 */
class LspStdioClient(
    private val input: InputStream,
    output: OutputStream,
    private val notificationHandler: (JsonObject) -> Unit = {},
    /** 服务器主动请求（server→client request，如 window/workDoneProgress/create）的处理器。
     *  未设置时回错误响应，避免服务器等待超时。返回值须是任意 JSON 值（对象/数组/字面量），
     *  `workspace/configuration` 要求数组形态。 */
    private val serverRequestHandler: ((method: String, params: JsonObject?) -> JsonElement?)? = null,
) : Closeable {

  companion object {
    private val log = LoggerFactory.getLogger(LspStdioClient::class.java)

    /** 等待响应回调的默认超时。 */
    const val DEFAULT_REQUEST_TIMEOUT_MS = 10_000L
  }

  private val gson = Gson()
  private val nextId = AtomicInteger(1)
  private val pending = ConcurrentHashMap<Int, PendingRequest>()
  private val writeLock = Any()
  private val outputStream = BufferedOutputStream(output)

  @Volatile private var closed = false

  /** 处理过程中的异常（reader 线程记录，调用方可查）。 */
  @Volatile var lastError: Throwable? = null
    private set

  private class PendingRequest {
    val lock = Object()
    @Volatile var response: JsonObject? = null
    @Volatile var done = false
  }

  private val readerThread =
      Thread({ readLoop() }, "lsp-stdio-reader").apply {
        isDaemon = true
        start()
      }

  private fun readLoop() {
    val stream = BufferedInputStream(input, 64 * 1024)
    try {
      while (!closed) {
        val json = LspFraming.readMessage(stream) ?: break
        dispatch(json)
      }
    } catch (e: Throwable) {
      if (!closed) {
        lastError = e
        log.warn("LSP reader stopped: {}", e.message)
      }
    } finally {
      // 流结束 = 进程退出或主动关闭：唤醒所有等待者，避免调用方挂死。
      pending.values.forEach {
        it.done = true
        synchronized(it.lock) { it.lock.notifyAll() }
      }
    }
  }

  private fun dispatch(json: String) {
    val obj =
        try {
          gson.fromJson(json, JsonObject::class.java)
        } catch (e: Exception) {
          log.warn("Ignoring malformed LSP message ({} bytes)", json.length)
          return
        }
    if (obj == null) {
      return
    }

    when {
      // 带 id 的响应：唤醒等待者
      obj.has("id") && (obj.has("result") || obj.has("error")) -> {
        val id = obj.get("id").asInt
        val request = pending.remove(id)
        if (request == null) {
          log.debug("No pending request for id {}", id)
          return
        }
        request.response = obj
        request.done = true
        synchronized(request.lock) { request.lock.notifyAll() }
      }
      // 服务器→客户端请求：必须应答，否则服务器可能阻塞
      obj.has("id") && obj.has("method") -> {
        val method = obj.get("method").asString
        val id = obj.get("id")
        val result = serverRequestHandler?.invoke(method, obj.getAsJsonObject("params"))
        val reply =
            JsonObject().apply {
              addProperty("jsonrpc", "2.0")
              add("id", id)
              if (result != null) {
                add("result", result)
              } else {
                add(
                    "error",
                    JsonObject().apply {
                      addProperty("code", -32601)
                      addProperty("message", "Method not supported: $method")
                    })
              }
            }
        writeMessage(gson.toJson(reply))
      }
      // 通知
      obj.has("method") -> {
        try {
          notificationHandler(obj)
        } catch (e: Throwable) {
          log.warn("Notification handler failed for {}: {}", obj.get("method"), e.message)
        }
      }
      else -> log.debug("Unhandled LSP message: {}", json.take(200))
    }
  }

  /**
   * 发送请求并等待响应。
   *
   * @return 完整响应对象（含 result 或 error 字段）；超时或连接断开返回 null。
   */
  fun request(
      method: String,
      params: JsonObject?,
      timeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS,
  ): JsonObject? {
    if (closed) {
      return null
    }
    val id = nextId.getAndIncrement()
    val payload =
        JsonObject().apply {
          addProperty("jsonrpc", "2.0")
          addProperty("id", id)
          addProperty("method", method)
          if (params != null) {
            add("params", params)
          }
        }

    val pendingRequest = PendingRequest()
    pending[id] = pendingRequest
    writeMessage(gson.toJson(payload))

    val deadline = System.currentTimeMillis() + timeoutMs
    synchronized(pendingRequest.lock) {
      while (!pendingRequest.done) {
        val remaining = deadline - System.currentTimeMillis()
        if (remaining <= 0) {
          pending.remove(id)
          log.warn("LSP request timed out: {} (id={})", method, id)
          return null
        }
        try {
          pendingRequest.lock.wait(remaining)
        } catch (e: InterruptedException) {
          Thread.currentThread().interrupt()
          pending.remove(id)
          return null
        }
      }
    }
    return pendingRequest.response
  }

  /** 发送通知（无响应）。 */
  fun notify(method: String, params: JsonObject?) {
    if (closed) {
      return
    }
    val payload =
        JsonObject().apply {
          addProperty("jsonrpc", "2.0")
          addProperty("method", method)
          if (params != null) {
            add("params", params)
          }
        }
    writeMessage(gson.toJson(payload))
  }

  private fun writeMessage(json: String) {
    synchronized(writeLock) {
      try {
        outputStream.write(LspFraming.encode(json))
        outputStream.flush()
      } catch (e: Exception) {
        lastError = e
        log.warn("Failed to write LSP message: {}", e.message)
      }
    }
  }

  /** 服务器是否已停止（reader 线程退出或流结束）。 */
  fun isBroken(): Boolean = closed || !readerThread.isAlive || lastError != null

  override fun close() {
    closed = true
    pending.values.forEach {
      it.done = true
      synchronized(it.lock) { it.lock.notifyAll() }
    }
    pending.clear()
    try {
      outputStream.close()
    } catch (e: Exception) {
      // ignore
    }
    readerThread.interrupt()
  }
}

/** 响应对象中取 result（无 result 或为 null 时返回 null）。 */
fun JsonObject.resultOrNull(): JsonObject? {
  val result = get("result") ?: return null
  return if (result.isJsonObject) result.asJsonObject else null
}

/** 响应对象是否为错误响应。 */
fun JsonObject.isErrorResponse(): Boolean = has("error") && !get("error").isJsonNull

/** 响应错误信息（非错误响应返回 null）。 */
fun JsonObject.errorMessage(): String? {
  val error = get("error") ?: return null
  if (error.isJsonNull) {
    return null
  }
  val obj = error.asJsonObject
  return obj.get("message")?.asString ?: "Unknown error"
}
