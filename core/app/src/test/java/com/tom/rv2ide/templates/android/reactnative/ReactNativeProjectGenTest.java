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

package com.tom.rv2ide.templates.android.reactnative;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;

/**
 * 在 JVM 上生成一个<b>结构完整</b>的 React Native 工程到 {@code build/rn-project/}。
 *
 * <p>与 {@code ComposeMultiplatformProjectGenTest} 同一模式：字符串级测试
 * （{@code ReactNativeSourcesTest}）断言「生成物长什么样」，本测试把模板的
 * 文本生成器拼成完整工程树，断言结构齐备——供后续真实 Gradle 构建
 * （设备 E2E 或 CI）消费。
 *
 * <p>注意：本测试生成的工程**不含 node_modules**（那是 npm install 的产物），
 * 因此不能直接跑 Gradle——RN 的 settings 阶段就会失败。设备/CI E2E 时
 * 需先 `npm install`。
 */
public class ReactNativeProjectGenTest {

  private static final String PKG = "com.example.rntest";
  private static final String PROJECT_NAME = "rntest";

  @Test
  public void generateFullProjectStructure() throws IOException {
    File root = new File("build/rn-project");
    deleteRecursively(root);
    // 已存在的空目录（上次删除残留）会让 mkdirs() 返回 false，不算失败
    assertTrue(root.exists() || root.mkdirs());

    writeGradleFiles(root);
    writeAndroidModule(root);
    writeJsFiles(root);
    writeWorkflowAndReadme(root);

    // ---- 断言 Gradle 侧（扁平化：全部在项目根）----
    assertTrue("缺 settings.gradle", new File(root, "settings.gradle").exists());
    assertTrue("缺根 build.gradle", new File(root, "build.gradle").exists());
    assertTrue("缺 gradle.properties", new File(root, "gradle.properties").exists());
    assertTrue("缺 gradlew", new File(root, "gradlew").exists());
    assertTrue(
        "缺 wrapper jar", new File(root, "gradle/wrapper/gradle-wrapper.jar").exists());
    assertTrue(
        "缺 react-native.config.js", new File(root, "react-native.config.js").exists());

    // ---- 断言 Android 模块 ----
    assertTrue("缺 app/build.gradle", new File(root, "app/build.gradle").exists());
    assertTrue(
        "缺 MainApplication",
        new File(
                root,
                "app/src/main/kotlin/" + PKG.replace('.', '/') + "/MainApplication.kt")
            .exists());
    assertTrue(
        "缺 MainActivity",
        new File(root, "app/src/main/kotlin/" + PKG.replace('.', '/') + "/MainActivity.kt")
            .exists());
    assertTrue(
        "缺 AndroidManifest",
        new File(root, "app/src/main/AndroidManifest.xml").exists());
    assertTrue("缺 strings.xml", new File(root, "app/src/main/res/values/strings.xml").exists());
    assertTrue("缺 styles.xml", new File(root, "app/src/main/res/values/styles.xml").exists());
    assertTrue(
        "缺 debug.keystore", new File(root, "app/debug.keystore").exists());

    // ---- 断言 JS 侧（扁平化：全部在项目根）----
    assertTrue("缺 package.json", new File(root, "package.json").exists());
    assertTrue("缺 index.js", new File(root, "index.js").exists());
    assertTrue("缺 App.tsx", new File(root, "App.tsx").exists());
    assertTrue("缺 app.json", new File(root, "app.json").exists());
    assertTrue("缺 metro.config.js", new File(root, "metro.config.js").exists());
    assertTrue("缺 babel.config.js", new File(root, "babel.config.js").exists());

    // ---- 断言 CI 与文档 ----
    assertTrue(
        "缺 workflow", new File(root, ".github/workflows/react-native.yml").exists());
    assertTrue("缺 README", new File(root, "README.md").exists());
    assertTrue("缺 .gitignore", new File(root, ".gitignore").exists());

    // ---- 跨文件一致性：MainActivity 的组件名 == app.json 的 name == MainActivity 引用 ----
    String appJson =
        new String(
            Files.readAllBytes(new File(root, "app.json").toPath()), StandardCharsets.UTF_8);
    assertTrue("app.json 的 name 必须是项目名", appJson.contains("\"" + PROJECT_NAME + "\""));

    String mainActivity =
        new String(
            Files.readAllBytes(
                new File(
                        root,
                        "app/src/main/kotlin/" + PKG.replace('.', '/') + "/MainActivity.kt")
                    .toPath()),
            StandardCharsets.UTF_8);
    assertTrue(
        "MainActivity 的组件名必须与 app.json 一致",
        mainActivity.contains("= \"" + PROJECT_NAME + "\""));

    // ---- settings 与 app/build.gradle 的 node 路径必须一致（两处硬要求）----
    String settings =
        new String(
            Files.readAllBytes(new File(root, "settings.gradle").toPath()),
            StandardCharsets.UTF_8);
    String appBuild =
        new String(
            Files.readAllBytes(new File(root, "app/build.gradle").toPath()),
            StandardCharsets.UTF_8);
    assertTrue(
        "settings 缺 node 绝对路径",
        settings.contains(ReactNativeSources.TERMUX_NODE_PATH));
    assertTrue(
        "app/build.gradle 缺 node 绝对路径",
        appBuild.contains(ReactNativeSources.TERMUX_NODE_PATH));
  }

  @Test
  public void flatLayoutConsistency() throws IOException {
    // 扁平化布局的关键不变量：settings.gradle 的 includeBuild 路径、
    // app/build.gradle 的 root 路径、react-native.config.js 的 sourceDir
    // 三者必须互相一致——任何一处指向 android/ 子目录都会破坏布局。
    File root = new File("build/rn-project");
    if (!new File(root, "settings.gradle").exists()) {
      generateFullProjectStructure();
    }

    String settings = read(root, "settings.gradle");
    assertTrue(
        "includeBuild 必须指向项目根的 node_modules",
        settings.contains("includeBuild('node_modules/@react-native/gradle-plugin')"));

    String appBuild = read(root, "app/build.gradle");
    assertTrue("root 必须回指项目根", appBuild.contains("root = file(\"../\")"));

    String config = read(root, "react-native.config.js");
    assertTrue("sourceDir 必须是项目根", config.contains("sourceDir: '.'"));

    // 不得出现官方模板的 android/ 子目录布局痕迹
    assertTrue("settings 不得引用 android/ 子目录", !settings.contains("includeBuild(\"../node_modules"));
    assertTrue("config 不得指向 android/", !config.contains("sourceDir: 'android'"));
  }

  @Test
  public void versionConsistencyAcrossFiles() throws IOException {
    File root = new File("build/rn-project");
    if (!new File(root, "settings.gradle").exists()) {
      generateFullProjectStructure();
    }

    // 根 build.gradle 的 ext 块版本与 Sources 常量一致
    String rootBuild = read(root, "build.gradle");
    assertTrue(
        "buildTools 不一致", rootBuild.contains(ReactNativeSources.BUILD_TOOLS_VERSION));
    assertTrue("kotlin 不一致", rootBuild.contains(ReactNativeSources.KOTLIN_VERSION));

    // package.json 的 RN 版本与 Sources 常量一致
    String packageJson = read(root, "package.json");
    assertTrue(
        "package.json 的 RN 版本不一致",
        packageJson.contains("\"react-native\": \"" + ReactNativeSources.REACT_NATIVE_VERSION + "\""));
    assertTrue(
        "package.json 的 react 版本不一致",
        packageJson.contains("\"react\": \"" + ReactNativeSources.REACT_VERSION + "\""));
  }

  // ------------------------------------------------------------------ 写入辅助

  private void writeGradleFiles(File root) throws IOException {
    write(
        root,
        "settings.gradle",
        ReactNativeSources.INSTANCE.settingsGradle(PROJECT_NAME));
    write(
        root,
        "build.gradle",
        ReactNativeSources.INSTANCE.rootBuildGradle(ReactNativeSources.MIN_SDK));
    write(root, "gradle.properties", ReactNativeSources.INSTANCE.gradleProperties());
    write(root, "react-native.config.js", ReactNativeSources.INSTANCE.reactNativeConfigJs());

    // wrapper（gradlew + gradle/wrapper/*）——与真实模板的 copyWrapperFiles
    // 同布局：gradlew/gradlew.bat 在项目根，wrapper 目录在 gradle/ 下。
    // 生成树带上它才满足「可被 Gradle 消费」的声明（审查 M3）。
    File assetsGradle = new File("src/main/assets/ReactNative/gradle");
    if (assetsGradle.exists()) {
      Files.copy(
          new File(assetsGradle, "gradlew").toPath(), new File(root, "gradlew").toPath());
      Files.copy(
          new File(assetsGradle, "gradlew.bat").toPath(),
          new File(root, "gradlew.bat").toPath());
      copyDir(
          new File(assetsGradle, "wrapper"), new File(root, "gradle/wrapper"));
    }
  }

  private void writeAndroidModule(File root) throws IOException {
    write(root, "app/build.gradle", ReactNativeSources.INSTANCE.appBuildGradle(PKG));

    // debug.keystore 从 assets 复制真实文件——占位内容会让 packageDebug 的
    // 签名步骤失败（KeytoolException），掩盖「模板本身能不能出 APK」的验证。
    File keystoreSrc = new File("src/main/assets/ReactNative/app/debug.keystore");
    File keystoreDst = new File(root, "app/debug.keystore");
    keystoreDst.getParentFile().mkdirs();
    if (keystoreSrc.exists()) {
      Files.copy(keystoreSrc.toPath(), keystoreDst.toPath());
    } else {
      write(root, "app/debug.keystore", "placeholder-keystore");
    }

    String pkgPath = PKG.replace('.', '/');
    write(
        root,
        "app/src/main/kotlin/" + pkgPath + "/MainApplication.kt",
        ReactNativeSources.INSTANCE.mainApplication(PKG));
    write(
        root,
        "app/src/main/kotlin/" + pkgPath + "/MainActivity.kt",
        ReactNativeSources.INSTANCE.mainActivity(PKG, PROJECT_NAME));
    write(
        root,
        "app/src/main/AndroidManifest.xml",
        ReactNativeSources.INSTANCE.appManifest());
    write(
        root,
        "app/src/main/res/values/strings.xml",
        ReactNativeSources.INSTANCE.stringsXml(PROJECT_NAME));
    write(
        root, "app/src/main/res/values/styles.xml", ReactNativeSources.INSTANCE.stylesXml());

    // 从 assets 源目录拷贝全部资源（图标 + rn_edit_text_material）——
    // 与真实模板的 copyResourceFiles 行为一致。测试 CWD 是模块目录
    // （core/app），assets 的相对路径即 src/main/assets/ReactNative/resources。
    File assetsRes = new File("src/main/assets/ReactNative/resources");
    if (assetsRes.exists()) {
      copyDir(assetsRes, new File(root, "app/src/main/res"));
    } else {
      // 回退：至少让 styles.xml 的引用可解析（测试 CWD 不一定是模块目录）
      write(
          root,
          "app/src/main/res/drawable/rn_edit_text_material.xml",
          "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<selector xmlns:android=\"http://schemas.android.com/apk/res/android\" />\n");
    }
  }

  /** 递归拷贝目录（测试内小工具，避免依赖模板的私有拷贝逻辑）。 */
  private static void copyDir(File src, File dst) throws IOException {
    if (src.isDirectory()) {
      dst.mkdirs();
      File[] children = src.listFiles();
      if (children != null) {
        for (File c : children) copyDir(c, new File(dst, c.getName()));
      }
    } else {
      dst.getParentFile().mkdirs();
      Files.copy(src.toPath(), dst.toPath());
    }
  }

  private void writeJsFiles(File root) throws IOException {
    write(root, "package.json", ReactNativeSources.INSTANCE.packageJson(PROJECT_NAME));
    write(root, "index.js", ReactNativeSources.INSTANCE.indexJs());
    write(root, "App.tsx", ReactNativeSources.INSTANCE.appTsx(PROJECT_NAME));
    write(root, "app.json", ReactNativeSources.INSTANCE.appJson(PROJECT_NAME));
    write(root, "metro.config.js", ReactNativeSources.INSTANCE.metroConfigJs());
    write(root, "babel.config.js", ReactNativeSources.INSTANCE.babelConfigJs());
  }

  private void writeWorkflowAndReadme(File root) throws IOException {
    write(
        root,
        ".github/workflows/react-native.yml",
        ReactNativeSources.INSTANCE.ciWorkflow(PROJECT_NAME));
    write(root, "README.md", ReactNativeSources.INSTANCE.projectReadme(PKG, PROJECT_NAME));
    write(root, ".gitignore", ReactNativeSources.INSTANCE.gitignore());
  }

  private void write(File root, String relative, String content) throws IOException {
    File f = new File(root, relative);
    f.getParentFile().mkdirs();
    Files.write(f.toPath(), content.getBytes(StandardCharsets.UTF_8));
  }

  private String read(File root, String relative) throws IOException {
    return new String(
        Files.readAllBytes(new File(root, relative).toPath()), StandardCharsets.UTF_8);
  }

  private static void deleteRecursively(File f) {
    if (f.isDirectory()) {
      File[] children = f.listFiles();
      if (children != null) {
        for (File c : children) deleteRecursively(c);
      }
    }
    //noinspection ResultOfMethodCallIgnored
    f.delete();
  }
}
