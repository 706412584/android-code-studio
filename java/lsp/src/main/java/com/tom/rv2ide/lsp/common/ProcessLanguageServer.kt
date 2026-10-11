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

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.tom.rv2ide.eventbus.events.editor.DocumentChangeEvent
import com.tom.rv2ide.eventbus.events.editor.DocumentCloseEvent
import com.tom.rv2ide.eventbus.events.editor.DocumentOpenEvent
import com.tom.rv2ide.eventbus.events.editor.DocumentSaveEvent
import com.tom.rv2ide.lsp.api.ILanguageClient
import com.tom.rv2ide.lsp.api.ILanguageServer
import com.tom.rv2ide.lsp.api.IServerSettings
import com.tom.rv2ide.lsp.models.CompletionParams
import com.tom.rv2ide.lsp.models.CompletionResult
import com.tom.rv2ide.lsp.models.DefinitionParams
import com.tom.rv2ide.lsp.models.DefinitionResult
import com.tom.rv2ide.lsp.models.DiagnosticResult
import com.tom.rv2ide.lsp.models.ExpandSelectionParams
import com.tom.rv2ide.lsp.models.LSPFailure
import com.tom.rv2ide.lsp.models.MarkupContent
import com.tom.rv2ide.lsp.models.ReferenceParams
import com.tom.rv2ide.lsp.models.ReferenceResult
import com.tom.rv2ide.lsp.models.SignatureHelp
import com.tom.rv2ide.lsp.models.SignatureHelpParams
import com.tom.rv2ide.models.Range
import com.tom.rv2ide.projects.IWorkspace
import com.tom.rv2ide.tasks.runOnUiThread
import com.tom.rv2ide.utils.Environment
import com.tom.rv2ide.utils.flashError
import java.io.File
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.slf4j.LoggerFactory

/**
 * 以「外部进程 + stdio JSON-RPC」方式运行的语言服务器基类（TS / Dart 共用）。
 *
 * <p><b>为什么单独做基类而不是复制两份</b>：两个服务器除「启动命令 / 文件类型 /
 * 工作目录」外完全同构（进程生命周期、initialize 握手、文档同步、请求转换、
 * 诊断分发），协议层已抽到 [LspStdioClient] / [LspConverters] / [LspDocumentSync]，
 * 这里只留差异点作为抽象成员。
 *
 * <p><b>懒启动</b>：构造时不启动进程，首次遇到本服务器支持的文件（打开/编辑）或
 * 首次请求时才启动。注册了服务器但用户从不打开 TS/Dart 文件时，零开销。
 *
 * <p><b>线程模型</b>：
 * - 文档通知（didOpen/didChange/...）走单线程 [ioExecutor]，保证发送顺序与编辑器
 *   事件顺序一致；didChange 的乱序会让服务器状态与磁盘内容静默漂移。
 * - 请求（completion/hover/definition）在调用线程同步等待（接口本身就是同步的），
 *   发送前通过 [flushIoQueue] 让已排队的 didChange 先落地，保证「请求基于最新文本」。
 * - 诊断通知在 reader 线程解析后切主线程交给 [ILanguageClient]。
 */
abstract class ProcessLanguageServer : ILanguageServer {

  companion object {
    /** initialize 握手超时。TSLS 冷启动要加载 tsconfig，给足余量。 */
    private const val INITIALIZE_TIMEOUT_MS = 20_000L

    /** 补全超时。超过说明服务器异常，返回空比卡住编辑器好。 */
    private const val COMPLETION_TIMEOUT_MS = 4_000L

    private const val DEFINITION_TIMEOUT_MS = 5_000L
    private const val HOVER_TIMEOUT_MS = 5_000L
    private const val SHUTDOWN_TIMEOUT_MS = 1_500L
  }

  protected val log = LoggerFactory.getLogger(javaClass)

  /** 按文件给出 LSP languageId（如 "typescript" / "typescriptreact" / "dart"）。 */
  protected abstract fun languageIdFor(file: Path): String

  /** 该服务器处理的文件扩展名（小写、不带点）。 */
  protected abstract val fileExtensions: Set<String>

  /** 服务器不可用时展示给用户的一句话（含获取方式）。 */
  protected abstract val unavailableMessage: String

  /**
   * 构造启动命令。返回 null 表示服务器不可用（可执行文件 / 脚本 / SDK 缺失），
   * 由调用方给出 [unavailableMessage] 提示。
   *
   * @param fallbackFile 触发启动的文件（用于向上查找 node_modules 等），可能为 null。
   */
  protected abstract fun createCommand(fallbackFile: Path?): List<String>?

  /** 进程工作目录。决定 tsserver / pub 等对项目根的解析，默认取项目根。 */
  protected open fun workingDirectory(fallbackFile: Path?): File =
      projectRootDirOrNull() ?: fallbackFile?.parent?.toFile() ?: Environment.HOME

  @Volatile private var clientRef: ILanguageClient? = null

  override val client: ILanguageClient?
    get() = clientRef

  private var process: Process? = null
  private var stdio: LspStdioClient? = null
  private var documentSync: LspDocumentSync? = null

  /** 启动 / 初始化互斥：多线程（事件线程与请求线程）可能同时触发启动。 */
  private val startLock = Any()

  @Volatile private var initialized = false

  /** shutdown() 已调用。在途的编辑器事件/请求不得再拉起新进程（否则退出时
   *  会出现「刚关掉又启动」的进程泄漏）。 */
  @Volatile private var shutdownRequested = false

  @Volatile private var projectRootDir: File? = null

  /** 只提示一次「服务器不可用」，避免每次补全都弹。
   *  注意不重置：用户装好服务器后需重开项目（进程重启），提示不会重复刷屏。 */
  private val unavailableNotified = AtomicBoolean(false)

  private val ioExecutor = Executors.newSingleThreadExecutor { r ->
    Thread(r, "lsp-io").apply { isDaemon = true }
  }

  init {
    if (!EventBus.getDefault().isRegistered(this)) {
      EventBus.getDefault().register(this)
    }
  }

  /** 项目根目录（[setupWorkspace] 记录），未初始化时为 null。 */
  protected fun projectRootDirOrNull(): File? = projectRootDir

  /** 该文件是否由本服务器处理。 */
  protected fun isSupported(file: Path): Boolean {
    val name = file.fileName?.toString() ?: return false
    val dot = name.lastIndexOf('.')
    if (dot < 0) {
      return false
    }
    return name.substring(dot + 1).lowercase() in fileExtensions
  }

  // ------------------------------------------------------------------
  // ILanguageServer 生命周期
  // ------------------------------------------------------------------

  override fun connectClient(client: ILanguageClient?) {
    clientRef = client
  }

  override fun applySettings(settings: IServerSettings?) {
    // 外部进程服务器自行管理设置（读取项目内的 tsconfig / analysis_options）
  }

  override fun setupWorkspace(workspace: IWorkspace) {
    projectRootDir = workspace.getProjectDir()
  }

  override fun shutdown() {
    if (EventBus.getDefault().isRegistered(this)) {
      EventBus.getDefault().unregister(this)
    }
    shutdownRequested = true
    synchronized(startLock) {
      val stdio = this.stdio
      if (stdio != null && !stdio.isBroken()) {
        try {
          stdio.request("shutdown", null, SHUTDOWN_TIMEOUT_MS)
          stdio.notify("exit", null)
        } catch (e: Exception) {
          log.warn("[{}] graceful shutdown failed: {}", serverId, e.message)
        }
      }
      destroyProcess()
      initialized = false
    }
    ioExecutor.shutdown()
  }

  // ------------------------------------------------------------------
  // 编辑器事件 → 文档同步
  // ------------------------------------------------------------------

  @Subscribe(threadMode = ThreadMode.ASYNC)
  fun onDocumentOpen(event: DocumentOpenEvent) {
    if (!isSupported(event.openedFile)) {
      return
    }
    submitIo {
      if (ensureStarted(event.openedFile) != null) {
        documentSync?.didOpen(event.openedFile, event.text)
      }
    }
  }

  @Subscribe(threadMode = ThreadMode.ASYNC)
  fun onDocumentChange(event: DocumentChangeEvent) {
    if (!isSupported(event.changedFile)) {
      return
    }
    submitIo {
      if (ensureStarted(event.changedFile) != null) {
        documentSync?.didChange(event)
      }
    }
  }

  @Subscribe(threadMode = ThreadMode.ASYNC)
  fun onDocumentClose(event: DocumentCloseEvent) {
    if (!isSupported(event.closedFile)) {
      return
    }
    submitIo { documentSync?.didClose(event) }
  }

  @Subscribe(threadMode = ThreadMode.ASYNC)
  fun onDocumentSave(event: DocumentSaveEvent) {
    if (!isSupported(event.savedFile)) {
      return
    }
    submitIo { documentSync?.didSave(event) }
  }

  /**
   * 投递到 ioExecutor。
   *
   * <p>shutdown 后（编辑器销毁与在途事件竞态）executor 会拒绝任务——
   * `RejectedExecutionException` 必须吞掉：这是正常关闭路径，抛出去只会污染
   * EventBus 的异常日志。
   */
  private fun submitIo(block: () -> Unit) {
    if (shutdownRequested) {
      return
    }
    try {
      ioExecutor.execute(block)
    } catch (e: java.util.concurrent.RejectedExecutionException) {
      log.debug("[{}] io executor rejected task (shutting down)", serverId)
    }
  }

  // ------------------------------------------------------------------
  // 请求
  // ------------------------------------------------------------------

  override fun complete(params: CompletionParams?): CompletionResult {
    params ?: return CompletionResult.EMPTY
    if (!isSupported(params.file)) {
      return CompletionResult.EMPTY
    }
    if (params.cancelChecker.isCancelled()) {
      return CompletionResult.EMPTY
    }
    val stdio = ensureStarted(params.file) ?: return CompletionResult.EMPTY

    // 编辑器当前内容可能领先于事件队列（刚敲的字符还没触发 didChange），
    // 先把它刷进服务器再请求，避免补全基于上一版文本。
    syncContent(params.file, params.content)

    val request =
        JsonObject().apply {
          add("textDocument", textDocumentIdentifier(params.file))
          add("position", LspConverters.writePosition(params.position))
          add("context", JsonObject().apply { addProperty("triggerKind", 1) })
        }
    val response = stdio.request("textDocument/completion", request, COMPLETION_TIMEOUT_MS)
    if (params.cancelChecker.isCancelled()) {
      return CompletionResult.EMPTY
    }
    return LspConverters.parseCompletionResult(response)
  }

  override suspend fun findDefinition(params: DefinitionParams): DefinitionResult {
    if (!isSupported(params.file)) {
      return DefinitionResult(emptyList())
    }
    val stdio = ensureStarted(params.file) ?: return DefinitionResult(emptyList())
    val request =
        JsonObject().apply {
          add("textDocument", textDocumentIdentifier(params.file))
          add("position", LspConverters.writePosition(params.position))
        }
    val response = stdio.request("textDocument/definition", request, DEFINITION_TIMEOUT_MS)
    return DefinitionResult(LspConverters.parseDefinitionResult(response))
  }

  override suspend fun hover(params: DefinitionParams): MarkupContent {
    if (!isSupported(params.file)) {
      return MarkupContent()
    }
    val stdio = ensureStarted(params.file) ?: return MarkupContent()
    val request =
        JsonObject().apply {
          add("textDocument", textDocumentIdentifier(params.file))
          add("position", LspConverters.writePosition(params.position))
        }
    val response = stdio.request("textDocument/hover", request, HOVER_TIMEOUT_MS)
    return LspConverters.parseHoverResult(response)
  }

  override suspend fun findReferences(params: ReferenceParams): ReferenceResult {
    return ReferenceResult(emptyList())
  }

  override suspend fun expandSelection(params: ExpandSelectionParams): Range {
    return params.selection
  }

  override suspend fun signatureHelp(params: SignatureHelpParams): SignatureHelp {
    return SignatureHelp(emptyList(), -1, -1)
  }

  override suspend fun analyze(file: Path): DiagnosticResult {
    // 诊断由服务器主动推送（textDocument/publishDiagnostics），无需拉取
    return DiagnosticResult.NO_UPDATE
  }

  override fun handleFailure(failure: LSPFailure?): Boolean {
    log.error("[{}] LSP failure: type={}, error={}", serverId, failure?.type, failure?.error?.message)
    return false
  }

  // ------------------------------------------------------------------
  // 进程管理
  // ------------------------------------------------------------------

  /**
   * 确保进程已启动并完成 initialize 握手。
   *
   * @return 可用的客户端；服务器不可用（缺依赖）或启动失败时返回 null。
   */
  private fun ensureStarted(fallbackFile: Path?): LspStdioClient? {
    synchronized(startLock) {
      if (shutdownRequested) {
        return null
      }
      val existing = stdio
      // isBroken：reader 线程退出 / 写失败 / 已关闭——进程崩溃后必须重建，
      // 只看 initialized 会让编辑器永远对着一个死进程发请求。
      if (existing != null && initialized && !existing.isBroken()) {
        return existing
      }
      if (existing != null || process?.isAlive == true) {
        log.info("[{}] restarting language server (previous instance broken)", serverId)
        destroyProcess()
      }

      val command =
          try {
            createCommand(fallbackFile)
          } catch (e: Exception) {
            log.error("[{}] failed to build command", serverId, e)
            null
          }
      if (command == null) {
        notifyUnavailable()
        return null
      }

      val wd = workingDirectory(fallbackFile)
      if (!wd.isDirectory) {
        wd.mkdirs()
      }

      return try {
        val proc =
            ProcessBuilder(command)
                .apply {
                  directory(wd)
                  redirectErrorStream(false)
                  configureEnvironment(this, command)
                }
                .start()
        process = proc
        val stdioClient =
            LspStdioClient(
                proc.inputStream,
                proc.outputStream,
                ::handleNotification,
                ::handleServerRequest,
            )
        stdio = stdioClient
        documentSync = LspDocumentSync(stdioClient).also { it.languageIdFor = ::languageIdFor }
        startStderrReader(proc)

        if (!sendInitialize(stdioClient, wd)) {
          log.error("[{}] initialize handshake failed", serverId)
          destroyProcess()
          return null
        }
        initialized = true
        log.info("[{}] language server started: {}", serverId, command.joinToString(" "))
        stdioClient
      } catch (e: Exception) {
        log.error("[{}] failed to start language server", serverId, e)
        destroyProcess()
        null
      }
    }
  }

  private fun sendInitialize(stdio: LspStdioClient, rootDir: File): Boolean {
    val rootUri = rootDir.toURI().toString()
    val params =
        JsonObject().apply {
          addProperty("processId", android.os.Process.myPid())
          addProperty("rootUri", rootUri)
          add(
              "workspaceFolders",
              JsonArray().apply {
                add(
                    JsonObject().apply {
                      addProperty("uri", rootUri)
                      addProperty("name", rootDir.name)
                    })
              },
          )
          add("capabilities", clientCapabilities())
          add(
              "clientInfo",
              JsonObject().apply {
                addProperty("name", "AndroidCodeStudio")
                addProperty("version", "1.0")
              },
          )
        }
    val response = stdio.request("initialize", params, INITIALIZE_TIMEOUT_MS) ?: return false
    if (response.isErrorResponse()) {
      log.error("[{}] initialize error: {}", serverId, response.errorMessage())
      return false
    }
    stdio.notify("initialized", JsonObject())
    return true
  }

  /**
   * 客户端能力声明。
   *
   * <p>刻意**不**声明 `workspace.configuration` 与 `window.workDoneProgress`：
   * 声明后 TSLS / Dart 服务器会反过来发请求要配置或进度令牌，而我们没有实现这些
   * 交互——不声明时服务器走默认行为，链路更简单。
   */
  private fun clientCapabilities(): JsonObject =
      JsonObject().apply {
        add(
            "workspace",
            JsonObject().apply {
              // LSP 规范：workspaceFolders 是 **bool**（不是 {supported: true}——
              // 那是 dynamicRegistration 类能力的形状）。写成对象会被 Dart 分析
              // 服务器以 -32602 Invalid params 拒绝，TSLS 宽容地忽略它。
              // 实测错误原文："params.capabilities.workspace.workspaceFolders
              // must be of type bool"。
              addProperty("workspaceFolders", true)
            },
        )
        add(
            "textDocument",
            JsonObject().apply {
              add(
                  "synchronization",
                  JsonObject().apply {
                    addProperty("dynamicRegistration", false)
                    addProperty("didSave", true)
                  },
              )
              add(
                  "completion",
                  JsonObject().apply {
                    add(
                        "completionItem",
                        JsonObject().apply {
                          addProperty("snippetSupport", true)
                          addProperty("deprecatedSupport", true)
                          add(
                              "documentationFormat",
                              JsonArray().apply {
                                add("markdown")
                                add("plaintext")
                              },
                          )
                        },
                    )
                    addProperty("contextSupport", true)
                  },
              )
              add(
                  "hover",
                  JsonObject().apply {
                    add(
                        "contentFormat",
                        JsonArray().apply {
                          add("markdown")
                          add("plaintext")
                        },
                    )
                  },
              )
              add("definition", JsonObject().apply { addProperty("linkSupport", true) })
              add(
                  "publishDiagnostics",
                  JsonObject().apply { addProperty("relatedInformation", false) },
              )
            },
        )
      }

  /**
   * 注入 Termux 环境。
   *
   * <p>Android 应用的进程环境里没有 Termux 的 PATH/HOME：node 启动后如果调用
   * 子进程（TSLS 会 spawn tsserver）、或 Dart 需要 HOME 定位缓存目录时会失败。
   * 这里显式注入，与 CodeGraphInstaller 的 wrapper 同一套变量。
   */
  private fun configureEnvironment(pb: ProcessBuilder, command: List<String>) {
    val env = pb.environment()
    val prefix = Environment.PREFIX
    val home = Environment.HOME

    val pathParts = mutableListOf<String>()
    File(command[0]).parentFile?.absolutePath?.let { pathParts.add(it) }
    if (prefix != null) {
      pathParts.add(File(prefix, "bin").absolutePath)
    }
    env["PATH"]?.let { pathParts.add(it) }
    env["PATH"] = pathParts.joinToString(":")

    if (home != null) {
      env["HOME"] = home.absolutePath
    }
    if (prefix != null) {
      env["TMPDIR"] = File(prefix, "tmp").absolutePath
      // Termux 的 node/dart 及其动态库在 $PREFIX/lib（libicu、libsqlite 等）
      env["LD_LIBRARY_PATH"] = File(prefix, "lib").absolutePath
    }
  }

  private fun startStderrReader(proc: Process) {
    Thread(
            {
              try {
                proc.errorStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                  lines.forEach { line ->
                    if (line.isNotBlank()) {
                      log.info("[{}] {}", serverId, line)
                    }
                  }
                }
              } catch (e: Exception) {
                log.debug("[{}] stderr reader stopped: {}", serverId, e.message)
              }
            },
            "lsp-stderr",
        )
        .apply { isDaemon = true }
        .start()
  }

  private fun destroyProcess() {
    try {
      stdio?.close()
    } catch (e: Exception) {
      // ignore
    }
    try {
      process?.destroy()
    } catch (e: Exception) {
      // ignore
    }
    stdio = null
    process = null
    documentSync = null
  }

  private fun notifyUnavailable() {
    if (!unavailableNotified.compareAndSet(false, true)) {
      return
    }
    log.error("[{}] {}", serverId, unavailableMessage)
    runOnUiThread { flashError(unavailableMessage) }
  }

  // ------------------------------------------------------------------
  // 服务器 → 客户端
  // ------------------------------------------------------------------

  private fun handleNotification(obj: JsonObject) {
    when (obj.get("method")?.asString) {
      "textDocument/publishDiagnostics" -> {
        val result = LspConverters.parseDiagnostics(obj.getAsJsonObject("params")) ?: return
        // 诊断更新要改编辑器 UI（squiggle + 问题面板），必须切主线程
        runOnUiThread { clientRef?.publishDiagnostics(result) }
      }
      "window/logMessage" -> {
        val message = obj.getAsJsonObject("params")?.get("message")?.asString
        if (!message.isNullOrBlank()) {
          log.info("[{}] {}", serverId, message)
        }
      }
      "window/showMessage" -> {
        val params = obj.getAsJsonObject("params")
        val message = params?.get("message")?.asString
        val type = params?.get("type")?.asInt ?: 3
        if (!message.isNullOrBlank()) {
          log.info("[{}] showMessage({}): {}", serverId, type, message)
          // type=1 是 Error（如「找不到工作区的 typescript 依赖」）——这类信息
          // 对用户是有效引导，弹出来比埋在日志里有价值
          if (type == 1) {
            runOnUiThread { flashError(message) }
          }
        }
      }
      "$/progress" -> {
        // 未声明 workDoneProgress 能力，正常不会收到；忽略
      }
      else -> log.debug("[{}] unhandled notification: {}", serverId, obj.get("method"))
    }
  }

  /**
   * 处理服务器 → 客户端请求。
   *
   * @return JsonNull 表示「成功但无结果」；返回 Kotlin null 则回 MethodNotFound
   *   （见 [LspStdioClient] 的约定）。
   */
  private fun handleServerRequest(method: String, params: JsonObject?): JsonElement? {
    return when (method) {
      "workspace/configuration" -> {
        val count = params?.getAsJsonArray("items")?.size() ?: 0
        JsonArray().apply { repeat(count) { add(JsonNull.INSTANCE) } }
      }
      "window/workDoneProgress/create", "client/registerCapability" -> JsonNull.INSTANCE
      "workspace/workspaceFolders" -> {
        val root = projectRootDirOrNull()
        if (root == null) {
          JsonNull.INSTANCE
        } else {
          JsonArray().apply {
            add(
                JsonObject().apply {
                  addProperty("uri", root.toURI().toString())
                  addProperty("name", root.name)
                })
          }
        }
      }
      else -> {
        log.debug("[{}] unsupported server request: {}", serverId, method)
        null
      }
    }
  }

  // ------------------------------------------------------------------
  // 内容同步
  // ------------------------------------------------------------------

  /** 用编辑器当前内容刷新服务器（如有）；并等待队列中已排队的通知先落地。 */
  private fun syncContent(path: Path, content: CharSequence?) {
    if (content == null || content.isEmpty()) {
      return
    }
    val text = content.toString()
    ioExecutor.execute {
      val sync = documentSync ?: return@execute
      if (sync.isOpen(path)) {
        sync.didChange(path, text)
      } else {
        sync.didOpen(path, text)
      }
    }
    flushIoQueue()
  }

  /** 等待 ioExecutor 队列排空（提交一个空任务并等它执行完）。 */
  private fun flushIoQueue() {
    try {
      ioExecutor.submit {}.get(2, java.util.concurrent.TimeUnit.SECONDS)
    } catch (e: Exception) {
      log.debug("[{}] flush io queue timed out: {}", serverId, e.message)
    }
  }

  private fun textDocumentIdentifier(path: Path): JsonObject =
      JsonObject().apply { addProperty("uri", LspConverters.pathToUri(path)) }
}
