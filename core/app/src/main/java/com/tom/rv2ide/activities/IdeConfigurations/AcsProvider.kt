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

package com.tom.rv2ide.activities.IdeConfigurations

import android.util.Log
import com.tom.androidcodestudio.acsprovider.ACSProvider as ACSLibProvider
import com.tom.androidcodestudio.acsprovider.models.ACSConfig
import com.tom.androidcodestudio.acsprovider.utils.DownloadCallback
import com.tom.rv2ide.BuildConfig
import com.tom.rv2ide.utils.Environment
import java.io.File
import kotlinx.coroutines.runBlocking

/**
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
 *
 * Interface reflection for ACS (Android Code Studio Build System Provider) Powered by the ACS
 * library
 */
object AcsCommandInterface {

  private const val TAG = "AcsCommandInterface"

  /** ACS download directory */
  private val ACS_DOWNLOAD_DIR = File("${Environment.HOME}/acs")
  private val ACS_TEMP_DIR = File("${Environment.TMP_DIR}")

  /** Lazy initialization of ACS library provider */
  private val acsLibProvider: ACSLibProvider by lazy {
    ACSLibProvider(downloadDir = ACS_DOWNLOAD_DIR, tempDir = ACS_TEMP_DIR, enableLogging = false)
  }

  /** Supported architectures for ACS packages */
  enum class Architecture(val value: String) {
    ARM64_V8A("arm64-v8a"),
    X86_64("x86_64"),
    ARM_V7A("armeabi-v7a"),
  }

  /** Available fields that can be retrieved from manifest */
  enum class ManifestField(val value: String) {
    VERSION("version"),
    URL("url"),
    FILENAME("filename"),
    SHA256("sha256"),
  }

  /** Result wrapper for ACS command execution */
  data class AcsResult(
      val success: Boolean,
      val output: String,
      val errorOutput: String,
      val exitCode: Int,
  )

  /** Package information from manifest */
  data class PackageInfo(
      val id: String,
      val architecture: String,
      val version: String,
      val filename: String,
      val url: String,
      val sha256: String,
  )

  /** ACS command builder for fluent API */
  class AcsCommandBuilder {
    private var manifestUrl: String? = null
    private var architecture: Architecture? = null
    private var packageId: String? = null
    private var version: String? = null
    private var field: ManifestField? = null
    private var directUrl: String? = null
    private var shouldDownload: Boolean = false
    private var shouldListVersions: Boolean = false
    private var shouldShowHelp: Boolean = false

    fun readFrom(manifestUrl: String): AcsCommandBuilder {
      this.manifestUrl = manifestUrl
      return this
    }

    fun getForArch(architecture: Architecture): AcsCommandBuilder {
      this.architecture = architecture
      return this
    }

    fun getField(field: ManifestField): AcsCommandBuilder {
      this.field = field
      return this
    }

    fun withPackageId(packageId: String): AcsCommandBuilder {
      this.packageId = packageId
      return this
    }

    fun withVersion(version: String): AcsCommandBuilder {
      this.version = version
      return this
    }

    fun listVersions(): AcsCommandBuilder {
      this.shouldListVersions = true
      return this
    }

    fun downloadFromUrl(url: String): AcsCommandBuilder {
      this.directUrl = url
      return this
    }

    fun enableDownload(): AcsCommandBuilder {
      this.shouldDownload = true
      return this
    }

    fun showHelp(): AcsCommandBuilder {
      this.shouldShowHelp = true
      return this
    }

    /** Execute the built command */
    fun execute(): AcsResult {
      return try {
        if (shouldShowHelp) {
          return AcsResult(success = true, output = getHelpText(), errorOutput = "", exitCode = 0)
        }

        if (shouldListVersions) {
          return executeListVersions()
        }

        if (shouldDownload) {
          return executeDownload()
        }

        if (field != null) {
          return executeGetField()
        }

        if (directUrl != null) {
          return AcsResult(success = true, output = directUrl!!, errorOutput = "", exitCode = 0)
        }

        AcsResult(
            success = false,
            output = "",
            errorOutput = "No valid operation specified",
            exitCode = 1,
        )
      } catch (e: Exception) {
        AcsResult(success = false, output = "", errorOutput = "Error: ${e.message}", exitCode = 1)
      }
    }

    private fun executeListVersions(): AcsResult {
      return runBlocking {
        try {
          // 镜像优先，失败回退上游（见 AcsProvider.MIRROR_MANIFEST_URL）
          val jsonContent = downloadManifestWithFallback(manifestUrl!!, silent = true)
          val versions =
              acsLibProvider.listAvailableVersions(jsonContent, architecture!!.value, packageId)

          val output =
              if (versions.isEmpty()) {
                "No versions found for the specified criteria"
              } else {
                "Available versions:\n" + versions.joinToString("\n") { "  $it" }
              }

          AcsResult(success = true, output = output, errorOutput = "", exitCode = 0)
        } catch (e: Exception) {
          // 必须留日志：这段错误此前只塞进 errorOutput 显示在 flashbar 上，而 flashbar
          // 一闪而过、logcat 里什么都没有。用户报「加载不出 NDK 版本」时无从查起——
          // 是网络不通、manifest 结构变了、还是解析失败，全都看不出来。
          Log.e(
              TAG,
              "listVersions 失败: manifest=$manifestUrl arch=${architecture?.value} " +
                  "packageId=$packageId",
              e,
          )
          AcsResult(
              success = false,
              output = "",
              errorOutput = "Error: ${e.message}",
              exitCode = 1,
          )
        }
      }
    }

    /**
     * 下载 manifest：主源失败回退备用源（双向）。
     *
     * <p>传入的 [url] 是镜像或上游地址时，首次失败后改试另一侧
     * （见 [AcsProvider.otherManifestUrl]）；自定义地址直接抛错。
     * 回退仅覆盖「manifest 拉取」一步——包体下载（downloadPackage 内部）
     * 仍按 manifest 里写的 URL 走。
     */
    private suspend fun downloadManifestWithFallback(url: String, silent: Boolean): String {
      return try {
        acsLibProvider.downloadJson(url, silent = silent)
      } catch (e: Exception) {
        // 双向回退：Gitee 主源失败试 GitHub，GitHub 主源失败试 Gitee（主源由
        // CN_MIRROR 决定，见 AcsProvider.getManifestUrl）。自定义 URL 不回退。
        val other = AcsProvider.otherManifestUrl(url)
        if (other != null && other != url) {
          Log.w(TAG, "manifest 拉取失败，回退备用源: $url -> $other", e)
          acsLibProvider.downloadJson(other, silent = silent)
        } else {
          throw e
        }
      }
    }

    private fun executeDownload(): AcsResult {
      return runBlocking {
        try {
          val config =
              ACSConfig(
                  jsonUrl = manifestUrl,
                  architecture = architecture!!.value,
                  packageId = packageId,
                  version = version,
                  directUrl = directUrl,
                  shouldDownload = true,
                  // 双向回退：主源为镜像或上游时，备用源各取另一侧；自定义 URL 不回退。
                  fallbackJsonUrl = manifestUrl?.let { AcsProvider.otherManifestUrl(it) },
              )

          val file =
              acsLibProvider.downloadPackage(
                  config,
                  object : DownloadCallback {
                    override fun onProgress(
                        bytesDownloaded: Long,
                        totalBytes: Long,
                        percentage: Int,
                    ) {
                      // Progress callback - could be used to update UI
                    }

                    override fun onComplete(file: File) {
                      // Download complete
                    }

                    override fun onError(error: Throwable) {
                      // Error during download
                    }
                  },
              )

          AcsResult(
              success = true,
              output = "Successfully downloaded: ${file.absolutePath}",
              errorOutput = "",
              exitCode = 0,
          )
        } catch (e: Exception) {
          AcsResult(success = false, output = "", errorOutput = "Error: ${e.message}", exitCode = 1)
        }
      }
    }

    private fun executeGetField(): AcsResult {
      return runBlocking {
        try {
          val config =
              ACSConfig(
                  jsonUrl = manifestUrl!!,
                  architecture = architecture!!.value,
                  packageId = packageId,
                  version = version,
                  getField = field!!.value,
                  // 同 executeDownload：双向回退。
                  fallbackJsonUrl = AcsProvider.otherManifestUrl(manifestUrl!!),
              )

          val fieldValue = acsLibProvider.getField(config)

          AcsResult(success = true, output = fieldValue, errorOutput = "", exitCode = 0)
        } catch (e: Exception) {
          AcsResult(success = false, output = "", errorOutput = "Error: ${e.message}", exitCode = 1)
        }
      }
    }

    private fun getHelpText(): String {
      return """
               Android Code Studio Build System Provider - Download and manage build system packages

               Usage: acs [OPTIONS]

               Options:
                 -r, --read-from <url>     JSON manifest URL to read from
                 -a, --get-for <arch>      Architecture to get (e.g., arm64-v8a, x86_64)
                 -f, --get <field>         Field to retrieve (e.g., version, url, filename)
                 -i, --id <package_id>     Package ID to filter by (e.g., android-native-kit)
                 -v, --version <version>   Specific version to get (e.g., 28.2.13676358)
                 -l, --list-versions       List all available versions for given architecture and ID
                     --get <url>           Direct URL to download
                 -d, --download            Download the file and verify checksum
                 -h, --help                Show this help message

               Examples:
                 # List available versions for android-native-kit on arm64-v8a
                 acs -r https://example.com/manifest.json --get-for arm64-v8a -i android-native-kit --list-versions

                 # Get version for specific package and version
                 acs -r https://example.com/manifest.json --get-for arm64-v8a -i android-native-kit -v 28.2.13676358 -f version

                 # Download specific version of package
                 acs -r https://example.com/manifest.json --get-for arm64-v8a -i android-native-kit -v 29.0.14033849 --download
             """
                 .trimIndent()
    }

    /** Get the command arguments as array (for compatibility) */
    fun buildArgs(): Array<String> {
      val args = mutableListOf<String>()
      manifestUrl?.let { args.addAll(listOf("-r", it)) }
      architecture?.let { args.addAll(listOf("-a", it.value)) }
      field?.let { args.addAll(listOf("-f", it.value)) }
      packageId?.let { args.addAll(listOf("-i", it)) }
      version?.let { args.addAll(listOf("-v", it)) }
      if (shouldListVersions) args.add("--list-versions")
      directUrl?.let { args.addAll(listOf("--get", it)) }
      if (shouldDownload) args.add("-d")
      if (shouldShowHelp) args.add("-h")
      return args.toTypedArray()
    }
  }

  /** Create a new ACS command builder */
  fun newCommand(): AcsCommandBuilder = AcsCommandBuilder()

  /** List available versions for a package */
  fun listVersions(manifestUrl: String, architecture: Architecture, packageId: String): AcsResult {
    return newCommand()
        .readFrom(manifestUrl)
        .getForArch(architecture)
        .withPackageId(packageId)
        .listVersions()
        .execute()
  }

  /** Get specific field value for a package */
  fun getPackageField(
      manifestUrl: String,
      architecture: Architecture,
      packageId: String,
      field: ManifestField,
      version: String? = null,
  ): AcsResult {
    val command =
        newCommand()
            .readFrom(manifestUrl)
            .getForArch(architecture)
            .withPackageId(packageId)
            .getField(field)

    version?.let { command.withVersion(it) }

    return command.execute()
  }

  /** Download a package with verification */
  fun downloadPackage(
      manifestUrl: String,
      architecture: Architecture,
      packageId: String,
      version: String? = null,
  ): AcsResult {
    val command =
        newCommand()
            .readFrom(manifestUrl)
            .getForArch(architecture)
            .withPackageId(packageId)
            .enableDownload()

    version?.let { command.withVersion(it) }

    return command.execute()
  }

  /** Download directly from URL */
  fun downloadFromUrl(url: String): AcsResult {
    return newCommand().downloadFromUrl(url).enableDownload().execute()
  }

  /** Check if ACS is available (library is always available) */
  fun isAcsAvailable(): Boolean {
    return try {
      ACS_DOWNLOAD_DIR.exists() || ACS_DOWNLOAD_DIR.mkdirs()
    } catch (e: Exception) {
      false
    }
  }

  /** Get ACS help output */
  fun getHelp(): AcsResult {
    return newCommand().showHelp().execute()
  }
}

/** Updated AcsProvider using the new command interface */
object AcsProvider {

  const val REPO_HOST = "github.com"
  const val REPO_OWNER = "AndroidCSOfficial"
  const val REPO_NAME = "android-code-studio"
  const val ACS_BUILD_SYSTEM_REPONAME = "acs-build-system"
  const val ACS_BUILD_SYSTEM_REPOURL = "https://$REPO_HOST/$REPO_OWNER/$ACS_BUILD_SYSTEM_REPONAME"

  /**
   * 镜像 manifest（Gitee）。
   *
   * <p><b>为什么有 Gitee 镜像</b>：上游 manifest 与其指向的包全部托管在
   * `github.com/AndroidCSOfficial/acs-build-system/releases`——国内设备实测
   * `github.com` 主站与 Release 下载域均超时（NDK ~180MB 根本下不动），
   * 这是「首次安装卡在 SDK/NDK 下载」的根因。Gitee 镜像实测 700KB/s+。
   *
   * <p>镜像由 `wu-yongchengsvip_admin/acs-mirror` 仓库的 release 承载：
   * - manifest 本体：raw 文件（跟随 master 更新）
   * - 大包（>100MB，Gitee 单文件上限）切成 95MB 分卷，manifest 里以
   *   `parts` 数组给出各分卷 URL（客户端顺序下载→拼接→sha256 校验）
   * - 小包原样单 URL
   *
   * <p>sha256 与上游完全一致（转存不重打包），校验逻辑不变。
   */
  const val MIRROR_MANIFEST_URL =
      "https://gitee.com/wu-yongchengsvip_admin/acs-mirror/raw/master/acs-manifest.json"

  /** 上游 manifest 地址（GitHub）。 */
  val getUpstreamManifestUrl: String =
      "${ACS_BUILD_SYSTEM_REPOURL}/raw/refs/heads/main/acs-manifest.json"

  /**
   * 当前构建的主 manifest 源。
   *
   * <p>CN 版（`-PcnMirror=true`）以 Gitee 为主源（国内直达）；常规版以 GitHub
   * 上游为主源（与已发布 r08 主行为一致）。两者都有对方作回退
   * （[otherManifestUrl]），任一源不可用时自动切换。
   *
   * <p>声明在 [getUpstreamManifestUrl] 之后：object 属性按声明顺序初始化。
   */
  val getManifestUrl: String =
      if (BuildConfig.CN_MIRROR) MIRROR_MANIFEST_URL else getUpstreamManifestUrl

  /**
   * 与 [url] 互为备用的另一 manifest 源；自定义 URL 返回 null（不回退）。
   *
   * <p>供拉取回退（downloadManifestWithFallback）与包下载回退
   * （ACSConfig.fallbackJsonUrl）共用，保证两条路径的回退方向一致。
   */
  fun otherManifestUrl(url: String): String? =
      when (url) {
        MIRROR_MANIFEST_URL -> getUpstreamManifestUrl
        getUpstreamManifestUrl -> MIRROR_MANIFEST_URL
        else -> null
      }

  /** Enhanced ACS runner with proper command interface */
  fun acsRunner(
      packageId: String,
      artifactVersion: String? = null,
      arch: AcsCommandInterface.Architecture,
  ): AcsCommandInterface.AcsResult {
    return if (artifactVersion != null) {
      AcsCommandInterface.downloadPackage(
          manifestUrl = getManifestUrl,
          architecture = arch,
          packageId = packageId,
          version = artifactVersion,
      )
    } else {
      AcsCommandInterface.downloadPackage(
          manifestUrl = getManifestUrl,
          architecture = arch,
          packageId = packageId,
      )
    }
  }

  /** List available versions for a package */
  fun listAvailableVersions(
      packageId: String,
      arch: AcsCommandInterface.Architecture,
  ): AcsCommandInterface.AcsResult {
    return AcsCommandInterface.listVersions(
        manifestUrl = getManifestUrl,
        architecture = arch,
        packageId = packageId,
    )
  }

  /** Get package version information */
  fun getPackageVersion(
      packageId: String,
      arch: AcsCommandInterface.Architecture,
      version: String? = null,
  ): AcsCommandInterface.AcsResult {
    return AcsCommandInterface.getPackageField(
        manifestUrl = getManifestUrl,
        architecture = arch,
        packageId = packageId,
        field = AcsCommandInterface.ManifestField.VERSION,
        version = version,
    )
  }

  /** Get package URL */
  fun getPackageUrl(
      packageId: String,
      arch: AcsCommandInterface.Architecture,
      version: String? = null,
  ): AcsCommandInterface.AcsResult {
    return AcsCommandInterface.getPackageField(
        manifestUrl = getManifestUrl,
        architecture = arch,
        packageId = packageId,
        field = AcsCommandInterface.ManifestField.URL,
        version = version,
    )
  }
}
