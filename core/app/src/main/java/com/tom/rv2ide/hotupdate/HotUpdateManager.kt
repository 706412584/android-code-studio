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

package com.tom.rv2ide.hotupdate

import android.content.Context
import android.util.Log
import com.orange.update.HotUpdateHelper
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 热更新补丁的下载与应用。
 *
 * <p>**不使用 SDK 的服务端组件**（`UpdateConfig` / `ServerApi` / `VersionChecker`）：
 * 那套要求一个自建服务端，而 ACS 的更新检查已经走 GitHub Release 的 `updater.json`。
 * 这里只保留 SDK 的两个纯本地能力：`HotUpdateHelper.applyPatch(File)` 与
 * `loadPatchIfNeeded()`（后者在 [com.tom.rv2ide.app.IDEApplication.attachBaseContext] 调用）。
 *
 * <p><b>安全模型（有意为之，见 CLAUDE.md）</b>：不启用补丁签名校验。ACS 的发布包用公开的
 * debug 密钥签名，任何拿到该密钥的人都能签出「合法」补丁，签名校验在此前提下没有防护价值。
 * 保留的校验是 [verifySha256]——清单里带哈希，能挡住下载链路被替换/损坏的补丁。
 * 这不能防住能改清单的人；要真正防篡改需换成私钥签名分发。
 */
object HotUpdateManager {

  private const val TAG = "HotUpdateManager"

  /** 下载好的补丁文件名。 */
  private const val PATCH_FILE_NAME = "hotupdate-patch.zip"

  /** 规范化后的补丁文件名（dex 重命名后）。 */
  private const val NORMALIZED_FILE_NAME = "hotupdate-patch-normalized.zip"

  /**
   * 下载并应用补丁。
   *
   * @param patchUrl 补丁下载地址
   * @param expectedSha256 清单里声明的 SHA-256（小写十六进制）。**必须提供**：缺失即拒绝应用，
   *   因为这是唯一的下载完整性校验（签名校验按用户要求未启用，见类注释）。
   * @param expectedBaseVersionCode 补丁期望的 base versionCode（即生成补丁时使用的已装版本）。
   *   -1 表示清单未声明，此时跳过 base 校验（向后兼容旧清单）。
   * @param onResult 结果回调，在非主线程调用
   */
  fun downloadAndApply(
      context: Context,
      patchUrl: String,
      expectedSha256: String?,
      expectedBaseVersionCode: Int,
      onResult: (Boolean, String) -> Unit,
  ) {
    val raw = File(context.filesDir, PATCH_FILE_NAME)
    val normalized = File(context.filesDir, NORMALIZED_FILE_NAME)
    try {
      download(patchUrl, raw)

      // 完整性校验：清单没给 sha256 就拒绝，而不是「跳过校验」——静默跳过等于没有校验。
      if (expectedSha256.isNullOrBlank()) {
        onResult(false, "清单未提供补丁 SHA-256，拒绝应用（无法验证完整性）")
        return
      }
      val actual = sha256(raw)
      if (!actual.equals(expectedSha256, ignoreCase = true)) {
        onResult(false, "补丁校验失败：哈希不匹配（期望 $expectedSha256，实际 $actual）")
        return
      }

      // base 版本校验：补丁是「上一版本 → 本版本」的增量，只能装在生成它时用的那个版本上。
      // SDK 的 validatePatchFormat 只校验包名、**不校验 base 版本**，装了不匹配的补丁会
      // 静默注入错误的 dex（补丁里的类替换掉当前版本的同名类，行为不可预期）。
      // 因此这里必须自己比：读补丁内 patch.json 的 baseVersionCode 与当前安装版本。
      val patchBase = PatchPackage.readBaseVersionCode(raw)
      val current =
          try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionCode
          } catch (e: Exception) {
            -1
          }
      val baseToCheck = if (expectedBaseVersionCode > 0) expectedBaseVersionCode else patchBase
      if (baseToCheck > 0 && current > 0 && baseToCheck != current) {
        onResult(
            false,
            "补丁不适用于当前版本（补丁基于 $baseToCheck，当前 $current）。请更新完整 APK。",
        )
        return
      }

      // 关键修复：把补丁内的 dex 重命名为连续的 classes*.dex。
      //
      // Android 的 DexPathList 只按 classes.dex → classes2.dex → classes3.dex … 顺序查找，
      // 中间跳号即**之后的所有 dex 都不可见**。而 patch-cli 生成补丁时沿用 base APK 里的
      // dex 名（ACS 有 12+ 个 dex，会产出 classes8.dex / classes12.dex 这种名字），
      // 注入后 ART 找不到任何类——表现为「补丁应用成功、DEX 注入 ✓、但代码毫无变化」。
      // 这是静默失败，比报错危险得多，因此在这里无条件规范化（CI 侧也做，双保险）。
      val dexNames = PatchPackage.normalizeDexNames(raw, normalized)
      Log.i(TAG, "normalized dex -> $dexNames")

      val helper = HotUpdateHelper.getInstance(context)
      helper.applyPatch(
          normalized,
          object : HotUpdateHelper.Callback {
            override fun onProgress(percent: Int, message: String) {
              Log.d(TAG, "apply $percent%: $message")
            }

            override fun onSuccess(result: HotUpdateHelper.PatchResult) {
              Log.i(TAG, "patch applied: version=${result.patchVersion} dex=${result.dexInjected}")
              onResult(true, result.patchVersion ?: "")
            }

            override fun onError(message: String) {
              Log.e(TAG, "apply failed: $message")
              onResult(false, message)
            }
          },
      )
    } catch (e: Exception) {
      Log.e(TAG, "download/apply failed", e)
      onResult(false, e.message ?: e.javaClass.simpleName)
    } finally {
      // 无论成功失败都清掉中间文件：半成品补丁留在 filesDir 里没有任何用处，
      // 且下次下载会覆盖它——不如直接删掉，避免磁盘上出现来路不明的 zip。
      raw.delete()
      normalized.delete()
    }
  }

  private fun download(urlStr: String, dest: File) {
    val conn = URL(urlStr).openConnection() as HttpURLConnection
    conn.requestMethod = "GET"
    conn.connectTimeout = 30000
    conn.readTimeout = 30000
    try {
      if (conn.responseCode != HttpURLConnection.HTTP_OK) {
        throw IllegalStateException("HTTP ${conn.responseCode}")
      }
      conn.inputStream.use { input ->
        FileOutputStream(dest).use { output -> input.copyTo(output, 8192) }
      }
    } finally {
      conn.disconnect()
    }
  }

  private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
      val buf = ByteArray(8192)
      while (true) {
        val n = input.read(buf)
        if (n <= 0) break
        digest.update(buf, 0, n)
      }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }

}
