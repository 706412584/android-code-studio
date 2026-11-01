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

package com.assistant.termux

import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream

/**
 * Ubuntu rootfs 的下载与解压。
 *
 * <p><b>为什么运行时下载而不是打包进 APK</b>：Ubuntu base 的 arm64 tarball 约 30MB，
 * 打进 APK 会让安装包翻倍，而大多数用户不会用到终端。运行时下载让不用终端的人
 * 不必付这个体积代价。
 *
 * <p><b>为什么不引 OkHttp</b>：本模块刻意保持零额外依赖（只有 AAR 与 Kotlin 标准库）。
 * 一次 HTTP GET + 写文件用 `HttpURLConnection` 足够；换 OkHttp 要多一个依赖、
 * 还要处理它的线程池与证书配置。
 */
class RootfsInstaller(private val container: LinuxContainer) {

  /** 下载进度回调。`total <= 0` 表示服务端没给 Content-Length。 */
  fun interface ProgressListener {
    fun onProgress(downloaded: Long, total: Long)
  }

  /**
   * 安装 rootfs：依次尝试各镜像，下载并解压到目标目录。
   *
   * <p><b>先解压到临时目录再改名</b>：解压中途失败（磁盘满、网络断）时，直接写目标
   * 目录会留下一个「有 /bin 但不完整」的 rootfs —— `isRootfsInstalled` 会因为
   * /bin 存在而误判为已装好，之后每次进容器都失败且原因不明。原子改名保证
   * 目标目录要么完整、要么不存在。
   *
   * @return 成功时 null；失败时是各镜像的失败原因（合在一起给用户看）
   */
  fun install(
      onProgress: ProgressListener = ProgressListener { _, _ -> },
      onStatus: (String) -> Unit = {},
  ): String? {
    val target = container.rootfs
    if (target.exists()) {
      onStatus("清理未完成的安装…")
      target.deleteRecursively()
    }
    val staging = File(container.rootfsDir, "ubuntu-staging")
    if (staging.exists()) staging.deleteRecursively()
    staging.mkdirs()

    onStatus("查询可用镜像…")
    val candidates = container.rootfsUrls()
    if (candidates.isEmpty()) {
      staging.deleteRecursively()
      return "所有镜像都没能列出可用的 Ubuntu 版本。\n" +
          "可能是网络不通，或上游目录结构变了——请检查网络后重试。"
    }

    val failures = mutableListOf<String>()
    for (url in candidates) {
      onStatus("下载 ${url.substringAfter("//").substringBefore("/")}…")
      val archive = File(container.rootfsDir, "rootfs.tar.gz")
      try {
        download(url, archive, onProgress)
        onStatus("解压…")
        extractTarGz(archive, staging)
        archive.delete()
        // 判 usr/bin 而不是 bin：Ubuntu base 的 /bin 是软链（bin -> usr/bin），
        // File.isDirectory 对软链返回 false。详见 LinuxContainer.isRootfsInstalled。
        // 同时验 /bin 这个软链真的建出来了——只判 usr/bin 会漏掉「文件解出来了但
        // 软链全没保留」这种半残状态（那样容器起来后所有命令都找不到）。
        val binLink = File(staging, "bin")
        val linksOk = java.nio.file.Files.isSymbolicLink(binLink.toPath())
        if (!File(staging, "usr/bin").isDirectory || !linksOk) {
          failures +=
              "${mirrorOf(url)}：解压不完整（usr/bin=${File(staging, "usr/bin").isDirectory}, bin 软链=$linksOk）"
          staging.deleteRecursively()
          staging.mkdirs()
          continue
        }
        // 原子改名：同分区内的 rename 是原子操作。
        if (!staging.renameTo(target)) {
          failures += "${mirrorOf(url)}：改名失败（目标已存在？）"
          staging.deleteRecursively()
          continue
        }
        onStatus("完成")
        return null
      } catch (e: Exception) {
        failures += "${mirrorOf(url)}：${e.message ?: e.javaClass.simpleName}"
        archive.delete()
        staging.deleteRecursively()
        staging.mkdirs()
      }
    }
    staging.deleteRecursively()
    // 同一镜像有多个版本候选，失败时会产生多条同样原因的信息。按镜像去重后
    // 只留第一条（用户看的是「哪家不行、为什么」，不是每个版本都试过一遍）。
    val byMirror = LinkedHashMap<String, String>()
    for (message in failures) {
      val mirror = message.substringBefore("：")
      byMirror.putIfAbsent(mirror, message)
    }
    return byMirror.values.joinToString("\n")
  }

  /** 从 URL 里取镜像主机名，用于错误提示（完整 URL 太长，用户看不出是哪一家）。 */
  private fun mirrorOf(url: String): String =
      url.substringAfter("//").substringBefore("/")

  private fun download(url: String, dest: File, onProgress: ProgressListener) {
    val conn =
        (URL(url).openConnection() as HttpURLConnection).apply {
          connectTimeout = 20_000
          readTimeout = 60_000
          // 不设 UA 会被部分镜像拒（它们拦 curl 型或空 UA，返回 403）。
          setRequestProperty("User-Agent", LinuxContainer.UA)
          instanceFollowRedirects = true
        }
    try {
      val code = conn.responseCode
      if (code !in 200..299) {
        throw java.io.IOException("HTTP $code")
      }
      val total = conn.contentLengthLong
      var read = 0L
      conn.inputStream.use { input ->
        dest.outputStream().buffered().use { out ->
          val buf = ByteArray(64 * 1024)
          var lastReport = 0L
          while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            read += n
            // 每 512KB 报一次进度：每个 chunk 都回调会让 UI 每秒重组上百次。
            if (read - lastReport >= 512 * 1024) {
              onProgress.onProgress(read, total)
              lastReport = read
            }
          }
        }
      }
      onProgress.onProgress(read, total)
    } finally {
      conn.disconnect()
    }
  }

  /**
   * 解压 tar.gz。
   *
   * <p><b>为什么要校验路径</b>：tar 条目里可以写 {@code ../../etc/passwd} 这类相对路径，
   * 朴素解压会写到目标目录之外（zip-slip）。镜像来自官方源、理论上可信，但这是
   * 便宜的防御，且一旦发生就是任意文件写入。
   */
  private fun extractTarGz(archive: File, target: File) {
    val targetPath = target.canonicalPath
    val stream =
        TarArchiveInputStream(
            java.util.zip.GZIPInputStream(archive.inputStream().buffered(), 64 * 1024))
    stream.use { tar ->
      while (true) {
        // getNextTarEntry() 而不是 nextEntry：后者返回基类 ArchiveEntry，
        // 拿不到 tar 专有的 mode；前者返回 TarArchiveEntry，可读权限位。
        val entry = tar.nextTarEntry ?: break
        val out = File(target, entry.name)
        // 归一化后必须仍在目标目录内。
        if (!out.canonicalPath.startsWith(targetPath + File.separator) &&
            out.canonicalPath != targetPath) {
          throw java.io.IOException("tar 条目越界：${entry.name}")
        }
        when {
          entry.isDirectory -> out.mkdirs()
          // 符号链接必须原样重建：Ubuntu 的根目录结构大量依赖软链
          // （`bin -> usr/bin`、`lib -> usr/lib`、`/etc/alternatives/...`）。
          // 当普通文件处理会写出一个内容为空的常规文件 —— 后果是 `/bin` 不是目录，
          // 于是「rootfs 装好了没」的判断失败，且容器起来后所有命令都找不到。
          //
          // 用 java.nio 的 createSymbolicLink：File 没有建软链的 API。
          entry.isSymbolicLink -> {
            out.parentFile?.mkdirs()
            val linkTarget = entry.linkName
            // 先删掉可能已存在的同路径条目，否则 createSymbolicLink 抛
            // FileAlreadyExistsException（tar 里同一路径可能先有目录后有软链）。
            if (out.exists() || java.nio.file.Files.isSymbolicLink(out.toPath())) {
              out.delete()
            }
            java.nio.file.Files.createSymbolicLink(
                out.toPath(), java.nio.file.Paths.get(linkTarget))
          }
          else -> {
            out.parentFile?.mkdirs()
            out.outputStream().buffered().use { sink -> tar.copyTo(sink) }
            // 可执行位必须保留：解出来的 /bin/sh 没有 +x 时容器起不来，
            // 且报错是「permission denied」，容易被误判成 proot 的问题。
            // 判的是 owner 执行位（八进制 0100 = 十进制 64）。
            val ownerExec = 0b001_000_000
            if (entry.mode and ownerExec != 0) {
              out.setExecutable(true, false)
            }
          }
        }
      }
    }
  }

}
