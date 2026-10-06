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
import com.tom.rv2ide.templates.android.quickdevelop.QuickDevelopSources
import com.tom.rv2ide.templates.android.quickdevelop.QuickDevelopTicode
import com.tom.rv2ide.templates.android.quickdevelop.QuickDevelopToolkits
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
 * Quick Develop：纯 Gradle 依赖、零 NDK、零 C++ 的单 Activity 模板。
 *
 * <p>生成的工程与 [EmptyActivity] 一样是标准 AndroidX 应用（appcompat + material +
 * constraintlayout），区别只在源码：本模板额外生成一套**中文命名**的 UI 组件封装
 * （`视图` / `线性布局` / `约束布局` / `文本` / `按钮` / `输入框` / `页面`），
 * MainActivity 直接用这套组件以代码搭界面，不写 XML 布局。
 *
 * <h3>与 LibGdx 的差异</h3>
 *
 * 二者都是「零 NDK」模板，但取舍不同：
 * <ul>
 *   <li>LibGdx 依赖 `com.badlogicgames.gdx` 两个构件，并把预编译的 `libgdx.so`
 *       按 ABI 放进 `jniLibs/`（体积约 +700 KB）；本模板**没有任何原生库**，
 *       也不生成 `jniLibs/`，因此不需要 `copyJniLibs`。
 *   <li>LibGdx 面向游戏（Activity 继承 `AndroidApplication`）；本模板面向普通
 *       表单/工具类界面，Activity 继承自 `页面`（即 AppCompatActivity）。
 * </ul>
 *
 * <h3>与 EmptyActivity 的差异</h3>
 *
 * 工程骨架、依赖、wrapper 资源完全同构，差异是：
 * <ul>
 *   <li>不生成 `res/layout/activity_main.xml`（界面全用代码搭）。
 *   <li>额外生成 7 个中文组件类（见 [QuickDevelopSources]）。
 *   <li>语言恒为 Java（见下）。
 * </ul>
 *
 * <h3>为什么恒为 Java</h3>
 *
 * 组件类名与方法是中文，Java/Kotlin 都合法，但本模板只产出 Java：向导的语言下拉
 * 默认是 Kotlin，若用户在向导里选了 Kotlin 而模板只写 Java 源码，就会出现
 * 「build.gradle 里没有 kotlin 插件、源码却是 .kt」或反过来的不一致。
 * 因此 [AtcWizardDialog.createProject] 对本模板强制 `LanguageType.JAVA`
 * （与 Compose 模板强制 Kotlin 同一处理方式）。
 */
class QuickDevelop : Template {
  override val displayName = "Quick Develop"
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

  // Asset paths（assets/QuickDevelop/ 由 EmptyActivity 的 wrapper + 图标 + 主题复制而来）
  private val ASSETS_BASE_PATH = QuickDevelop::class.simpleName
  private val ASSETS_RESOURCES_PATH = "$ASSETS_BASE_PATH/resources"
  private val ASSETS_GRADLE_PATH = "$ASSETS_BASE_PATH/gradle"

  /**
   * 纯 Gradle 依赖，不启用 native cpp。
   *
   * `resetToDefaults()` 是必须的：Options 是全局单例，用户可能刚点过
   * Game Activity 或 Native C++ 模板，那些模板会把 `OPT_IS_NATIVE_CPP`
   * 设为 true；不复位就会给本模板生成的项目写上 externalNativeBuild。
   *
   * <p>**minSdk 必须在复位前保存、复位后写回**。`resetToDefaults()` 会把
   * `OPT_MIN_SDK` 强制回到 21（见 `Options.kt` 的 resetToDefaults），而本方法
   * 在用户**点选模板时**调用（`AtcWizardDialog.setupTemplatesGrid`）。此时用户
   * 可能已在上一次进入选项页时选过 minSdk：向导的「返回」按钮会回到模板列表，
   * 再点一次本模板就会重跑本方法。不复原的话，`OPT_MIN_SDK` 被改成 21，
   * 而选项页的 SDK 下拉文本仍是用户先前选的（如 API 34），`createProject` 读的
   * 是 `Options.OPT_MIN_SDK`——UI 显示 34、实际生成 21，正是要避免的不一致。
   *
   * <p>注意这与 [EmptyActivity] 的取舍相反：EmptyActivity 直接沿用复位后的
   * `OPT_MIN_SDK`，因此在「选过 minSdk 后返回重选」这条路径上有同样的显示/生成
   * 不一致问题；本模板显式修掉它。
   */
  override fun configureOptions() {
    // 复位前先记住用户已选的 minSdk，复位后写回（resetToDefaults 会把它改回 21）。
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
          Log.d("QuickDevelop", "create() called - START")

          withContext(Dispatchers.Main) {
            Toast.makeText(context, "Creating Quick Develop project...", Toast.LENGTH_SHORT).show()
          }

          val packageHelper =
              PackageHelper.createForProject(context, options.projectName.lowercase() + "_project")
          packageHelper.setPackageIdBlocking(options.packageId)

          val sdkHelper = SdkVersionHelper.getInstance(context)
          sdkHelper.setAllSdkVersionsBlocking(options.minSdk, 34, 34)

          val projectRoot = File(options.saveLocation, options.projectName)
          Log.d("QuickDevelop", "Project root: ${projectRoot.absolutePath}")

          projectRoot.mkdirs()

          // Create project structure（Java only；无 XML 布局，hasLayout = false）
          val structResult =
              projectStructBuilder.buildProjectStructure(
                  moduleName = "app",
                  projectType =
                      com.tom.androidcodestudio.project.manager.builder.ProjectType.JAVA,
                  packageId = packageHelper.getPackageId(),
                  baseDir = projectRoot,
                  hasLayout = false,
              )

          if (!structResult.success) {
            Log.e("QuickDevelop", "Structure creation failed: ${structResult.message}")
            withContext(Dispatchers.Main) {
              listener?.onTemplateCreated(false, structResult.message)
            }
            return@withContext
          }

          Log.d("QuickDevelop", "Project structure created successfully")

          // Copy wrapper files (gradlew, gradle folder)
          copyWrapperFiles(context, projectRoot)

          // Version catalog：本模板恒为 Java，只登记 androidx-core（非 core-ktx），
          // 不登记 kotlin 版本与 kotlin 插件。
          val versions = buildList {
            add(catalogVersion { name("agp"); version(ANDROID_GRADLE_PLUGIN_VERSION) })
            add(catalogVersion { name("core"); version(ANDROIDX_CORE_VERSION) })
            add(catalogVersion { name("appcompat"); version(ANDROIDX_APPCOMPAT_VERSION) })
            add(catalogVersion { name("material"); version(GOOGLE_MATERIAL_COMPONENTS_VERSION) })
            add(
                catalogVersion {
                  name("constraintlayout")
                  version(ANDROIDX_CONSTRAINTLAYOUT_VERSION)
                }
            )
            // 中文控件封装覆盖到 45 个后，下面这几个库不再只是「传递依赖」：
            // 生成的源码会直接 import RecyclerView / ViewPager2 / SwipeRefreshLayout /
            // CardView / DrawerLayout / CoordinatorLayout，编译期必须显式声明。
            add(catalogVersion { name("recyclerview"); version(ANDROIDX_RECYCLERVIEW_VERSION) })
            add(catalogVersion { name("viewpager2"); version(ANDROIDX_VIEWPAGER2_VERSION) })
            add(
                catalogVersion {
                  name("swiperefreshlayout")
                  version(ANDROIDX_SWIPEREFRESHLAYOUT_VERSION)
                }
            )
            add(catalogVersion { name("cardview"); version(ANDROIDX_CARDVIEW_VERSION) })
            add(catalogVersion { name("drawerlayout"); version(ANDROIDX_DRAWERLAYOUT_VERSION) })
            add(
                catalogVersion {
                  name("coordinatorlayout")
                  version(ANDROIDX_COORDINATORLAYOUT_VERSION)
                }
            )
            // :ticode（结绳移植）的 弹性布局 等 9 个类直接 import flexbox。
            add(catalogVersion { name("flexbox"); version(ANDROIDX_FLEXBOX_VERSION) })
          }

          val plugins = buildList {
            add(
                catalogPlugin {
                  alias("android-application")
                  id("com.android.application")
                  versionRef("agp")
                }
            )
            // :ticode 是 Android 库模块（引用 android.* + androidx AAR，不能是 java-library）
            add(
                catalogPlugin {
                  alias("android-library")
                  id("com.android.library")
                  versionRef("agp")
                }
            )
          }

          val libraries = buildList {
            add(
                catalogLibrary {
                  alias("androidx-core")
                  group("androidx.core")
                  name("core")
                  versionRef("core")
                }
            )
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
            add(
                catalogLibrary {
                  alias("androidx-recyclerview")
                  group("androidx.recyclerview")
                  name("recyclerview")
                  versionRef("recyclerview")
                }
            )
            add(
                catalogLibrary {
                  alias("androidx-viewpager2")
                  group("androidx.viewpager2")
                  name("viewpager2")
                  versionRef("viewpager2")
                }
            )
            add(
                catalogLibrary {
                  alias("androidx-swiperefreshlayout")
                  group("androidx.swiperefreshlayout")
                  name("swiperefreshlayout")
                  versionRef("swiperefreshlayout")
                }
            )
            add(
                catalogLibrary {
                  alias("androidx-cardview")
                  group("androidx.cardview")
                  name("cardview")
                  versionRef("cardview")
                }
            )
            add(
                catalogLibrary {
                  alias("androidx-drawerlayout")
                  group("androidx.drawerlayout")
                  name("drawerlayout")
                  versionRef("drawerlayout")
                }
            )
            add(
                catalogLibrary {
                  alias("androidx-coordinatorlayout")
                  group("androidx.coordinatorlayout")
                  name("coordinatorlayout")
                  versionRef("coordinatorlayout")
                }
            )
            add(
                catalogLibrary {
                  alias("androidx-flexbox")
                  group("com.google.android.flexbox")
                  name("flexbox")
                  versionRef("flexbox")
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
            // :ticode —— 结绳语言基本库移植（334 个中文 API 类），独立 Android 库模块
            include(QuickDevelopTicode.MODULE_PATH)
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

          // Create module build.gradle.kts（Java：关闭 kotlin options）
          val moduleConfig = moduleGradleConfig {
            addPlugin(
                com.tom.androidcodestudio.project.manager.builder.module.GradlePlugin(
                    "alias",
                    "libs.plugins.android.application",
                )
            )
            enableKotlinOptions(false)
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
            addDependency(GradleDependency("implementation(libs.androidx.core)"))
            addDependency(GradleDependency("implementation(libs.androidx.appcompat)"))
            addDependency(GradleDependency("implementation(libs.material)"))
            addDependency(GradleDependency("implementation(libs.androidx.constraintlayout)"))
            addDependency(GradleDependency("implementation(libs.androidx.recyclerview)"))
            addDependency(GradleDependency("implementation(libs.androidx.viewpager2)"))
            addDependency(GradleDependency("implementation(libs.androidx.swiperefreshlayout)"))
            addDependency(GradleDependency("implementation(libs.androidx.cardview)"))
            addDependency(GradleDependency("implementation(libs.androidx.drawerlayout)"))
            addDependency(GradleDependency("implementation(libs.androidx.coordinatorlayout)"))
            // 结绳移植库（独立模块）
            addDependency(GradleDependency("implementation(project(\":ticode\"))"))
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

          // Write the Chinese-named UI components into <pkg>/ui/
          writeComponents(projectRoot, packageHelper.getPackageId())

          // 结绳移植库：独立 :ticode 模块（build.gradle + 从 assets 拷 334 个 .java）
          writeTicodeModule(context, projectRoot)

          // Write MainActivity (Java, uses the components)
          val activityConfig = activityConfig {
            moduleName("app")
            languageType(LanguageType.JAVA)
            packageId(packageHelper.getPackageId())
            activityName("MainActivity")
            content(QuickDevelopSources.mainActivityJava(packageHelper.getPackageId()))
          }
          activityWriter.writeToFile(projectRoot, activityConfig)

          // Create AndroidManifest.xml
          //
          // theme 用 @style/AppTheme：本模板从 assets 拷的 themes.xml 定义的正是
          // AppTheme（与 EmptyActivity 同源），不是 LibGdx 的 Theme.AppTheme。
          // 内容由 QuickDevelopSources.manifestXml() 生成，便于 JVM 单测断言权限声明。
          val manifestDir = File(projectRoot, "app/src/main")
          activityWriter.createFile(
              manifestDir,
              "AndroidManifest",
              "xml",
              QuickDevelopSources.manifestXml(),
          )

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
                  projectType = "Quick Develop",
              )
          projectManager.addProjectBlocking(projectInfo)

          Log.d("QuickDevelop", "Project created successfully")

          withContext(Dispatchers.Main) {
            listener?.onTemplateCreated(
                true,
                "Quick Develop project created successfully at ${projectRoot.absolutePath}",
            )
            listener?.onTemplateCreated(true, "", projectRoot)
          }
        } catch (e: Exception) {
          Log.e("QuickDevelop", "Error creating project", e)
          withContext(Dispatchers.Main) {
            listener?.onTemplateCreated(false, "Error creating project: ${e.message}")
          }
        }
      }

  /**
   * 生成结绳移植库的独立模块 `:ticode`。
   *
   * <h3>为什么是 Android 库模块而不是 java-library</h3>
   *
   * ticode 引用 `android.*`（Activity/View/Bitmap…）与 androidx（appcompat /
   * constraintlayout / recyclerview / flexbox）。这些依赖是 **AAR**，纯
   * `java-library` 解析不了 AAR 里的 classes.jar，所以必须用
   * `com.android.library`（AGP 会正确处理 AAR）。
   *
   * <p>注意这与仓库内的 `core/ticode` 不同：那个模块为了「零 AGP、纯 javac 离线可编」
   * 用本地 jar 垫片（`libs` 下的 jar，约 32MB）绕开 AAR。生成给用户的工程不能背这 32MB，
   * 因此这里走标准 AGP 路径。
   *
   * <h3>源码来源</h3>
   *
   * 334 个 .java 从 APK 的 assets（`assets/QuickDevelop/ticode/`）拷贝——而不是像
   * `QuickDevelopSources` 那样写成 Kotlin 字符串常量：那会造出 700KB 的巨型 object，
   * 且每次改 ticode 都要重新生成字符串。assets 拷贝可递归、可脚本化同步。
   */
  private fun writeTicodeModule(context: Context, projectRoot: File) {
    val ticodeDir = File(projectRoot, "ticode")
    val srcRoot = File(ticodeDir, "src/main/java")
    srcRoot.mkdirs()

    // 1) 从 assets 递归拷源码（保持 ticode/zh/** 结构）
    copyAssetFolder(context, QuickDevelopTicode.ASSETS_PATH, srcRoot)

    // 2) 写 ticode/build.gradle(.kts)
    val useKts = Options.OPT_USE_GRADLE_KTS
    val fileName = if (useKts) "build.gradle.kts" else "build.gradle"
    File(ticodeDir, fileName)
        .writeText(
            QuickDevelopTicode.moduleGradle(
                useKts = useKts,
                compileSdk = PROJECTS_COMPILE_SDK_VERSION,
                minSdk = Options.OPT_MIN_SDK,
            )
        )

    // 3) 模块 README
    File(ticodeDir, "README.md").writeText(QuickDevelopTicode.readme())

    val n = srcRoot.walkTopDown().count { it.extension == "java" }
    Log.d("QuickDevelop", "Wrote :ticode module ($n java files) at ${ticodeDir.absolutePath}")
  }

  /**
   * 把中文组件写进 `<module>/src/main/java/<包路径>/ui/`。
   *
   * 目录推导与 `ActivityWriter` 一致，但 ActivityWriter 一次只写一个类，
   * 这里直接按包路径落文件（与 LibGdx 写 GameListener 的做法相同）。
   */
  private fun writeComponents(projectRoot: File, packageId: String) {
    val packagePath = packageId.replace('.', File.separatorChar)
    val destDir = File(projectRoot, "app/src/main/java/$packagePath/ui")
    destDir.mkdirs()

    for ((className, content) in QuickDevelopSources.components(packageId)) {
      val file = File(destDir, "$className.java")
      file.writeText(content)
      Log.d("QuickDevelop", "Wrote component: ${file.absolutePath}")
    }

    writeToolkits(projectRoot, packageId)
    writeDocs(projectRoot, packageId)
  }

  /**
   * 把工具库写进 `<module>/src/main/java/<包路径>/tool/`。
   *
   * 与 `ui/` 的分工：`ui/` 是视图层（45 个控件），`tool/` 是非视图能力
   * （字符/文件/数据/工具/系统）。对应 Appv5 的 `zf`/`wj`/`sj`/`gj`/`xt` 五个根组件。
   */
  private fun writeToolkits(projectRoot: File, packageId: String) {
    val packagePath = packageId.replace('.', File.separatorChar)
    val destDir = File(projectRoot, "app/src/main/java/$packagePath/tool")
    destDir.mkdirs()

    for ((className, content) in QuickDevelopToolkits.all(packageId)) {
      val file = File(destDir, "$className.java")
      file.writeText(content)
      Log.d("QuickDevelop", "Wrote toolkit: ${file.absolutePath}")
    }
  }

  /**
   * 写开发文档：工程根 README.md + `ui/README.md` + `tool/README.md`。
   *
   * 三份文档都**从生成器的规格表产出**，不是手写——否则加一个控件就会过期。
   */
  private fun writeDocs(projectRoot: File, packageId: String) {
    val packagePath = packageId.replace('.', File.separatorChar)
    val javaRoot = File(projectRoot, "app/src/main/java/$packagePath")

    File(projectRoot, "README.md").writeText(projectReadme(packageId))
    File(javaRoot, "ui/README.md").writeText(QuickDevelopSources.uiReadme(packageId))
    File(javaRoot, "tool/README.md").writeText(QuickDevelopToolkits.readme(packageId))
    Log.d("QuickDevelop", "Wrote dev docs")
  }

  /**
   * 工程根 README：定位、结构、构建方式、快速上手。
   *
   * 组件与工具库的明细不在这里重复，只给入口链接——避免同一份信息两处维护。
   */
  private fun projectReadme(packageId: String): String =
      """
      # $packageId

      由 **Android Code Studio** 的 *Quick Develop* 模板生成：一套中文命名的
      UI 组件 + 工具库，用来快速把界面和常用能力搭起来，不写 XML 布局。

      ## 目录

      - [项目结构](#项目结构)
      - [快速上手](#快速上手)
      - [UI 组件](#ui-组件)
      - [工具库](#工具库)
      - [构建](#构建)

      ## 项目结构

      ```
      app/src/main/java/$packageId/
      ├── MainActivity.java        入口 Activity（用中文组件搭界面）
      ├── ui/                      45 个中文 UI 组件 + 英文别名
      │   └── README.md            组件 API 文档（带目录）
      └── tool/                    5 个工具类
          └── README.md            工具 API 文档（带目录）
      ticode/                      结绳语言基本库（Java 移植），独立 Android 库模块
      └── src/main/java/ticode/zh/ 333 个中文 API 类（base / jvm / android / meng）
      ```

      ${QuickDevelopTicode.选型指引()}

      ## 快速上手

      界面在 `MainActivity.搭建()` 里用代码搭，不用 XML：

      ```java
      @Override
      protected 视图 搭建() {
          线性布局 根 = new 线性布局(this).方向(线性布局.垂直).内边距(16f);

          文本 标题 = new 文本(this).文字("你好").字号(24f).粗体(true);
          按钮 按钮 = new 按钮(this).文字("点我").点击(() -> 标题.文字("已点击"));

          根.添加(标题, 按钮);
          return 根;
      }
      ```

      ## UI 组件

      45 个中文组件，覆盖容器 / 文本 / 输入 / 列表 / 系统控件等。
      全部支持链式调用，且都有等价的英文别名。

      详见 **[ui/README.md](app/src/main/java/$packageId/ui/README.md)**。

      ## 工具库

      5 个工具类：`字符`（字符串/正则/JSON）、`文件`、`数据`（SQLite/类型转换）、
      `工具`（动画/媒体/通知）、`系统`（设备信息/应用/截屏）。

      详见 **[tool/README.md](app/src/main/java/$packageId/tool/README.md)**。

      ## 构建

      本机（设备上）直接构建：

      ```bash
      ./gradlew assembleDebug
      ```

      或用 Android Code Studio 打开本工程，点构建按钮。

      产物在 `app/build/outputs/apk/debug/`。
      """
          .trimIndent() + "\n"

  /** Copy wrapper files (gradlew, gradlew.bat, gradle/wrapper folder) from assets to project root */
  private fun copyWrapperFiles(context: Context, projectRoot: File) {
    try {
      Log.d("QuickDevelop", "Copying wrapper files...")

      copyAssetFile(context, "$ASSETS_GRADLE_PATH/gradlew", File(projectRoot, "gradlew"))
      File(projectRoot, "gradlew").setExecutable(true, false)
      copyAssetFile(context, "$ASSETS_GRADLE_PATH/gradlew.bat", File(projectRoot, "gradlew.bat"))

      val wrapperDestDir = File(projectRoot, "gradle/wrapper")
      copyAssetFolder(context, "$ASSETS_GRADLE_PATH/wrapper", wrapperDestDir)

      Log.d("QuickDevelop", "Wrapper files copied successfully")
    } catch (e: Exception) {
      Log.e("QuickDevelop", "Error copying wrapper files", e)
      // Don't throw, just log - wrapper files are optional
    }
  }

  /**
   * Copy resource files from assets/QuickDevelop/resources to app/src/main/res
   *
   * 拷贝后校验几个**必需**资源是否存在。`copyAssetFolder` 只记日志不抛异常，
   * 若不校验就会出现「模板报告创建成功、项目却编译不过（aapt 找不到主题/图标）」
   * 的假成功。
   *
   * <p>失败时**先删掉已写了一半的 res 目录再抛**。`copyAssetFolder` 是逐个文件
   * 拷的，抛错前往往已经落了部分文件；用户修正问题后用同名项目重试时，残留目录
   * 会让后续创建撞上「已存在」的目录/文件。清掉它，失败才可干净重试。
   */
  private fun copyResourceFiles(context: Context, projectRoot: File) {
    val resDestDir = File(projectRoot, "app/src/main/res")
    try {
      Log.d("QuickDevelop", "Copying resource files...")

      copyAssetFolder(context, ASSETS_RESOURCES_PATH, resDestDir)

      // AndroidManifest 引用了 @style/AppTheme 与 @mipmap/ic_launcher，
      // 二者缺失会让项目在 aapt 阶段直接失败。
      val required = listOf("values/themes.xml", "mipmap-anydpi-v26/ic_launcher.xml")
      val missing = required.filterNot { File(resDestDir, it).isFile }
      if (missing.isNotEmpty()) {
        throw IllegalStateException(
            "Missing required resources after copy: ${missing.joinToString()}. " +
                "The generated project would fail at the aapt stage.",
        )
      }

      Log.d("QuickDevelop", "Resource files copied successfully")
    } catch (e: Exception) {
      Log.e("QuickDevelop", "Error copying resource files", e)
      // 清理半成品，保证失败后可用同名项目重试。
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
      Log.e("QuickDevelop", "Error copying asset folder: $assetPath", e)
    }
  }

  /** Copy a single file from assets to destination */
  private fun copyAssetFile(context: Context, assetPath: String, destFile: File) {
    try {
      val inputStream: InputStream = context.assets.open(assetPath)
      val outputStream = FileOutputStream(destFile)

      inputStream.use { input -> outputStream.use { output -> input.copyTo(output) } }

      Log.d("QuickDevelop", "Copied: $assetPath -> ${destFile.absolutePath}")
    } catch (e: Exception) {
      Log.e("QuickDevelop", "Error copying file: $assetPath", e)
      throw e
    }
  }
}
