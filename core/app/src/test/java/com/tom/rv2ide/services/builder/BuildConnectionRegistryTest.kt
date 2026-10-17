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

package com.tom.rv2ide.services.builder

import com.tom.rv2ide.projects.builder.BuildOutcome
import com.tom.rv2ide.projects.builder.BuildOutputBuffer
import com.tom.rv2ide.projects.builder.BuildService
import com.tom.rv2ide.tooling.api.messages.InitializeProjectParams
import com.tom.rv2ide.tooling.api.messages.result.BuildCancellationRequestResult
import com.tom.rv2ide.tooling.api.messages.result.InitializeResult
import com.tom.rv2ide.tooling.api.messages.result.TaskExecutionResult
import com.tom.rv2ide.tooling.api.models.ToolingServerMetadata
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 注册表的键归一化与生命周期规则。
 *
 * <p><b>为什么这些断言重要</b>：同一项目被登记两次会开出两个 tooling 进程，
 * 两个进程同时构建会撞 `.gradle` 文件锁；而回收一个正在构建的连接会直接打断构建。
 * 这两类问题在真机上表现为「偶发构建失败」，很难归因。
 */
class BuildConnectionRegistryTest {

  /** 最小可用的 BuildService 桩：注册表只关心身份，不调用任何方法。 */
  private class FakeService(private val name: String) : BuildService {
    override val isBuildInProgress: Boolean = false
    override val buildOutput = BuildOutputBuffer()
    override val lastBuildOutcome: BuildOutcome? = null

    override fun recordRejectedBuildAttempt() = Unit

    override fun isToolingServerStarted(): Boolean = false

    override fun metadata(): CompletableFuture<ToolingServerMetadata> =
        CompletableFuture.completedFuture(null)

    override fun initializeProject(
        params: InitializeProjectParams
    ): CompletableFuture<InitializeResult> = CompletableFuture.completedFuture(null)

    override fun executeTasks(vararg tasks: String): CompletableFuture<TaskExecutionResult> =
        CompletableFuture.completedFuture(null)

    override fun cancelCurrentBuild(): CompletableFuture<BuildCancellationRequestResult> =
        CompletableFuture.completedFuture(null)

    override fun toString(): String = "FakeService(" + name + ")"
  }

  private var now = 1_000L

  private fun registry() = BuildConnectionRegistry { now }

  private fun tempDir(): File = Files.createTempDirectory("registry-test").toFile()

  @Test
  fun `trailing separator and dot segments resolve to the same key`() {
    val dir = tempDir()
    val registry = registry()

    val plain = registry.keyOf(dir.absolutePath)
    val trailing = registry.keyOf(dir.absolutePath + File.separator)
    val dotted = registry.keyOf(File(dir, "sub/..").absolutePath)

    assertEquals(plain, trailing)
    assertEquals(plain, dotted)
  }

  @Test
  fun `blank path yields empty key`() {
    assertEquals("", registry().keyOf("   "))
    assertEquals("", registry().keyOf(""))
  }

  @Test
  fun `register then find returns the same connection`() {
    val dir = tempDir()
    val registry = registry()
    val service = FakeService("editor")

    registry.register(dir.absolutePath, service, BuildConnectionRegistry.Owner.EDITOR)

    assertTrue(registry.find(dir.absolutePath) === service)
    assertTrue(registry.find(dir.absolutePath + File.separator) === service)
    assertEquals(1, registry.size())
  }

  @Test
  fun `registering the same project twice replaces rather than duplicates`() {
    val dir = tempDir()
    val registry = registry()
    val first = FakeService("first")
    val second = FakeService("second")

    registry.register(dir.absolutePath, first, BuildConnectionRegistry.Owner.EDITOR)
    registry.register(dir.absolutePath, second, BuildConnectionRegistry.Owner.EDITOR)

    assertEquals("同一项目不应有两个连接", 1, registry.size())
    assertTrue(registry.find(dir.absolutePath) === second)
  }

  @Test
  fun `find on unknown project returns null`() {
    assertNull(registry().find(tempDir().absolutePath))
    assertNull(registry().find(""))
  }

  @Test
  fun `editor returns the editor-owned connection only`() {
    val editorDir = tempDir()
    val backgroundDir = tempDir()
    val registry = registry()
    val editor = FakeService("editor")
    val background = FakeService("background")

    registry.register(editorDir.absolutePath, editor, BuildConnectionRegistry.Owner.EDITOR)
    registry.register(
        backgroundDir.absolutePath, background, BuildConnectionRegistry.Owner.BACKGROUND)

    assertTrue(registry.editor() === editor)
  }

  @Test
  fun `promoteToEditor transfers ownership`() {
    val dir = tempDir()
    val registry = registry()
    val service = FakeService("s")
    registry.register(dir.absolutePath, service, BuildConnectionRegistry.Owner.BACKGROUND)

    assertNull(registry.editor())
    assertTrue(registry.promoteToEditor(dir.absolutePath) === service)
    assertTrue(registry.editor() === service)
  }

  @Test
  fun `demoteToBackground makes the connection eligible for ttl eviction`() {
    val dir = tempDir()
    val registry = registry()
    val service = FakeService("s")
    registry.register(dir.absolutePath, service, BuildConnectionRegistry.Owner.EDITOR)
    registry.demoteToBackground(dir.absolutePath)

    // 未到 TTL：不回收。
    val closed = mutableListOf<BuildService>()
    registry.evictIdle({ false }, { closed += it })
    assertTrue(closed.isEmpty())

    // 超过 TTL：回收。
    now += BuildConnectionRegistry.IDLE_TTL_MS + 1
    registry.evictIdle({ false }, { closed += it })
    assertEquals(1, closed.size)
    assertNull(registry.find(dir.absolutePath))
  }

  @Test
  fun `busy connections are never evicted`() {
    val dir = tempDir()
    val registry = registry()
    val service = FakeService("s")
    registry.register(dir.absolutePath, service, BuildConnectionRegistry.Owner.BACKGROUND)
    now += BuildConnectionRegistry.IDLE_TTL_MS + 1

    val closed = mutableListOf<BuildService>()
    registry.evictIdle({ true }, { closed += it })

    assertTrue("正在构建的连接不能回收——会打断构建", closed.isEmpty())
    assertNotNull(registry.find(dir.absolutePath))
  }

  @Test
  fun `editor connections are never evicted even when idle`() {
    val dir = tempDir()
    val registry = registry()
    val service = FakeService("editor")
    registry.register(dir.absolutePath, service, BuildConnectionRegistry.Owner.EDITOR)
    now += BuildConnectionRegistry.IDLE_TTL_MS * 10

    val closed = mutableListOf<BuildService>()
    registry.evictIdle({ false }, { closed += it })

    assertTrue("编辑器连接由 Activity 显式释放，不受 TTL 影响", closed.isEmpty())
    assertTrue(registry.find(dir.absolutePath) === service)
  }

  @Test
  fun `background connections beyond the cap are evicted oldest first`() {
    val registry = registry()
    val dirs = (0 until 5).map { tempDir() }
    val services = dirs.map { FakeService("bg-${it.name}") }

    dirs.forEachIndexed { index, dir ->
      now += 10
      registry.register(dir.absolutePath, services[index], BuildConnectionRegistry.Owner.BACKGROUND)
    }

    val closed = mutableListOf<BuildService>()
    registry.evictIdle({ false }, { closed += it })

    assertEquals(
        "应只保留 MAX_BACKGROUND 个后台连接",
        dirs.size - BuildConnectionRegistry.MAX_BACKGROUND,
        closed.size)
    // 最旧的先被回收。
    assertTrue(closed.contains(services[0]))
    assertTrue(closed.contains(services[1]))
    assertTrue(closed.contains(services[2]))
  }

  @Test
  fun `remove and clear drop entries`() {
    val dir = tempDir()
    val registry = registry()
    registry.register(dir.absolutePath, FakeService("s"), BuildConnectionRegistry.Owner.EDITOR)

    registry.remove(dir.absolutePath)
    assertNull(registry.find(dir.absolutePath))

    registry.register(dir.absolutePath, FakeService("s2"), BuildConnectionRegistry.Owner.EDITOR)
    registry.clear()
    assertEquals(0, registry.size())
  }
}
