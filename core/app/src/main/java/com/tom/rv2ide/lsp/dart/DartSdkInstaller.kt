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

package com.tom.rv2ide.lsp.dart

import android.content.Context
import com.termux.shared.termux.TermuxConstants
import com.tom.rv2ide.utils.Environment
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream

/**
 * Dart SDK（Android/arm64）的下载、安装与就绪判定。
 *
 * <p><b>为什么按需下载而不是进 APK</b>：SDK 解压后 169MB（压缩后 67MB），
 * 超过 APK 的合理体积上限；且只有 Flutter 项目才用得到。托管到 Release，
 * 应用内下载解压（通道选择的实测依据见 [PACKAGE_URL]）。
 *
 * <p><b>为什么装到 `$HOME/acs/dart-sdk` 而不是 `$PREFIX`</b>：与
 * CodeGraphInstaller 同一决策——SDK 与 Termux 包管理无关，放进 `$PREFIX` 会被
 * apt 当成「不属于任何包的文件」（dpkg --verify 一直报未知文件）。放 HOME 下
 * 卸载就是删目录。
 *
 * <p><b>就绪判定是三个文件而不是一个</b>：只看 bin/dart 会把「解包一半」
 * （dart 已解出但快照没解出）判成就绪，然后服务器启动失败、用户看不出原因。
 * 必须同时存在 dart 可执行文件、分析器快照与 lib/ 目录。
 *
 * <p>SDK 来源：mumumusuc/termux-flutter release 3.29.2 抽取的 bionic ELF 构建
 * （NDK r27c），`dart --version` 实测 `Dart SDK version: 3.7.2 (stable) on
 * "linux_arm64"`——可直接在 Android 上运行，无需 Termux 依赖。
 */
object DartSdkInstaller {

  /** 解包后 SDK 的顶层目录名（tar 内的 `dart-sdk/`）。 */
  private const val PACKAGE_DIR_NAME = "dart-sdk"

  /** 分析器快照相对 SDK 根的路径（与 DartLanguageServer 保持一致）。 */
  private const val SNAPSHOT_RELATIVE_PATH = "bin/snapshots/analysis_server.dart.snapshot"

  /**
   * 包下载地址（Gitee 镜像，国内加速）。
   *
   * <p><b>2026-10-13 换源决策</b>：此前用 GitHub Release 并验证过「302 到
   * objects.githubusercontent.com 可达」，但**设备实测复验推翻了该结论**：
   * 黑鲨上完整跟随 302 的下载在 20 秒内 0 字节超时（`github.com` 主站与
   * release 重定向链路均不可达），即国内设备根本无法完成 67MB 下载。
   * Gitee 实测 700KB/s+，故默认改走 Gitee 镜像（`acs-mirror` 仓库）。
   *
   * <p>Gitee 单文件上限 100MB——67MB 包未超限，单文件直传。
   * 换版本时改这里的 tag/文件名，上传到 `wu-yongchengsvip_admin/acs-mirror`
   * 的 release（见 tools/ 下的发布脚本）。
   */
  const val PACKAGE_URL =
      "https://gitee.com/wu-yongchengsvip_admin/acs-mirror/releases/download/dart-sdk-v3.7.2/dart-sdk-3.7.2-aarch64.tar.gz"

  /**
   * 回退地址（GitHub 上游）。
   *
   * <p>Gitee 不可用时（仓库异常/海外网络反而不通 Gitee 的场景）回退原源。
   * 下载逻辑：先试 [PACKAGE_URL]，失败后改试本地址。
   */
  const val PACKAGE_URL_FALLBACK =
      "https://github.com/706412584/android-code-studio/releases/download/dart-sdk-v3.7.2/dart-sdk-3.7.2-aarch64.tar.gz"

  /** 包大小（用于下载进度显示与完整性预检）。 */
  const val PACKAGE_SIZE_BYTES = 67_532_974L

  /** SDK 安装根目录：`$HOME/acs/dart-sdk`。 */
  fun installDir(): File = File(Environment.HOME, DartLanguageServer.SDK_DIR_NAME)

  /** dart 可执行文件。 */
  fun dartBinary(): File = File(installDir(), "bin/dart")

  /** 分析器快照。 */
  fun snapshotFile(): File = File(installDir(), SNAPSHOT_RELATIVE_PATH)

  /**
   * 是否已安装**且**可用。
   *
   * <p>三个条件缺一不可：dart 可执行文件、分析器快照、lib/ 目录（分析服务器
   * 依赖 lib/ 下的分析库）。只看 dart 会把解包一半判成就绪，然后 LSP 启动失败。
   */
  fun isInstalled(): Boolean =
      dartBinary().isFile && snapshotFile().isFile && File(installDir(), "lib").isDirectory

  /** 已安装 SDK 占用的字节数（供界面显示）。 */
  fun installedSizeBytes(): Long =
      installDir().walkBottomUp().filter { it.isFile }.sumOf { it.length() }

  /** 删除 SDK。 */
  fun uninstall() {
    installDir().deleteRecursively()
  }

  /** 下载与解包进度回调。 */
  interface ProgressListener {
    /** @param message 当前阶段的人类可读描述 */
    fun onStage(message: String)

    /** @param fraction 0..1；无法估算时传 null */
    fun onProgress(fraction: Float?)
  }

  private val client: OkHttpClient by lazy {
    OkHttpClient.Builder()
        // 67MB 在慢网络下会超过默认超时；不设整体超时，靠读写超时兜底
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()
  }

  /**
   * 完整安装：下载 → 解包。
   *
   * <p>下载到 cacheDir（空间不足时系统可回收）而不是 filesDir——半成品包
   * 不该占用持久空间，失败时删除。
   */
  suspend fun install(
      context: Context,
      listener: ProgressListener? = null,
      packageUrl: String = PACKAGE_URL,
  ): Result<Unit> =
      withContext(Dispatchers.IO) {
        try {
          listener?.onStage("下载 Dart SDK（67MB）…")
          val archive = File(context.cacheDir, "dart-sdk-download.tar.gz")
          try {
            try {
              download(packageUrl, archive) { fraction -> listener?.onProgress(fraction) }
            } catch (primary: Exception) {
              // 镜像失败回退上游（默认 Gitee → GitHub，见 PACKAGE_URL 注释）。
              // 显式传入自定义 URL 时（测试/换源）不触发回退。
              if (packageUrl == PACKAGE_URL && PACKAGE_URL_FALLBACK != PACKAGE_URL) {
                listener?.onStage("镜像下载失败，尝试上游源…")
                download(PACKAGE_URL_FALLBACK, archive) { fraction ->
                  listener?.onProgress(fraction)
                }
              } else {
                throw primary
              }
            }
            listener?.onStage("解包…")
            extract(archive)
          } finally {
            archive.delete()
          }

          if (!isInstalled()) {
            return@withContext Result.failure(
                IllegalStateException("解包完成但 SDK 校验未通过（dart 或分析器快照缺失）"))
          }
          listener?.onStage("安装完成")
          listener?.onProgress(1f)
          Result.success(Unit)
        } catch (e: Exception) {
          Result.failure(e)
        }
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

  /**
   * 解包 dart-sdk tar.gz。
   *
   * <p>与 CodeGraphInstaller.extract 同一套安全规则：解包前清空目标目录
   * （避免旧版本残留与新版本混合）、防目录穿越、按 tar 记录的 mode 恢复
   * 可执行位（dart 二进制需要 +x，否则服务器起不来）。
   */
  private fun extract(archive: File): Int {
    val target = installDir()
    // 解包前清掉旧目录：旧版本残留会与新版本混在一起
    target.deleteRecursively()
    target.mkdirs()

    var count = 0
    TarArchiveInputStream(GZIPInputStream(FileInputStream(archive))).use { tar ->
      var entry = tar.nextTarEntry
      while (entry != null) {
        val name = entry.name.removePrefix("./")
        val outFile = File(target, name)
        if (!isInside(target, outFile)) {
          // 目录穿越防护：归档里的 ../ 能写到目标之外
          entry = tar.nextTarEntry
          continue
        }
        if (entry.isDirectory) {
          outFile.mkdirs()
        } else {
          outFile.parentFile?.mkdirs()
          FileOutputStream(outFile).use { out -> tar.copyTo(out) }
          if ((entry.mode and 0b001_000_000) != 0) {
            outFile.setExecutable(true, false)
          }
          count++
        }
        entry = tar.nextTarEntry
      }
    }
    return count
  }

  /** 路径是否落在根目录内（防目录穿越）。 */
  private fun isInside(root: File, candidate: File): Boolean {
    return try {
      val rootPath = root.canonicalPath
      val candidatePath = candidate.canonicalPath
      candidatePath == rootPath || candidatePath.startsWith(rootPath + File.separator)
    } catch (e: java.io.IOException) {
      false
    }
  }
}
