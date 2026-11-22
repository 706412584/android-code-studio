/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 */

package com.tom.rv2ide.utils

import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.json.JSONObject

/**
 * 语言服务器清单与包的统一下载源：**Gitee 优先、GitHub 兜底**。
 *
 * <p><b>为什么要集中到一处</b>：Kotlin 语言服务器有三条安装路径（打开项目自动安装、
 * 更新检查、设置页下载），此前各自硬编码同一份 GitHub raw 清单，且都没有超时与换源。
 * 国内设备直连 GitHub 经常超时，安装于是失败；三处各改一遍容易漂移，因此把
 * 「拉清单 → 选条目 → 生成候选 URL → 下载/校验」收敛到这里。
 *
 * <p><b>清单</b>：Gitee 上的副本由 `tools/publish-lsp-mirror.ps1` 发布，字段与上游一致，
 * 额外附带 `sha256`（上游没有，缺失时跳过校验）。Gitee 不可达时回退上游 GitHub raw。
 *
 * <p><b>兜底 URL</b>：条目没给 link（或给了但失效）时，用
 * `.../releases/download/v<version>/server.zip` 推导 GitHub 地址。
 */
object LspServerSource {

  /** Gitee 镜像清单（`acs-lsp-mirror` 仓库根）。 */
  const val GITEE_MANIFEST_URL =
      "https://gitee.com/wu-yongchengsvip_admin/acs-lsp-mirror/raw/master/servers-manifest.json"

  /** 上游清单（GitHub raw）。 */
  const val GITHUB_MANIFEST_URL =
      "https://raw.githubusercontent.com/AndroidCSOfficial/acs-language-servers/refs/heads/main/servers-manifest.json"

  /** 上游 Release 下载前缀；兜底地址形如 `<base>/v1.3.15/server.zip`。 */
  const val GITHUB_RELEASE_BASE =
      "https://github.com/AndroidCSOfficial/acs-language-servers/releases/download"

  /** 清单候选，**按国内可用性排序**（先 Gitee）。 */
  @JvmField val MANIFEST_URLS: List<String> = listOf(GITEE_MANIFEST_URL, GITHUB_MANIFEST_URL)

  private const val DEFAULT_TIMEOUT_MS = 15_000
  private const val CONNECT_TIMEOUT_MS = 15_000

  /**
   * 清单里的一个服务器条目。
   *
   * @property link 主下载地址；`"null"` / 空串归一化为 null
   * @property sha256 可选的完整性摘要（Gitee 清单提供；上游没有）
   */
  data class Entry(val id: String, val version: String, val link: String?, val sha256: String?)

  /**
   * 依次尝试 [MANIFEST_URLS]，返回第一个能解析出 [serverId] 的条目。
   *
   * <p>全部失败返回 null（调用方负责提示用户，而不是抛出网络异常）。
   */
  @JvmStatic
  @JvmOverloads
  fun fetch(serverId: String, timeoutMs: Int = DEFAULT_TIMEOUT_MS): Entry? {
    for (url in MANIFEST_URLS) {
      try {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = timeoutMs
        connection.readTimeout = timeoutMs
        try {
          if (connection.responseCode !in 200..299) continue
          val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
          parse(text, serverId)?.let { return it }
        } finally {
          connection.disconnect()
        }
      } catch (_: Exception) {
        // 试下一个源；两个都失败才返回 null
      }
    }
    return null
  }

  /**
   * 从清单 JSON 里取出指定 id 的条目（纯函数，便于单测）。
   *
   * @return 找不到或缺少版本号时返回 null
   */
  @JvmStatic
  fun parse(json: String, serverId: String): Entry? {
    val servers = JSONObject(json).optJSONArray("Servers") ?: return null
    for (i in 0 until servers.length()) {
      val item = servers.optJSONObject(i) ?: continue
      if (item.optString("id") != serverId) continue
      val version = item.optString("version")
      if (version.isBlank()) continue
      val link = item.optString("link").takeUnless { it.isBlank() || it == "null" }
      val sha256 = item.optString("sha256").takeUnless { it.isBlank() || it == "null" }
      return Entry(serverId, version, link, sha256)
    }
    return null
  }

  /**
   * 下载候选地址，按顺序尝试：清单给的 link 在前，GitHub Release 推导地址兜底。
   *
   * <p>link 缺失时只给 GitHub；两者相同则去重。
   */
  @JvmStatic
  fun downloadCandidates(entry: Entry): List<String> =
      listOfNotNull(entry.link, "$GITHUB_RELEASE_BASE/v${entry.version}/server.zip").distinct()

  /**
   * 依次尝试 [urls]，第一个成功即返回。
   *
   * <p>失败/中断会删除半成品目标文件，避免残缺 zip 流到解压阶段。
   *
   * <p>`onProgress` 放在最后：三个调用点都写成尾随 lambda。
   *
   * @param onProgress (已读字节, 总字节)；总长未知时总字节为 -1
   */
  @JvmStatic
  @JvmOverloads
  fun download(
      urls: List<String>,
      dest: File,
      timeoutMs: Int = 60_000,
      onProgress: ((Long, Long) -> Unit)? = null,
  ): Boolean {
    for (url in urls) {
      var connection: HttpURLConnection? = null
      try {
        connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = timeoutMs
        if (connection.responseCode !in 200..299) continue
        val total = connection.contentLengthLong
        dest.parentFile?.mkdirs()
        connection.inputStream.use { input ->
          FileOutputStream(dest).use { output ->
            val buffer = ByteArray(8192)
            var readTotal = 0L
            while (true) {
              val n = input.read(buffer)
              if (n <= 0) break
              output.write(buffer, 0, n)
              readTotal += n
              onProgress?.invoke(readTotal, total)
            }
          }
        }
        if (dest.length() > 0L) return true
      } catch (_: Exception) {
        // 换下一个候选
      } finally {
        connection?.disconnect()
      }
      dest.delete()
    }
    return false
  }

  /** 文件的 SHA-256（小写十六进制）。 */
  @JvmStatic
  fun sha256Of(file: File): String {
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

  /**
   * 校验下载文件。
   *
   * <p>[expected] 为 null/空白时跳过校验并返回 true（上游清单没有 sha256；
   * 有值时校验失败会删除文件，防止把残缺包解压成“已安装”）。
   */
  @JvmStatic
  fun verify(file: File, expected: String?): Boolean {
    if (expected.isNullOrBlank()) return true
    val actual =
        try {
          sha256Of(file)
        } catch (_: Exception) {
          return false
        }
    if (actual.equals(expected, ignoreCase = true)) return true
    file.delete()
    return false
  }
}