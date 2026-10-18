/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.build.config

import org.gradle.api.Project

/** @author Akash Yadav */
object ProjectConfig {

  /*
  * 本 fork 的仓库地址（App 身份来源）。BuildInfo.REPO_OWNER/REPO_URL 由此生成，
  * 关于页 / 贡献者页 / 崩溃报告 / CONTRIBUTING / 赞助链接 / 应用内更新跳转全派生自它。
  *
  * 注意：构建系统仓库（acs-build-system，CMake/NDK 安装用）是**另一个仓库**，
  * 见 AcsProvider.REPO_OWNER——那个仍指向上游，此处不要改（本 fork 没有该仓库）。
  */
  const val REPO_HOST = "github.com"
  const val REPO_OWNER = "706412584"
  const val REPO_NAME = "android-code-studio"

  const val ACS_BUILD_SYSTEM_REPONAME = "acs-build-system"
  const val ACS_BUILD_SYSTEM_REPOURL = "https://$REPO_HOST/$REPO_OWNER/$ACS_BUILD_SYSTEM_REPONAME"
  
  const val REPO_URL = "https://$REPO_HOST/$REPO_OWNER/$REPO_NAME"
  const val SCM_GIT =
    "scm:git:git://$REPO_HOST/$REPO_OWNER/$REPO_NAME.git"
  const val SCM_SSH =
    "scm:git:ssh://git@$REPO_HOST/$REPO_OWNER/$REPO_NAME.git"

  // const val PROJECT_SITE = "https://m.androidide.com"
  const val PROJECT_SITE = REPO_URL
}

private var shouldPrintNotAGitRepoWarning = true
private var shouldPrintVersionName = true

val Project.simpleVersionName: String
  get() {
    val version = rootProject.version.toString()
    val simpleVersion = version

    if (shouldPrintVersionName) {
       logger.warn("Version name is '$simpleVersion'")
       shouldPrintVersionName = false
    }
    
    return simpleVersion
  }

private var shouldPrintVersionCode = true
val Project.projectVersionCode: Int
  get() {

    // 解析顺序：环境变量 → 仓库根 updater.json → 硬编码兜底。
    //
    // 必须与 updater.json 里的 versionCode 保持同步：应用启动时拿本地 versionCode
    // 与清单比对来决定是否提示更新。若这里落后于清单，每次启动都会弹「可用更新」——
    // 即使装的已经是最新代码。反过来，本地包若低于设备上已装的版本，覆盖安装虽然
    // 能成功（-d 允许降级），但应用内更新会误判「已是最新」。
    //
    // 此前只有环境变量 + 硬编码 1027：CI/发布脚本会注入，本地 `assembleDebug` 却
    // 固定拿到 1027——而清单已到 1034，本地包的版本号因此落后于线上。
    val fromEnv = System.getenv("PROJECT_CONFIG_KT_BASE_VERSION_CODE")?.toIntOrNull()
    val baseVersionCode = fromEnv ?: readUpdaterJsonVersionCode() ?: 1027
    // 兜底值的历史含义：中间位代表修订号，如 1.0.0+gh.r0<2>。

    val versionCode = baseVersionCode

    if (shouldPrintVersionCode) {
      logger.warn(
        "Version code is '$versionCode' (source: ${if (fromEnv != null) "env" else "updater.json"})")
      shouldPrintVersionCode = false
    }

    return versionCode
  }

/**
 * 从仓库根的 `updater.json` 读 `baseVersionCode`。
 *
 * <p>不引 JSON 库：build-logic 是独立编译的 Kotlin 模块，加依赖会拖慢配置阶段；
 * 而这里只需要一个整数。正则容忍空白与字段顺序。
 *
 * @return 解析失败/文件不存在时返回 null，由调用方回落到硬编码兜底
 */
private fun Project.readUpdaterJsonVersionCode(): Int? {
  return try {
    val file = rootDir.resolve("updater.json")
    if (!file.isFile) {
      return null
    }
    val text = file.readText()
    Regex("\"baseVersionCode\"\\s*:\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull()
  } catch (e: Exception) {
    // 构建配置阶段不该因为一个可选元数据文件失败。
    null
  }
}
/**
 * The version name which is used to download the artifacts at runtime.
 *
 * The value varies based on the following cases :
 * - For CI and F-Droid builds: same as [publishingVersion].
 * - For local builds: `latest.integration` to make sure that Gradle downloads the latest snapshots.
 */
val Project.downloadVersion: String
  get() {
      // sometimes, when working locally, Gradle fails to download the latest snapshot version
      // this may cause issues while initializing the project in AndroidIDE
      return VersionUtils.getLatestSnapshotVersion("gradle-plugin")
  }
  