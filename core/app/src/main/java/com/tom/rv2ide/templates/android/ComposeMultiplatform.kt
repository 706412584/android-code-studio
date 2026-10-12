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
import com.tom.androidcodestudio.project.manager.builder.module.GradleDependency
import com.tom.androidcodestudio.project.manager.builder.module.GradlePlugin
import com.tom.androidcodestudio.project.manager.builder.module.JavaVersion
import com.tom.androidcodestudio.project.manager.builder.module.MLGradleWriter
import com.tom.androidcodestudio.project.manager.builder.module.ProguardRulesPresets
import com.tom.androidcodestudio.project.manager.builder.module.ProguardRulesWriter
import com.tom.androidcodestudio.project.manager.builder.module.moduleGradleConfig
import com.tom.androidcodestudio.project.manager.builder.toplevel.GradleFileType
import com.tom.androidcodestudio.project.manager.builder.toplevel.GradlePlugin as TopLevelGradlePlugin
import com.tom.androidcodestudio.project.manager.builder.toplevel.SettingsGradleFileType
import com.tom.androidcodestudio.project.manager.builder.toplevel.SettingsGradleWriter
import com.tom.androidcodestudio.project.manager.builder.toplevel.TopLevelGradleWriter
import com.tom.androidcodestudio.project.manager.builder.toplevel.GradlePropertiesPresets
import com.tom.androidcodestudio.project.manager.builder.toplevel.GradlePropertiesWriter
import com.tom.androidcodestudio.project.manager.builder.toplevel.settingsGradleConfig
import com.tom.rv2ide.templates.*
import com.tom.rv2ide.templates.AtcInterface
import com.tom.rv2ide.templates.android.composemultiplatform.ComposeMultiplatformSources
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
 * Compose Multiplatform 模板：一份共享 Compose UI，跑 Android / Desktop / Web 三平台。
 *
 * <h3>版本组合（定稿依据见 [ComposeMultiplatformSources] 类注释）</h3>
 *
 * CMP 1.10.3 × Kotlin 2.2.20 × AGP 8.13.0。**禁止顺手升级 CMP**：1.12+ 的
 * aar-metadata 要求 compileSdk 37 / AGP 9.1+，设备端 AGP 8.13 直接解析失败。
 *
 * <h3>工程形态</h3>
 *
 * 三个模块：
 * - `app` — Android 壳。设备端构建闭环只编它（assembleDebug），标准 AGP +
 *   kotlin-android + compose 插件，与 ComposeEmptyActivity 同构；
 *   shared 以 `implementation(project(":composeApp"))` 消费。
 * - `composeApp` — KMP shared 模块（androidTarget + jvm("desktop") + wasmJs），
 *   共享 Compose UI 在 `commonMain`。**不是** java-library：androidTarget 走
 *   com.android.library（KGP 自动应用，catalog 里声明即可）。
 * - `desktop` — Desktop 壳（compose.desktop.application），云端出 fat jar。
 *
 * v1 **不生成 iOS targets**：设备（linux-aarch64 host）上 iOS 编译从未被 K/N
 * 支持，无 Xcode 环境触发 iOS 任务直接 MissingXcodeException。共享代码全放
 * commonMain，以后在云端加 targets 不需要动源码结构（workflow 里有现成注释）。
 *
 * <h3>与其它模板的差异</h3>
 *
 * - **强制 KTS**：向导有 Groovy/KTS 开关（`Options.OPT_USE_GRADLE_KTS`），但
 *   KMP 插件的 Groovy DSL 与 KTS 在源集写法上差异不小（`val desktopMain by
 *   getting` 这类语法没有 Groovy 等价物），本模板只产出 KTS——
 *   [create] 里无视 `options.useKts`。
 * - **composeApp/desktop 的 build 文件是手写字符串**（[ComposeMultiplatformSources]），
 *   不走 MLGradleWriter——KMP 源集 DSL 表达不了。`app` 模块仍走结构化构建器。
 * - **强制 Kotlin**：向导按 `simpleName.contains("Compose")` 自动选 Kotlin
 *   （`AtcWizardDialog.createProject`），类名命中，无需额外处理。
 * - **不用 androidx.compose BOM**：CMP 的 `org.jetbrains.compose.*` 构件自带
 *   统一版本，混用 BOM 会拉进两套 androidx compose 版本。
 * - 无 jniLibs、无 NDK；assets 只带 wrapper 与图标资源（源码全是字符串生成器）。
 */
class ComposeMultiplatform : Template {
  override val displayName = "Compose Multiplatform"
  override val description =
      "跨平台首选：一份 Kotlin + Compose 代码出 Android、桌面、网页三个平台。" +
          "手机直接构建安卓 APK；桌面与网页产物由「云编译」一键出包"
  override val isCrossPlatform = true
  override val templateType = Template.TemplateType.COMPOSE

  private val projectStructBuilder = ProjectStructBuilder()
  private val activityWriter = ActivityWriter()
  private val topLevelGradleWriter = TopLevelGradleWriter()
  private val settingsGradleWriter = SettingsGradleWriter()
  private val gradlePropertiesWriter = GradlePropertiesWriter()
  private val moduleGradleWriter = MLGradleWriter()
  private val proguardRulesWriter = ProguardRulesWriter()

  private val PRIMARY_MODULES = listOf(":app", ComposeMultiplatformSources.SHARED_MODULE_PATH, ComposeMultiplatformSources.DESKTOP_MODULE_PATH)

  // Asset paths（assets/ComposeMultiplatform/ 由 QuickDevelop 的 wrapper + 图标资源
  // 复制而来；themes.xml 与 ticode 已删——本模板 manifest 用系统主题）
  private val ASSETS_BASE_PATH = ComposeMultiplatform::class.simpleName
  private val ASSETS_RESOURCES_PATH = "$ASSETS_BASE_PATH/resources"
  private val ASSETS_GRADLE_PATH = "$ASSETS_BASE_PATH/gradle"

  override fun configureOptions() {
    // 与 QuickDevelop 同款处理：保存/写回 minSdk，避免「返回重选模板」路径上
    // resetToDefaults 把它改回 21 造成 UI 显示与实际生成不一致。
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
            Toast.makeText(context, "Creating Compose Multiplatform project...", Toast.LENGTH_SHORT)
                .show()
          }

          val packageHelper =
              PackageHelper.createForProject(context, options.projectName.lowercase() + "_project")
          packageHelper.setPackageIdBlocking(options.packageId)

          val sdkHelper = SdkVersionHelper.getInstance(context)
          // 落盘到全局 SDK 偏好的 minSdk 也要钳到 23，与 build 文件里的
          // maxOf(options.minSdk, CMP_MIN_SDK) 保持一致——否则用户选 21 时，
          // 项目元数据显示 21、实际 Gradle 是 23（对抗审查 moderate #1）。
          sdkHelper.setAllSdkVersionsBlocking(
              maxOf(options.minSdk, CMP_MIN_SDK), 34, 34)

          val projectRoot = File(options.saveLocation, options.projectName)
          Log.d(TAG, "Project root: ${projectRoot.absolutePath}")

          projectRoot.mkdirs()

          // :app 的目录骨架（KOTLIN → src/main/kotlin/）。composeApp/desktop 的
          // 目录由 writeSharedModule / writeDesktopModule 自建。
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

          // ---- 版本目录（手写：KMP 目录的 plugins 节有五个插件，见 writeVersionCatalog）----
          val gradleDir = File(projectRoot, "gradle")
          gradleDir.mkdirs()
          writeVersionCatalog(gradleDir)

          // ---- settings.gradle.kts：带国内镜像的仓库预设 + include 三模块 ----
          // 不用 RepositoryPresets.STANDARD_KTS（它没有镜像，会影响所有模板所以
          // 不能动）：CMP 的依赖体量大（KGP+CMP 约 300MB），设备直连
          // Maven Central 高延迟会导致插件 marker 解析超时（2026-10-10 实机
          // 验证：阿里云镜像前置后 sync 成功）。
          settingsGradleWriter.writeToFile(
              projectRoot,
              SettingsGradleFileType.KTS,
              settingsGradleConfig {
                pluginManagement(MIRRORED_PLUGIN_REPOSITORIES)
                dependencyResolution(MIRRORED_DEPENDENCY_REPOSITORIES)
                rootProjectName(options.projectName)
                include(PRIMARY_MODULES)
              },
          )

          // ---- 顶层 build.gradle.kts：六个插件 apply false ----
          // android.library 由 KGP 的 androidTarget 隐式需要；kotlin.android 是
          // :app 壳用的。任一漏声明，子模块 alias 解析时会报
          // 「plugin is already on the classpath with an unknown version」
          // （QuickDevelop 的 :ticode 教训）。
          //
          // includeCleanTask=false 是硬要求：writer 默认会注册
          // tasks.register<Delete>("clean")，而任一子模块 apply 了
          // org.jetbrains.kotlin.multiplatform 时 LifecycleBasePlugin 会先注册
          // 同名 clean——顶层再注册就撞出「Cannot add task 'clean' as a task
          // with that name already exists」（KMP 工程独有，单模块模板不触发）。
          topLevelGradleWriter.writeToFile(
              projectRoot,
              GradleFileType.KTS,
              listOf(
                  TopLevelGradlePlugin(id = "android.application", useAlias = true),
                  TopLevelGradlePlugin(id = "android.library", useAlias = true),
                  TopLevelGradlePlugin(id = "kotlin.android", useAlias = true),
                  TopLevelGradlePlugin(id = "kotlin.multiplatform", useAlias = true),
                  TopLevelGradlePlugin(id = "kotlin.compose", useAlias = true),
                  TopLevelGradlePlugin(id = "compose", useAlias = true),
              ),
              includeCleanTask = false,
          )

          gradlePropertiesWriter.writeToFile(
              projectRoot, GradlePropertiesPresets.STANDARD_ANDROID)

          // ---- :app（Android 壳，结构化构建器）----
          writeAppModule(projectRoot, packageHelper.getPackageId(), options)

          // ---- :composeApp（shared KMP，手写 Gradle）----
          writeSharedModule(projectRoot, packageHelper.getPackageId(), options)

          // ---- 共享 UI 源码 ----
          val sharedSrcDir =
              File(
                  projectRoot,
                  "${ComposeMultiplatformSources.SHARED_MODULE_DIR}/src/commonMain/kotlin/${pkgPath(packageHelper.getPackageId())}/shared")
          sharedSrcDir.mkdirs()
          File(sharedSrcDir, "App.kt")
              .writeText(
                  ComposeMultiplatformSources.sharedApp(packageHelper.getPackageId()))

          // ---- :desktop（Desktop 壳 + 入口）----
          writeDesktopModule(projectRoot, packageHelper.getPackageId(), options.projectName)

          // ---- Android 壳源码 / manifest / strings / themes ----
          val activityConfig = activityConfig {
            moduleName(APP_MODULE_DIR)
            languageType(LanguageType.KOTLIN)
            packageId(packageHelper.getPackageId())
            activityName("MainActivity")
            content(ComposeMultiplatformSources.mainActivity(packageHelper.getPackageId()))
          }
          activityWriter.writeToFile(projectRoot, activityConfig)

          val manifestDir = File(projectRoot, "app/src/main")
          activityWriter.createFile(
              manifestDir, "AndroidManifest", "xml", ComposeMultiplatformSources.appManifest())

          val valuesDir = File(projectRoot, "app/src/main/res/values")
          activityWriter.createFile(
              valuesDir,
              "strings",
              "xml",
              "<resources><string name=\"app_name\">${options.projectName}</string></resources>")

          // 图标等资源。assets 里 QuickDevelop 的 themes.xml 已在源里删除，
          // 拷贝后仍校验必需资源——缺失时 aapt 阶段才报错就是假成功（LibGdx 教训）。
          //
          // 顺序是硬要求：copyResourceFiles 的防御删除会清掉 values*/themes.xml
          // （assets 遗留清理），必须**先拷资源、后写自写的 themes.xml**——
          // 反过来的话自写文件会被防御删除一并清掉（2026-10-10 实机二测踩中：
          // 生成项目缺 Theme.CmpApp，aapt link 失败）。
          copyResourceFiles(context, projectRoot)

          activityWriter.createFile(
              valuesDir, "themes", "xml", ComposeMultiplatformSources.appThemes())

          // ---- GitHub Actions workflow（云端出包）----
          val workflowDir = File(projectRoot, ".github/workflows")
          workflowDir.mkdirs()
          File(workflowDir, "multiplatform.yml")
              .writeText(ComposeMultiplatformSources.ciWorkflow(options.projectName))

          // ---- README ----
          File(projectRoot, "README.md")
              .writeText(
                  ComposeMultiplatformSources.projectReadme(
                      packageHelper.getPackageId(), options.projectName))

          // ---- .gitignore ----
          // 必须含云编译产物目录：ACS 的云编译会把下载的 artifact（APK 可达上百 MB）
          // 落到 cloudbuild-output/，没有忽略规则时一次 `git add .` 就会误提交大文件。
          File(projectRoot, ".gitignore")
              .writeText(ComposeMultiplatformSources.gitignore())

          // Track project
          val projectManager = ProjectManager.getInstance(context)
          val projectInfo =
              projectManager.createTemplateProjectInfo(
                  projectName = options.projectName,
                  projectDir = projectRoot.absolutePath,
                  projectType = "Compose Multiplatform",
              )
          projectManager.addProjectBlocking(projectInfo)

          Log.d(TAG, "Project created successfully")

          withContext(Dispatchers.Main) {
            listener?.onTemplateCreated(
                true,
                "Compose Multiplatform project created at ${projectRoot.absolutePath}",
            )
            listener?.onTemplateCreated(true, "", projectRoot)
          }
        } catch (e: Exception) {
          Log.e(TAG, "Error creating project", e)
          withContext(Dispatchers.Main) {
            listener?.onTemplateCreated(false, "Error creating project: ${e.message}")
          }
        }
      }

  /**
   * 版本目录。手写而不是 VersionCatalogWriter：KMP 目录的 [plugins] 节有五个插件
   * （多了 android-library），[libraries] 里 CMP 三件套的 group 有规律但坐标零散，
   * DSL 拼装并不比字符串短，且失去对注释的控制。
   */
  private fun writeVersionCatalog(gradleDir: File) {
    File(gradleDir, "libs.versions.toml")
        .writeText(
            """
            [versions]
            agp = "$ANDROID_GRADLE_PLUGIN_VERSION"
            kotlin = "${ComposeMultiplatformSources.KOTLIN_VERSION}"
            composeMultiplatform = "${ComposeMultiplatformSources.COMPOSE_MULTIPLATFORM_VERSION}"
            lifecycleRuntime = "2.9.2"
            activityCompose = "$ANDROIDX_ACTIVITY_COMPOSE"
            coreKtx = "$ANDROIDX_CORE_KTEXT_VERSION"

            [plugins]
            android-application = { id = "com.android.application", version.ref = "agp" }
            android-library = { id = "com.android.library", version.ref = "agp" }
            kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
            kotlin-multiplatform = { id = "org.jetbrains.kotlin.multiplatform", version.ref = "kotlin" }
            kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
            compose = { id = "org.jetbrains.compose", version.ref = "composeMultiplatform" }

            [libraries]
            androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
            androidx-lifecycle-runtime = { group = "androidx.lifecycle", name = "lifecycle-runtime-ktx", version.ref = "lifecycleRuntime" }
            androidx-activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }
            """
                .trimIndent() + "\n")
  }

  /**
   * `:app` 模块 build 文件——结构化构建器（与其它模板一致的 Android 壳形态）。
   *
   * 用 `kotlin-android` 而不是 `kotlin-multiplatform`：app 是普通单平台
   * application，KMP 插件在这里没有收益，反而要求整个 android{} 配置搬进
   * kotlin{}。shared 以普通 project 依赖消费，对壳无感知。
   */
  private fun writeAppModule(projectRoot: File, packageId: String, options: TemplateOptions) {
    val moduleConfig = moduleGradleConfig {
      addPlugin(GradlePlugin("alias", "libs.plugins.android.application"))
      addPlugin(GradlePlugin("alias", "libs.plugins.kotlin.android"))
      addPlugin(GradlePlugin("alias", "libs.plugins.kotlin.compose"))
      namespace(packageId)
      compileSdk(PROJECTS_COMPILE_SDK_VERSION)
      defaultConfig(
          com.tom.androidcodestudio.project.manager.builder.module.DefaultConfig(
              applicationId = packageId,
              // 钳到 23：CMP 1.10.3 的传递依赖 androidx.compose.animation.core 要求
              // minSdk ≥ 23，用户选 21/22 时 manifest merge 直接失败
              // （「Suggestion: use a compatible library with a minSdk of at most 21…」）。
              // 与 LibGdx 钳 21 同一模式：取两者较大值而不是给出构建不过的项目。
              minSdk = maxOf(options.minSdk, CMP_MIN_SDK),
              targetSdk = 34,
              versionCode = 1,
              versionName = "1.0",
          )
      )
      javaVersion(JavaVersion.VERSION_17)
      addDependency(GradleDependency("implementation(project(\":composeApp\"))"))
      addDependency(GradleDependency("implementation(libs.androidx.activity.compose)"))
      addDependency(GradleDependency("implementation(libs.androidx.lifecycle.runtime)"))
      addDependency(GradleDependency("implementation(libs.androidx.core.ktx)"))
    }

    val appDir = File(projectRoot, APP_MODULE_DIR)
    moduleGradleWriter.writeToFile(
        appDir,
        com.tom.androidcodestudio.project.manager.builder.module.GradleFileType.KTS,
        moduleConfig,
    )

    proguardRulesWriter.writeToFile(appDir, ProguardRulesPresets.DEFAULT_ANDROID)
  }

  /**
   * `:composeApp`（shared KMP 模块）。
   *
   * build.gradle.kts 是手写文本（KMP 源集 DSL 不在结构化构建器射程内）；
   * 目录自建，不经过 ProjectStructBuilder（它按 Android 布局建 src/main/res，
   * KMP 模块不需要）。
   */
  private fun writeSharedModule(
      projectRoot: File,
      packageId: String,
      options: TemplateOptions,
  ) {
    val moduleDir = File(projectRoot, ComposeMultiplatformSources.SHARED_MODULE_DIR)
    moduleDir.mkdirs()

    File(moduleDir, "build.gradle.kts")
        .writeText(
            ComposeMultiplatformSources.sharedModuleGradle(
                packageId,
                compileSdk = PROJECTS_COMPILE_SDK_VERSION,
                // 与 :app 壳同一钳制——shared 的 minSdk 必须不低于依赖要求，
                // 且低于 app 壳无意义（以 app 为准）
                minSdk = maxOf(options.minSdk, CMP_MIN_SDK),
            ))
    Log.d(TAG, "Wrote :composeApp module at ${moduleDir.absolutePath}")
  }

  /** `:desktop` 壳模块（手写 Gradle + 入口 Main.kt）。 */
  private fun writeDesktopModule(
      projectRoot: File,
      packageId: String,
      projectName: String,
  ) {
    val moduleDir = File(projectRoot, ComposeMultiplatformSources.DESKTOP_MODULE_DIR)
    moduleDir.mkdirs()

    File(moduleDir, "build.gradle.kts")
        .writeText(
            ComposeMultiplatformSources.desktopModuleGradle(packageId, projectName))

    val mainDir = File(moduleDir, "src/desktopMain/kotlin/${pkgPath(packageId)}")
    mainDir.mkdirs()
    File(mainDir, "Main.kt").writeText(ComposeMultiplatformSources.desktopMain(packageId))
    Log.d(TAG, "Wrote :desktop module at ${moduleDir.absolutePath}")
  }

  /**
   * 拷贝图标等资源。
   *
   * 拷贝后校验 manifest 引用的 `mipmap-anydpi-v26/ic_launcher.xml` 存在：
   * `copyAssetFolder` 只记日志不抛异常，缺失时让项目过不了 aapt 却报创建成功，
   * 就是假成功（LibGdx 的教训）。失败时清掉半成品 res 目录，保证同名重试可干净开始
   * （QuickDevelop 同款处理）。
   */
  private fun copyResourceFiles(context: Context, projectRoot: File) {
    val resDestDir = File(projectRoot, "app/src/main/res")
    try {
      Log.d(TAG, "Copying resource files...")

      copyAssetFolder(context, ASSETS_RESOURCES_PATH, resDestDir)

      // 防御性删除：assets 若残留任何 themes.xml（values/ 或 values-night/）都会与
      // 本模板自写的 Theme.CmpApp 并存——values-night 那份引用 Theme.Material3.*
      // 而生成的 app 壳没有 material 依赖，aapt2 link 会因解析不到 parent 失败。
      // 2026-10-10 实机首测正是 values-night/themes.xml 残留踩中此坑。
      File(resDestDir, "values/themes.xml").delete()
      File(resDestDir, "values-night/themes.xml").delete()

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

  private fun pkgPath(packageId: String): String = packageId.replace('.', '/')

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
    const val TAG = "ComposeMultiplatform"
    const val APP_MODULE_DIR = "app"

    /**
     * CMP 传递依赖要求的最低 API。androidx.compose.animation.core（CMP 1.10.3 解析出的
     * 版本）的 AAR manifest 声明 minSdkVersion="23"。向导下拉从 21 起选，这里兜底。
     */
    const val CMP_MIN_SDK = 23

    /**
     * 阿里云镜像前置的插件仓库。镜像在前、官方在后：镜像命中直接走国内快路，
     * 未命中回落官方，不影响任何可用性。
     */
    const val MIRRORED_PLUGIN_REPOSITORIES =
        """repositories {
            maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/central") }
            google()
            mavenCentral()
            gradlePluginPortal()
        }"""

    /**
     * 阿里云镜像前置的依赖仓库（同上策略）。
     *
     * mode 必须用 PREFER_PROJECT 而不是 FAIL_ON_PROJECT_REPOS：Kotlin/wasm 的
     * Node.js 环境配置会在 project 级别注入 `nodejs.org/dist` 仓库（kotlinWasmNodeJsSetup
     * 任务依赖它），FAIL_ON_PROJECT_REPOS 会直接炸 configuration——
     * 「repository 'Distributions at https://nodejs.org/dist' was added by unknown code」
     * （2026-10-10 本机跑 wasmJsBrowserDistribution 实测抓到）。
     * PREFER_PROJECT 下 settings 仓库优先、project 仓库（KGP 注入的 Node 仓库）
     * 仍然生效，两全。
     *
     * 必须带 gradlePluginPortal()：binaryen（wasm 优化器）的坐标
     * `com.github.webassembly:binaryen` 是 integration 状态的 portal 构件，
     * 依赖解析只走 dependencyResolutionManagement 的仓库列表——缺了 portal
     * 就「Could not find com.github.webassembly:binaryen:123」（本机实测）。
     */
    const val MIRRORED_DEPENDENCY_REPOSITORIES =
        """repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
            repositories {
                maven { url = uri("https://maven.aliyun.com/repository/google") }
                maven { url = uri("https://maven.aliyun.com/repository/central") }
                maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
                google()
                mavenCentral()
                gradlePluginPortal()
            }"""
  }
}
