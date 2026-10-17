/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.tom.rv2ide.lsp.java

import androidx.annotation.RestrictTo
import com.tom.rv2ide.eventbus.events.editor.DocumentChangeEvent
import com.tom.rv2ide.eventbus.events.editor.DocumentCloseEvent
import com.tom.rv2ide.eventbus.events.editor.DocumentOpenEvent
import com.tom.rv2ide.eventbus.events.editor.DocumentSelectedEvent
import com.tom.rv2ide.javac.services.fs.CacheFSInfoSingleton
import com.tom.rv2ide.javac.services.fs.CachingJarFileSystemProvider.clearCache
import com.tom.rv2ide.javac.services.fs.CachingJarFileSystemProvider.clearCachesForPaths
import com.tom.rv2ide.lsp.api.ILanguageClient
import com.tom.rv2ide.lsp.api.ILanguageServer
import com.tom.rv2ide.lsp.api.IServerSettings
import com.tom.rv2ide.lsp.internal.model.CachedCompletion
import com.tom.rv2ide.lsp.java.actions.JavaCodeActionsMenu
import com.tom.rv2ide.lsp.java.compiler.JavaCompilerService
import com.tom.rv2ide.lsp.java.compiler.SourceFileManager
import com.tom.rv2ide.lsp.java.models.JavaServerSettings
import com.tom.rv2ide.lsp.java.providers.CodeFormatProvider
import com.tom.rv2ide.lsp.java.providers.CompletionProvider
import com.tom.rv2ide.lsp.java.providers.DefinitionProvider
import com.tom.rv2ide.lsp.java.providers.JavaDiagnosticProvider
import com.tom.rv2ide.lsp.java.providers.JavaSelectionProvider
import com.tom.rv2ide.lsp.java.providers.ReferenceProvider
import com.tom.rv2ide.lsp.java.providers.SignatureProvider
import com.tom.rv2ide.lsp.java.providers.snippet.JavaSnippetRepository.init
import com.tom.rv2ide.lsp.java.utils.AnalyzeTimer
import com.tom.rv2ide.lsp.java.utils.CancelChecker.Companion.isCancelled
import com.tom.rv2ide.lsp.models.CodeFormatResult
import com.tom.rv2ide.lsp.models.CompletionParams
import com.tom.rv2ide.lsp.models.CompletionResult
import com.tom.rv2ide.lsp.models.DefinitionParams
import com.tom.rv2ide.lsp.models.DefinitionResult
import com.tom.rv2ide.lsp.models.DiagnosticResult
import com.tom.rv2ide.lsp.models.ExpandSelectionParams
import com.tom.rv2ide.lsp.models.FailureType
import com.tom.rv2ide.lsp.models.FormatCodeParams
import com.tom.rv2ide.lsp.models.LSPFailure
import com.tom.rv2ide.lsp.models.ReferenceParams
import com.tom.rv2ide.lsp.models.ReferenceResult
import com.tom.rv2ide.lsp.models.SignatureHelp
import com.tom.rv2ide.lsp.models.SignatureHelpParams
import com.tom.rv2ide.lsp.util.LSPEditorActions
import com.tom.rv2ide.models.Range
import com.tom.rv2ide.projects.FileManager.getActiveDocumentCount
import com.tom.rv2ide.projects.IWorkspace
import com.tom.rv2ide.projects.ModuleProject
import com.tom.rv2ide.utils.DocumentUtils
import com.tom.rv2ide.utils.VMUtils
import java.io.File
import java.nio.file.Path
import java.util.Objects
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.slf4j.LoggerFactory

class JavaLanguageServer : ILanguageServer {

  private val completionProvider: CompletionProvider = CompletionProvider()
  private val diagnosticProvider: JavaDiagnosticProvider?
  override var client: ILanguageClient? = null
    private set

  private var _settings: IServerSettings? = null
  private var selectedFile: Path? = null
  private val timer = AnalyzeTimer { analyzeSelected() }
  private var cachedCompletion: CachedCompletion

  /**
   * Workspace pushed by [setupWorkspace].
   *
   * <p>Deliberately **not** read from the global `IProjectManager`: with multiple project tabs that
   * singleton points at whichever session happens to be active, while this server must resolve
   * files against the workspace it was last set up with. `@Volatile` because setup happens on the
   * registry's background thread while completions/diagnostics run on worker threads.
   */
  @Volatile
  private var workspace: IWorkspace? = null

  /** Root directory of [workspace]; used to detect real tab switches and re-syncs. */
  @Volatile
  private var workspaceRoot: Path? = null

  val settings: IServerSettings
    get() {
      return _settings ?: JavaServerSettings.getInstance().also { _settings = it }
    }

  override val serverId: String = SERVER_ID

  companion object {

    const val SERVER_ID = "ide.lsp.java"
    private val log = LoggerFactory.getLogger(JavaLanguageServer::class.java)
  }

  init {
    diagnosticProvider = JavaDiagnosticProvider { workspace }
    cachedCompletion = CachedCompletion.EMPTY

    applySettings(JavaServerSettings.getInstance())

    if (!EventBus.getDefault().isRegistered(this)) {
      EventBus.getDefault().register(this)
    }

    init()
  }

  override fun shutdown() {
    JavaCompilerProvider.getInstance().destroy()
    SourceFileManager.clearCache()
    CacheFSInfoSingleton.clearCache()
    clearCache()
    EventBus.getDefault().unregister(this)
    timer.cancel()
    workspace = null
    workspaceRoot = null
  }

  override fun connectClient(client: ILanguageClient?) {
    this.client = client
  }

  override fun applySettings(settings: IServerSettings?) {
    this._settings = settings
  }

  override fun setupWorkspace(workspace: IWorkspace) {
    LSPEditorActions.ensureActionsMenuRegistered(JavaCodeActionsMenu)

    val root = workspace.getProjectDir().toPath().toAbsolutePath().normalize()
    val previousRoot = workspaceRoot

    // Scoped invalidation: with multiple project tabs open, the caches below are shared by every
    // project. Clearing them globally would throw away the other tabs' compilers/file managers and
    // force a re-index on the next switch back, so only the entries of *this* workspace are
    // dropped. Entries are keyed by `ModuleProject` identity and a re-sync rebuilds those
    // instances, so a repeated setup of the same project also needs this (the old entries can
    // never be hit again).
    if (previousRoot != null && previousRoot != root) {
      // Per-file caches keyed by path would otherwise leak diagnostics across projects.
      diagnosticProvider?.clearAll()
      cachedCompletion = CachedCompletion.EMPTY
    }

    JavaCompilerService.NO_MODULE_COMPILER.destroy()
    SourceFileManager.clearCache(workspace)
    // Cached JAR file systems for R.jar must be re-created so completions see regenerated
    // resources. Scope it to this workspace: the other tabs' R.jar caches stay valid.
    val rootPrefix = root.toString() + File.separator
    clearCachesForPaths { path: String -> path.endsWith("/R.jar") && path.startsWith(rootPrefix) }
    JavaCompilerProvider.getInstance().destroy(workspace)

    // Cache classpath locations for this workspace's modules.
    for (subModule in workspace.getSubProjects()) {
      if (subModule !is ModuleProject || subModule.path == workspace.getRootProject().path) {
        continue
      }
      SourceFileManager.forModule(subModule)
    }

    // Publish the workspace only after the caches are ready: requests racing with setup must not
    // resolve against a half-initialized state.
    this.workspace = workspace
    this.workspaceRoot = root
    if (previousRoot != null && previousRoot != root) {
      log.info("Java language server switched workspace: {} -> {}", previousRoot, root)
    }

    startOrRestartAnalyzeTimer()
  }

  override fun complete(params: CompletionParams?): CompletionResult {
    val compiler = getCompiler(params!!.file)
    if (!settings.completionsEnabled() || !completionProvider.canComplete(params.file)) {
      return CompletionResult.EMPTY
    }

    if (diagnosticProvider!!.isAnalyzing()) {
      log.warn("Cancelling source code analysis due to completion request")
      diagnosticProvider.cancel()
    }

    completionProvider.reset(compiler, settings, cachedCompletion) {
        cachedCompletion: CachedCompletion ->
      updateCachedCompletion(cachedCompletion)
    }

    val result = completionProvider.complete(params)

    // log.warn(result.toString())

    return result
  }

  override suspend fun findReferences(params: ReferenceParams): ReferenceResult {
    val compiler = getCompiler(params.file)
    return if (!settings.referencesEnabled()) {
      ReferenceResult(emptyList())
    } else ReferenceProvider(compiler, params.cancelChecker).findReferences(params)
  }

  override suspend fun findDefinition(params: DefinitionParams): DefinitionResult {
    val compiler = getCompiler(params.file)
    return if (!settings.definitionsEnabled()) {
      DefinitionResult(emptyList())
    } else DefinitionProvider(compiler, settings, params.cancelChecker).findDefinition(params)
  }

  override suspend fun expandSelection(params: ExpandSelectionParams): Range {
    val compiler = getCompiler(params.file)
    return if (!settings.smartSelectionsEnabled()) {
      params.selection
    } else JavaSelectionProvider(compiler).expandSelection(params)
  }

  override suspend fun signatureHelp(params: SignatureHelpParams): SignatureHelp {
    val compiler = getCompiler(params.file)
    return if (!settings.signatureHelpEnabled()) {
      SignatureHelp(emptyList(), -1, -1)
    } else SignatureProvider(compiler, params.cancelChecker).signatureHelp(params)
  }

  override suspend fun hover(params: DefinitionParams): com.tom.rv2ide.lsp.models.MarkupContent {
    // Java LSP does not currently implement hover; return empty
    return com.tom.rv2ide.lsp.models.MarkupContent()
  }

  override suspend fun analyze(file: Path): DiagnosticResult {
    if (!settings.diagnosticsEnabled() || !DocumentUtils.isJavaFile(file)) {
      return DiagnosticResult.NO_UPDATE
    }

    return if (!settings.codeAnalysisEnabled()) {
      DiagnosticResult.NO_UPDATE
    } else diagnosticProvider!!.analyze(file)
  }

  override fun formatCode(params: FormatCodeParams?): CodeFormatResult {
    return CodeFormatProvider(settings).format(params)
  }

  override fun handleFailure(failure: LSPFailure?): Boolean {
    return when (failure!!.type) {
      FailureType.COMPLETION -> {
        if (isCancelled(failure.error)) {
          return true
        }
        JavaCompilerProvider.getInstance().destroy()
        true
      }
    }
  }

  @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
  fun getCompiler(file: Path?): JavaCompilerService {
    if (!DocumentUtils.isJavaFile(file)) {
      return JavaCompilerService.NO_MODULE_COMPILER
    }
    val workspace = this.workspace ?: return JavaCompilerService.NO_MODULE_COMPILER
    val module =
        workspace.findModuleForFile(file!!) ?: return JavaCompilerService.NO_MODULE_COMPILER
    return JavaCompilerProvider.get(module)
  }

  /**
   * The workspace this server is currently configured for.
   *
   * <p>Exposed for diagnostics providers and tests. May be `null` before the first [setupWorkspace].
   */
  @RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
  fun getWorkspace(): IWorkspace? = workspace

  private fun updateCachedCompletion(cachedCompletion: CachedCompletion) {
    Objects.requireNonNull(cachedCompletion)
    this.cachedCompletion = cachedCompletion
  }

  private fun startOrRestartAnalyzeTimer() {
    if (VMUtils.isJvm()) {
      return
    }
    if (!timer.isStarted) {
      timer.start()
    } else {
      timer.restart()
    }
  }

  @Subscribe(threadMode = ThreadMode.ASYNC)
  @Suppress("unused")
  fun onContentChange(event: DocumentChangeEvent) {
    if (!DocumentUtils.isJavaFile(event.changedFile)) {
      return
    }

    // TODO Find an alternative to efficiently update changeDelta in JavaCompilerService instance
    JavaCompilerService.NO_MODULE_COMPILER.onDocumentChange(event)
    val module = workspace?.findModuleForFile(event.changedFile, true)
    if (module != null) {
      val compiler = JavaCompilerProvider.get(module)
      compiler.onDocumentChange(event)
    }
    startOrRestartAnalyzeTimer()
  }

  @Subscribe(threadMode = ThreadMode.ASYNC)
  @Suppress("unused")
  fun onFileSelected(event: DocumentSelectedEvent) {
    selectedFile = event.selectedFile
  }

  @Subscribe(threadMode = ThreadMode.ASYNC)
  @Suppress("unused")
  fun onFileOpened(event: DocumentOpenEvent) {
    selectedFile = event.openedFile
    startOrRestartAnalyzeTimer()
  }

  @Subscribe(threadMode = ThreadMode.ASYNC)
  @Suppress("unused")
  fun onFileClosed(event: DocumentCloseEvent) {
    diagnosticProvider?.clearTimestamp(event.closedFile)

    if (getActiveDocumentCount() == 0) {
      selectedFile = null
      timer.cancel()
    }
  }

  private fun analyzeSelected() {
    if (selectedFile == null || client == null) {
      return
    }

    CoroutineScope(Dispatchers.Default).launch {
      val result = analyze(selectedFile!!)
      withContext(Dispatchers.Main) { client?.publishDiagnostics(result) }
    }
  }
}
