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

/*
 * ticode —— 结绳基本库的 Java 移植（双语言版本）。
 *
 * 由 tools/jieba-translate/translate.py 从结绳源码转译而来：
 *   src/main/java/ticode/{base,jvm,meng,android}/   ← 英文版（默认）
 *   src/zh/java/ticode/zh/{base,jvm,meng,android}/  ← 中文版（可选 sourceSet）
 *
 * 两版**包名不同**（ticode.* vs ticode.zh.*），可同时存在于一个 module，
 * 不会类名冲突。默认只编译英文版；加 -Pticode.lang=zh 一并编译中文版。
 *
 * ## 为什么是 java-library 而不是 android-library
 *
 * 生成的代码只引用三类东西：
 *   1. Android framework（`android.*`，含 `android.R` 常量）
 *   2. androidx.recyclerview / androidx.constraintlayout / flexbox
 *   3. 库内其它类
 * 没有任何 res/R.layout/R.id 引用，也没有 Activity/Service 子类。
 *
 * 用 `com.android.library` 时 AGP 会解析上面三者的 **POM 传递依赖**，
 * 其中 appcompat/material 拉进 `kotlin-stdlib-jdk8:1.6.21` 与
 * `androidx.core:core-ktx:1.2.0` —— 本机 Gradle 缓存里没有这两个版本，
 * `--offline` 直接失败。改成纯 `java-library` + 本地 jar 后不解析 POM，
 * 与 core/ai-* 三个模块的既有做法一致，完全离线可编译。
 *
 * 本模块不产出 AAR：它的用途是**给模板工程提供源码**（模板把类拷进生成
 * 工程的 src/main/java/），因此不需要打进 ACS 的 APK。
 *
 * libs/ 下的 jar 来源：
 *   android.jar        ← utilities/framework-stubs/libs/android.jar（SDK 框架面）
 *   recyclerview.jar   ← androidx.recyclerview:recyclerview:1.3.2 的 classes.jar
 *   constraintlayout.jar ← androidx.constraintlayout:constraintlayout:2.1.4 的 classes.jar
 *   flexbox.jar        ← com.google.android.flexbox:flexbox:3.0.0 的 classes.jar
 * 版本与 gradle/libs.versions.toml 中声明的版本一致。
 */

@Suppress("JavaPluginLanguageLevel")
plugins {
    id("java-library")
}

// 中文版放独立 sourceSet：默认不编译，否则 296×2 个类全进产物。
// 需要时 -Pticode.lang=zh 把它加进来（两版包名不同，并存不冲突）。
sourceSets {
    named("main") {
        if (providers.gradleProperty("ticode.lang").orNull == "zh") {
            java.srcDir("src/zh/java")
        }
    }
}

dependencies {
    // 只做编译期依赖：真正运行时的这些库由宿主（模板生成的工程）提供，
    // 这里不能打进产物，否则重复类。jar 来源见 libs/README.md。
    compileOnly(files("libs/android.jar"))
    compileOnly(files("libs/recyclerview.jar"))
    compileOnly(files("libs/constraintlayout.jar"))
    compileOnly(files("libs/flexbox.jar"))
    compileOnly(files("libs/appcompat.jar"))
}

// 源码含中文标识符与中文字符串字面量，必须显式钉 UTF-8：
// 中文 Windows 上 javac 默认按 GBK 读源文件，会把标识符/字面量读坏。
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}
