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

package com.tom.rv2ide.lsp.typescript

import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment
import com.tom.rv2ide.app.IDEApplication
import com.tom.rv2ide.shell.executeProcessAsync
import com.tom.rv2ide.utils.Environment
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * TypeScript 语言服务器（TSLS）的**应用内一键安装**。
 *
 * <p><b>为什么装进项目而不是全局</b>：TSLS 会用工作区内的 `typescript` 包，装进项目
 * `node_modules` 才能与项目锁定的 TS 版本配套（与 CI/用户环境一致）。全局安装只有
 * 用户手动 `npm install -g` 时才有，不是本类负责的路径。
 *
 * <p><b>为什么走 npm 而不是下载 zip</b>：TSLS 是纯 JS，官方分发渠道就是 npm；且
 * 它依赖同工作区的 `typescript`，任何「下载预打包目录」的方案都要额外解决版本匹配。
 * 直接用内置 Termux 的 node/npm 在项目根跑 `npm install -D`，与用户手动执行的命令
 * 完全等价（README 与设置页给的也是这条命令）。
 *
 * <p>国内设备直连 npm 官方源常超时，因此固定走 npmmirror（项目内已验证的镜像）。
 */
object TypeScriptServerInstaller {

  /** TSLS 包名（与 [com.tom.rv2ide.lsp.typescript.TypeScriptLanguageServer] 的查找一致）。 */
  const val PACKAGE_NAME = "typescript-language-server"

  /** 入口脚本相对包目录的路径；用它判断「装好了」。 */
  private const val CLI_RELATIVE_PATH = "lib/cli.mjs"

  /** npm 镜像源（国内可达；与设置页提示的命令一致）。 */
  private const val NPM_REGISTRY = "https://registry.npmmirror.com"

  /** 安装超时：两个包 + 依赖，慢网络下给足 8 分钟。 */
  private const val INSTALL_TIMEOUT_MINUTES = 8L

  /** 项目内是否已安装（与 [TypeScriptLanguageServer] 的项目本地查找一致）。 */
  fun isInstalled(projectDir: File): Boolean =
      File(projectDir, "node_modules/$PACKAGE_NAME/$CLI_RELATIVE_PATH").isFile

  /** 全局（Termux 前缀）是否已安装。 */
  fun isInstalledGlobally(): Boolean {
    val prefix = Environment.PREFIX ?: return false
    return File(prefix, "lib/node_modules/$PACKAGE_NAME/$CLI_RELATIVE_PATH").isFile
  }

  /**
   * 在项目根执行 npm 安装。
   *
   * @param projectDir 项目根目录（Gradle 根，与 `package.json` 同级）
   * @return 成功时 `Result.success`；失败时携带 npm 输出的尾部，便于用户判断原因
   */
  suspend fun install(projectDir: File): Result<Unit> =
      withContext(Dispatchers.IO) {
        if (!projectDir.isDirectory) {
          return@withContext Result.failure(
              IOException("项目目录不存在：${projectDir.absolutePath}"))
        }
        val shell = Environment.BASH_SHELL
        if (shell == null || !shell.canExecute()) {
          return@withContext Result.failure(IOException("找不到可执行的内置 shell"))
        }

        val env = HashMap(TermuxShellEnvironment().getEnvironment(IDEApplication.instance, false))
        // Termux 环境已把 $PREFIX/bin 放进 PATH（node/npm 都在那），再补上系统 bin，
        // 避免 npm 内部调用 coreutils 时找不到。
        val prefixBin = Environment.PREFIX?.resolve("bin")?.absolutePath
        env["PATH"] =
            listOfNotNull(prefixBin, "/system/bin", "/system/xbin", env["PATH"]).joinToString(":")

        val cmd = "npm install -D $PACKAGE_NAME typescript --registry=$NPM_REGISTRY"
        try {
          val process = executeProcessAsync {
            command = listOf(shell.absolutePath, "-c", cmd)
            environment = env
            workingDirectory = projectDir
            // npm 把进度写到 stderr；合并后才能一起读出来判断失败原因。
            redirectErrorStream = true
          }

          // 必须边读边等：npm 输出量可能超过管道缓冲区，只 waitFor 会死锁。
          // 读放在后台线程，等待带超时，超时后强杀进程。
          val outputBuffer = StringBuilder()
          val reader =
              Thread {
                try {
                  process.inputStream.bufferedReader().use { br ->
                    val buf = CharArray(8192)
                    var n = br.read(buf)
                    while (n != -1) {
                      synchronized(outputBuffer) { outputBuffer.append(buf, 0, n) }
                      n = br.read(buf)
                    }
                  }
                } catch (_: Exception) {
                  // 进程被强杀时读到 IOException 属正常，忽略。
                }
              }
          reader.isDaemon = true
          reader.start()

          val finished = process.waitFor(INSTALL_TIMEOUT_MINUTES, TimeUnit.MINUTES)
          if (!finished) {
            process.destroyForcibly()
            return@withContext Result.failure(
                IOException("npm install 超时（$INSTALL_TIMEOUT_MINUTES 分钟）"))
          }
          reader.join(2000)
          val output = synchronized(outputBuffer) { outputBuffer.toString() }

          if (process.exitValue() == 0 && isInstalled(projectDir)) {
            Result.success(Unit)
          } else {
            val detail =
                output.trim().takeLast(1500).ifEmpty { "npm install 退出码 ${process.exitValue()}" }
            Result.failure(IOException(detail))
          }
        } catch (e: Exception) {
          Result.failure(e)
        }
      }
}
