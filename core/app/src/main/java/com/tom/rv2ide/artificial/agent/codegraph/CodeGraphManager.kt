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

package com.tom.rv2ide.artificial.agent.codegraph

import android.content.Context
import com.termux.shared.termux.TermuxConstants
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * CodeGraph 的下载、安装、执行与索引管理。
 *
 * <p><b>为什么用 wrapper 而不是自己拼环境</b>：AI 的 `shell_execute` 走 `/system/bin/sh -c`，
 * 不设置任何环境变量。所有环境（PATH / LD_LIBRARY_PATH / 两个 CODEGRAPH_* 开关）都封在
 * [CodeGraphInstaller.WRAPPER_NAME] 里，因此本类与 AI 都只需调用那一个脚本——不会出现
 * 「界面里能跑、AI 跑不了」这种两套环境不一致的问题。
 *
 * <p><b>执行方式</b>：直接用 [ProcessBuilder] 调 wrapper，而不是走 Termux 的 shell 服务。
 * 原因有二：一是 wrapper 自带全部环境，不依赖 Termux 的会话；二是 shell 服务是异步的
 * （结果写文件再轮询），而这里需要同步拿输出。ACS 的 `targetSdk=28`，不受 Android 10
 * 「禁止执行应用目录内文件」的限制，因此可执行。
 */
class CodeGraphManager(private val context: Context) {

  /** 下载与解包进度。 */
  interface ProgressListener {
    /** @param message 当前阶段的人类可读描述 */
    fun onStage(message: String)

    /** @param fraction 0..1；无法估算时传 null（例如 tar 解包没有总条目数） */
    fun onProgress(fraction: Float?)
  }

  /** 一次命令执行的结果。 */
  data class CommandResult(
      val exitCode: Int,
      val stdout: String,
      val stderr: String,
      val timedOut: Boolean,
  ) {
    val ok: Boolean get() = exitCode == 0 && !timedOut

    /** 供界面/AI 展示的组合输出。 */
    fun combined(): String =
        when {
          timedOut -> "命令超时。\n$stdout\n$stderr".trim()
          else -> (stdout + "\n" + stderr).trim()
        }
  }

  /** 当前状态，供管理界面渲染。 */
  data class Status(
      val installed: Boolean,
      val nodeInstalled: Boolean,
      val version: String?,
      val sizeBytes: Long,
      val missingPackages: List<String>,
      val indexedProjectCount: Int,
  )

  private val client: OkHttpClient by lazy {
    OkHttpClient.Builder()
        // 大文件下载：不设整体超时（16.8MB 在慢网络下会超过默认 10s），
        // 只靠读超时兜底——卡住不动的连接仍会被中断。
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
  }

  /** 查询状态。会在 IO 线程执行（可能读目录、跑 `--version`）。 */
  suspend fun status(): Status =
      withContext(Dispatchers.IO) {
        val installed = CodeGraphInstaller.isInstalled()
        val nodeInstalled = CodeGraphInstaller.nodeBinary().isFile
        val version = if (installed) run(listOf("--version"), timeoutMs = VERSION_TIMEOUT_MS).stdout.trim().ifBlank { null } else null
        val projectsRoot = File(TermuxConstants.TERMUX_HOME_DIR_PATH, "ACSProjects")
        Status(
            installed = installed,
            nodeInstalled = nodeInstalled,
            version = version,
            sizeBytes = if (installed) CodeGraphInstaller.installedSizeBytes() else 0L,
            missingPackages = CodeGraphInstaller.missingPackages(),
            indexedProjectCount = CodeGraphInstaller.indexedProjects(projectsRoot).size,
        )
      }

  /**
   * 完整安装：下载 → 解包 → 写 wrapper。
   *
   * <p><b>不包含 Termux 包的安装</b>（nodejs 及其依赖）：那需要 `apt`/`dpkg`，而设备出网
   * 可能被代理阻断（实测设备走 VPN 时 Termux 无法访问仓库）。调用方应先查
   * [CodeGraphInstaller.missingPackages]，缺包时走 apt 或引导用户，见 [installTermuxPackages]。
   */
  suspend fun install(
      listener: ProgressListener? = null,
      packageUrl: String = DEFAULT_PACKAGE_URL,
  ): Result<Unit> =
      withContext(Dispatchers.IO) {
        try {
          val missing = CodeGraphInstaller.missingPackages()
          if (missing.isNotEmpty()) {
            return@withContext Result.failure(
                IllegalStateException("缺少运行依赖：${missing.joinToString()}。请先在终端安装。"))
          }

          listener?.onStage("下载 CodeGraph…")
          val archive = File(context.cacheDir, "codegraph-download.tgz")
          download(packageUrl, archive) { fraction -> listener?.onProgress(fraction) }

          listener?.onStage("解包…")
          CodeGraphInstaller.extract(archive) { }
          archive.delete()

          listener?.onStage("写入启动脚本…")
          CodeGraphInstaller.writeWrapper()

          listener?.onStage("安装完成")
          listener?.onProgress(1f)
          Result.success(Unit)
        } catch (e: Exception) {
          Result.failure(e)
        }
      }

  /**
   * 安装缺失的 Termux 包。
   *
   * <p>走 `apt-get install -y`。设备出网被拦时会失败——那时错误信息里会有
   * `connection timed out`，调用方据此提示用户「先在终端里装好」而不是笼统报「安装失败」。
   */
  suspend fun installTermuxPackages(packages: List<String>): CommandResult =
      withContext(Dispatchers.IO) {
        if (packages.isEmpty()) {
          return@withContext CommandResult(0, "", "", false)
        }
        val script =
            "export PREFIX=${TermuxConstants.TERMUX_PREFIX_DIR_PATH}; " +
                "export PATH=\"\$PREFIX/bin:\$PATH\"; " +
                "export LD_LIBRARY_PATH=\"\$PREFIX/lib\"; " +
                "apt-get install -y ${packages.joinToString(" ")}"
        execRaw(script, null, APT_TIMEOUT_MS)
      }

  /**
   * 确保项目已建索引；未建则 `init`。
   *
   * @return 是否新建了索引（false 表示已存在，无需动作）
   */
  suspend fun ensureIndex(projectDir: File): CommandResult =
      withContext(Dispatchers.IO) {
        if (!CodeGraphInstaller.isInstalled()) {
          return@withContext CommandResult(1, "", "CodeGraph 未安装", false)
        }
        if (File(projectDir, CodeGraphInstaller.INDEX_DIR_NAME).isDirectory) {
          return@withContext CommandResult(0, "索引已存在", "", false)
        }
        // 索引是派生数据，先确保它不会被提交进版本库。
        CodeGraphInstaller.ensureGitIgnore(projectDir)
        run(listOf("init", "."), cwd = projectDir, timeoutMs = INIT_TIMEOUT_MS)
      }

  /** 增量更新索引。项目没有索引时退化为 `init`。 */
  suspend fun syncIndex(projectDir: File): CommandResult =
      withContext(Dispatchers.IO) {
        if (!CodeGraphInstaller.isInstalled()) {
          return@withContext CommandResult(1, "", "CodeGraph 未安装", false)
        }
        if (!File(projectDir, CodeGraphInstaller.INDEX_DIR_NAME).isDirectory) {
          return@withContext ensureIndex(projectDir)
        }
        run(listOf("sync", "."), cwd = projectDir, timeoutMs = SYNC_TIMEOUT_MS)
      }

  /**
   * 执行一次 codegraph 命令。
   *
   * @param args 传给 wrapper 的参数（不含脚本名本身）
   * @param cwd 工作目录；null 表示用安装目录
   */
  suspend fun run(
      args: List<String>,
      cwd: File? = null,
      timeoutMs: Long = QUERY_TIMEOUT_MS,
  ): CommandResult =
      withContext(Dispatchers.IO) {
        val wrapper = CodeGraphInstaller.wrapperFile()
        if (!wrapper.isFile) {
          return@withContext CommandResult(1, "", "CodeGraph 未安装", false)
        }
        execRaw(commandFor(wrapper, args), cwd ?: CodeGraphInstaller.installDir(), timeoutMs)
      }

  /** 拼命令行。参数逐个引号包裹，避免含空格的项目名被拆开。 */
  private fun commandFor(wrapper: File, args: List<String>): String {
    val quoted = args.joinToString(" ") { "\"" + it.replace("\"", "\\\"") + "\"" }
    return "\"${wrapper.absolutePath}\" $quoted"
  }

  /** 用 `/system/bin/sh -c` 执行一条命令并同步取回结果。 */
  private fun execRaw(command: String, cwd: File?, timeoutMs: Long): CommandResult {
    val process =
        try {
          ProcessBuilder("/system/bin/sh", "-c", command)
              .apply {
                directory(cwd ?: CodeGraphInstaller.installDir())
                // 合并 stderr 到 stdout：codegraph 的错误有时只走 stderr，
                // 分开读会漏掉真正的原因。
                redirectErrorStream(true)
              }
              .start()
        } catch (e: Exception) {
          return CommandResult(-1, "", e.message ?: "无法启动进程", false)
        }

    val output = StringBuilder()
    val reader = process.inputStream.bufferedReader()
    val readerThread =
        Thread {
          try {
            reader.forEachLine { output.append(it).append('\n') }
          } catch (e: Exception) {
            // 进程被强杀时读会抛，忽略——下面的 exitCode 已经表达了失败。
          }
        }
    readerThread.start()

    val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
    if (!finished) {
      process.destroyForcibly()
      readerThread.join(READER_JOIN_MS)
      return CommandResult(-1, output.toString(), "", true)
    }
    readerThread.join(READER_JOIN_MS)
    return CommandResult(process.exitValue(), output.toString(), "", false)
  }

  /** 下载到指定文件。失败时删掉半成品，避免下次被当成完整包解包。 */
  private fun download(url: String, target: File, onProgress: (Float) -> Unit) {
    val request = Request.Builder().url(url).get().build()
    client.newCall(request).execute().use { response ->
      if (!response.isSuccessful) {
        throw java.io.IOException("下载失败：HTTP ${response.code}")
      }
      val body = response.body ?: throw java.io.IOException("响应体为空")
      val total = body.contentLength()
      target.outputStream().use { out ->
        body.byteStream().use { input ->
          val buffer = ByteArray(64 * 1024)
          var read: Int
          var done = 0L
          while (input.read(buffer).also { read = it } != -1) {
            out.write(buffer, 0, read)
            done += read
            if (total > 0) {
              onProgress((done.toFloat() / total).coerceIn(0f, 1f))
            }
          }
        }
      }
      if (target.length() == 0L) {
        target.delete()
        throw java.io.IOException("下载的文件为空")
      }
    }
  }

  companion object {
    /**
     * 包下载地址。
     *
     * <p>用**精简包**（去掉自带 node 与 Rust kernel，仅 JS + WASM）：压缩后约 16.8MB、
     * 解压后约 134MB。官方完整包是 62MB / 284MB，多出的部分在 Android 上加载不了。
     */
    const val DEFAULT_PACKAGE_URL =
        "https://github.com/706412584/android-code-studio/releases/download/codegraph-v1.6.0/codegraph-android-slim-1.6.0.tgz"

    /** 各操作超时。实测参考：300 文件 init 4s、sync 2s、query 1.3s，余量取 10 倍以上。 */
    private const val VERSION_TIMEOUT_MS = 30_000L
    private const val QUERY_TIMEOUT_MS = 60_000L
    private const val SYNC_TIMEOUT_MS = 180_000L
    private const val INIT_TIMEOUT_MS = 600_000L
    private const val APT_TIMEOUT_MS = 600_000L
    private const val READER_JOIN_MS = 2_000L
  }
}
