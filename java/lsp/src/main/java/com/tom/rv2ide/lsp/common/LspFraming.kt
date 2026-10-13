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

import java.io.InputStream

/**
 * LSP 基础协议（JSON-RPC over stdio）的帧编解码。
 *
 * <p><b>为什么单独抽出</b>：Content-Length 帧是 TS/Dart 两个外部语言服务器共用的传输层，
 * 也是整条链路里唯一能脱离 Android 环境做 JVM 单测的部分——进程管理（ProcessBuilder +
 * Android 路径）在单测里跑不起来，但帧解析的边界条件（UTF-8 字节数 vs 字符数、非规范的
 * 裸 \n 头、损坏的长度字段）恰恰是最容易出 bug 的地方，值得测试兜住。
 *
 * <p><b>为什么按字节数而不是字符数写头</b>：Content-Length 是 UTF-8 编码后的**字节数**。
 * 中文注释、emoji 都会让 length 与字符数不一致，按字符数写会让对端把消息截断或粘连。
 * 这是 LSP 客户端最常见的实现错误之一。
 */
object LspFraming {

  private const val HEADER_CONTENT_LENGTH = "content-length"

  /**
   * 单条消息长度上限（50MB）。
   *
   * <p>长度字段损坏（读到脏字节）或对端异常时，没有上限就会尝试分配巨型数组导致 OOM。
   * 语言服务器的正常消息远小于此值（最大的可能是补全列表，通常几百 KB）。
   */
  const val MAX_MESSAGE_SIZE = 50 * 1024 * 1024

  /** 把 JSON 文本编码为一帧：`Content-Length: N\r\n\r\n` + UTF-8 body。 */
  fun encode(json: String): ByteArray {
    val body = json.toByteArray(Charsets.UTF_8)
    val header = "Content-Length: ${body.size}\r\n\r\n".toByteArray(Charsets.US_ASCII)
    val frame = ByteArray(header.size + body.size)
    System.arraycopy(header, 0, frame, 0, header.size)
    System.arraycopy(body, 0, frame, header.size, body.size)
    return frame
  }

  /**
   * 从流中读取一帧消息的 body（阻塞）。流已结束返回 null。
   *
   * <p>头按 LSP 规范要求识别 `Content-Length`（大小写不敏感，忽略其他头）；行结束对
   * `\r\n` 与裸 `\n` 都宽容——规范要求前者，但部分实现的调试输出只有后者。
   *
   * @throws java.io.IOException 长度字段缺失/非法，或超过 [MAX_MESSAGE_SIZE] 时抛出。
   */
  fun readMessage(input: InputStream): String? {
    var contentLength = -1
    while (true) {
      val line = readAsciiLine(input) ?: return null
      if (line.isEmpty()) {
        break
      }
      val colon = line.indexOf(':')
      if (colon <= 0) {
        continue
      }
      val name = line.substring(0, colon).trim().lowercase()
      if (name == HEADER_CONTENT_LENGTH) {
        contentLength = line.substring(colon + 1).trim().toIntOrNull() ?: -1
      }
    }

    if (contentLength < 0) {
      throw java.io.IOException("Missing or invalid Content-Length header")
    }
    if (contentLength > MAX_MESSAGE_SIZE) {
      throw java.io.IOException("Message length $contentLength exceeds limit $MAX_MESSAGE_SIZE")
    }

    val body = ByteArray(contentLength)
    var read = 0
    while (read < contentLength) {
      val n = input.read(body, read, contentLength - read)
      if (n < 0) {
        return null
      }
      read += n
    }
    return String(body, Charsets.UTF_8)
  }

  /** 读取一行 ASCII 头（到 \n 为止）。EOF 且无任何数据时返回 null。 */
  private fun readAsciiLine(input: InputStream): String? {
    val sb = StringBuilder(64)
    while (true) {
      val b = input.read()
      if (b < 0) {
        return if (sb.isEmpty()) null else sb.toString()
      }
      if (b == '\n'.code) {
        if (sb.isNotEmpty() && sb.last() == '\r') {
          sb.deleteCharAt(sb.length - 1)
        }
        return sb.toString()
      }
      sb.append(b.toChar())
    }
  }
}
