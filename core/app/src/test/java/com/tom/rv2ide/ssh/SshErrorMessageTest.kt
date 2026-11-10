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

package com.tom.rv2ide.ssh

import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SSH 错误 → 面向模型/用户的文案。
 *
 * <p><b>为什么值得钉住</b>：这些文案是模型唯一的失败信号。若全退化成
 * 「SSH 连接失败: &lt;原始英文栈信息&gt;」，模型无法判断「密码错」与「网络不通」的区别——
 * 前者重试无用、必须改配置，后者重试一次可能就好。这个区别直接决定它下一步做什么。
 */
class SshErrorMessageTest {

  @Test
  fun connectionRefusedPointsToServerOrPort() {
    val message = friendlySshError(ConnectException("Connection refused"))

    assertTrue("应说明连接被拒绝: $message", message.contains("拒绝"))
    // 必须给可执行的下一步，而不是只说「失败」
    assertTrue("应提示检查服务器与端口: $message", message.contains("端口"))
  }

  @Test
  fun unknownHostPointsToHostAddress() {
    val message = friendlySshError(UnknownHostException("unknown host foo"))

    assertTrue("应指向主机地址: $message", message.contains("未知主机"))
  }

  @Test
  fun timeoutIsDistinguishedFromAuthFailure() {
    val timeout = friendlySshError(SocketTimeoutException("timed out"))
    val auth = friendlySshError(IllegalStateException("Auth fail for user"))

    // 两类失败必须给出不同的文案：重试策略相反（超时值得重试，认证失败重试只会撞 fail2ban）。
    assertTrue("超时文案: $timeout", timeout.contains("超时"))
    assertTrue("认证文案: $auth", auth.contains("认证失败"))
    assertFalse("超时不应被说成认证失败: $timeout", timeout.contains("认证失败"))
  }

  @Test
  fun unreachableNetworkGetsItsOwnMessage() {
    val message = friendlySshError(SocketException("Network is unreachable"))

    assertTrue("应说明网络不可达: $message", message.contains("网络不可达"))
  }

  @Test
  fun cooldownMessageIsPassedThroughVerbatim() {
    // 认证冷却（防 fail2ban）自己已经写好了带剩余时间的文案，不能再套一层
    // 「SSH 连接失败」，否则用户看不到「还要等几分钟」。
    val original = "SSH 认证失败：距上次失败不足 5 分钟，已暂停连接（还需 3 分钟）"
    val actual = friendlySshError(IllegalStateException(original))

    assertTrue("冷却文案应原样透出: $actual", actual.startsWith(original))
  }

  @Test
  fun unknownErrorStillCarriesTheOriginalMessage() {
    val message = friendlySshError(RuntimeException("something odd happened"))

    // 兜底也要带上原始消息：完全吞掉会让排查无从下手。
    assertTrue("应包含原始消息: $message", message.contains("something odd happened"))
  }
}