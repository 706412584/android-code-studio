/*
 *  This file is part of AndroidIDE.
 *
 *  AndroidIDE is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidIDE is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidIDE.  If not, see <https://www.gnu.org/licenses/>.
 */

import com.android.build.api.dsl.CommonExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.android.library) apply false
  alias(libs.plugins.kotlin.android) apply false
}

subprojects {
  plugins.withId("com.android.library") {
    // AGP 9：BaseExtension 不再是注册的扩展类型，且其 compileSdkVersion(int) 旧 API
    // 无法表达 minor。改用 CommonExtension + getter/属性（AGP 8/9 通用）。
    // 这里固定 compileSdk=34（vendored 依赖的 AAR 只需这么高），不需要 minor 后缀，
    // 因为 android-34 是标准包名（无 minor）——见 SDK 平台目录名。
    extensions.getByType(CommonExtension::class.java).apply {
      buildToolsVersion = "34.0.0"
      compileSdk = 34

      defaultConfig.apply {
        minSdk = 26
        // targetSdk 只存在于 ApplicationBaseFlavor；本 block 只作用于 com.android.library
        // 子项目（见上面的 plugins.withId），故不设 targetSdk（AGP 9 起会报 Unresolved）。
      }

      compileOptions.apply {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
      }

      buildTypes.register("dev") {
        initWith(buildTypes.getByName("release"))
        isMinifyEnabled = false
      }
    }
  }

tasks.withType(KotlinCompile::class.java) {
  compilerOptions {
    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
  }
}

}