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

import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [LspFraming] 的边界测试。
 *
 * <p>重点覆盖三个真实会踩的坑：UTF-8 字节数 vs 字符数（中文注释场景）、
 * 裸 \n 头（非规范实现）、长度字段损坏（读到脏字节）。
 */
class LspFramingTest {

  @Test
  fun encode_usesUtf8ByteLengthNotCharLength() {
    // 中文字符 3 字节/个。若按字符数写头，对端会把消息截断。
    val json = "{\"message\":\"中文注释\"}"
    val frame = LspFraming.encode(json)
    val header = String(frame.copyOfRange(0, frame.indexOf('\n'.code.toByte()) + 1), Charsets.US_ASCII)
    val byteLength = json.toByteArray(Charsets.UTF_8).size
    val charLength = json.length
    assertTrue("测试前提：字节数 != 字符数", byteLength != charLength)
    assertTrue("Content-Length 必须是 UTF-8 字节数", header.contains("Content-Length: $byteLength"))
  }

  @Test
  fun encode_thenRead_roundTrip() {
    val json = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"value\":\"中文 ✓\"}}"
    val frame = LspFraming.encode(json)
    val decoded = LspFraming.readMessage(ByteArrayInputStream(frame))
    assertEquals(json, decoded)
  }

  @Test
  fun readMessage_acceptsBareLfHeaders() {
    // 规范要求 \r\n，但部分实现的调试输出只有 \n
    val body = "{\"id\":1}"
    val raw = "Content-Length: ${body.length}\n\n$body"
    val decoded = LspFraming.readMessage(ByteArrayInputStream(raw.toByteArray(Charsets.US_ASCII)))
    assertEquals(body, decoded)
  }

  @Test
  fun readMessage_ignoresUnknownHeaders() {
    val body = "{\"id\":2}"
    val raw = "Content-Type: application/vscode-jsonrpc\r\nContent-Length: ${body.length}\r\n\r\n$body"
    val decoded = LspFraming.readMessage(ByteArrayInputStream(raw.toByteArray(Charsets.US_ASCII)))
    assertEquals(body, decoded)
  }

  @Test
  fun readMessage_headerNameIsCaseInsensitive() {
    val body = "{\"id\":3}"
    val raw = "content-length: ${body.length}\r\n\r\n$body"
    val decoded = LspFraming.readMessage(ByteArrayInputStream(raw.toByteArray(Charsets.US_ASCII)))
    assertEquals(body, decoded)
  }

  @Test
  fun readMessage_returnsNullAtEof() {
    assertNull(LspFraming.readMessage(ByteArrayInputStream(ByteArray(0))))
  }

  @Test
  fun readMessage_throwsOnMissingContentLength() {
    val raw = "Content-Type: text\r\n\r\n{}"
    try {
      LspFraming.readMessage(ByteArrayInputStream(raw.toByteArray(Charsets.US_ASCII)))
      fail("Expected IOException for missing Content-Length")
    } catch (e: IOException) {
      assertTrue(e.message!!.contains("Content-Length"))
    }
  }

  @Test
  fun readMessage_throwsOnInvalidContentLength() {
    val raw = "Content-Length: abc\r\n\r\n{}"
    try {
      LspFraming.readMessage(ByteArrayInputStream(raw.toByteArray(Charsets.US_ASCII)))
      fail("Expected IOException for invalid Content-Length")
    } catch (e: IOException) {
      // expected
    }
  }

  @Test
  fun readMessage_throwsOnOversizedLength() {
    // 长度字段损坏成巨大值时必须拒绝，而不是尝试分配巨型数组（OOM）
    val raw = "Content-Length: ${LspFraming.MAX_MESSAGE_SIZE + 1}\r\n\r\n"
    try {
      LspFraming.readMessage(ByteArrayInputStream(raw.toByteArray(Charsets.US_ASCII)))
      fail("Expected IOException for oversized message")
    } catch (e: IOException) {
      assertTrue(e.message!!.contains("exceeds limit"))
    }
  }

  @Test
  fun readMessage_handlesBackToBackFrames() {
    val first = "{\"id\":1}"
    val second = "{\"id\":2,\"value\":\"第二帧\"}"
    val raw =
        "Content-Length: ${first.length}\r\n\r\n$first" +
            "Content-Length: ${second.toByteArray(Charsets.UTF_8).size}\r\n\r\n$second"
    val stream = ByteArrayInputStream(raw.toByteArray(Charsets.UTF_8))
    assertEquals(first, LspFraming.readMessage(stream))
    assertEquals(second, LspFraming.readMessage(stream))
    assertNull(LspFraming.readMessage(stream))
  }
}
