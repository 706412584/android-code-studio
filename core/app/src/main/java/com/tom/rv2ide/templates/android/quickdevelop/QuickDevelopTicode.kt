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

package com.tom.rv2ide.templates.android.quickdevelop

/**
 * Quick Develop 模板的 **`:ticode` 模块**生成逻辑。
 *
 * <h3>ticode 是什么</h3>
 *
 * 「结绳」语言基本库到 Java 的移植，333 个**中文命名**的 API 类，包名 `ticode.zh.*`。
 * （仓库源共 334 个：另有 1 个默认包的 `简易无障碍.java`，因无包名不随模板生成。）
 * 源码随模板生成进用户工程（`ticode/src/main/java/`），可直接改。
 *
 * <h3>为什么这个 object 只放纯字符串生成</h3>
 *
 * 与 [QuickDevelopSources] / [QuickDevelopToolkits] 同一取舍：把「生成什么内容」
 * 与「怎么落盘（需要 Android Context / assets）」分开。这样 `moduleGradle()` 与
 * `readme()` 可以在 JVM 单测里直接断言，不需要 Robolectric。
 * 拷贝源码（需要 `context.assets`）留在 `QuickDevelop.writeTicodeModule()`。
 *
 * <h3>为什么是 Android 库模块而不是 java-library</h3>
 *
 * ticode 引用 `android.*` 与 androidx（appcompat / constraintlayout / recyclerview /
 * flexbox）。这些依赖是 **AAR**，纯 `java-library` 解析不了 AAR 里的 classes.jar，
 * 所以必须 `com.android.library`（AGP 会正确处理 AAR）。
 * 仓库内的 `core/ticode` 为「零 AGP、纯 javac 离线可编」用本地 jar 垫片绕开，
 * 但生成给用户的工程不该背那 32MB。
 */
object QuickDevelopTicode {

  /** 模块名。生成 `ticode/` 目录，`settings.gradle` 里 `include(":ticode")`。 */
  const val MODULE_NAME = "ticode"

  /** 模块的 Gradle 项目路径。 */
  const val MODULE_PATH = ":ticode"

  /**
   * Android namespace。**固定**为 `ticode.zh`，不随用户包名变——
   * 源码里的 `package ticode.zh.*` 是写死的（结绳包名映射的产物）。
   */
  const val NAMESPACE = "ticode.zh"

  /** assets 里 ticode 源码的根目录（相对 `assets/`）。 */
  const val ASSETS_PATH = "QuickDevelop/ticode"

  /** 源码根在工程内的相对路径（`:ticode` 模块的 java source root）。 */
  const val SOURCE_ROOT = "ticode/src/main/java"

  /** 预期的源码文件数。改动 ticode 后此值需同步（`tools/sync-ticode-assets.sh` 会提示）。 */
  const val EXPECTED_SOURCE_COUNT = 333

  /** ticode 依赖的 androidx 库（catalog 别名）。flexbox 是它独有的。 */
  private val DEPENDENCIES =
      listOf(
          "libs.androidx.core",
          "libs.androidx.appcompat",
          "libs.androidx.recyclerview",
          "libs.androidx.constraintlayout",
          "libs.androidx.flexbox",
      )

  /**
   * 生成 `ticode/build.gradle(.kts)`。
   *
   * 手工拼字符串而非复用 `MLGradleWriter`：后者**无条件**输出
   * `android { namespace; compileSdk; defaultConfig { applicationId … } }`，
   * 且 `build()` 里 `require(namespace)` / `require(compileSdk > 0)` /
   * `requireNotNull(defaultConfig)` —— 库模块没有 applicationId，硬套会生成非法配置。
   *
   * @param useKts 用户工程用 Kotlin DSL（`build.gradle.kts`）还是 Groovy（`build.gradle`）
   * @param compileSdk compileSdk（与主模块一致）
   * @param minSdk minSdk（与主模块一致）
   */
  fun moduleGradle(useKts: Boolean, compileSdk: Int, minSdk: Int): String {
    val deps = DEPENDENCIES.joinToString("\n") {
      if (useKts) "    implementation($it)" else "    implementation $it"
    }
    return if (useKts) {
      """
        plugins {
            alias(libs.plugins.android.library)
        }

        android {
            namespace = "$NAMESPACE"
            compileSdk = $compileSdk

            defaultConfig {
                minSdk = $minSdk
            }

            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
        }

        dependencies {
        $deps
        }
      """
          .trimIndent()
    } else {
      """
        plugins {
            alias(libs.plugins.android.library)
        }

        android {
            namespace '$NAMESPACE'
            compileSdk $compileSdk

            defaultConfig {
                minSdk $minSdk
            }

            compileOptions {
                sourceCompatibility JavaVersion.VERSION_17
                targetCompatibility JavaVersion.VERSION_17
            }
        }

        dependencies {
        $deps
        }
      """
          .trimIndent()
    }
  }

  /** `ticode/README.md`：告诉用户这个模块是什么、怎么用。 */
  fun readme(): String =
      """
        # ticode —— 结绳语言基本库（Java 移植）

        本模块是「结绳」语言基本库到 Java 的移植，提供 333 个**中文命名**的 API 类，
        覆盖字符串 / 文件 / 集合 / 加解密 / 压缩 / 网络 / 反射 / Android UI 等。

        包名 `ticode.zh.*`：

        | 子包 | 内容 |
        |---|---|
        | `ticode.zh.base` | 基础类型（文本 / 整数 / 字符…） |
        | `ticode.zh.jvm`  | 纯 JVM（集合 / 流 / ZIP / 反射 / 正则…） |
        | `ticode.zh.android` | Android（控件 / 网络 / 加解密 / 文件…） |
        | `ticode.zh.meng` | RecyclerView 系列（适配器 / 布局管理器 / 弹性布局） |

        用法（`:app` 已 `implementation(project(":ticode"))`）：

        ```java
        import ticode.zh.android.加解密操作;
        import ticode.zh.jvm.GZIP操作;

        String md5 = 加解密操作.MD5加密("abc", "UTF-8");
        byte[] gz = GZIP操作.压缩字节集("hello".getBytes("UTF-8"));
        ```

        本模块由 Android Code Studio 的 Quick Develop 模板生成，源码随工程走，可直接改。
      """
          .trimIndent()

  /**
   * 工程根 `README.md` 里「该用哪一套」那一节。
   *
   * <p>为什么单独抽成函数：工程里同时有 `ui/`（45 控件）、`tool/`（5 工具类）、
   * `ticode`（333 类）**三套**中文 API，用户第一次打开会不知道用哪个。这段是
   * **唯一的选型指引**，必须跟着生成走，且要有测试锁住（`QuickDevelopTicodeTest`）。
   *
   * <p>控件数量从 [QuickDevelopSources.CHINESE_COMPONENT_COUNT] 取，不硬编码——
   * 之前写死「44」而真实已是 45，加一个控件就过期。
   */
  fun 选型指引(): String {
    val uiCount = QuickDevelopSources.CHINESE_COMPONENT_COUNT
    return """
        ## 该用哪一套？（三套并存，各司其职）

        工程里有三套中文 API，**按用途选，不要混着用**：

        | 要做什么 | 用哪个 | 例子 |
        |---|---|---|
        | **写界面**（推荐） | `ui/` | `new 按钮(this).文字("确定")` |
        | **常见工具**（推荐） | `tool/` | `字符.转换大写("abc")` |
        | **加解密 / ZIP / 反射 / 集合族 / 大数** | `ticode` | `加解密操作.MD5加密(s, "UTF-8")` |
        | **跑结绳老代码**（兼容） | `ticode` | `ticode.zh.android.文本框` |

        **为什么界面推荐 `ui/` 而不是 ticode 的控件**：两者实测都能用
        （`ui/` $uiCount/$uiCount、ticode 21/21），但设计不同——`ui/` 基于 androidx、
        链式 API、控件更全（$uiCount vs 21），是为本模板写的现代封装；ticode 的控件
        是结绳移植的原始包装（`getView()` 模式），价值在兼容结绳老代码。

        **ticode 不可替代的是它的工具类**（`ticode.zh.jvm` + `ticode.zh.base`，
        114 个类：ZIP/GZIP、反射、集合族、大整数、UUID…），这些 `tool/` 没有。

        不想要 ticode？删掉 `app/build.gradle` 里的
        `implementation(project(":ticode"))` 与 `settings.gradle` 里的
        `include(":ticode")` 即可。
      """
          .trimIndent()
  }
}
