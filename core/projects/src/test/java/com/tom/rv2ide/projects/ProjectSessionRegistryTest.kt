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

package com.tom.rv2ide.projects

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话表的行为约定。
 *
 * <p><b>为什么这些断言重要</b>：S1 要求「单项目行为完全不变」。最危险的回归不是多开，
 * 而是同一路径被登记成两个会话（会重复同步、重复索引），或关闭一个会话把别的会话
 * 的状态一起清掉。这两类问题在真机上表现为「项目偶发不同步 / 编辑器空白」，很难归因。
 */
class ProjectSessionRegistryTest {

  private fun tempDir(name: String): File =
      File.createTempFile("acs-session-", "").let {
        it.delete()
        File(it.parentFile, "$name-${System.nanoTime()}").apply { mkdirs() }
      }

  @Test
  fun `activate returns the same session for equivalent paths`() {
    val dir = tempDir("a")
    val registry = ProjectSessionRegistry(maxSessions = 1)

    val first = registry.activate(dir)
    // 带尾分隔符与不带尾分隔符必须归一到同一个会话。
    val second = registry.activate(File("${dir.absolutePath}${File.separator}"))

    assertSame(first, second)
    assertEquals(1, registry.size())
    assertSame(first, registry.active())
  }

  @Test
  fun `single session registry closes the previous session on activate`() {
    val registry = ProjectSessionRegistry(maxSessions = 1)
    val sessionA = registry.activate(tempDir("a"))
    val sessionB = registry.activate(tempDir("b"))

    assertNotSame(sessionA, sessionB)
    assertEquals(1, registry.size())
    assertSame(sessionB, registry.active())
    assertTrue(registry.all().none { it === sessionA })
    // 被淘汰的会话必须清空状态，避免持有已关闭项目的 workspace。
    assertNull(sessionA.workspace)
    assertTrue(!sessionA.projectInitialized)
  }

  @Test
  fun `close removes the session and clears the active pointer`() {
    val dir = tempDir("a")
    val registry = ProjectSessionRegistry(maxSessions = 2)
    val session = registry.activate(dir)
    session.projectInitialized = true

    registry.close(dir)

    assertEquals(0, registry.size())
    assertNull(registry.active())
    assertNull(session.workspace)
    assertTrue(!session.projectInitialized)
  }

  @Test
  fun `activate after close creates a fresh session`() {
    val dir = tempDir("a")
    val registry = ProjectSessionRegistry(maxSessions = 2)
    val session = registry.activate(dir)
    session.projectInitialized = true
    registry.close(dir)

    val reopened = registry.activate(dir)

    assertNotSame(session, reopened)
    assertTrue(!reopened.projectInitialized)
  }

  @Test
  fun `multi session registry evicts the least recently used inactive session`() {
    val a = tempDir("a")
    val b = tempDir("b")
    val c = tempDir("c")
    val registry = ProjectSessionRegistry(maxSessions = 2)

    val sessionA = registry.activate(a)
    val sessionB = registry.activate(b)

    // 重新激活 A：B 变成最久未使用。
    assertSame(sessionA, registry.activate(a))

    val sessionC = registry.activate(c)

    assertEquals(2, registry.size())
    assertSame(sessionC, registry.active())
    assertTrue(registry.all().any { it === sessionA })
    assertTrue(registry.all().none { it === sessionB })
  }

  @Test
  fun `closeAll clears every session`() {
    val registry = ProjectSessionRegistry(maxSessions = 3)
    registry.activate(tempDir("a"))
    registry.activate(tempDir("b"))

    registry.closeAll()

    assertEquals(0, registry.size())
    assertNull(registry.active())
    assertTrue(registry.all().isEmpty())
  }

  @Test
  fun `session id is the canonical path without trailing separator`() {
    val dir = tempDir("a")
    val registry = ProjectSessionRegistry(maxSessions = 1)

    val session = registry.activate(dir)

    assertEquals(registry.keyOf(dir), session.id)
    assertTrue(!session.id.endsWith(File.separator) || session.id == File.separator)
  }

  @Test
  fun `limit below one is coerced to one`() {
    val registry = ProjectSessionRegistry(maxSessions = 0)
    registry.activate(tempDir("a"))
    registry.activate(tempDir("b"))

    assertEquals(1, registry.size())
  }

}
