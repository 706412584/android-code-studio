/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 */

package com.tom.rv2ide.utils

import android.os.Build
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * JDK 的 Gitee 镜像兜底（aarch64 deb）。
 *
 * <p><b>为什么需要</b>：JDK 管理器走 Termux 的 apt，源写死在国内 GitLab
 * （`acs-packages-*.gitlab.io`，见 [JdkInstaller] 的说明）。该源不可达时，用户只能看到
 * `apt` 的网络错误，没有任何换源手段；而 Gitee 上已经镜像了 openjdk 17/21 的 deb。
 *
 * <p><b>包从哪来</b>：下载自上述 GitLab apt 源的官方 deb，原样上传到
 * `wu-yongchengsvip_admin/acs-apt-arm64` 的 `jdk-debs-v1` Release。JDK 21 的 deb
 * 为 105.6MB，超过 Gitee 单文件 100MB 上限，按 95MB 切为两卷，下载时顺序拼接。
 *
 * <p><b>为什么用 dpkg 而不是 apt</b>：apt 仍会去连同一个源；`dpkg -i` 只处理本地文件，
 * 依赖缺失时直接报出来（Termux 闭包里 JDK 的运行依赖已随首装就绪）。
 */
object JdkMirror {

  private const val RELEASE_BASE =
      "https://gitee.com/wu-yongchengsvip_admin/acs-apt-arm64/releases/download/jdk-debs-v1"

  private const val CONNECT_TIMEOUT_MS = 15_000
  private const val READ_TIMEOUT_MS = 120_000
  private const val DPKG_TIMEOUT_MS = 10 * 60 * 1000L

  /** 一个版本的镜像包（可能由多卷组成）。 */
  data class Package(val fileName: String, val sha256: String, val partUrls: List<String>)

  /** 只覆盖实际存在的两个版本；新增版本需同步上传 deb 并在此登记。 */
  private val PACKAGES =
      mapOf(
          "17" to
              Package(
                  fileName = "openjdk-17_17.0.16-2_aarch64.deb",
                  sha256 = "e3eaa8c97a21acf4592b91cab59eb8a88c2f3f75d73725e66119c0c893905b25",
                  partUrls = listOf("$RELEASE_BASE/openjdk-17_17.0.16-2_aarch64.deb"),
              ),
          "21" to
              Package(
                  fileName = "openjdk-21_21.0.8-1_aarch64.deb",
                  sha256 = "7e8fd96a304dc8ea960012ef96e528dcafcbceab5c080fef34e70b38848f429e",
                  partUrls =
                      listOf(
                          "$RELEASE_BASE/openjdk-21_21.0.8-1_aarch64.deb.part.00",
                          "$RELEASE_BASE/openjdk-21_21.0.8-1_aarch64.deb.part.01",
                      ),
              ),
      )

  /**
   * 该版本是否有可用镜像（还要求设备是 arm64）。
   */
  fun isAvailable(javaVersion: String): Boolean =
      Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a" && PACKAGES.containsKey(javaVersion)

  /**
   * 镜像覆盖的大版本（升序）。
   *
   * <p>JDK 管理器的可用列表来自 `apt-cache`，而 apt 源不可达时它只会列出**已安装**的
   * 版本（实测：源坏了以后卸载 21，列表里只剩 17），用户连「安装」按钮都看不到，
   * 兜底路径永远走不到。把镜像版本并进列表，源失败时仍能发起安装。
   */
  fun availableVersions(): List<String> =
      if (Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a") {
        PACKAGES.keys.sortedBy { it.toIntOrNull() ?: 0 }
      } else {
        emptyList()
      }

  /**
   * 下载 + 校验 + dpkg 安装。
   *
   * @return 目标目录 `$PREFIX/lib/jvm/java-<v>-openjdk` 已出现
   */
  fun install(javaVersion: String, onOutput: (String) -> Unit): Boolean {
    if (Build.SUPPORTED_ABIS.firstOrNull() != "arm64-v8a") {
      onOutput("[镜像] 仅提供 aarch64 包，当前设备架构不受支持。")
      return false
    }
    val pkg = PACKAGES[javaVersion]
    if (pkg == null) {
      onOutput("[镜像] 没有 JDK $javaVersion 的镜像包。")
      return false
    }

    val workDir = File(Environment.HOME, "acs/jdk-mirror").apply { mkdirs() }
    val deb = File(workDir, pkg.fileName)
    deb.delete()

    onOutput("[镜像] 从 Gitee 下载 ${pkg.fileName}（${pkg.partUrls.size} 个文件）…")
    if (!downloadParts(pkg, deb, onOutput)) {
      deb.delete()
      onOutput("[镜像] 下载失败。")
      return false
    }

    val actual = sha256(deb)
    if (!actual.equals(pkg.sha256, ignoreCase = true)) {
      deb.delete()
      onOutput("[镜像] 校验失败：期望 ${pkg.sha256}，实际 $actual")
      return false
    }
    onOutput("[镜像] 校验通过，安装 deb…")

    if (!runDpkg(deb, onOutput)) {
      onOutput("[镜像] dpkg 安装失败（可检查上方输出里的依赖问题）。")
      return false
    }

    val jvmDir = File(Environment.PREFIX, "lib/jvm/java-$javaVersion-openjdk")
    val ok = jvmDir.isDirectory
    if (ok) {
      deb.delete()
      onOutput("[镜像] JDK $javaVersion 安装完成：${jvmDir.absolutePath}")
    } else {
      onOutput("[镜像] dpkg 报告成功，但 ${jvmDir.absolutePath} 未出现。")
    }
    return ok
  }

  /** 顺序下载各卷并追加到同一文件。 */
  private fun downloadParts(pkg: Package, target: File, onOutput: (String) -> Unit): Boolean {
    for ((index, url) in pkg.partUrls.withIndex()) {
      var connection: HttpURLConnection? = null
      try {
        connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        if (connection.responseCode !in 200..299) {
          onOutput("[镜像] HTTP ${connection.responseCode}：$url")
          return false
        }
        val total = connection.contentLengthLong
        val label = "第 ${index + 1}/${pkg.partUrls.size} 卷"
        var readTotal = 0L
        var lastPercent = -1
        connection.inputStream.use { input ->
          FileOutputStream(target, /* append = */ index > 0).use { output ->
            val buffer = ByteArray(8192)
            while (true) {
              val n = input.read(buffer)
              if (n <= 0) break
              output.write(buffer, 0, n)
              readTotal += n
              if (total > 0) {
                val percent = ((readTotal * 100) / total).toInt()
                if (percent / 10 != lastPercent / 10) {
                  lastPercent = percent
                  onOutput("[镜像] $label 下载 $percent%")
                }
              }
            }
          }
        }
      } catch (e: Exception) {
        onOutput("[镜像] 下载异常：${e.message}")
        return false
      } finally {
        connection?.disconnect()
      }
    }
    return target.isFile && target.length() > 0L
  }

  /** `dpkg -i` 安装本地 deb；输出原样转发（与 [JdkInstaller] 的 apt 路径一致）。 */
  private fun runDpkg(deb: File, onOutput: (String) -> Unit): Boolean {
    val prefix = Environment.PREFIX.absolutePath
    val script =
        "export PREFIX='$prefix'; " +
            "export PATH=\"\$PREFIX/bin:\$PATH\"; " +
            "export LD_LIBRARY_PATH=\"\$PREFIX/lib\"; " +
            "export HOME='${Environment.HOME.absolutePath}'; " +
            "export DEBIAN_FRONTEND=noninteractive; " +
            "dpkg -i --force-overwrite '${deb.absolutePath}' 2>&1"
    return try {
      val pb = ProcessBuilder("/system/bin/sh", "-c", script)
      pb.redirectErrorStream(true)
      val process = pb.start()
      val reader =
          Thread {
            try {
              process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { onOutput(it) }
              }
            } catch (_: Exception) {
              // 进程被强杀时读取会抛，忽略：退出码已表达失败。
            }
          }
      reader.start()
      val finished = process.waitFor(DPKG_TIMEOUT_MS, TimeUnit.MILLISECONDS)
      if (!finished) {
        process.destroyForcibly()
        onOutput("[镜像] dpkg 超时（${DPKG_TIMEOUT_MS / 1000}s），已终止。")
        return false
      }
      reader.join(2000)
      process.exitValue() == 0
    } catch (e: Exception) {
      onOutput("[镜像] 无法执行 dpkg：${e.message}")
      false
    }
  }

  private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
      val buffer = ByteArray(8192)
      while (true) {
        val n = input.read(buffer)
        if (n <= 0) break
        digest.update(buffer, 0, n)
      }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }
}