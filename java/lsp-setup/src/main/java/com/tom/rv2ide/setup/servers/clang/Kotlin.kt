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
 *   along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
*/

package com.tom.rv2ide.setup.servers.kotlin

import android.content.Context
import com.tom.rv2ide.setup.servers.ILanguageServerInstaller
import com.tom.rv2ide.utils.Environment
import com.tom.rv2ide.utils.LspServerSource
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
 */

class Kotlin(private val context: Context) : ILanguageServerInstaller {
  
  companion object {
    private const val SERVER_ID = "Kotlin"
  }
  
  override fun isInstalled(): Boolean {
    val kotlinBinary = File(Environment.SERVERS_KOTLIN_DIR, "bin/kotlin-language-server")
    val atLeastOneJar = File(Environment.SERVERS_KOTLIN_DIR, "lib/kotlin-stdlib-2.1.0.jar")
    return kotlinBinary.exists() && atLeastOneJar.exists()
  }
  
  override fun install(onOutput: (String) -> Unit): Boolean {
    return try {
      onOutput("Fetching Kotlin language server information...")

      // Gitee 优先、GitHub 兜底；两处清单都不可达时返回 null。
      val entry =
          LspServerSource.fetch(SERVER_ID)
              ?: run {
                onOutput("\nError: 无法获取 Kotlin 语言服务器清单（Gitee 与 GitHub 均不可达）")
                onOutput("Server ID searched: $SERVER_ID")
                return false
              }
      val version = entry.version
      onOutput("Found Kotlin language server version: $version")

      val serversDir = File(Environment.HOME, "acs/servers")
      serversDir.mkdirs()

      val serverDir = File(serversDir, SERVER_ID.lowercase())
      serverDir.mkdirs()

      onOutput("\nConnecting to download server...")

      val candidates = LspServerSource.downloadCandidates(entry)
      onOutput("Download URL: ${candidates.first()}")

      val tempFile = File(serversDir, "temp_${SERVER_ID}.zip")

      onOutput("Downloading Kotlin language server...")
      var lastProgress = -1
      val downloaded =
          LspServerSource.download(candidates, tempFile) { read, total ->
            if (total > 0) {
              val progress = ((read * 100) / total).toInt()
              if (progress != lastProgress && progress % 10 == 0) {
                onOutput("Download progress: $progress%")
                lastProgress = progress
              }
            }
          }

      if (!downloaded) {
        onOutput("\nError: 下载失败（已尝试 ${candidates.size} 个源）")
        tempFile.delete()
        return false
      }

      if (!LspServerSource.verify(tempFile, entry.sha256)) {
        onOutput("\nError: 下载文件校验失败（sha256 不匹配），已删除")
        return false
      }

      onOutput("\nDownload completed!")
      onOutput("Extracting files...")
      
      val zipInputStream = ZipInputStream(tempFile.inputStream())
      var zipEntry = zipInputStream.nextEntry
      var extractedCount = 0
      
      while (zipEntry != null) {
        val file = File(serverDir, zipEntry.name)
        
        if (zipEntry.isDirectory) {
          file.mkdirs()
        } else {
          file.parentFile?.mkdirs()
          val fileOutputStream = FileOutputStream(file)
          val extractBuffer = ByteArray(8192)
          var extractLen: Int
          while (zipInputStream.read(extractBuffer).also { extractLen = it } > 0) {
            fileOutputStream.write(extractBuffer, 0, extractLen)
          }
          fileOutputStream.close()
          
          if (file.extension.isEmpty() || file.name.endsWith(".sh")) {
            file.setExecutable(true)
          }
          
          extractedCount++
          
          if (extractedCount % 50 == 0) {
            onOutput("Extracted $extractedCount files...")
          }
        }
        
        zipInputStream.closeEntry()
        zipEntry = zipInputStream.nextEntry
      }
      
      zipInputStream.close()
      tempFile.delete()
      
      onOutput("\nExtraction completed!")
      onOutput("Total files extracted: $extractedCount")
      
      onOutput("\nVerifying installation...")
      if (isInstalled()) {
        onOutput("Kotlin language server binaries found!")
        true
      } else {
        onOutput("Installation verification failed. Server binaries not found.")
        false
      }
      
    } catch (e: Exception) {
      onOutput("\nError during installation: ${e.message}")
      e.printStackTrace()
      false
    }
  }
}