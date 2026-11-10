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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * [SshProfile] 的主机标识。
 *
 * <p><b>为什么值得钉住</b>：`hostKey()` 是权限 scope 与主机指纹存储共用的主机身份
 * （`ToolPermissionRule.sshScope` 与 `SshHostKeyStore` 都以它为准）。若它漏掉端口，
 * 「同一台主机的 22 与 2222 两个服务」会被当成同一个——授权与指纹都会串味。
 *
 * <p>注意本测试**不**覆盖 [SharedPrefsSshProfileStore] 的读写：那需要 Android
 * `Context`，而 `core/app` 未接 Robolectric。该路径由实机端到端验证覆盖
 * （见任务「SSH 实机端到端验证」）。
 */
class SshProfileTest {

  private fun profile(host: String, port: Int = 22) =
      SshProfile(
          id = "p1",
          name = "test",
          host = host,
          port = port,
          username = "root",
          auth = SshAuthRef.Password("encrypted"))

  @Test
  fun hostKeyIncludesPort() {
    assertEquals("example.com:22", profile("example.com").hostKey())
    assertEquals("example.com:2222", profile("example.com", 2222).hostKey())
  }

  @Test
  fun sameHostDifferentPortYieldsDifferentKeys() {
    // 端口必须区分：22 与 2222 是两个不同的服务，指纹与授权都不该互相串。
    assertNotEquals(profile("example.com", 22).hostKey(), profile("example.com", 2222).hostKey())
  }

  @Test
  fun ipAddressesWorkTheSameWay() {
    assertEquals("192.168.1.10:22", profile("192.168.1.10").hostKey())
  }

  @Test
  fun ipv6HostKeepsItsBrackets() {
    // IPv6 字面量在 URL/host:port 语境里必须带方括号，否则冒号会有歧义。
    // 这里只锁定「原样保留」——用户输什么就是什么，不做额外改写。
    assertEquals("[::1]:22", profile("[::1]").hostKey())
  }
}