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

package com.tom.rv2ide.templates.android

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.tom.androidcodestudio.project.manager.PackageHelper
import com.tom.androidcodestudio.project.manager.ProjectManager
import com.tom.androidcodestudio.project.manager.SdkVersionHelper
import com.tom.androidcodestudio.project.manager.builder.*
import com.tom.androidcodestudio.project.manager.builder.module.MLGradleWriter
import com.tom.androidcodestudio.project.manager.builder.module.ProguardRulesPresets
import com.tom.androidcodestudio.project.manager.builder.module.ProguardRulesWriter
import com.tom.androidcodestudio.project.manager.builder.toplevel.GradlePropertiesWriter
import com.tom.androidcodestudio.project.manager.builder.toplevel.SettingsGradleWriter
import com.tom.androidcodestudio.project.manager.builder.toplevel.TopLevelGradleWriter
import com.tom.rv2ide.templates.AtcInterface
import com.tom.rv2ide.templates.android.reactnative.ReactNativeSources
import com.tom.rv2ide.templates.preferences.Options
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
 */

/**
 * React Native 模板：用 JavaScript / TypeScript 写界面与逻辑，原生渲染到 Android。
 *
 * <h3>版本组合（定稿依据见 [ReactNativeSources] 类注释）</h3>
 *
 * RN 0.81.4 × Kotlin 2.1.20 × Gradle 8.14.3 × compileSdk 36 × minSdk 24。
 * **禁止顺手升级 RN**：0.83+ 需要 AGP 8.12、0.87 需要 compileSdk 37，都超出
 * 设备端工具链范围。
 *
 * <h3>工程形态（与官方模板的关键差异）</h3>
 *
 * **扁平化布局**：Gradle 根 = 项目根（gradlew、settings.gradle、package.json
 * 同级），Android 模块在 `app/`。官方模板把 Gradle 根放 `android/` 子目录，
 * 但 ACS 的 `GradleBuildService.kt:147-150` 在项目根查找 gradlew——子目录布局
 * 下构建按钮直接报「wrapper 不可用」。CLI 侧靠 `react-native.config.js` 的
 * `sourceDir: '.'` 指回根目录。
 *
 * **old architecture**（`newArchEnabled=false`）：RN 的 NdkConfiguratorUtils
 * 在 old arch 下完全跳过 NDK/CMake 配置，纯预编译 aar——设备端零 C++ 编译。
 *
 * **node 显式注入**：settings 的 autolinking 与 build 的 codegen/bundle
 * 两个位点都写死 Termux node 的绝对路径（daemon 没有 Termux PATH）。
 *
 * <h3>npm install 是硬前置</h3>
 *
 * RN 的 Gradle 插件与 autolinking 全部从 `node_modules` 加载——缺失时
 * **settings 配置阶段**即失败（不是等到编译）。create() 完成后会提示用户在
 * 终端执行 npm install（README 里也有完整说明）。
 */
class ReactNative : Template {
  override val displayName = "React Native"
  override val description =
      "用 JavaScript / TypeScript 写界面与逻辑，原生渲染到 Android；" +
          "首次使用需在终端执行 npm install 安装 JS 依赖"
  override val templateType = Template.TemplateType.OTHER

  private val projectStructBuilder = ProjectStructBuilder()
  private val activityWriter = ActivityWriter()
  private val proguardRulesWriter = ProguardRulesWriter()

  // assets/ReactNative/ 由 CMP 模板的 wrapper + 图标资源复制而来，
  // 另加 RN 专用的 debug.keystore 与 rn_edit_text_material.xml。
  private val ASSETS_BASE_PATH = ReactNative::class.simpleName
  private val ASSETS_RESOURCES_PATH = "$ASSETS_BASE_PATH/resources"
  private val ASSETS_GRADLE_PATH = "$ASSETS_BASE_PATH/gradle"
  private val ASSETS_APP_PATH = "$ASSETS_BASE_PATH/app"

  override fun configureOptions() {
    // 与 QuickDevelop/CMP 同款处理：保存/写回 minSdk，避免「返回重选模板」
    // 路径上 resetToDefaults 把它改回 21 造成 UI 显示与实际生成不一致。
    val userMinSdk = Options.OPT_MIN_SDK
    Options.resetToDefaults()
    Options.OPT_MIN_SDK = userMinSdk
    Options.OPT_IS_NATIVE_CPP = false
    Options.OPT_IS_NATIVE_GAME_ACTIVITY = false
    Options.OPT_BUILD_SYSTEM_USE_CMAKE = false
  }

  override suspend fun create(
      context: Context,
      listener: AtcInterface.TemplateCreationListener?,
      options: TemplateOptions,
  ) =
      withContext(Dispatchers.IO) {
        try {
          Log.d(TAG, "create() called - START")

          withContext(Dispatchers.Main) {
            Toast.makeText(context, "Creating React Native project...", Toast.LENGTH_SHORT).show()
          }

          val packageHelper =
              PackageHelper.createForProject(context, options.projectName.lowercase() + "_project")
          packageHelper.setPackageIdBlocking(options.packageId)

          val sdkHelper = SdkVersionHelper.getInstance(context)
          // 落盘到全局 SDK 偏好的 minSdk 也要钳到 24，与 build 文件里的
          // maxOf(options.minSdk, RN_MIN_SDK) 保持一致。
          sdkHelper.setAllSdkVersionsBlocking(maxOf(options.minSdk, RN_MIN_SDK), 36, 36)

          val projectRoot = File(options.saveLocation, options.projectName)
          Log.d(TAG, "Project root: ${projectRoot.absolutePath}")

          projectRoot.mkdirs()

          // app/ 模块的目录骨架（KOTLIN → src/main/kotlin/）。
          val structResult =
              projectStructBuilder.buildProjectStructure(
                  moduleName = APP_MODULE_DIR,
                  projectType = ProjectType.KOTLIN,
                  packageId = packageHelper.getPackageId(),
                  baseDir = projectRoot,
                  hasLayout = false,
              )

          if (!structResult.success) {
            Log.e(TAG, "Structure creation failed: ${structResult.message}")
            withContext(Dispatchers.Main) {
              listener?.onTemplateCreated(false, structResult.message)
            }
            return@withContext
          }

          Log.d(TAG, "Project structure created successfully")

          copyWrapperFiles(context, projectRoot)

          // ---- settings.gradle（Groovy：RN 插件与文档全用 Groovy）----
          // 不用 SettingsGradleWriter——RN 的 settings 含 includeBuild 与自定义
          // autolink 命令，结构化 writer 表达不了。直接写文本。
          File(projectRoot, "settings.gradle")
              .writeText(ReactNativeSources.settingsGradle(options.projectName))

          // ---- 根 build.gradle ----
          // minSdk 传钳制后的值：用户选 21 时实际按 24 生成，
          // 与 setAllSdkVersionsBlocking 落盘的全局偏好保持一致。
          File(projectRoot, "build.gradle")
              .writeText(
                  ReactNativeSources.rootBuildGradle(maxOf(options.minSdk, RN_MIN_SDK)))

          // ---- gradle.properties ----
          File(projectRoot, "gradle.properties")
              .writeText(ReactNativeSources.gradleProperties())

          // ---- react-native.config.js（扁平化布局的 sourceDir 声明）----
          File(projectRoot, "react-native.config.js")
              .writeText(ReactNativeSources.reactNativeConfigJs())

          // ---- app 模块 build.gradle ----
          writeAppModule(projectRoot, packageHelper.getPackageId())

          // ---- Android 壳源码 / manifest / res ----
          writeAndroidSources(context, projectRoot, packageHelper.getPackageId(), options)

          // ---- JS 侧文件 ----
          writeJsFiles(projectRoot, options.projectName)

          // ---- .gitignore ----
          File(projectRoot, ".gitignore").writeText(ReactNativeSources.gitignore())

          // ---- GitHub Actions workflow（云端出包）----
          val workflowDir = File(projectRoot, ".github/workflows")
          workflowDir.mkdirs()
          File(workflowDir, "react-native.yml")
              .writeText(ReactNativeSources.ciWorkflow(options.projectName))

          // ---- README ----
          File(projectRoot, "README.md")
              .writeText(
                  ReactNativeSources.projectReadme(
                      packageHelper.getPackageId(), options.projectName))

          // Track project
          val projectManager = ProjectManager.getInstance(context)
          val projectInfo =
              projectManager.createTemplateProjectInfo(
                  projectName = options.projectName,
                  projectDir = projectRoot.absolutePath,
                  projectType = "React Native",
              )
          projectManager.addProjectBlocking(projectInfo)

          Log.d(TAG, "Project created successfully")

          withContext(Dispatchers.Main) {
            listener?.onTemplateCreated(
                true,
                "React Native project created at ${projectRoot.absolutePath}",
            )
            // 3 参重载：让 TemplateListFragment / MainFragment 自动打开新项目
            // （与 CMP 同款——2 参版本只弹提示，不触发打开）
            listener?.onTemplateCreated(true, "", projectRoot)
          }
        } catch (e: Exception) {
          Log.e(TAG, "Error creating project", e)
          withContext(Dispatchers.Main) {
            listener?.onTemplateCreated(false, "Error: ${e.message}")
          }
        }
      }

  /**
   * `app/build.gradle`——手写文本而不是 MLGradleWriter。
   *
   * 原因：`react { }` 块是 RN 插件的自由形态扩展，结构化 writer 的
   * addPlugin/dependency 那套 DSL 表达不了；且整个文件的形态与官方模板
   * 逐行对齐，手写更利于日后对照升级。
   */
  private fun writeAppModule(projectRoot: File, packageId: String) {
    val appDir = File(projectRoot, APP_MODULE_DIR)
    File(appDir, "build.gradle").writeText(ReactNativeSources.appBuildGradle(packageId))

    // proguard 规则（release 用，默认 minify 关闭但文件必须存在）
    proguardRulesWriter.writeToFile(appDir, ProguardRulesPresets.DEFAULT_ANDROID)
  }

  /** Android 壳源码、manifest 与资源。 */
  private fun writeAndroidSources(
      context: Context,
      projectRoot: File,
      packageId: String,
      options: TemplateOptions,
  ) {
    val appDir = File(projectRoot, APP_MODULE_DIR)
    val pkgPath = packageId.replace('.', '/')

    // MainApplication / MainActivity（Kotlin，与 RN 官方模板一致）
    val kotlinDir = File(appDir, "src/main/kotlin/$pkgPath")
    kotlinDir.mkdirs()
    File(kotlinDir, "MainApplication.kt")
        .writeText(ReactNativeSources.mainApplication(packageId))
    File(kotlinDir, "MainActivity.kt")
        .writeText(ReactNativeSources.mainActivity(packageId, options.projectName))

    // AndroidManifest.xml
    val manifestDir = File(appDir, "src/main")
    activityWriter.createFile(
        manifestDir, "AndroidManifest", "xml", ReactNativeSources.appManifest())

    // values/strings.xml + styles.xml
    val valuesDir = File(appDir, "src/main/res/values")
    valuesDir.mkdirs()
    File(valuesDir, "strings.xml").writeText(ReactNativeSources.stringsXml(options.projectName))
    File(valuesDir, "styles.xml").writeText(ReactNativeSources.stylesXml())

    // 图标等资源 + rn_edit_text_material.xml
    copyResourceFiles(context, projectRoot, appDir)

    // debug.keystore（RN 官方模板内置的调试签名，release 块也引用它）
    copyAssetFile(context, "$ASSETS_APP_PATH/debug.keystore", File(appDir, "debug.keystore"))
  }

  /** JS 侧文件（扁平化：全部放项目根）。 */
  private fun writeJsFiles(projectRoot: File, projectName: String) {
    File(projectRoot, "package.json").writeText(ReactNativeSources.packageJson(projectName))
    File(projectRoot, "index.js").writeText(ReactNativeSources.indexJs())
    File(projectRoot, "App.tsx").writeText(ReactNativeSources.appTsx(projectName))
    File(projectRoot, "app.json").writeText(ReactNativeSources.appJson(projectName))
    File(projectRoot, "metro.config.js").writeText(ReactNativeSources.metroConfigJs())
    File(projectRoot, "babel.config.js").writeText(ReactNativeSources.babelConfigJs())
  }

  /**
   * 拷贝图标等资源到 `app/src/main/res`。
   *
   * 拷贝后校验 manifest 引用的 `mipmap-anydpi-v26/ic_launcher.xml` 存在：
   * `copyAssetFolder` 只记日志不抛异常，缺失时让项目过不了 aapt 却报创建成功，
   * 就是假成功（LibGdx 的教训）。失败时清掉半成品 res 目录，保证同名重试可干净开始。
   */
  private fun copyResourceFiles(context: Context, projectRoot: File, appDir: File) {
    val resDestDir = File(appDir, "src/main/res")
    try {
      Log.d(TAG, "Copying resource files...")

      copyAssetFolder(context, ASSETS_RESOURCES_PATH, resDestDir)

      val required = listOf("mipmap-anydpi-v26/ic_launcher.xml")
      val missing = required.filterNot { File(resDestDir, it).isFile }
      if (missing.isNotEmpty()) {
        throw IllegalStateException(
            "Missing required resources after copy: ${missing.joinToString()}. " +
                "The generated project would fail at the aapt stage.")
      }

      Log.d(TAG, "Resource files copied successfully")
    } catch (e: Exception) {
      Log.e(TAG, "Error copying resource files", e)
      resDestDir.deleteRecursively()
      throw e
    }
  }

  /** Copy wrapper files (gradlew, gradlew.bat, gradle/wrapper folder) from assets */
  private fun copyWrapperFiles(context: Context, projectRoot: File) {
    try {
      Log.d(TAG, "Copying wrapper files...")

      copyAssetFile(context, "$ASSETS_GRADLE_PATH/gradlew", File(projectRoot, "gradlew"))
      File(projectRoot, "gradlew").setExecutable(true, false)
      copyAssetFile(context, "$ASSETS_GRADLE_PATH/gradlew.bat", File(projectRoot, "gradlew.bat"))

      val wrapperDestDir = File(projectRoot, "gradle/wrapper")
      copyAssetFolder(context, "$ASSETS_GRADLE_PATH/wrapper", wrapperDestDir)

      Log.d(TAG, "Wrapper files copied successfully")
    } catch (e: Exception) {
      Log.e(TAG, "Error copying wrapper files", e)
      // Don't throw, just log - wrapper files are optional
    }
  }

  /** Recursively copy a folder from assets to destination */
  private fun copyAssetFolder(context: Context, assetPath: String, destDir: File) {
    try {
      val files = context.assets.list(assetPath) ?: emptyArray()

      if (files.isEmpty()) {
        // It's a file, not a directory
        copyAssetFile(context, assetPath, destDir)
      } else {
        destDir.mkdirs()
        for (fileName in files) {
          copyAssetFolder(context, "$assetPath/$fileName", File(destDir, fileName))
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error copying asset folder: $assetPath", e)
    }
  }

  /** Copy a single file from assets to destination */
  private fun copyAssetFile(context: Context, assetPath: String, destFile: File) {
    try {
      val inputStream: InputStream = context.assets.open(assetPath)
      val outputStream = FileOutputStream(destFile)

      inputStream.use { input -> outputStream.use { output -> input.copyTo(output) } }

      Log.d(TAG, "Copied: $assetPath -> ${destFile.absolutePath}")
    } catch (e: Exception) {
      Log.e(TAG, "Error copying file: $assetPath", e)
      throw e
    }
  }

  private companion object {
    const val TAG = "ReactNative"
    const val APP_MODULE_DIR = "app"

    /** RN 0.81 的 minSdk 要求（官方模板值）。向导下拉从 21 起选，这里兜底。 */
    const val RN_MIN_SDK = 24
  }
}
