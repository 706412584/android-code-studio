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
 * C/C++ 语言服务器（clang / clangd）的 Gitee 镜像兜底。
 *
 * <p><b>为什么需要</b>：clangd 由 Termux apt 安装，源写死在国内 GitLab
 * （`acs-packages-*.gitlab.io`）。源不可达时 apt 只能报网络错误，没有任何换源手段。
 * Gitee 上已镜像了 `clang` 及其运行所需的依赖 deb。
 *
 * <p><b>包含哪些包</b>：clang 的 apt 依赖链是
 * `clang → libcompiler-rt, lld, llvm, ndk-sysroot, libllvm`。
 * 这里镜像除 `ndk-sysroot` 外的全部实体包——`ndk-sysroot` 只提供头文件，且与已装包的
 * 头文件路径冲突（`Clang.kt` 有实测记录），缺它不影响 `clang`/`clangd` 二进制运行。
 *
 * <p><b>为什么是 dpkg 而不是 apt</b>：apt 仍会去连同一个源；`dpkg -i` 只处理本地文件，
 * 依赖缺失时会明确报出来。装好后由调用方按 `$PREFIX/bin/clang` 与 `clangd` 复核。
 */
object ClangMirror {

  private const val RELEASE_BASE =
      "https://gitee.com/wu-yongchengsvip_admin/acs-apt-arm64/releases/download/clang-debs-v1"

  private const val CONNECT_TIMEOUT_MS = 15_000
  private const val READ_TIMEOUT_MS = 120_000
  private const val DPKG_TIMEOUT_MS = 10 * 60 * 1000L

  /** 按依赖从底层到顶层排列，dpkg 会按此顺序解包/配置。 */
  private val PACKAGES =
      listOf(
          Deb("libcompiler-rt_20.1.8-3_aarch64.deb", "6b13aa7ac476b522b555a3ab65a48050be90c5749c47f302db95685122830902"),
          Deb("libllvm_20.1.8-3_aarch64.deb", "63a6c791ea9b236fe97437155de34d90dc4a023cc0269f34cf8e68c24655df7b"),
          Deb("llvm_20.1.8-3_aarch64.deb", "dc06d3cb2ba95203fd8fa5f000e7e4ebab67cc7634dbbd782c129ae92400e8f2"),
          Deb("lld_20.1.8-3_aarch64.deb", "790a3807ecb0c91323dd15b6b3b3009969dce3418d0770d616d1e2dee468bff2"),
          Deb("clang_20.1.8-3_aarch64.deb", "65e8f3de35a17c49c7f6400755e8f55c1e16801cff3d8278bf3543917c634626"),
      )

  private data class Deb(val fileName: String, val sha256: String)

  /** 镜像只提供 aarch64 包。 */
  fun isAvailable(): Boolean = Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a"

  /**
   * 下载、逐个校验并 dpkg 安装全部 deb。
   *
   * @return `$PREFIX/bin/clang` 与 `clangd` 都已存在
   */
  fun install(onOutput: (String) -> Unit): Boolean {
    if (!isAvailable()) {
      onOutput("[镜像] 仅提供 aarch64 包，当前设备架构不受支持。")
      return false
    }
    val workDir = File(Environment.HOME, "acs/clang-mirror").apply { mkdirs() }

    val debs = mutableListOf<File>()
    for (pkg in PACKAGES) {
      val dest = File(workDir, pkg.fileName)
      dest.delete()
      onOutput("[镜像] 下载 ${pkg.fileName}…")
      if (!download(pkg.fileName, dest)) {
        dest.delete()
        onOutput("[镜像] 下载失败：${pkg.fileName}")
        return false
      }
      val actual = sha256(dest)
      if (!actual.equals(pkg.sha256, ignoreCase = true)) {
        dest.delete()
        onOutput("[镜像] 校验失败：${pkg.fileName}\n  期望 ${pkg.sha256}\n  实际 $actual")
        return false
      }
      debs += dest
    }

    onOutput("[镜像] 校验通过，安装 ${debs.size} 个 deb…")
    if (!runDpkg(debs, onOutput)) {
      onOutput("[镜像] dpkg 安装失败（可检查上方输出里的依赖问题）。")
      return false
    }

    val clang = File(Environment.BIN_DIR, "clang")
    val clangd = File(Environment.BIN_DIR, "clangd")
    val ok = clang.exists() && clangd.exists()
    if (ok) {
      debs.forEach { it.delete() }
      onOutput("[镜像] clang/clangd 安装完成。")
    } else {
      onOutput("[镜像] dpkg 报告完成，但 clang/clangd 未出现在 ${Environment.BIN_DIR.absolutePath}。")
    }
    return ok
  }

  private fun download(fileName: String, dest: File): Boolean {
    var connection: HttpURLConnection? = null
    try {
      connection = URL("$RELEASE_BASE/$fileName").openConnection() as HttpURLConnection
      connection.connectTimeout = CONNECT_TIMEOUT_MS
      connection.readTimeout = READ_TIMEOUT_MS
      if (connection.responseCode !in 200..299) return false
      connection.inputStream.use { input ->
        FileOutputStream(dest).use { output ->
          val buffer = ByteArray(8192)
          while (true) {
            val n = input.read(buffer)
            if (n <= 0) break
            output.write(buffer, 0, n)
          }
        }
      }
      return dest.isFile && dest.length() > 0L
    } catch (_: Exception) {
      return false
    } finally {
      connection?.disconnect()
    }
  }

  private fun runDpkg(debs: List<File>, onOutput: (String) -> Unit): Boolean {
    val prefix = Environment.PREFIX.absolutePath
    val files = debs.joinToString(" ") { "'${it.absolutePath}'" }
    val script =
        "export PREFIX='$prefix'; " +
            "export PATH=\"\$PREFIX/bin:\$PATH\"; " +
            "export LD_LIBRARY_PATH=\"\$PREFIX/lib\"; " +
            "export HOME='${Environment.HOME.absolutePath}'; " +
            "export DEBIAN_FRONTEND=noninteractive; " +
            "dpkg -i --force-overwrite $files 2>&1"
    return try {
      val pb = ProcessBuilder("/system/bin/sh", "-c", script)
      pb.redirectErrorStream(true)
      val process = pb.start()
      val reader =
          Thread {
            try {
              process.inputStream.bufferedReader().useLines { lines -> lines.forEach { onOutput(it) } }
            } catch (_: Exception) {
              // 进程被强杀时读取会抛，忽略：退出码已表达失败。
            }
          }
      reader.start()
      if (!process.waitFor(DPKG_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
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