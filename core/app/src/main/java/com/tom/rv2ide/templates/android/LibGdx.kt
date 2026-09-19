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
import com.tom.androidcodestudio.project.manager.builder.module.*
import com.tom.androidcodestudio.project.manager.builder.toplevel.*
import com.tom.rv2ide.templates.*
import com.tom.rv2ide.templates.AtcInterface
import com.tom.rv2ide.templates.android.libgdx.LibGdxSources
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
 * libGDX 游戏模板：生成一个**不依赖 NDK** 的 libGDX 项目。
 *
 * <h3>为什么不用 NDK</h3>
 *
 * libGDX 的 Android 后端在 Maven Central 上是现成的 AAR，Java 侧只依赖
 * `gdx` + `gdx-backend-android` 两个构件；原生部分只有 `libgdx.so` 一个文件。
 * 因此本模板把 `libgdx.so` 按 ABI 预置到 `jniLibs/`，让 AGP 直接打包——
 * 生成的项目**不需要** NDK、CMake 或任何 C++ 编译，纯 Gradle 依赖即可构建。
 *
 * <p>这与 [GameActivity] 模板的取舍正好相反：那个模板必须编译 C++，
 * 因此要求设备上装了 NDK/CMake；本模板只要网络能拉到依赖就能构建。
 *
 * <h3>为什么不给构建器加 raw Gradle 注入口</h3>
 *
 * 官方 libGDX 项目用一段自定义 Gradle task（`copyAndroidNatives`）把
 * natives jar 里的 `.so` 解到 `jniLibs`。但 `ModuleGradleConfig` 只有
 * 结构化的字段（dependencies / buildFeatures / externalNativeBuild 等），
 * 没有原始代码注入点；为它加一个注入口会动到所有模板共用的构建器。
 * 预置 `.so` 到 assets 既不需要改构建器，也让生成的项目零自定义脚本，
 * 代价是模板体积增加约 700 KB。
 */
class LibGdx : Template {
  override val displayName = "LibGDX Game"
  override val templateType = Template.TemplateType.ACTIVITY

  private val projectStructBuilder = ProjectStructBuilder()
  private val activityWriter = ActivityWriter()
  private val topLevelGradleWriter = TopLevelGradleWriter()
  private val settingsGradleWriter = SettingsGradleWriter()
  private val versionCatalogWriter = VersionCatalogWriter()
  private val gradlePropertiesWriter = GradlePropertiesWriter()
  private val moduleGradleWriter = MLGradleWriter()
  private val proguardRulesWriter = ProguardRulesWriter()

  private val PRIMARY_MODULE = ":app"

  // Asset paths
  private val ASSETS_BASE_PATH = LibGdx::class.simpleName
  private val ASSETS_RESOURCES_PATH = "$ASSETS_BASE_PATH/resources"
  private val ASSETS_JNI_LIBS_PATH = "$ASSETS_BASE_PATH/native/jniLibs"

  /**
   * 纯 Gradle 依赖，不启用 native cpp。
   *
   * `resetToDefaults()` 是必须的：Options 是全局单例，用户可能刚点过
   * Game Activity 或 Native C++ 模板，那些模板会把 `OPT_IS_NATIVE_CPP`
   * 设为 true；不复位就会给本模板生成的项目写上 externalNativeBuild。
   */
  override fun configureOptions() {
    Options.resetToDefaults()
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
          Log.d("LibGdx", "create() called - START")

          withContext(Dispatchers.Main) {
            Toast.makeText(context, "Creating libGDX project...", Toast.LENGTH_SHORT).show()
          }

          val packageHelper =
              PackageHelper.createForProject(context, options.projectName.lowercase() + "_project")
          packageHelper.setPackageIdBlocking(options.packageId)

          val sdkHelper = SdkVersionHelper.getInstance(context)
          sdkHelper.setAllSdkVersionsBlocking(options.minSdk, 34, 34)

          val projectRoot = File(options.saveLocation, options.projectName)
          Log.d("LibGdx", "Project root: ${projectRoot.absolutePath}")

          projectRoot.mkdirs()

          // Create project structure
          val structResult =
              projectStructBuilder.buildProjectStructure(
                  moduleName = "app",
                  projectType =
                      if (options.languageType == LanguageType.KOTLIN)
                          com.tom.androidcodestudio.project.manager.builder.ProjectType.KOTLIN
                      else com.tom.androidcodestudio.project.manager.builder.ProjectType.JAVA,
                  packageId = packageHelper.getPackageId(),
                  baseDir = projectRoot,
                  hasLayout = true,
              )

          if (!structResult.success) {
            Log.e("LibGdx", "Structure creation failed: ${structResult.message}")
            withContext(Dispatchers.Main) {
              listener?.onTemplateCreated(false, structResult.message)
            }
            return@withContext
          }

          Log.d("LibGdx", "Project structure created successfully")

          // Copy wrapper files (gradlew, gradle folder)
          copyWrapperFiles(context, projectRoot)

          // Create version catalog
          val versions = buildList {
            add(
                catalogVersion {
                  name("agp")
                  version(ANDROID_GRADLE_PLUGIN_VERSION)
                }
            )
            if (options.languageType == LanguageType.KOTLIN) {
              add(
                  catalogVersion {
                    name("kotlin")
                    version(KOTLIN_VERSION)
                  }
              )
              add(
                  catalogVersion {
                    name("coreKtx")
                    version(ANDROIDX_CORE_KTEXT_VERSION)
                  }
              )
            } else {
              add(
                  catalogVersion {
                    name("core")
                    version(ANDROIDX_CORE_VERSION)
                  }
              )
            }
            add(
                catalogVersion {
                  name("appcompat")
                  version(ANDROIDX_APPCOMPAT_VERSION)
                }
            )
            add(
                catalogVersion {
                  name("material")
                  version(GOOGLE_MATERIAL_COMPONENTS_VERSION)
                }
            )
            add(
                catalogVersion {
                  name("constraintlayout")
                  version(ANDROIDX_CONSTRAINTLAYOUT_VERSION)
                }
            )
            add(
                catalogVersion {
                  name("gdx")
                  version(LIBGDX_VERSION)
                }
            )
          }

          val plugins = buildList {
            add(
                catalogPlugin {
                  alias("android-application")
                  id("com.android.application")
                  versionRef("agp")
                }
            )
            if (options.languageType == LanguageType.KOTLIN) {
              add(
                  catalogPlugin {
                    alias("kotlin-android")
                    id("org.jetbrains.kotlin.android")
                    versionRef("kotlin")
                  }
              )
            }
          }

          val libraries = buildList {
            if (options.languageType == LanguageType.KOTLIN) {
              add(
                  catalogLibrary {
                    alias("androidx-core-ktx")
                    group("androidx.core")
                    name("core-ktx")
                    versionRef("coreKtx")
                  }
              )
            } else {
              add(
                  catalogLibrary {
                    alias("androidx-core")
                    group("androidx.core")
                    name("core")
                    versionRef("core")
                  }
              )
            }
            add(
                catalogLibrary {
                  alias("androidx-appcompat")
                  group("androidx.appcompat")
                  name("appcompat")
                  versionRef("appcompat")
                }
            )
            add(
                catalogLibrary {
                  alias("material")
                  group("com.google.android.material")
                  name("material")
                  versionRef("material")
                }
            )
            add(
                catalogLibrary {
                  alias("androidx-constraintlayout")
                  group("androidx.constraintlayout")
                  name("constraintlayout")
                  versionRef("constraintlayout")
                }
            )
            // libGDX：Java 侧的 API + Android 后端。原生库由 jniLibs 提供，
            // 因此不需要 gdx-platform 的 natives 分类构件。
            add(
                catalogLibrary {
                  alias("gdx")
                  group("com.badlogicgames.gdx")
                  name("gdx")
                  versionRef("gdx")
                }
            )
            add(
                catalogLibrary {
                  alias("gdx-backend-android")
                  group("com.badlogicgames.gdx")
                  name("gdx-backend-android")
                  versionRef("gdx")
                }
            )
          }

          val gradleDir = File(projectRoot, "gradle")
          gradleDir.mkdirs()
          versionCatalogWriter.writeToFile(gradleDir, versions, plugins, libraries)

          // Create top-level build.gradle.kts
          val topLevelPlugins = buildList {
            add(
                com.tom.androidcodestudio.project.manager.builder.toplevel.GradlePlugin(
                    id = "android.application",
                    version = null,
                    apply = false,
                    useAlias = true,
                )
            )
            if (options.languageType == LanguageType.KOTLIN) {
              add(
                  com.tom.androidcodestudio.project.manager.builder.toplevel.GradlePlugin(
                      id = "kotlin.android",
                      version = null,
                      apply = false,
                      useAlias = true,
                  )
              )
            }
          }
          topLevelGradleWriter.writeToFile(
              projectRoot,
              if (options.useKts)
                  com.tom.androidcodestudio.project.manager.builder.toplevel.GradleFileType.KTS
              else com.tom.androidcodestudio.project.manager.builder.toplevel.GradleFileType.GROOVY,
              topLevelPlugins,
          )

          // Create settings.gradle.kts
          val settingsConfig = settingsGradleConfig {
            pluginManagement(
                if (Options.OPT_USE_GRADLE_KTS) {
                  RepositoryPresets.STANDARD_KTS
                } else {
                  RepositoryPresets.STANDARD_GROOVY
                }
            )
            dependencyResolution(
                if (Options.OPT_USE_GRADLE_KTS) {
                  RepositoryPresets.DEPENDENCY_RESOLUTION_KTS
                } else {
                  RepositoryPresets.DEPENDENCY_RESOLUTION_GROOVY
                }
            )
            rootProjectName(options.projectName)
            include(PRIMARY_MODULE)
          }
          settingsGradleWriter.writeToFile(
              projectRoot,
              if (Options.OPT_USE_GRADLE_KTS) {
                SettingsGradleFileType.KTS
              } else {
                SettingsGradleFileType.GROOVY
              },
              settingsConfig,
          )

          // Create gradle.properties
          gradlePropertiesWriter.writeToFile(projectRoot, GradlePropertiesPresets.STANDARD_ANDROID)

          // Create module build.gradle.kts
          val moduleConfig = moduleGradleConfig {
            addPlugin(
                com.tom.androidcodestudio.project.manager.builder.module.GradlePlugin(
                    "alias",
                    "libs.plugins.android.application",
                )
            )
            if (options.languageType == LanguageType.KOTLIN) {
              addPlugin(
                  com.tom.androidcodestudio.project.manager.builder.module.GradlePlugin(
                      "alias",
                      "libs.plugins.kotlin.android",
                  )
              )
            } else {
              // Disable Kotlin Options
              enableKotlinOptions(false)
            }
            namespace(packageHelper.getPackageId())
            compileSdk(PROJECTS_COMPILE_SDK_VERSION)

            defaultConfig(
                DefaultConfig(
                    applicationId = packageHelper.getPackageId(),
                    minSdk = Options.OPT_MIN_SDK,
                    targetSdk = 34,
                    versionCode = 1,
                    versionName = "1.0",
                )
            )
            javaVersion(JavaVersion.VERSION_17)
            if (options.languageType == LanguageType.KOTLIN) {
              addDependency(GradleDependency("implementation(libs.androidx.core.ktx)"))
            } else {
              addDependency(GradleDependency("implementation(libs.androidx.core)"))
            }
            addDependency(GradleDependency("implementation(libs.androidx.appcompat)"))
            addDependency(GradleDependency("implementation(libs.material)"))
            addDependency(GradleDependency("implementation(libs.androidx.constraintlayout)"))
            addDependency(GradleDependency("implementation(libs.gdx)"))
            addDependency(GradleDependency("implementation(libs.gdx.backend.android)"))
          }

          val appDir = File(projectRoot, "app")
          moduleGradleWriter.writeToFile(
              appDir,
              if (options.useKts)
                  com.tom.androidcodestudio.project.manager.builder.module.GradleFileType.KTS
              else com.tom.androidcodestudio.project.manager.builder.module.GradleFileType.GROOVY,
              moduleConfig,
          )

          // Create proguard-rules.pro
          proguardRulesWriter.writeToFile(appDir, ProguardRulesPresets.DEFAULT_ANDROID)

          // Copy resource files from assets (launcher icons, themes, colors)
          copyResourceFiles(context, projectRoot)

          // Copy the prebuilt libgdx.so into jniLibs so AGP packages it without NDK
          copyJniLibs(context, projectRoot)

          // Write MainActivity (libGDX entry point)
          val mainActivityContent =
              if (options.languageType == LanguageType.KOTLIN)
                  LibGdxSources.mainActivityKotlin(packageHelper.getPackageId())
              else LibGdxSources.mainActivityJava(packageHelper.getPackageId())

          val activityConfig = activityConfig {
            moduleName("app")
            languageType(options.languageType)
            packageId(packageHelper.getPackageId())
            activityName("MainActivity")
            content(mainActivityContent)
          }

          activityWriter.writeToFile(projectRoot, activityConfig)

          // Write the game itself (ApplicationAdapter) next to MainActivity.
          // ActivityWriter 只写一个类，所以这里复用同一套目录推导逻辑再写一个。
          writeGameListener(context, projectRoot, options, packageHelper.getPackageId())

          // Create AndroidManifest.xml
          //
          // 与其它模板的两点不同：
          // - theme 用 Theme.AppTheme（本模板从 assets 拷了定义该主题的 themes.xml）
          // - 不加 android.app.lib_name meta-data，那是 GameActivity 原生库用的
          val manifestContent =
              """
                  <?xml version="1.0" encoding="utf-8"?>
                  <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                      <uses-permission
                          android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
                      <application
                          android:allowBackup="true"
                          android:icon="@mipmap/ic_launcher"
                          android:label="@string/app_name"
                          android:roundIcon="@mipmap/ic_launcher_round"
                          android:supportsRtl="true"
                          android:theme="@style/Theme.AppTheme">
                          <activity
                              android:name=".MainActivity"
                              android:exported="true"
                              android:screenOrientation="portrait">
                              <intent-filter>
                                  <action android:name="android.intent.action.MAIN" />
                                  <category android:name="android.intent.category.LAUNCHER" />
                              </intent-filter>
                          </activity>
                      </application>

                  </manifest>
              """
                  .trimIndent()

          val manifestDir = File(projectRoot, "app/src/main")
          activityWriter.createFile(manifestDir, "AndroidManifest", "xml", manifestContent)

          // Create strings.xml
          val stringsContent =
              """
                <resources>
                    <string name="app_name">${options.projectName}</string>
                </resources>
            """
                  .trimIndent()

          val valuesDir = File(projectRoot, "app/src/main/res/values")
          activityWriter.createFile(valuesDir, "strings", "xml", stringsContent)

          // Track project
          val projectManager = ProjectManager.getInstance(context)
          val projectInfo =
              projectManager.createTemplateProjectInfo(
                  projectName = options.projectName,
                  projectDir = projectRoot.absolutePath,
                  projectType = "LibGDX Game",
              )
          projectManager.addProjectBlocking(projectInfo)

          Log.d("LibGdx", "Project created successfully")

          withContext(Dispatchers.Main) {
            listener?.onTemplateCreated(
                true,
                "libGDX project created successfully at ${projectRoot.absolutePath}",
            )
            listener?.onTemplateCreated(true, "", projectRoot)
          }
        } catch (e: Exception) {
          Log.e("LibGdx", "Error creating project", e)
          withContext(Dispatchers.Main) {
            listener?.onTemplateCreated(false, "Error creating project: ${e.message}")
          }
        }
      }

  /**
   * 把游戏主类写到与 MainActivity 相同的包目录下。
   *
   * 目录推导与 `ActivityWriter` 内部一致：`<module>/src/main/<lang>/<包路径>/`。
   * 不直接用 ActivityWriter 是因为它按 `ActivityConfig` 写单一文件名（MainActivity）。
   */
  private fun writeGameListener(
      context: Context,
      projectRoot: File,
      options: TemplateOptions,
      packageId: String,
  ) {
    val languageDir = if (options.languageType == LanguageType.KOTLIN) "kotlin" else "java"
    val extension = if (options.languageType == LanguageType.KOTLIN) "kt" else "java"
    val packagePath = packageId.replace('.', File.separatorChar)

    val destDir = File(projectRoot, "app/src/main/$languageDir/$packagePath")
    destDir.mkdirs()

    val content =
        if (options.languageType == LanguageType.KOTLIN) LibGdxSources.gameListenerKotlin(packageId)
        else LibGdxSources.gameListenerJava(packageId)

    val file = File(destDir, "${LibGdxSources.LISTENER_CLASS}.$extension")
    file.writeText(content)
    Log.d("LibGdx", "Wrote game class: ${file.absolutePath}")
  }

  /**
   * 把预置的 `libgdx.so` 按 ABI 拷到 `app/src/main/jniLibs/<abi>/`。
   *
   * AGP 会自动把 `jniLibs` 下的原生库打进 APK，因此生成的项目不需要
   * NDK、CMake 或任何自定义 Gradle task。
   */
  private fun copyJniLibs(context: Context, projectRoot: File) {
    try {
      val destRoot = File(projectRoot, "app/src/main/jniLibs")
      val abis = context.assets.list(ASSETS_JNI_LIBS_PATH) ?: emptyArray()

      if (abis.isEmpty()) {
        Log.e("LibGdx", "No ABI directories found under assets/$ASSETS_JNI_LIBS_PATH")
        return
      }

      for (abi in abis) {
        val destDir = File(destRoot, abi)
        destDir.mkdirs()
        copyAssetFile(context, "$ASSETS_JNI_LIBS_PATH/$abi/libgdx.so", File(destDir, "libgdx.so"))
      }

      Log.d("LibGdx", "Copied libgdx.so for ${abis.size} ABI(s): ${abis.joinToString()}")
    } catch (e: Exception) {
      // 原生库缺失会导致运行期 UnsatisfiedLinkError，这里必须让创建流程失败，
      // 否则用户拿到一个「能编译、一启动就崩」的项目。
      Log.e("LibGdx", "Error copying jniLibs", e)
      throw IllegalStateException(
          "Failed to copy libgdx.so into jniLibs: ${e.message}. " +
              "The generated project would crash on startup.",
          e,
      )
    }
  }

  /** Copy wrapper files (gradlew, gradlew.bat, gradle/wrapper folder) from assets to project root */
  private fun copyWrapperFiles(context: Context, projectRoot: File) {
    try {
      Log.d("LibGdx", "Copying wrapper files...")

      val wrapperAssetPath = "$ASSETS_BASE_PATH/gradle"

      copyAssetFile(context, "$wrapperAssetPath/gradlew", File(projectRoot, "gradlew"))
      File(projectRoot, "gradlew").setExecutable(true, false)
      copyAssetFile(context, "$wrapperAssetPath/gradlew.bat", File(projectRoot, "gradlew.bat"))

      val wrapperDestDir = File(projectRoot, "gradle/wrapper")
      copyAssetFolder(context, "$wrapperAssetPath/wrapper", wrapperDestDir)

      Log.d("LibGdx", "Wrapper files copied successfully")
    } catch (e: Exception) {
      Log.e("LibGdx", "Error copying wrapper files", e)
      // Don't throw, just log - wrapper files are optional
    }
  }

  /**
   * Copy resource files from assets/LibGdx/resources to app/src/main/res
   *
   * 拷贝后校验几个**必需**资源是否存在。`copyAssetFolder` 只记日志不抛异常，
   * 若不校验就会出现「模板报告创建成功、项目却编译不过（aapt 找不到主题/图标）」
   * 的假成功。这与 [copyJniLibs] 的立场一致：缺了就不能算成功。
   */
  private fun copyResourceFiles(context: Context, projectRoot: File) {
    try {
      Log.d("LibGdx", "Copying resource files...")

      val resDestDir = File(projectRoot, "app/src/main/res")
      copyAssetFolder(context, ASSETS_RESOURCES_PATH, resDestDir)

      // AndroidManifest 引用了 @style/Theme.AppTheme 与 @mipmap/ic_launcher，
      // 二者缺失会让项目在 aapt 阶段直接失败。
      val required = listOf("values/themes.xml", "mipmap-anydpi/ic_launcher.xml")
      val missing = required.filterNot { File(resDestDir, it).isFile }
      if (missing.isNotEmpty()) {
        throw IllegalStateException(
            "Missing required resources after copy: ${missing.joinToString()}. " +
                "The generated project would fail at the aapt stage.",
        )
      }

      Log.d("LibGdx", "Resource files copied successfully")
    } catch (e: Exception) {
      Log.e("LibGdx", "Error copying resource files", e)
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
      Log.e("LibGdx", "Error copying asset folder: $assetPath", e)
    }
  }

  /** Copy a single file from assets to destination */
  private fun copyAssetFile(context: Context, assetPath: String, destFile: File) {
    try {
      val inputStream: InputStream = context.assets.open(assetPath)
      val outputStream = FileOutputStream(destFile)

      inputStream.use { input -> outputStream.use { output -> input.copyTo(output) } }

      Log.d("LibGdx", "Copied: $assetPath -> ${destFile.absolutePath}")
    } catch (e: Exception) {
      Log.e("LibGdx", "Error copying file: $assetPath", e)
      throw e
    }
  }
}
