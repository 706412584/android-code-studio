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
 * 配置去重的判据。
 *
 * <p><b>为什么值得钉住</b>：真机反馈「ssh 添加后列表显示 3 个重复 item」。复现结论是
 * 用户连续新建了同一条配置（主机/端口/用户名都一样），而列表只显示
 * `user@host:port`，三条看起来完全一样。
 *
 * <p>去重键必须是「主机+端口+用户名」这三元组：
 * - 少了端口 → 同一台机器的 22 与 2222 两个服务被误合并
 * - 少了用户名 → 同一台机器上不同账号被误合并
 *
 * <p>这些用例只验证**判据本身**（纯 data class 比较），不碰 SharedPreferences
 * ——那需要 Android Context，而 core/app 未接 Robolectric；读写路径由实机验证覆盖。
 */
class SshProfileDedupeTest {

  private fun profile(
      id: String,
      host: String = "10.0.0.5",
      port: Int = 22,
      username: String = "deploy",
      name: String = "test"
  ) =
      SshProfile(
          id = id,
          name = name,
          host = host,
          port = port,
          username = username,
          auth = SshAuthRef.Password("cipher"))

  /** 与 `all()` 里的去重键保持一致：Triple(host, port, username)。 */
  private fun dedupeKey(p: SshProfile) = Triple(p.host, p.port, p.username)

  @Test
  fun sameHostPortUserCollapsesToOne() {
    // 用户连点两次「新建」填了相同的值：应折叠成一条。
    val a = profile(id = "id-1")
    val b = profile(id = "id-2")

    val distinct = listOf(a, b).distinctBy { dedupeKey(it) }

    assertEquals("同主机+端口+用户名的配置应折叠为一条", 1, distinct.size)
  }

  @Test
  fun differentPortIsTreatedAsDifferentMachine() {
    // 同一台机器的 22 与 2222 是两个独立服务，不能合并。
    val a = profile(id = "id-1", port = 22)
    val b = profile(id = "id-2", port = 2222)

    assertNotEquals(dedupeKey(a), dedupeKey(b))
  }

  @Test
  fun differentUsernameIsTreatedAsDifferentLogin() {
    // 同一台机器上 root 与 deploy 是两个账号，权限完全不同。
    val a = profile(id = "id-1", username = "deploy")
    val b = profile(id = "id-2", username = "root")

    assertNotEquals(dedupeKey(a), dedupeKey(b))
  }

  @Test
  fun differentHostIsNeverMerged() {
    val a = profile(id = "id-1", host = "10.0.0.5")
    val b = profile(id = "id-2", host = "10.0.0.6")

    assertNotEquals(dedupeKey(a), dedupeKey(b))
  }

  @Test
  fun dedupeIsStableRegardlessOfInsertionOrder() {
    // 去重前要先按 id 排序，否则「保留哪一条」会随偏好键序变化——用户会看到
    // 条目时有时无地跳变。这里锁定：无论输入顺序，保留下来的都是 id 最小的那条。
    val b = profile(id = "id-b")
    val a = profile(id = "id-a")

    val kept1 = listOf(b, a).sortedBy { it.id }.distinctBy { dedupeKey(it) }.single()
    val kept2 = listOf(a, b).sortedBy { it.id }.distinctBy { dedupeKey(it) }.single()

    assertEquals("id-a", kept1.id)
    assertEquals(kept1.id, kept2.id)
  }
}