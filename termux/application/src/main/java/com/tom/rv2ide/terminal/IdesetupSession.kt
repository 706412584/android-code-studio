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

package com.tom.rv2ide.terminal

import android.content.Context
import com.termux.shared.file.FileUtils
import com.termux.shared.shell.command.ExecutionCommand
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession
import com.termux.terminal.TerminalSession
import com.tom.rv2ide.app.configuration.CpuArch
import com.tom.rv2ide.app.configuration.IDEBuildConfigProvider
import com.tom.rv2ide.managers.ToolsManager
import com.tom.rv2ide.utils.Environment
import java.io.File
import java.io.FileOutputStream
import org.slf4j.LoggerFactory

/**
 * [TermuxSession] implementation that is used to run the `idesetup` script during automatic
 * installation.
 *
 * @author Akash Yadav
 */
class IdesetupSession
private constructor(
    terminalSession: TerminalSession,
    executionCommand: ExecutionCommand,
    termuxSessionClient: TermuxSessionClient?,
    setStdoutOnExit: Boolean,
    private val script: File,
) : TermuxSession(terminalSession, executionCommand, termuxSessionClient, setStdoutOnExit) {

  companion object {

    private val log = LoggerFactory.getLogger(IdesetupSession::class.java)

    @JvmStatic
    fun wrap(session: TermuxSession?, script: File): IdesetupSession? {
      return session?.let { IdesetupSession(it, script) }
    }

    @JvmStatic
    fun createScript(context: Context): File? {
      // Create temp file with proper executable name
      val tempDir = File(context.filesDir, "temp")
      if (!tempDir.exists()) {
        tempDir.mkdirs()
      }
      val script = File(tempDir, "idesetup")

      // write script contents
      if (!writeIdesetupScript(context, script)) {
        return null
      }

      // make it readable and executable
      FileUtils.setFilePermissions("idesetupScript", script.absolutePath, "rwx")

      return script
    }

    private fun writeIdesetupScript(context: Context, script: File): Boolean {
      return try {
        val cpuArch = IDEBuildConfigProvider.getInstance().cpuArch
        val folderName =
            when (cpuArch) {
              com.tom.rv2ide.app.configuration.CpuArch.AARCH64 -> "arm64"
              com.tom.rv2ide.app.configuration.CpuArch.ARM -> "arm"
              com.tom.rv2ide.app.configuration.CpuArch.X86_64 -> "x86_64"
              com.tom.rv2ide.app.configuration.CpuArch.X86 -> "x86"
            }
        context.assets.open(ToolsManager.getCommonAsset("${folderName}/idesetup")).use { inputStream
          ->
          FileOutputStream(script).use { outputStream -> inputStream.copyTo(outputStream) }
        }

        // 同步新版 idesetup 到 $PREFIX/bin（覆盖 apt 包 androidide-tools 内的旧版）。
        //
        // temp/idesetup 是 wrapper：内部 `exec idesetup` 走 PATH，命中
        // $PREFIX/bin/idesetup——那是 apt 包 androidide-tools_1.1-idetools-0 装的
        // **旧版**（32-bit ARM、不支持 --ndk/--manifest 等新参数），64 位设备上
        // 首装必报 `Invalid option: --ndk` 卡死（真机实测）。
        // 这里把 assets 内同架构的新版覆盖过去，保证 wrapper 执行到正确版本。
        syncIdesetupToPrefix(context, folderName)
        true
      } catch (e: Exception) {
        log.error("Failed to write idesetup script: {}", e.message, e)
        false
      }
    }

    /** 把 assets 的 idesetup（按 CPU 架构）覆盖到 `$PREFIX/bin/idesetup`。 */
    private fun syncIdesetupToPrefix(context: Context, folderName: String) {
      val prefixBin = File(Environment.PREFIX, "bin")
      if (!prefixBin.isDirectory) {
        // prefix 未初始化（bootstrap 还没装）——此时 apt 包也不存在，跳过。
        return
      }
      val target = File(prefixBin, "idesetup")
      try {
        context.assets.open(ToolsManager.getCommonAsset("${folderName}/idesetup")).use { inputStream
          ->
          FileOutputStream(target).use { outputStream -> inputStream.copyTo(outputStream) }
        }
        // 保留可执行位（FileOutputStream 创建的文件默认 rw-rw-rw-）
        target.setExecutable(true, false)
      } catch (e: Exception) {
        log.error("Failed to sync idesetup to prefix: {}", e.message, e)
      }
    }
  }

  private constructor(
      src: TermuxSession,
      script: File,
  ) : this(
      src.terminalSession,
      src.executionCommand,
      src.termuxSessionClient,
      src.isSetStdoutOnExit,
      script,
  )

  override fun finish() {
    super.finish()
    // Delete the temporary script file once the session is finished
    val error = FileUtils.deleteFile("idesetupScript", script.absolutePath, true)
    if (error != null) {
      log.error(error.errorLogString)
    }
  }
}
