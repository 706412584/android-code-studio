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
import com.tom.rv2ide.templates.AtcInterface
import com.tom.rv2ide.templates.android.flutter.FlutterSources
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
 * Flutter 模板：用 Dart 写一份代码，构建 Android APK 与 Web 站点。
 *
 * <h3>设备端不构建（本模板与其它模板的根本差异）</h3>
 *
 * Flutter 的构建必须有完整 Flutter SDK（Dart 编译器 + 引擎工具链）：
 * - `settings.gradle.kts` 的 `require(flutterSdkPath != null)` 缺 SDK 即失败
 * - Dart 产物（kernel_blob.bin / app.so）必须由 SDK 内 `flutter assemble`
 *   生成，任何 Maven 都没有
 * - 官方 HostPlatform 枚举不含 Android/bionic（flutter issue #75864 至今 open）
 *
 * 因此本模板生成**完整工程骨架**（flutter create 3.47.7 产物固化到 assets）
 * + **GitHub Actions workflow**：设备端只编辑 Dart 代码（纯文本打开——
 * ACS 编辑器暂未内置 Dart 语言支持），推送到 GitHub 后云端出 APK 与
 * Web 站点。README 显著说明了这一点。
 *
 * <h3>版本组合（flutter create 实测产物）</h3>
 *
 * Flutter 3.47.7 × Dart 3.13.5 × Gradle 9.3.1 × AGP 9.1.0 × Kotlin 2.4.0。
 * 这些值由 Flutter 工具链写入（templateDefaultGradleVersion 等），不要手改。
 *
 * <h3>工程形态</h3>
 *
 * 标准 Flutter 布局：Gradle 根在 `android/` 子目录（Flutter 工具链的固定
 * 约定——`flutter build` 从 android/ 启动 gradle），项目根是 Dart 包根
 * （pubspec.yaml）。ACS 的设备端构建服务虽然只认项目根的 gradlew，但本
 * 模板设备端本来就不构建，所以这个布局差异无影响。
 */
class Flutter : Template {
  override val displayName = "Flutter"
  override val description =
      "跨平台：一份 Dart 代码出 Android、iOS、网页。编辑器内置 Dart 补全，" +
          "APK 与网页产物由「云编译」云端出包"
  override val isCrossPlatform = true
  override val templateType = Template.TemplateType.OTHER

  // assets/Flutter/ 由 flutter create 3.47.7 的真实产物固化而来
  // （wrapper、图标、res、web 资源等二进制/静态文件）。
  private val ASSETS_BASE_PATH = Flutter::class.simpleName
  private val ASSETS_ANDROID_PATH = "$ASSETS_BASE_PATH/android"
  private val ASSETS_WEB_PATH = "$ASSETS_BASE_PATH/web"

  override fun configureOptions() {
    // 与其它模板同款处理：保存/写回 minSdk。
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
  ) {
    withContext(Dispatchers.IO) {
        try {
          Log.d(TAG, "create() called - START")

          withContext(Dispatchers.Main) {
            Toast.makeText(context, "Creating Flutter project...", Toast.LENGTH_SHORT).show()
          }

          val packageHelper =
              PackageHelper.createForProject(context, options.projectName.lowercase() + "_project")
          packageHelper.setPackageIdBlocking(options.packageId)

          // Flutter 3.47 的实际 minSdk 是 24（flutter.minSdkVersion，
          // gradle_utils.dart:63）。落盘到全局 SDK 偏好时钳到同一值——
          // 否则用户选 21 时，项目元数据显示 21、实际构建产物是 24
          // （审查 M4，与 CMP/RN 的钳制约定一致）。
          val sdkHelper = SdkVersionHelper.getInstance(context)
          sdkHelper.setAllSdkVersionsBlocking(maxOf(options.minSdk, FLUTTER_MIN_SDK), 34, 34)

          val projectRoot = File(options.saveLocation, options.projectName)
          Log.d(TAG, "Project root: ${projectRoot.absolutePath}")

          projectRoot.mkdirs()

          // ---- Android 平台目录（flutter create 产物固化）----
          writeAndroidPlatform(context, projectRoot, packageHelper.getPackageId(), options)

          // ---- Web 平台目录 ----
          writeWebPlatform(context, projectRoot, options.projectName)

          // ---- Dart 侧 ----
          writeDartFiles(projectRoot, options.projectName)

          // ---- 工程元数据 ----
          File(projectRoot, ".metadata").writeText(FlutterSources.metadata())
          File(projectRoot, ".gitignore").writeText(FlutterSources.gitignore())

          // ---- GitHub Actions workflow（云端出包——本模板的唯一出包路径）----
          val workflowDir = File(projectRoot, ".github/workflows")
          workflowDir.mkdirs()
          File(workflowDir, "flutter.yml")
              .writeText(FlutterSources.ciWorkflow(options.projectName))

          // ---- README ----
          File(projectRoot, "README.md")
              .writeText(
                  FlutterSources.projectReadme(
                      packageHelper.getPackageId(), options.projectName))

          // Track project
          val projectManager = ProjectManager.getInstance(context)
          val projectInfo =
              projectManager.createTemplateProjectInfo(
                  projectName = options.projectName,
                  projectDir = projectRoot.absolutePath,
                  projectType = "Flutter",
              )
          projectManager.addProjectBlocking(projectInfo)

          Log.d(TAG, "Project created successfully")

          withContext(Dispatchers.Main) {
            listener?.onTemplateCreated(
                true,
                "Flutter project created at ${projectRoot.absolutePath}",
            )
            // 3 参重载：让 TemplateListFragment / MainFragment 自动打开新项目
            listener?.onTemplateCreated(true, "", projectRoot)
          }
        } catch (e: Exception) {
          Log.e(TAG, "Error creating project", e)
          withContext(Dispatchers.Main) {
            listener?.onTemplateCreated(false, "Error: ${e.message}")
          }
        }
      }
  }

  /** Android 平台目录：Gradle 工程 + 壳源码 + 资源（全部来自 flutter create 产物）。 */
  private fun writeAndroidPlatform(
      context: Context,
      projectRoot: File,
      packageId: String,
      options: TemplateOptions,
  ) {
    val androidDir = File(projectRoot, "android")
    androidDir.mkdirs()

    // Gradle 工程文件
    File(androidDir, "settings.gradle.kts")
        .writeText(FlutterSources.androidSettingsGradleKts())
    File(androidDir, "build.gradle.kts").writeText(FlutterSources.androidRootBuildGradleKts())
    File(androidDir, "gradle.properties").writeText(FlutterSources.androidGradleProperties())
    File(androidDir, ".gitignore").writeText(FlutterSources.androidGitignore())

    // wrapper（gradlew / gradlew.bat / gradle-wrapper.jar / properties）
    copyAssetFile(context, "$ASSETS_ANDROID_PATH/gradlew", File(androidDir, "gradlew"))
    File(androidDir, "gradlew").setExecutable(true, false)
    copyAssetFile(context, "$ASSETS_ANDROID_PATH/gradlew.bat", File(androidDir, "gradlew.bat"))

    val wrapperDir = File(androidDir, "gradle/wrapper")
    wrapperDir.mkdirs()
    copyAssetFile(
        context, "$ASSETS_ANDROID_PATH/gradle/wrapper/gradle-wrapper.jar",
        File(wrapperDir, "gradle-wrapper.jar"))
    File(wrapperDir, "gradle-wrapper.properties")
        .writeText(FlutterSources.gradleWrapperProperties())

    // app 模块（mkdirs 是硬要求：File.writeText 不创建父目录，缺了它
    // build.gradle.kts 的写入直接抛 FileNotFoundException——审查 S1）
    val appDir = File(androidDir, "app")
    appDir.mkdirs()
    File(appDir, "build.gradle.kts").writeText(FlutterSources.appBuildGradleKts(packageId))

    val pkgPath = packageId.replace('.', '/')
    val kotlinDir = File(appDir, "src/main/kotlin/$pkgPath")
    kotlinDir.mkdirs()
    File(kotlinDir, "MainActivity.kt").writeText(FlutterSources.mainActivityKt(packageId))

    File(appDir, "src/main/AndroidManifest.xml")
        .writeText(FlutterSources.appManifest(options.projectName))

    // res 与 debug/profile manifest（从 assets 拷贝——二进制与多目录文件）
    copyResourceFiles(context, appDir)

    Log.d(TAG, "Android platform written at ${androidDir.absolutePath}")
  }

  /** Web 平台目录：index.html / manifest.json / 图标（文本自写、图标从 assets 拷）。 */
  private fun writeWebPlatform(context: Context, projectRoot: File, projectName: String) {
    val webDir = File(projectRoot, "web")
    webDir.mkdirs()

    File(webDir, "index.html").writeText(FlutterSources.webIndexHtml(projectName))
    File(webDir, "manifest.json").writeText(FlutterSources.webManifestJson(projectName))

    // 图标（favicon + 4 个 PWA 图标，flutter create 产物）
    copyAssetFolder(context, ASSETS_WEB_PATH, webDir)

    Log.d(TAG, "Web platform written at ${webDir.absolutePath}")
  }

  /** Dart 侧：lib/main.dart、pubspec.yaml、analysis_options、test。 */
  private fun writeDartFiles(projectRoot: File, projectName: String) {
    // pubspec 的 name 与自引用导入必须是合法 Dart 包名（小写）——
    // 项目名（如 MyFlutter）直接写进去会让 CI 的 flutter create 失败。
    val dartPackage = FlutterSources.dartPackageName(projectName)

    val libDir = File(projectRoot, "lib")
    libDir.mkdirs()
    File(libDir, "main.dart").writeText(FlutterSources.mainDart(projectName))

    val testDir = File(projectRoot, "test")
    testDir.mkdirs()
    File(testDir, "widget_test.dart").writeText(FlutterSources.widgetTest(dartPackage))

    File(projectRoot, "pubspec.yaml").writeText(FlutterSources.pubspecYaml(dartPackage))
    File(projectRoot, "analysis_options.yaml")
        .writeText(FlutterSources.analysisOptionsYaml())
  }

  /**
   * 拷贝 `assets/Flutter/android/app` 下的 res 与 debug/profile manifest。
   *
   * assets 的布局刻意与生成目录同构（`android/app/src/main/res/...`），
   * 所以直接按相对路径递归拷贝。拷贝后校验 manifest 引用的
   * `mipmap-xxxhdpi/ic_launcher.png` 存在——`copyAssetFolder` 只记日志不抛异常，
   * 缺失时让工程过不了构建却报创建成功，就是假成功（LibGdx 的教训）。
   */
  private fun copyResourceFiles(context: Context, appDir: File) {
    val resDestDir = File(appDir, "src/main/res")
    try {
      val srcBase = "$ASSETS_ANDROID_PATH/app/src"

      copyAssetFolder(context, "$srcBase/main/res", resDestDir)
      copyAssetFolder(context, "$srcBase/debug", File(appDir, "src/debug"))
      copyAssetFolder(context, "$srcBase/profile", File(appDir, "src/profile"))

      val required = listOf("mipmap-xxxhdpi/ic_launcher.png")
      val missing = required.filterNot { File(resDestDir, it).isFile }
      if (missing.isNotEmpty()) {
        throw IllegalStateException(
            "Missing required resources after copy: ${missing.joinToString()}. " +
                "The generated project would be incomplete.")
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error copying resource files", e)
      // 清掉半成品 res，保证同名重试可干净开始（CMP/RN 同款处理）
      resDestDir.deleteRecursively()
      throw e
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
    const val TAG = "Flutter"

    /**
     * Flutter 3.47 的实际 minSdk（`flutter.minSdkVersion`，gradle_utils.dart:63）。
     * 用于全局 SDK 偏好的钳制——与 build 文件里的 `flutter.minSdkVersion`
     * 保持同一语义。
     */
    const val FLUTTER_MIN_SDK = 24
  }
}
