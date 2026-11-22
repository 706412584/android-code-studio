/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 */

package com.tom.rv2ide.templates

import com.tom.rv2ide.utils.GradleDistributionChecksums
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

  /**
   * 一个候选下载源。
   *
   * @property baseUrl Gradle **发行包**的下载前缀（`.../gradle/`）
   * @property mavenGoogle Google 官方件的镜像仓库（AGP、AndroidX）；null 表示该源没有
   * @property mavenCentral Maven Central 的镜像仓库（Kotlin、第三方）；null 表示没有
   */
  data class Mirror(
      val id: String,
      val label: String,
      val baseUrl: String,
      val mavenGoogle: String? = null,
      val mavenCentral: String? = null,
  )

  /**
 * 阿里云。Gradle 发行包与 Maven 依赖都有——**唯一两者兼备的源**，
 * 因此 Maven 镜像固定用它（不随 Gradle 源选择变化）。
 *
 * <p>**Gradle 发行包的默认源**：实测 Java 客户端完整下载 9.6.1 与官方字节一致
 * （sha256 9c0f7fae…）。
 *
 * <p>**为什么候选里没有腾讯云**：腾讯的 EdgeOne WAF 会针对 Java 客户端下发反爬挑战页。
 * 实测（黑鲨 SKW-A0，2026-11-23）：同一 URL 用 curl 下载得到 140,682,664 字节的正常 zip，
 * 而用 `HttpURLConnection`（与 Gradle wrapper 相同的下载实现）拿到的是
 * **HTTP 200 + 983 字节的 JavaScript 挑战页**（设置 `EO_Bot_Ssid`/`__tst_status`）。
 * Gradle wrapper 不检查 HTTP 状态码，会把挑战页当成 zip 写盘——后果是解压失败，
 * 若有 `distributionSha256Sum` 则直接校验失败。更糟的是它对 HEAD 也返回 200，
 * 可达性探测会把腾讯误判为「可用」，从而在阿里云探测失败时选中它。
 * 因此**整项移除**，避免任何路径再选中它。
 */
  val ALIYUN =
      Mirror(
          id = "aliyun",
          label = "阿里云",
          baseUrl = "https://mirrors.aliyun.com/macports/distfiles/gradle/",
          // 实测：google 下有 AGP 9.2.0（200）；central 有 Kotlin（200）。
          mavenGoogle = "https://maven.aliyun.com/repository/google",
          mavenCentral = "https://maven.aliyun.com/repository/central",
      )

  /** Gradle 官方源。国外网络下最快，国内实测不可达——因此排在候选末位。无 Maven 镜像。 */
  val OFFICIAL = Mirror("official", "Gradle 官方", "https://services.gradle.org/distributions/")

  /**
   * Maven 镜像（名称 → URL，**固定的阿里云**）。
   *
   * <p>返回 `List<List<String>>`（而非 Kotlin 的 `Pair`）：调用方是 init script
   * 生成器与 Java 单测，两层列表在 Java/Kotlin 互操作下都不需要额外适配。
   * 找不到镜像时返回空列表，调用方据此跳过 Maven 加速。
   */
  @JvmStatic
  fun mavenRepositories(): List<List<String>> {
    val m = ALIYUN
    return listOfNotNull(
        m.mavenGoogle?.let { listOf("m-google", it) },
        m.mavenCentral?.let { listOf("m-central", it) },
    )
  }

  /**
   * 全部候选，**按国内可用性排序**（预检会挑第一个通的）。
   *
   * <p>`@JvmField`：给 Java 侧（设置页的单选列表、单测）直接读，
   * 不必走 `getALL()` 这种 Kotlin object 生成的访问器。
   */
  @JvmField val ALL: List<Mirror> = listOf(ALIYUN, OFFICIAL)

  /**
   * 默认镜像。
   *
   * <p>**必须是阿里云而不是腾讯云**：腾讯的 WAF 会给 Java 客户端返回 200 + 反爬挑战页，
   * 而 Gradle wrapper 正是 Java 实现（详见 [TENCENT] 的说明）。这个 bug 曾表现为
   * 「新建项目同步失败、报 distribution does not exist」，且 HEAD 探测看不出差异。
   */
  private const val ID_DEFAULT = "aliyun"

  /** 探测请求的 UA（阿里云会 403 掉 Dalvik 默认 UA，见 [probe]）。 */
  private const val PROBE_USER_AGENT = "AndroidCodeStudio/1.0 (Gradle mirror probe)"

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
    // 先 HEAD；失败再退化为「Range GET 只读首字节」。
    //
    // <p>为什么要这个回退：实测（黑鲨实机，2026-11-23）阿里云的 HEAD 在 Android 的
    // HttpURLConnection 下 5s 超时，而同一个 URL 的 GET 只需几百毫秒——只探 HEAD 会把
    // 唯一可用源判成不可达，进而让创建流程退回模板 URL 或选到其它源。
    // Range 只取 1KB，代价可忽略；仍不跟随重定向。
    if (probe(url, "HEAD", timeoutMs)) return true
    return probe(url, "GET", timeoutMs)
  }

  private fun probe(url: String, method: String, timeoutMs: Int): Boolean {
    var conn: HttpURLConnection? = null
    return try {
      conn =
          (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = false
            useCaches = false
            // **必须换掉 Dalvik 的默认 UA**：阿里云对 `Dalvik/2.1.0 (…)` 直接返回 403，
            // 实测（黑鲨，2026-11-23）HEAD 与 Range GET 都是 403，于是唯一可用源被
            // 判成不可达，创建流程退回模板 URL / 用户选的镜像永远不生效。
            // Gradle 自己也带 UA，实际下载不受影响；这里给探测一个明确的客户端标识。
            setRequestProperty("User-Agent", PROBE_USER_AGENT)
            if (method == "GET") setRequestProperty("Range", "bytes=0-1023")
          }
      val code = conn.responseCode
      val ok = code in 200..299
      if (ok && method == "GET") {
        // 读一小段再断开，确保连接可用（有些 CDN 先回首包再断）。
        conn.inputStream.use { it.read() }
      }
      ok
    } catch (e: Exception) {
      // 超时、DNS 失败、TLS 失败……一律视为不可达，不向上抛。
      log.debug("镜像探测失败：{}（{} / {}）", url, method, e.javaClass.simpleName)
      false
    } finally {
      conn?.disconnect()
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
   * 生成「Maven 依赖加速」的 Gradle init script 内容。
   *
   * <p><b>为什么走 init script 而不是改用户工程</b>：这样**已存在的项目也生效**，
   * 且不必往用户仓库里写文件（`settings.gradle` 是用户的，App 不该悄悄改）。
   * init script 由构建时 `--init-script` 注入，对任何项目都适用。
   *
   * <p><b>为什么在 `settingsEvaluated` 里改</b>：实测在 `allprojects { repositories {} }`
   * 里加会被 `FAIL_ON_PROJECT_REPOS` 直接拒绝——
   * 「repository 'aliyun-google' was added by initialization script」。只有
   * settings 级的仓库列表能改；而那个模式是模板与多数工程在用的。
   *
   * <p><b>为什么用 `reverseEach + addFirst`</b>：逐个 `addFirst` 会让顺序颠倒
   * （实测得到 `m-central m-google`）。倒序插入才得到声明的顺序。
   *
   * <p><b>为什么镜像在前</b>：命中镜像走国内快路，未命中回落官方源——
   * 对可用性零影响。追加到末尾等于不起作用。
   */
  @JvmStatic
  fun buildMavenInitScript(): String {
    val repos = mavenRepositories()
    if (repos.isEmpty()) return ""
    // 镜像声明行（14 空格缩进，对应 `def mirrors = [` 的下一层）。
    //
    // **不用模板插值也不用占位符 replace**：多行文本插进 Kotlin 原始字符串后
    // 不受 `trimIndent()` 管辖（它只按字面行算缩进），试过 `$decl` 与
    // `replace("__MIRRORS__")` 两种写法都出现过缩进错位。改为先用列表逐行加入、
    // 再 join——缩进完全由这里掌握，不依赖任何后处理。
    // 12 空格 = `        def mirrors = [`（8）之下再进一层（4）。
    val mirrorLines =
        repos.map { (name, url) -> "            ['$name', '$url']," }

    val body =
        listOf(
            "// 由 Android Code Studio 生成：新建/已有项目的 Maven 依赖走国内镜像。",
            "// 镜像前置、官方兜底——命中走快路，未命中回落，不影响可用性。",
            "settingsEvaluated { settings ->",
            "    def prependMirrors = { handler ->",
            "        def mirrors = [",
        ) +
            mirrorLines +
            listOf(
                "        ]",
                "        def existing = handler.collect { it.name }",
                "        def added = []",
                "        mirrors.each { n, u ->",
                "            if (!existing.contains(n)) {",
                "                added << handler.maven { name = n; url = u }",
                "            }",
                "        }",
                "        // 倒序 addFirst 才能得到声明顺序（逐个 addFirst 会颠倒）。",
                "        added.reverseEach { r ->",
                "            handler.remove(r)",
                "            handler.addFirst(r)",
                "        }",
                "    }",
                "    prependMirrors(settings.pluginManagement.repositories)",
                "    prependMirrors(settings.dependencyResolutionManagement.repositories)",
                "}",
            )
    return body.joinToString("\n")
  }

  /**
   * 把工程里的 wrapper 换成指定镜像，并同步维护 `distributionSha256Sum`。
   *
   * <p><b>只动两行</b>（`distributionUrl` 与 `distributionSha256Sum`，正则替换），
   * 文件其余内容与行尾一字不动——用户的 wrapper properties 里还有 `networkTimeout`、
   * `validateDistributionUrl` 等项，整文件重写会把它们丢掉。
   *
   * <p><b>为什么要写哈希</b>：Gradle 8.9 wrapper 在没有 `distributionSha256Sum` 时，
   * zip 解压失败会回退去拉同 URL 的 `.sha256`；国内镜像（腾讯云）不提供该文件（实测 404），
   * 于是失败被包装成 “The specified Gradle distribution ... does not exist”。
   * 钉住官方哈希后这条回退路径不会触发，坏下载会被明确拒绝。
   * 镜像侧的发行包已实测与官方字节一致，换镜像不影响哈希有效性。
   *
   * <p>版本不在 [GradleDistributionChecksums] 表中时**不改动**已有哈希行——
   * 宁可没有校验，也不写一个可能错误的值。
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
    // **只转义冒号**：模板 assets 就是这种写法，而 `/` 在 Properties 值里无需转义
    // （再转一道会得到 `https\:\/\/`，虽能解析但与模板风格不一致）。
    val newUrl = urlFor(mirror, gradleVersion, isAll).replace(":", "\\:")

    // **必须 quoteReplacement**：replaceFirst 的替换串会把 `\` 当转义符，
    // 直接传 `distributionUrl=https\://...` 会被吃成 `https://...`（实测）。
    val rewritten =
        syncChecksum(
            original.replaceFirst(
                Regex("distributionUrl=\\S+"),
                java.util.regex.Matcher.quoteReplacement("distributionUrl=$newUrl"),
            ),
            gradleVersion,
            isAll,
        )
    if (rewritten == original) return false
    props.writeText(rewritten)
    log.info("已把 {} 的 Gradle 源改为 {}（{}，哈希已同步）", projectRoot.name, mirror.label, gradleVersion)
    return true
  }

  /** `distributionSha256Sum` 整行（含可能存在的行尾空白）。 */
  private val checksumLine = Regex("(?m)^distributionSha256Sum=.*$")

  /**
   * 写入或校正 `distributionSha256Sum`；版本未知时原样返回。
   *
   * <p>插入位置固定在 `distributionUrl` 行的下一行，行尾风格沿用文件现有风格
   * （模板资源是 LF；Windows 上被外部工具改写过的文件可能是 CRLF）。
   */
  private fun syncChecksum(text: String, version: String, allVariant: Boolean): String {
    val expected = GradleDistributionChecksums.sha256For(version, allVariant) ?: return text
    val line = "distributionSha256Sum=$expected"
    checksumLine.find(text)?.let { match ->
      return text.replaceRange(match.range, line)
    }
    val urlLine = Regex("(?m)^distributionUrl=.*$").find(text) ?: return text
    val eol = if (text.contains("\r\n")) "\r\n" else "\n"
    val insertAt = urlLine.range.last + 1
    return text.substring(0, insertAt) + eol + line + text.substring(insertAt)
  }
}
