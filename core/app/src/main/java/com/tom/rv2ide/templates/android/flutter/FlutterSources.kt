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

package com.tom.rv2ide.templates.android.flutter

/**
 * Flutter 模板的文本生成器。
 *
 * <h3>与其它模板的根本差异：设备端不构建</h3>
 *
 * Flutter 的 Gradle 构建**必须有完整 Flutter SDK**：settings.gradle.kts 里
 * `require(flutterSdkPath != null)` + `includeBuild("$flutterSdkPath/packages/
 * flutter_tools/gradle")`（`FlutterPlugin.kt:56-66` 直接抛
 * "flutter.sdk must point to the Flutter SDK directory"），而 Dart 产物
 * （debug 的 kernel_blob.bin / release 的 app.so）必须由 SDK 内 `flutter
 * assemble` 生成——任何 Maven 都没有这些构件。官方 HostPlatform 枚举
 * 也不含 Android/bionic（flutter issue #75864 至今 open）。
 *
 * 因此本模板的形态是：**生成完整工程骨架（flutter create 产物固化）+
 * GitHub Actions workflow**；设备端只编辑 Dart 代码，构建由云端完成。
 * 在设备上点构建会因缺 SDK 报错——README 里明确说明这一点。
 *
 * <h3>版本组合（2026-10-12，flutter create 实测产物）</h3>
 *
 * Flutter 3.47.7 stable × Dart 3.13.5 × Gradle 9.3.1 × AGP 9.1.0 ×
 * Kotlin 2.4.0。这些是 flutter create 自动写入的值（模板变量
 * templateDefaultGradleVersion 等），**不要手改**——Flutter 工具链
 * 对版本组合有强校验。
 *
 * <h3>trimIndent 陷阱</h3>
 *
 * 所有 `"""…"""` 模板遵守 QuickDevelop 踩过三次的规则：多行插入值不直接内嵌，
 * 一律用单行占位符 + `trimIndent()` 后 `.replace("@…@", value)`。
 */
object FlutterSources {

  /** Flutter stable 版本（README 与 workflow 里展示用）。 */
  const val FLUTTER_VERSION = "3.47.7"

  /** Dart 版本（pubspec 的 sdk 约束来源）。 */
  const val DART_VERSION = "3.13.5"

  /** 模板默认的 Gradle 版本（flutter create 写入的 gradle-wrapper.properties 值）。 */
  const val GRADLE_VERSION = "9.3.1"

  /** 模板默认的 AGP 版本。 */
  const val AGP_VERSION = "9.1.0"

  /** 模板默认的 Kotlin 版本。 */
  const val KOTLIN_VERSION = "2.4.0"

  // ---------------------------------------------------------------------------
  // Android 侧（flutter create 产物固化）
  // ---------------------------------------------------------------------------

  /**
   * `android/settings.gradle.kts`。
   *
   * **flutter.sdk 的 require 是 Flutter 工具链的硬约束**——设备上没有
   * SDK 时这里直接失败（这正是本模板设备端不可构建的原因，README 已说明）。
   * 云端 CI 里 `flutter build` 会先运行 flutter tool 并自动写好
   * local.properties 的 flutter.sdk 行。
   */
  fun androidSettingsGradleKts(): String =
      """
      pluginManagement {
          val flutterSdkPath =
              run {
                  val properties = java.util.Properties()
                  file("local.properties").inputStream().use { properties.load(it) }
                  val flutterSdkPath = properties.getProperty("flutter.sdk")
                  require(flutterSdkPath != null) { "flutter.sdk not set in local.properties" }
                  flutterSdkPath
              }

          includeBuild("${'$'}flutterSdkPath/packages/flutter_tools/gradle")

          repositories {
              google()
              mavenCentral()
              gradlePluginPortal()
          }
      }

      plugins {
          id("dev.flutter.flutter-plugin-loader") version "1.0.0"
          id("com.android.application") version "$AGP_VERSION" apply false
          id("org.jetbrains.kotlin.android") version "$KOTLIN_VERSION" apply false
      }

      include(":app")
      """
          .trimIndent() + "\n"

  /** `android/build.gradle.kts`（flutter create 产物，一字未改）。 */
  fun androidRootBuildGradleKts(): String =
      """
      allprojects {
          repositories {
              google()
              mavenCentral()
          }
      }

      val newBuildDir: Directory =
          rootProject.layout.buildDirectory
              .dir("../../build")
              .get()
      rootProject.layout.buildDirectory.value(newBuildDir)

      subprojects {
          val newSubprojectBuildDir: Directory = newBuildDir.dir(project.name)
          project.layout.buildDirectory.value(newSubprojectBuildDir)
      }
      subprojects {
          project.evaluationDependsOn(":app")
      }

      tasks.register<Delete>("clean") {
          delete(rootProject.layout.buildDirectory)
      }
      """
          .trimIndent() + "\n"

  /**
   * `android/gradle.properties`。
   *
   * 内存参数调低（官方模板 -Xmx8G/-XX:MaxMetaspaceSize=4G 是桌面级配置）：
   * 云端 runner 规格不一（GitHub 标准 runner 现为 16G，但更小的环境常见），
   * 4G 堆实测足够构建（本机 `flutter build apk --debug` 验证通过）；
   * 设备端不构建，调低无副作用。
   */
  fun androidGradleProperties(): String =
      """
      org.gradle.jvmargs=-Xmx4G -XX:MaxMetaspaceSize=1G -XX:ReservedCodeCacheSize=512m -XX:+HeapDumpOnOutOfMemoryError
      android.useAndroidX=true
      # This newDsl flag was added by the Flutter template
      android.newDsl=false
      # This builtInKotlin flag was added by the Flutter template
      android.builtInKotlin=false
      """
          .trimIndent() + "\n"

  /**
   * `android/app/build.gradle.kts`。
   *
   * 与 flutter create 产物的唯一差异：namespace/applicationId 用用户包名。
   * `flutter.compileSdkVersion` 等由 Flutter Gradle 插件提供（来自 SDK），
   * 不在模板里写死——这也是设备端缺 SDK 时配置阶段就失败的原因之一。
   */
  fun appBuildGradleKts(packageId: String): String =
      """
      plugins {
          id("com.android.application")
          // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
          id("dev.flutter.flutter-gradle-plugin")
      }

      android {
          namespace = "$packageId"
          compileSdk = flutter.compileSdkVersion
          ndkVersion = flutter.ndkVersion

          compileOptions {
              sourceCompatibility = JavaVersion.VERSION_17
              targetCompatibility = JavaVersion.VERSION_17
          }

          defaultConfig {
              applicationId = "$packageId"
              minSdk = flutter.minSdkVersion
              targetSdk = flutter.targetSdkVersion
              versionCode = flutter.versionCode
              versionName = flutter.versionName
          }

          buildTypes {
              release {
                  // TODO: Add your own signing config for the release build.
                  // Signing with the debug keys for now, so `flutter run --release` works.
                  signingConfig = signingConfigs.getByName("debug")
              }
          }
      }

      kotlin {
          compilerOptions {
              jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
          }
      }

      flutter {
          source = "../.."
      }
      """
          .trimIndent() + "\n"

  /** `android/app/src/main/kotlin/<pkg>/MainActivity.kt`。 */
  fun mainActivityKt(packageId: String): String =
      """
      package $packageId

      import io.flutter.embedding.android.FlutterActivity

      class MainActivity : FlutterActivity()
      """
          .trimIndent() + "\n"

  /** `android/app/src/main/AndroidManifest.xml`（flutter create 产物，label 用项目名）。 */
  fun appManifest(projectName: String): String =
      """
      <manifest xmlns:android="http://schemas.android.com/apk/res/android">
          <application
              android:label="$projectName"
              android:name="${'$'}{applicationName}"
              android:icon="@mipmap/ic_launcher">
              <activity
                  android:name=".MainActivity"
                  android:exported="true"
                  android:launchMode="singleTop"
                  android:taskAffinity=""
                  android:theme="@style/LaunchTheme"
                  android:configChanges="orientation|keyboardHidden|keyboard|screenSize|smallestScreenSize|locale|layoutDirection|fontScale|screenLayout|density|uiMode"
                  android:hardwareAccelerated="true"
                  android:windowSoftInputMode="adjustResize">
                  <meta-data
                    android:name="io.flutter.embedding.android.NormalTheme"
                    android:resource="@style/NormalTheme"
                    />
                  <intent-filter>
                      <action android:name="android.intent.action.MAIN"/>
                      <category android:name="android.intent.category.LAUNCHER"/>
                  </intent-filter>
              </activity>
              <meta-data
                  android:name="flutterEmbedding"
                  android:value="2" />
          </application>
          <queries>
              <intent>
                  <action android:name="android.intent.action.PROCESS_TEXT"/>
                  <data android:mimeType="text/plain"/>
              </intent>
          </queries>
      </manifest>
      """
          .trimIndent() + "\n"

  // ---------------------------------------------------------------------------
  // Dart 侧
  // ---------------------------------------------------------------------------

  /**
   * `lib/main.dart`——自写的计数器示例（与 CMP/RN 模板同一验证语义）。
   *
   * 不用 flutter create 的完整计数器模板（它带 FloatingActionButton 与
   * 大量教学注释）；精简版保留「Material 主题 + setState 计数器」，
   * 验证 Dart 逻辑 → Flutter 框架 → 原生渲染整条链路。
   */
  fun mainDart(projectName: String): String =
      """
      import 'package:flutter/material.dart';

      void main() {
        runApp(const MyApp());
      }

      class MyApp extends StatelessWidget {
        const MyApp({super.key});

        @override
        Widget build(BuildContext context) {
          return MaterialApp(
            title: '$projectName',
            theme: ThemeData(
              colorScheme: ColorScheme.fromSeed(seedColor: Colors.blue),
            ),
            home: const MyHomePage(),
          );
        }
      }

      class MyHomePage extends StatefulWidget {
        const MyHomePage({super.key});

        @override
        State<MyHomePage> createState() => _MyHomePageState();
      }

      class _MyHomePageState extends State<MyHomePage> {
        int _counter = 0;

        void _incrementCounter() {
          setState(() {
            _counter++;
          });
        }

        @override
        Widget build(BuildContext context) {
          return Scaffold(
            appBar: AppBar(
              backgroundColor: Theme.of(context).colorScheme.inversePrimary,
              title: const Text('$projectName'),
            ),
            body: Center(
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: <Widget>[
                  const Text('Hello Flutter'),
                  Text(
                    '${'$'}_counter',
                    style: Theme.of(context).textTheme.displayLarge,
                  ),
                ],
              ),
            ),
            floatingActionButton: FloatingActionButton(
              onPressed: _incrementCounter,
              tooltip: 'Increment',
              child: const Icon(Icons.add),
            ),
          );
        }
      }
      """
          .trimIndent() + "\n"

  /**
   * `pubspec.yaml`。
   *
   * `flutter create` 产物的精简版：保留 name/description/version/environment/
   * dependencies/flutter 节；`flutter_lints` 与注释全部保留（lint 配置
   * 影响 IDE 诊断，去掉会让 analysis_options.yaml 的 include 失效）。
   */
  fun pubspecYaml(projectName: String): String =
      """
      name: $projectName
      description: "A new Flutter project created by Android Code Studio."
      publish_to: 'none'

      version: 1.0.0+1

      environment:
        sdk: ^$DART_VERSION

      dependencies:
        flutter:
          sdk: flutter

        # The following adds the Cupertino Icons font to your application.
        cupertino_icons: ^1.0.8

      dev_dependencies:
        flutter_test:
          sdk: flutter

        flutter_lints: ^6.0.0

      flutter:
        uses-material-design: true
      """
          .trimIndent() + "\n"

  /** `analysis_options.yaml`（flutter create 产物精简版）。 */
  fun analysisOptionsYaml(): String =
      """
      include: package:flutter_lints/flutter.yaml

      analyzer:
        exclude:
          - build/**
          - android/**
          - web/**

      linter:
        rules:
          # avoid_print: false
          # prefer_single_quotes: true
      """
          .trimIndent() + "\n"

  /** `test/widget_test.dart`——与 main.dart 的计数器示例对应的冒烟测试。 */
  fun widgetTest(projectName: String): String =
      """
      // 计数器冒烟测试：与 lib/main.dart 的示例对应。

      import 'package:flutter/material.dart';
      import 'package:flutter_test/flutter_test.dart';

      import 'package:$projectName/main.dart';

      void main() {
        testWidgets('Counter increments smoke test', (WidgetTester tester) async {
          await tester.pumpWidget(const MyApp());

          expect(find.text('0'), findsOneWidget);
          expect(find.text('1'), findsNothing);

          await tester.tap(find.byIcon(Icons.add));
          await tester.pump();

          expect(find.text('0'), findsNothing);
          expect(find.text('1'), findsOneWidget);
        });
      }
      """
          .trimIndent() + "\n"

  // ---------------------------------------------------------------------------
  // web 侧
  // ---------------------------------------------------------------------------

  /** `web/index.html`（flutter create 产物，标题用项目名）。 */
  fun webIndexHtml(projectName: String): String =
      """
      <!DOCTYPE html>
      <html>
      <head>
        <base href="${'$'}FLUTTER_BASE_HREF">

        <meta charset="UTF-8">
        <meta content="IE=Edge" http-equiv="X-UA-Compatible">
        <meta name="description" content="$projectName - Flutter web app.">

        <meta name="mobile-web-app-capable" content="yes">
        <meta name="apple-mobile-web-app-status-bar-style" content="black">
        <meta name="apple-mobile-web-app-title" content="$projectName">
        <link rel="apple-touch-icon" href="icons/Icon-192.png">

        <link rel="icon" type="image/png" href="favicon.png"/>

        <title>$projectName</title>
        <link rel="manifest" href="manifest.json">
      </head>
      <body>
        <script src="flutter_bootstrap.js" async></script>
      </body>
      </html>
      """
          .trimIndent() + "\n"

  /** `web/manifest.json`。 */
  fun webManifestJson(projectName: String): String =
      """
      {
          "name": "$projectName",
          "short_name": "$projectName",
          "start_url": ".",
          "display": "standalone",
          "background_color": "#0175C2",
          "theme_color": "#0175C2",
          "description": "$projectName - Flutter web app.",
          "orientation": "portrait-primary",
          "prefer_related_applications": false,
          "icons": [
              {
                  "src": "icons/Icon-192.png",
                  "sizes": "192x192",
                  "type": "image/png"
              },
              {
                  "src": "icons/Icon-512.png",
                  "sizes": "512x512",
                  "type": "image/png"
              },
              {
                  "src": "icons/Icon-maskable-192.png",
                  "sizes": "192x192",
                  "type": "image/png",
                  "purpose": "maskable"
              },
              {
                  "src": "icons/Icon-maskable-512.png",
                  "sizes": "512x512",
                  "type": "image/png",
                  "purpose": "maskable"
              }
          ]
      }
      """
          .trimIndent() + "\n"

  // ---------------------------------------------------------------------------
  // 工程元数据
  // ---------------------------------------------------------------------------

  /**
   * `.metadata`——Flutter 工具链的工程标识（版本 + migration 记录）。
   *
   * revision 固定为 3.47.7 的 commit（flutter create 产物值）。手工改它没有
   * 收益：Flutter 只在 migrate/upgrade 时读它，版本不匹配时给 warning 而非错误。
   */
  fun metadata(): String =
      """
      # This file tracks properties of this Flutter project.
      # Used by Flutter tool to assess capabilities and perform upgrades etc.
      #
      # This file should be version controlled and should not be manually edited.

      version:
        revision: "abaf9c523780a608bd46686fd5e53740a07077f8"
        channel: "stable"

      project_type: app

      # Tracks metadata for the flutter migrate command
      migration:
        platforms:
          - platform: root
            create_revision: abaf9c523780a608bd46686fd5e53740a07077f8
            base_revision: abaf9c523780a608bd46686fd5e53740a07077f8
          - platform: android
            create_revision: abaf9c523780a608bd46686fd5e53740a07077f8
            base_revision: abaf9c523780a608bd46686fd5e53740a07077f8
          - platform: web
            create_revision: abaf9c523780a608bd46686fd5e53740a07077f8
            base_revision: abaf9c523780a608bd46686fd5e53740a07077f8

        # User provided section

        # List of Local paths (relative to this file) that should be
        # ignored by the migrate tool.
        #
        # Files that are not part of the templates will be ignored by default.
        unmanaged_files:
          - 'lib/main.dart'
          - 'ios/Runner.xcodeproj/project.pbxproj'
      """
          .trimIndent() + "\n"

  /** 根 `.gitignore`（flutter create 产物精简版）。 */
  fun gitignore(): String =
      """
      # Miscellaneous
      *.class
      *.log
      *.pyc
      *.swp
      .DS_Store
      .atom/
      .build/
      .buildlog/
      .history
      .svn/
      .swiftpm/
      migrate_working_dir/

      # IntelliJ related
      *.iml
      *.ipr
      *.iws
      .idea/

      # Flutter/Dart/Pub related
      **/doc/api/
      .dart_tool/
      .flutter-plugins-dependencies
      build/

      # Android related
      **/android/**/gradle-wrapper.jar
      **/android/.gradle
      **/android/captures/
      **/android/gradlew
      **/android/gradlew.bat
      **/android/local.properties
      **/android/**/GeneratedPluginRegistrant.java
      **/android/key.properties
      *.jks

      # Web related
      lib/generated_plugin_registrant.dart

      # 云编译产物与缓存（ACS 云编译功能下载的 artifact / 日志 zip）
      cloudbuild-output/
      .cloudbuild-cache/
      """
          .trimIndent() + "\n"

  /** `android/.gitignore`（flutter create 产物，一字未改）。 */
  fun androidGitignore(): String =
      """
      gradle-wrapper.jar
      /.gradle
      /captures/
      /gradlew
      /gradlew.bat
      /local.properties
      GeneratedPluginRegistrant.java
      .cxx/

      # Remember to never publicly share your keystore.
      # See https://flutter.dev/to/reference-keystore
      key.properties
      **/*.keystore
      **/*.jks
      """
          .trimIndent() + "\n"

  /**
   * `android/gradle/wrapper/gradle-wrapper.properties`。
   *
   * distributionUrl 用腾讯镜像（与 CMP/RN 模板同策略）——云端 CI 无所谓，
   * 但用户若在有网络的环境尝试构建（如桌面 Flutter），镜像更快。
   * 注意 Flutter 模板默认是 `-all.zip`（IDE 需要源码），保留 all 分发。
   */
  fun gradleWrapperProperties(): String =
      """
      distributionBase=GRADLE_USER_HOME
      distributionPath=wrapper/dists
      zipStoreBase=GRADLE_USER_HOME
      zipStorePath=wrapper/dists
      distributionUrl=https\://mirrors.cloud.tencent.com/gradle/gradle-$GRADLE_VERSION-all.zip
      """
          .trimIndent() + "\n"

  // ---------------------------------------------------------------------------
  // CI workflow 与 README
  // ---------------------------------------------------------------------------

  /**
   * GitHub Actions workflow——云端出 Android APK + Web 站点。
   *
   * `subosito/flutter-action@v2` 是社区标准（2613★），支持锁定版本；
   * `flutter build apk --release` 在 ubuntu runner 上无需额外配置。
   * Web 构建同 runner（`flutter build web`），产物可直接部署静态托管。
   *
   * 注意：**Flutter 的设备端构建不可行**（缺 SDK），所以这个 workflow
   * 是用户获取安装包的唯一路径——README 里要显著说明。
   *
   * <p><b>retention-days: 7</b>：Actions artifact 计入仓库存储配额，而 APK
   * 单个上百 MB——仓库默认保留期 90 天时，几次构建就能把免费配额堆满，之后
   * workflow 直接跑不起来（用户实测踩过）。7 天足够取回产物。
   */
  fun ciWorkflow(projectName: String): String =
      """
      name: Build Flutter App

      on:
        push:
          branches: [ main, master ]
        workflow_dispatch:

      jobs:
        android-apk:
          runs-on: ubuntu-latest
          steps:
            - uses: actions/checkout@v4
            - uses: subosito/flutter-action@v2
              with:
                flutter-version: '$FLUTTER_VERSION'
                channel: 'stable'
                cache: true
            - run: flutter pub get
            - run: flutter build apk --release
            - uses: actions/upload-artifact@v4
              with:
                name: ${projectName}-android-apk
                retention-days: 7
                path: build/app/outputs/flutter-apk/*.apk

        web:
          runs-on: ubuntu-latest
          steps:
            - uses: actions/checkout@v4
            - uses: subosito/flutter-action@v2
              with:
                flutter-version: '$FLUTTER_VERSION'
                channel: 'stable'
                cache: true
            - run: flutter pub get
            - run: flutter build web --release
            - uses: actions/upload-artifact@v4
              with:
                name: ${projectName}-web
                retention-days: 7
                path: build/web/**
      """
          .trimIndent() + "\n"

  /**
   * README——必须显著说明「设备端不构建、云端出包」。
   *
   * 用户最容易困惑的点：在 ACS 里点构建会报 flutter.sdk 错误。README
   * 要开门见山解释为什么（Flutter 的构建必须有完整 SDK，手机上装不了），
   * 以及正确用法（编辑 Dart → 推 GitHub → 下载 artifact）。
   */
  fun projectReadme(packageId: String, projectName: String): String =
      """
      # $projectName

      由 **Android Code Studio** 的 *Flutter* 模板生成：
      用 Dart 写一份代码，构建 Android APK 与 Web 站点。

      ## 重要：本项目在手机上不构建，由 GitHub Actions 云端出包

      Flutter 的构建**必须依赖完整的 Flutter SDK**（Dart 编译器 + 引擎工具链，
      官方不支持 Android 宿主机）——手机上无法安装。因此：

      - **在 ACS 里编辑 `lib/main.dart`**
      - **推送到 GitHub**，`.github/workflows/flutter.yml` 自动构建
      - **在 Actions 页面下载产物**：`$projectName-android-apk`（APK）与
        `$projectName-web`（网页站点，可直接部署到任意静态托管）

      > 在手机上点「构建」会报 `flutter.sdk not set in local.properties`
      > ——这是预期行为，不是配置错误。

      ## 代码补全与诊断（Dart LSP）

      ACS 支持 Dart 语言服务器（基于官方 Dart SDK 的分析服务器，
      提供补全/诊断/跳转/格式化）。**需要先安装 Dart SDK**（约 67MB，
      解压后约 169MB）——它不是 APK 内置组件：

      1. 打开 **设置 → 编辑器 → 语言服务器 → Dart / Flutter**
      2. 点击「下载」等待完成（安装在应用私有目录，不占用项目空间）
      3. 重新打开本项目的 `.dart` 文件，补全与诊断即生效

      安装后打开 `lib/main.dart` 即可看到：输入 `setState` / `Widget` 等
      标识符时的补全、未定义变量的红线提示、`Ctrl+点击` 跳转定义。
      分析基于项目内的 `pubspec.yaml`——Flutter SDK 自身的包
      （`package:flutter/...`）在设备上未安装，因此对它们的引用会提示
      未解析，这是预期行为（云端 CI 构建不受影响）。

      ## 项目结构

      ```
      $projectName/
      ├── lib/main.dart            # 你的 Dart 代码（编辑这里）
      ├── pubspec.yaml             # 依赖声明
      ├── test/widget_test.dart    # 冒烟测试
      ├── web/                     # Web 平台（index.html / manifest）
      └── android/                 # Android 平台（Gradle 工程）
          ├── settings.gradle.kts  # flutter.sdk 引入点
          └── app/                 # 模块（MainActivity / manifest / res）
      ```

      ## 云端构建说明

      workflow 使用 `subosito/flutter-action@v2` 安装 Flutter
      $FLUTTER_VERSION（与本地工程一致），跑 `flutter build apk --release`
      与 `flutter build web --release`。

      - 公共仓库 Actions 免费（含 artifact 存储）
      - 首次构建约 5–10 分钟（含 Flutter SDK 下载缓存）
      - 如需 iOS：需要 macOS runner + Apple 开发者账号（workflow 里可扩展）

      ## 版本组合

      | 组件 | 版本 |
      |---|---|
      | Flutter | $FLUTTER_VERSION |
      | Dart | $DART_VERSION |
      | Gradle | $GRADLE_VERSION |
      | Android Gradle Plugin | $AGP_VERSION |
      | Kotlin | $KOTLIN_VERSION |
      | 包名 | $packageId |
      """
          .trimIndent() + "\n"
}
