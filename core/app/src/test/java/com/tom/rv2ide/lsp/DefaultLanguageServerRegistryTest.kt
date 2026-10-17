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

package com.tom.rv2ide.lsp

import com.android.builder.model.v2.models.ProjectSyncIssues
import com.tom.rv2ide.eventbus.events.project.ProjectInitializedEvent
import com.tom.rv2ide.lsp.api.DefaultLanguageServerRegistry
import com.tom.rv2ide.lsp.api.ILanguageClient
import com.tom.rv2ide.lsp.api.ILanguageServer
import com.tom.rv2ide.lsp.api.IServerSettings
import com.tom.rv2ide.lsp.models.CodeFormatResult
import com.tom.rv2ide.lsp.models.CompletionParams
import com.tom.rv2ide.lsp.models.CompletionResult
import com.tom.rv2ide.lsp.models.DefinitionParams
import com.tom.rv2ide.lsp.models.DefinitionResult
import com.tom.rv2ide.lsp.models.DiagnosticResult
import com.tom.rv2ide.lsp.models.ExpandSelectionParams
import com.tom.rv2ide.lsp.models.FormatCodeParams
import com.tom.rv2ide.lsp.models.LSPFailure
import com.tom.rv2ide.lsp.models.ReferenceParams
import com.tom.rv2ide.lsp.models.ReferenceResult
import com.tom.rv2ide.lsp.models.SignatureHelp
import com.tom.rv2ide.lsp.models.SignatureHelpParams
import com.tom.rv2ide.models.Range
import com.tom.rv2ide.projects.GradleProject
import com.tom.rv2ide.projects.IWorkspace
import com.tom.rv2ide.projects.ModuleProject
import com.tom.rv2ide.projects.android.AndroidModule
import com.tom.rv2ide.tooling.api.models.BuildVariantInfo
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 语言服务器按「活跃 workspace」路由的规则。
 *
 * <p><b>为什么这些断言重要</b>：server 是进程级单例。改动前
 * `onProjectInitialized` 把每个完成同步的 workspace 都推给所有 server，多标签下后打开的
 * 项目必然覆盖前台项目——表现为「在 A 项目里补全出 B 项目的符号」。这里锁定三条规则：
 * 事件只推活跃 workspace、切回旧标签会重新推送、重复推送同一 workspace 不重复 setup
 * （setup 会清全局编译缓存，重复调用会让前台编辑卡顿）。
 */
class DefaultLanguageServerRegistryTest {

  private val registry = DefaultLanguageServerRegistry()

  @After
  fun tearDown() {
    // EventBus 订阅与 server 都属于进程级状态，测试之间必须清干净。
    registry.destroy()
  }

  private fun tempWorkspace(): IWorkspace =
      FakeWorkspace(Files.createTempDirectory("lsp-routing").toFile())

  private fun initializedEvent(workspace: IWorkspace): ProjectInitializedEvent =
      ProjectInitializedEvent().apply { put(IWorkspace::class.java, workspace) }

  @Test
  fun `pushes active workspace to registered servers`() {
    val server = FakeServer()
    registry.register(server)
    val a = tempWorkspace()

    registry.setActiveWorkspace(a)

    assertSame(a, registry.getActiveWorkspace())
    assertEquals(listOf<File>(a.getProjectDir()), server.setups.map { it.getProjectDir() })
  }

  @Test
  fun `push A then B leaves the server on B`() {
    val server = FakeServer()
    registry.register(server)
    val a = tempWorkspace()
    val b = tempWorkspace()

    registry.setActiveWorkspace(a)
    registry.setActiveWorkspace(b)

    assertSame(b, registry.getActiveWorkspace())
    assertEquals(
        "server 最终必须持有 B，不能被 A 覆盖",
        b.getProjectDir(),
        server.setups.last().getProjectDir(),
    )
  }

  @Test
  fun `event for a background workspace is ignored`() {
    val server = FakeServer()
    registry.register(server)
    val a = tempWorkspace()
    val b = tempWorkspace()

    registry.setActiveWorkspace(a)
    registry.setActiveWorkspace(b)
    val setupsBefore = server.setups.size

    // A 的后台同步晚于切到 B 完成：不能把 server 拽回 A。
    registry.onProjectInitialized(initializedEvent(a))

    assertEquals("后台 workspace 的事件必须被忽略", setupsBefore, server.setups.size)
    assertSame(b, registry.getActiveWorkspace())
    assertEquals(b.getProjectDir(), server.setups.last().getProjectDir())
  }

  @Test
  fun `switching back to A re-pushes A`() {
    val server = FakeServer()
    registry.register(server)
    val a = tempWorkspace()
    val b = tempWorkspace()

    registry.setActiveWorkspace(a)
    registry.setActiveWorkspace(b)
    registry.setActiveWorkspace(a)

    assertSame(a, registry.getActiveWorkspace())
    assertEquals(
        "切回旧标签必须重新推送，否则诊断仍指向 B",
        a.getProjectDir(),
        server.setups.last().getProjectDir(),
    )
    assertEquals(3, server.setups.size)
  }

  @Test
  fun `re-pushing the same workspace does not set up again`() {
    val server = FakeServer()
    registry.register(server)
    val a = tempWorkspace()

    registry.setActiveWorkspace(a)
    registry.setActiveWorkspace(a)
    registry.setActiveWorkspace(a)

    assertEquals("同一 workspace 重复推送必须幂等", 1, server.setups.size)
  }

  @Test
  fun `force re-pushes the same workspace`() {
    val server = FakeServer()
    registry.register(server)
    val a = tempWorkspace()

    registry.setActiveWorkspace(a)
    registry.setActiveWorkspace(a, true)

    assertEquals(2, server.setups.size)
  }

  @Test
  fun `event without an active workspace is adopted`() {
    // 单项目启动路径：没有任何一方显式 setActiveWorkspace 时，事件必须照旧配置 server。
    val server = FakeServer()
    registry.register(server)
    val a = tempWorkspace()

    registry.onProjectInitialized(initializedEvent(a))

    assertSame(a, registry.getActiveWorkspace())
    assertEquals(1, server.setups.size)
  }

  @Test
  fun `event for the active project after a re-sync re-sets up`() {
    // Gradle 重新同步会为同一目录构建新的 Workspace 实例，classpath/R.jar 已变化，
    // 必须重新 setup；不能按「路径相同」把它当成重复推送丢掉。
    val server = FakeServer()
    registry.register(server)
    val dir = Files.createTempDirectory("lsp-resync").toFile()
    val first = FakeWorkspace(dir)
    val second = FakeWorkspace(dir)

    registry.setActiveWorkspace(first)
    registry.onProjectInitialized(initializedEvent(second))

    assertEquals(2, server.setups.size)
    assertSame(second, server.setups.last())
    assertSame(second, registry.getActiveWorkspace())
  }

  @Test
  fun `server registered after the workspace is configured receives it`() {
    val a = tempWorkspace()
    registry.setActiveWorkspace(a)
    val server = FakeServer()

    registry.register(server)

    assertEquals(1, server.setups.size)
    assertSame(a, server.setups.single())
  }

  @Test
  fun `destroy clears the active workspace`() {
    registry.setActiveWorkspace(tempWorkspace())

    registry.destroy()

    assertNull(registry.getActiveWorkspace())
    assertTrue(true)
  }

  /** 只关心身份的最小 workspace 桩。 */
  private class FakeWorkspace(private val dir: File) : IWorkspace {
    override fun getProjectDir(): File = dir

    override fun getRootProject(): GradleProject = unsupported()

    override fun getSubProjects(): List<GradleProject> = emptyList()

    override fun getProjectSyncIssues(): ProjectSyncIssues = unsupported()

    override fun getAndroidVariantSelections(): Map<String, BuildVariantInfo> = emptyMap()

    override fun findProject(path: String): GradleProject? = null

    override fun androidProjects(): Sequence<AndroidModule> = emptySequence()

    override fun findModuleForFile(file: Path, checkExistance: Boolean): ModuleProject? = null

    override fun findModuleForFile(file: File, checkExistance: Boolean): ModuleProject? = null

    override fun containsSourceFile(file: Path): Boolean = false

    override fun isAndroidResource(file: File): Boolean = false

    private fun <T> unsupported(): T = throw UnsupportedOperationException("not needed in tests")
  }

  /** 记录每次 setupWorkspace 收到的 workspace。 */
  private class FakeServer : ILanguageServer {
    val setups = mutableListOf<IWorkspace>()

    override val serverId: String = "test.server"

    override var client: ILanguageClient? = null

    override fun shutdown() {}

    override fun connectClient(client: ILanguageClient?) {
      this.client = client
    }

    override fun applySettings(settings: IServerSettings?) {}

    override fun setupWorkspace(workspace: IWorkspace) {
      setups += workspace
    }

    override fun complete(params: CompletionParams?): CompletionResult =
        CompletionResult(emptyList())

    override suspend fun findReferences(params: ReferenceParams): ReferenceResult =
        ReferenceResult(emptyList())

    override suspend fun findDefinition(params: DefinitionParams): DefinitionResult =
        DefinitionResult(emptyList())

    override suspend fun expandSelection(params: ExpandSelectionParams): Range = params.selection

    override suspend fun signatureHelp(params: SignatureHelpParams): SignatureHelp =
        SignatureHelp(emptyList(), -1, -1)

    override suspend fun analyze(file: Path): DiagnosticResult = DiagnosticResult.NO_UPDATE

    override fun formatCode(params: FormatCodeParams?): CodeFormatResult =
        CodeFormatResult(false, mutableListOf())

    override fun handleFailure(failure: LSPFailure?): Boolean = false
  }
}
