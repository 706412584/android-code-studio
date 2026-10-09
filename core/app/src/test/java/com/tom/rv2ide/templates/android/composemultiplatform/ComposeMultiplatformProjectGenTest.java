/*
 * This file is part of AndroidCodeStudio.
 *
 * AndroidCodeStudio is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AndroidCodeStudio is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.templates.android.composemultiplatform;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;

/**
 * 在 JVM 上生成一个<b>结构完整</b>的 Compose Multiplatform 工程到 {@code build/cmp-project/}。
 *
 * <h3>与 QuickDevelopProjectGenTest 的分工</h3>
 *
 * 同一模式：字符串级测试（{@code ComposeMultiplatformSourcesTest}）断言「生成物长什么样」，
 * 本测试把模板的文本生成器拼成完整工程树，断言结构齐备——供后续真实 Gradle 构建
 * （设备 E2E 或 CI）消费。与 QuickDevelop 的差异：本工程是三模块 KMP，
 * 设备端只构建 {@code :app:assembleDebug}；desktop/wasm 的构建发生在云端 Actions，
 * 本机不验证（也无从验证——需要下载 KGP wasm 工具链）。
 *
 * <p>手写 settings/catalog/顶层 build 与 ComposeMultiplatform.kt 的生成逻辑保持
 * 等价——真正被构建校验的是文件内容本身。
 */
public class ComposeMultiplatformProjectGenTest {

  private static final String PKG = "com.example.cmptest";
  private static final String PROJECT_NAME = "cmptest";

  private static final String KOTLIN_VERSION = ComposeMultiplatformSources.KOTLIN_VERSION;
  private static final String CMP_VERSION = ComposeMultiplatformSources.COMPOSE_MULTIPLATFORM_VERSION;
  private static final String AGP_VERSION = "8.13.0";

  @Test
  public void generateFullProjectStructure() throws IOException {
    File root = new File("build/cmp-project");
    deleteRecursively(root);
    assertTrue(root.mkdirs());

    writeSettings(root);
    writeCatalog(root);
    writeTopLevel(root);
    writeGradleProperties(root);

    writeSharedModule(root);
    writeAppModule(root);
    writeDesktopModule(root);

    writeWorkflow(root);
    writeReadme(root);

    // ---- 断言产物结构 ----
    assertTrue("缺 settings", new File(root, "settings.gradle.kts").exists());
    assertTrue("缺 catalog", new File(root, "gradle/libs.versions.toml").exists());
    assertTrue("缺顶层 build", new File(root, "build.gradle.kts").exists());
    assertTrue("缺 gradle.properties", new File(root, "gradle.properties").exists());

    assertTrue(
        "缺 shared UI",
        new File(
                root,
                "composeApp/src/commonMain/kotlin/"
                    + PKG.replace('.', '/')
                    + "/shared/App.kt")
            .exists());
    assertTrue(
        "缺 shared build",
        new File(root, "composeApp/build.gradle.kts").exists());
    assertTrue(
        "缺 MainActivity",
        new File(root, "app/src/main/kotlin/" + PKG.replace('.', '/') + "/MainActivity.kt")
            .exists());
    assertTrue("缺 app build", new File(root, "app/build.gradle.kts").exists());
    assertTrue("缺 app manifest", new File(root, "app/src/main/AndroidManifest.xml").exists());
    assertTrue(
        "缺 desktop 入口",
        new File(root, "desktop/src/desktopMain/kotlin/" + PKG.replace('.', '/') + "/Main.kt")
            .exists());
    assertTrue("缺 desktop build", new File(root, "desktop/build.gradle.kts").exists());
    assertTrue(
        "缺 workflow", new File(root, ".github/workflows/multiplatform.yml").exists());
    assertTrue("缺 README", new File(root, "README.md").exists());

    // 跨模块引用一致性：app/desktop 引用的 shared 包名必须与 App.kt 的 package 一致
    String appKt =
        new String(
            Files.readAllBytes(
                new File(
                        root,
                        "composeApp/src/commonMain/kotlin/"
                            + PKG.replace('.', '/')
                            + "/shared/App.kt")
                    .toPath()),
            StandardCharsets.UTF_8);
    assertTrue(appKt.startsWith("package " + PKG + ".shared"));
  }

  @Test
  public void catalog_versionsAreConsistentAcrossFiles() throws IOException {
    File root = new File("build/cmp-project");
    if (!new File(root, "settings.gradle.kts").exists()) {
      // 单独跑本测试时先生成
      generateFullProjectStructure();
    }

    String catalog =
        new String(
            Files.readAllBytes(new File(root, "gradle/libs.versions.toml").toPath()),
            StandardCharsets.UTF_8);
    assertTrue(catalog.contains("kotlin = \"" + KOTLIN_VERSION + "\""));
    assertTrue(catalog.contains("composeMultiplatform = \"" + CMP_VERSION + "\""));
    assertTrue(catalog.contains("agp = \"" + AGP_VERSION + "\""));

    // KMP 插件必须与 Kotlin 同版本（compose compiler 并入 Kotlin 发行）
    assertTrue(
        catalog.contains(
            "kotlin-multiplatform = { id = \"org.jetbrains.kotlin.multiplatform\", version.ref = \"kotlin\" }"));
    assertTrue(
        catalog.contains(
            "kotlin-compose = { id = \"org.jetbrains.kotlin.plugin.compose\", version.ref = \"kotlin\" }"));

    // 不允许 androidx.compose BOM 混入（CMP 构件自带统一版本）
    assertFalse(catalog.contains("compose-bom"));
  }

  // ---------------------------------------------------------------------------

  private void writeSettings(File root) throws IOException {
    Files.write(
        new File(root, "settings.gradle.kts").toPath(),
        ("pluginManagement {\n"
                + "    repositories {\n"
                + "        google()\n"
                + "        mavenCentral()\n"
                + "        gradlePluginPortal()\n"
                + "    }\n"
                + "}\n"
                + "\n"
                + "dependencyResolutionManagement {\n"
                + "    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)\n"
                + "    repositories {\n"
                + "        google()\n"
                + "        mavenCentral()\n"
                + "    }\n"
                + "}\n"
                + "\n"
                + "rootProject.name = \""
                + PROJECT_NAME
                + "\"\n"
                + "\n"
                + "include(\":app\")\n"
                + "include(\":composeApp\")\n"
                + "include(\":desktop\")\n")
            .getBytes(StandardCharsets.UTF_8));
  }

  private void writeCatalog(File root) throws IOException {
    // 注意：本测试的编译类路径上没有 templates-api（core:app 的 test 依赖不含它，
    // QuickDevelop 的同类测试也因此硬编码），只能字面量——升级模板常量时
    // 这里要手动跟着改（审查 minor #3 的残留，权衡后接受）。
    String activityCompose = "1.11.0"; // templates-api ANDROIDX_ACTIVITY_COMPOSE
    String coreKtx = "1.17.0"; // templates-api ANDROIDX_CORE_KTEXT_VERSION
    String toml =
        "[versions]\n"
            + "agp = \""
            + AGP_VERSION
            + "\"\n"
            + "kotlin = \""
            + KOTLIN_VERSION
            + "\"\n"
            + "composeMultiplatform = \""
            + CMP_VERSION
            + "\"\n"
            + "lifecycleRuntime = \"2.9.2\"\n"
            + "activityCompose = \""
            + activityCompose
            + "\"\n"
            + "coreKtx = \""
            + coreKtx
            + "\"\n"
            + "\n[plugins]\n"
            + "android-application = { id = \"com.android.application\", version.ref = \"agp\" }\n"
            + "android-library = { id = \"com.android.library\", version.ref = \"agp\" }\n"
            + "kotlin-android = { id = \"org.jetbrains.kotlin.android\", version.ref = \"kotlin\" }\n"
            + "kotlin-multiplatform = { id = \"org.jetbrains.kotlin.multiplatform\", version.ref = \"kotlin\" }\n"
            + "kotlin-compose = { id = \"org.jetbrains.kotlin.plugin.compose\", version.ref = \"kotlin\" }\n"
            + "compose = { id = \"org.jetbrains.compose\", version.ref = \"composeMultiplatform\" }\n"
            + "\n[libraries]\n"
            + "androidx-core-ktx = { group = \"androidx.core\", name = \"core-ktx\", version.ref = \"coreKtx\" }\n"
            + "androidx-lifecycle-runtime = { group = \"androidx.lifecycle\", name = \"lifecycle-runtime-ktx\", version.ref = \"lifecycleRuntime\" }\n"
            + "androidx-activity-compose = { group = \"androidx.activity\", name = \"activity-compose\", version.ref = \"activityCompose\" }\n";
    File gradleDir = new File(root, "gradle");
    assertTrue(gradleDir.mkdirs());
    Files.write(new File(gradleDir, "libs.versions.toml").toPath(), toml.getBytes(StandardCharsets.UTF_8));
  }

  private void writeTopLevel(File root) throws IOException {
    // 与真实模板输出对齐（ComposeMultiplatform.kt）：includeCleanTask=false——
    // KMP 插件链的 LifecycleBasePlugin 已注册 clean，顶层再注册会撞名
    // （「Cannot add task 'clean'」）。测试树若保留该构造，真实 Gradle 构建
    // 会在 configuration 阶段失败，与模板产物不再等价。
    Files.write(
        new File(root, "build.gradle.kts").toPath(),
        ("// Top-level build file where you can add configuration options common to all sub-projects/modules.\n"
                + "plugins {\n"
                + "    alias(libs.plugins.android.application) apply false\n"
                + "    alias(libs.plugins.android.library) apply false\n"
                + "    alias(libs.plugins.kotlin.android) apply false\n"
                + "    alias(libs.plugins.kotlin.multiplatform) apply false\n"
                + "    alias(libs.plugins.kotlin.compose) apply false\n"
                + "    alias(libs.plugins.compose) apply false\n"
                + "}\n")
            .getBytes(StandardCharsets.UTF_8));
  }

  private void writeGradleProperties(File root) throws IOException {
    Files.write(
        new File(root, "gradle.properties").toPath(),
        ("org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8\n"
                + "android.useAndroidX=true\n"
                + "android.nonTransitiveRClass=true\n"
                + "kotlin.code.style=official\n")
            .getBytes(StandardCharsets.UTF_8));
  }

  private void writeSharedModule(File root) throws IOException {
    File composeAppDir = new File(root, "composeApp");
    assertTrue(composeAppDir.mkdirs());
    Files.write(
        new File(composeAppDir, "build.gradle.kts").toPath(),
        ComposeMultiplatformSources.INSTANCE
            .sharedModuleGradle(PKG, 36, 24)
            .getBytes(StandardCharsets.UTF_8));

    String sharedPkgPath = (PKG + ".shared").replace('.', '/');
    File sharedDir = new File(root, "composeApp/src/commonMain/kotlin/" + sharedPkgPath);
    assertTrue(sharedDir.mkdirs());
    Files.write(
        new File(sharedDir, "App.kt").toPath(),
        ComposeMultiplatformSources.INSTANCE.sharedApp(PKG).getBytes(StandardCharsets.UTF_8));
  }

  private void writeAppModule(File root) throws IOException {
    String pkgPath = PKG.replace('.', '/');
    File appDir = new File(root, "app");
    assertTrue(appDir.mkdirs());
    Files.write(
        new File(appDir, "build.gradle.kts").toPath(),
        ("plugins {\n"
                + "    alias(libs.plugins.android.application)\n"
                + "    alias(libs.plugins.kotlin.android)\n"
                + "    alias(libs.plugins.kotlin.compose)\n"
                + "}\n"
                + "\n"
                + "android {\n"
                + "    namespace = \""
                + PKG
                + "\"\n"
                + "    compileSdk = 36\n"
                + "\n"
                + "    defaultConfig {\n"
                + "        applicationId = \""
                + PKG
                + "\"\n"
                + "        minSdk = 24\n"
                + "        targetSdk = 34\n"
                + "        versionCode = 1\n"
                + "        versionName = \"1.0\"\n"
                + "    }\n"
                + "\n"
                + "    buildFeatures {\n"
                + "        compose = true\n"
                + "    }\n"
                + "\n"
                + "    compileOptions {\n"
                + "        sourceCompatibility = JavaVersion.VERSION_17\n"
                + "        targetCompatibility = JavaVersion.VERSION_17\n"
                + "    }\n"
                + "}\n"
                + "\n"
                + "dependencies {\n"
                + "    implementation(project(\":composeApp\"))\n"
                + "    implementation(libs.androidx.activity.compose)\n"
                + "    implementation(libs.androidx.lifecycle.runtime)\n"
                + "    implementation(libs.androidx.core.ktx)\n"
                + "}\n")
            .getBytes(StandardCharsets.UTF_8));

    File ktDir = new File(root, "app/src/main/kotlin/" + pkgPath);
    assertTrue(ktDir.mkdirs());
    Files.write(
        new File(ktDir, "MainActivity.kt").toPath(),
        ComposeMultiplatformSources.INSTANCE.mainActivity(PKG).getBytes(StandardCharsets.UTF_8));

    Files.write(
        new File(root, "app/src/main/AndroidManifest.xml").toPath(),
        ComposeMultiplatformSources.INSTANCE.appManifest().getBytes(StandardCharsets.UTF_8));

    File valuesDir = new File(root, "app/src/main/res/values");
    assertTrue(valuesDir.mkdirs());
    Files.write(
        new File(valuesDir, "themes.xml").toPath(),
        ComposeMultiplatformSources.INSTANCE.appThemes().getBytes(StandardCharsets.UTF_8));
    Files.write(
        new File(valuesDir, "strings.xml").toPath(),
        ("<resources><string name=\"app_name\">"
                + PROJECT_NAME
                + "</string></resources>")
            .getBytes(StandardCharsets.UTF_8));
  }

  private void writeDesktopModule(File root) throws IOException {
    File desktopDir = new File(root, "desktop");
    assertTrue(desktopDir.mkdirs());
    Files.write(
        new File(desktopDir, "build.gradle.kts").toPath(),
        ComposeMultiplatformSources.INSTANCE
            .desktopModuleGradle(PKG, PROJECT_NAME)
            .getBytes(StandardCharsets.UTF_8));

    String pkgPath = PKG.replace('.', '/');
    File mainDir = new File(root, "desktop/src/desktopMain/kotlin/" + pkgPath);
    assertTrue(mainDir.mkdirs());
    Files.write(
        new File(mainDir, "Main.kt").toPath(),
        ComposeMultiplatformSources.INSTANCE.desktopMain(PKG).getBytes(StandardCharsets.UTF_8));
  }

  private void writeWorkflow(File root) throws IOException {
    File wfDir = new File(root, ".github/workflows");
    assertTrue(wfDir.mkdirs());
    Files.write(
        new File(wfDir, "multiplatform.yml").toPath(),
        ComposeMultiplatformSources.INSTANCE
            .ciWorkflow(PROJECT_NAME)
            .getBytes(StandardCharsets.UTF_8));
  }

  private void writeReadme(File root) throws IOException {
    Files.write(
        new File(root, "README.md").toPath(),
        ComposeMultiplatformSources.INSTANCE
            .projectReadme(PKG, PROJECT_NAME)
            .getBytes(StandardCharsets.UTF_8));
  }

  private static void deleteRecursively(File f) {
    File[] children = f.listFiles();
    if (children != null) {
      for (File c : children) {
        deleteRecursively(c);
      }
    }
    //noinspection ResultOfMethodCallIgnored
    f.delete();
  }
}
