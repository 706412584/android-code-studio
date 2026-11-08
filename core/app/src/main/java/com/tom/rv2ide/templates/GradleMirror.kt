/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 */

package com.tom.rv2ide.templates

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.slf4j.LoggerFactory

/**
 * Gradle 发行包的下载源。
 *
 * <p><b>为什么需要它</b>：模板生成的工程里 `gradle/wrapper/gradle-wrapper.properties`
 * 的 `distributionUrl` 决定首次构建时从哪里拉 Gradle。官方源
 * `services.gradle.org` 在国内**实测不可达**（HTTP 307 且 curl 不跟随，设备上表现为
 * 下载卡死在 0 字节），而换源此前只能改 13 份 assets 再发版。
 *
 * <p><b>做法</b>：模板创建完成后由 App 统一重写该文件的一行（[rewriteWrapper]），
 * 因此不必维护 13 份 assets，也让「用户可选镜像」成为可能。
 *
 * <p><b>为什么不在这里做「下载失败自动回退」</b>：wrapper 的实际下载由 `gradlew`
 * 脚本里的 `GradleWrapperMain` 执行，**失败不会通知 App**，App 无从感知该回退。
 * 折中做法是构建前预检可达性（[pickReachable]），把通的那个写进 properties——
 * 覆盖绝大多数场景，且无副作用。
 */
object GradleMirror {

  private val log = LoggerFactory.getLogger(GradleMirror::class.java)

  /** 一个候选下载源。 */
  data class Mirror(val id: String, val label: String, val baseUrl: String)

  /** 腾讯云。实测 HTTP 206、能正常下字节（本机与设备均可用）。 */
  val TENCENT = Mirror("tencent", "腾讯云", "https://mirrors.cloud.tencent.com/gradle/")

  /** 阿里云。与腾讯互为备份，避免单一镜像挂掉时无源可用。 */
  val ALIYUN = Mirror("aliyun", "阿里云", "https://mirrors.aliyun.com/macports/distfiles/gradle/")

  /** Gradle 官方源。国外网络下最快，国内实测不可达——因此排在候选末位。 */
  val OFFICIAL = Mirror("official", "Gradle 官方", "https://services.gradle.org/distributions/")

  /** 全部候选，**按国内可用性排序**（预检会挑第一个通的）。 */
  val ALL: List<Mirror> = listOf(TENCENT, ALIYUN, OFFICIAL)

  private const val ID_DEFAULT = "tencent"

  /**
   * 默认镜像 id。
   *
   * <p><b>为什么不按发行版分流</b>：最初想让 CN 版用国内镜像、国际版用官方源，
   * 但**发行版 ≠ 网络环境**——设备在哪国网络下与装的是哪个版无关。实测本机
   * （DEVELOPMENT 版）默认取官方源，而官方源在此必然
   * `307 → github.com/gradle/gradle-distributions/...` 超时（GitHub 不可达）。
   *
   * <p>因此默认一律国内镜像。真正的兜底是 [pickReachable] 的预检：
   * 国内镜像不通时它会依次试其它候选（含官方源），所以默认值不会成为硬编码限制。
   */
  @JvmStatic fun defaultMirrorId(): String = ID_DEFAULT

  /**
   * 兼容旧签名：发行版参数已不再影响默认值，保留参数只为调用方少改一处。
   *
   * @deprecated 改用无参的 [defaultMirrorId]。
   */
  @Deprecated("发行版不再是选镜像的依据，见 defaultMirrorId 的说明")
  @JvmStatic
  fun defaultMirrorId(@Suppress("UNUSED_PARAMETER") cnMirror: Boolean): String = defaultMirrorId()

  /** 按 id 找镜像；未知 id 返回 null（调用方回退到默认）。 */
  @JvmStatic fun byId(id: String?): Mirror? = ALL.firstOrNull { it.id == id }

  /** 某个镜像下、某个 Gradle 版本的完整 distributionUrl。 */
  @JvmStatic fun urlFor(mirror: Mirror, version: String, allVariant: Boolean = false): String {
    val suffix = if (allVariant) "all" else "bin"
    return "${mirror.baseUrl}gradle-$version-$suffix.zip"
  }

  /**
   * 预检各候选镜像，返回第一个可达的；全不可达时返回 null。
   *
   * <p><b>只探不下载</b>：用 HTTP HEAD 请求，看响应码即可——真去下载几十 MB 代价太大。
   * `HEAD` 被某些镜像拒绝时退化为 `GET` + 只读首字节并立刻断开。
   *
   * <p><b>为什么要按序试而不是并发</b>：这个探测发生在用户点「创建」之后的阻塞路径上，
   * 串行遇到第一个通的就返回，最坏情况（全不通）才付出 N 倍超时。
   *
   * @param version 需要探测的 Gradle 版本（不同版本可用性可能不同）
   * @param preferredId 用户偏好的镜像 id；为 null 用 [defaultMirrorId]
   * @param timeoutMs 单个镜像的超时
   */
  @JvmStatic
  @JvmOverloads
  fun pickReachable(
      version: String,
      preferredId: String? = null,
      timeoutMs: Int = 5_000,
  ): Mirror? {
    val ordered =
        buildList {
              byId(preferredId)?.let { add(it) }
              addAll(ALL.filter { it.id != preferredId })
            }
            .distinctBy { it.id }
    for (mirror in ordered) {
      if (isReachable(urlFor(mirror, version), timeoutMs)) {
        log.info("Gradle 镜像可用：{}（{}）", mirror.label, mirror.id)
        return mirror
      }
      log.info("Gradle 镜像不可达，试下一个：{}", mirror.id)
    }
    log.warn("所有 Gradle 镜像都不可达，保持模板原有 distributionUrl")
    return null
  }

  /**
   * 探测单个 URL 是否**直接**可下载（不下载正文）。
   *
   * <p><b>刻意不跟随重定向</b>：官方源实测返回
   * `307 → github.com/gradle/gradle-distributions/...`，而 GitHub 在目标网络下不可达。
   * 若跟随，`responseCode` 会因超时或最终 2xx 而给出误导结论（实测踩过：
   * 预检判定官方源「可用」，选中后 wrapper 下载仍然超时）。
   * 因此只认**直接的 2xx** —— 需要重定向的源一律不算可用。
   */
  private fun isReachable(url: String, timeoutMs: Int): Boolean {
    return try {
      val conn =
          (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "HEAD"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = false
          }
      try {
        conn.responseCode in 200..299
      } finally {
        conn.disconnect()
      }
    } catch (e: Exception) {
      // 超时、DNS 失败、TLS 失败……一律视为不可达，不向上抛。
      log.debug("镜像探测失败：{}（{}）", url, e.javaClass.simpleName)
      false
    }
  }

  /** 工程里 wrapper properties 的位置；两种布局都试（扁平 / `android/` 子目录）。 */
  @JvmStatic
  fun wrapperFile(projectRoot: File): File? =
      listOf(
              // 扁平布局（RN 等）：Gradle 根 = 项目根
              File(projectRoot, "gradle/wrapper/gradle-wrapper.properties"),
              // 官方 Flutter 形态：wrapper 在 android/ 子目录
              File(projectRoot, "android/gradle/wrapper/gradle-wrapper.properties"),
          )
          .firstOrNull { it.isFile }

  /**
   * 从工程的 wrapper 里读出 Gradle 版本号（如 `9.6.1`）。
   *
   * <p>挑镜像前要知道版本：不同 Gradle 版本在各镜像上的同步情况可能不同
   * （镜像可能滞后于官方发布）。读不到返回 null，调用方据此跳过镜像改写。
   */
  @JvmStatic
  fun readGradleVersion(projectRoot: File): String? {
    val file = wrapperFile(projectRoot) ?: return null
    val url = Regex("distributionUrl=(\\S+)").find(file.readText())?.groupValues?.get(1) ?: return null
    return Regex("gradle-([0-9][0-9.]*)-(bin|all)\\.zip").find(url)?.groupValues?.get(1)
  }

  /**
   * 把工程里的 wrapper 换成指定镜像。
   *
   * <p><b>只改 `distributionUrl` 一行</b>（正则替换），文件其余内容与行尾一字不动——
   * 用户的 wrapper properties 里还有 `networkTimeout`、`validateDistributionUrl` 等项，
   * 整文件重写会把它们丢掉。
   *
   * <p>找不到该文件（模板形态特殊）时静默返回 false，调用方不应因此判定创建失败。
   *
   * @return 是否真的改写了
   */
  @JvmStatic
  fun rewriteWrapper(projectRoot: File, mirror: Mirror): Boolean {
    val props =
        wrapperFile(projectRoot)
            ?: run {
              log.warn("未找到 gradle-wrapper.properties，跳过镜像改写：{}", projectRoot)
              return false
            }

    val original = props.readText()
    val current = Regex("distributionUrl=(\\S+)").find(original)?.groupValues?.get(1)
    // 从当前 URL 里抠出 Gradle 版本（.../gradle-9.6.1-bin.zip / -all.zip），
    // 换镜像只换前缀，版本与变体保持不变。
    val version = current?.let { Regex("gradle-([0-9][0-9.]*)-(bin|all)\\.zip").find(it) } ?: run {
      log.warn("distributionUrl 里认不出 Gradle 版本，跳过改写：{}", current)
      return false
    }
    val gradleVersion = version.groupValues[1]
    val isAll = version.groupValues[2] == "all"
    // 转义成 properties 里惯用的形式（`https\://...`），与模板其它文件保持一致。
    val newUrl = urlFor(mirror, gradleVersion, isAll).replace(":", "\\:").replace("/", "\\/")

    val rewritten = original.replaceFirst(Regex("distributionUrl=\\S+"), "distributionUrl=$newUrl")
    if (rewritten == original) return false
    props.writeText(rewritten)
    log.info("已把 {} 的 Gradle 源改为 {}（{}）", projectRoot.name, mirror.label, gradleVersion)
    return true
  }
}
