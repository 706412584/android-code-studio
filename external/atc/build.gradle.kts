/*
 * Android Template Creator - Free and open-source project
 * Android Template Creator is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for complete details.
 *
 * A copy of the LGPL license can be found at <https://www.gnu.org/licenses/lgpl-3.0.html>.
 */

/*
 ** @Author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
 */

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.tom.androidcodestudio.atc"
    // 与主项目统一（core:common 等已升到 37.2，AAR metadata 要求依赖方
    // compileSdk 不低于它）；用 BuildConfig 避免以后再漂移。
    compileSdk = com.tom.rv2ide.build.config.BuildConfig.compileSdk
    compileSdkMinor = com.tom.rv2ide.build.config.BuildConfig.compileSdkMinor

    defaultConfig { minSdk = 21 }
    
    buildFeatures {
        buildConfig = false
    }
    
}

dependencies {
    // AndroidX
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)

    implementation(libs.common.datastore)
    implementation(libs.common.kotlin.coroutines.android)
}

// Kotlin 2.3 起 android 块内的 kotlinOptions{} 已弃用且在 AGP 9 下为编译错误；
// 顶层 kotlin{compilerOptions{}} 是项目统一写法（见 assistant/app、annotation/processors）。
kotlin {
  compilerOptions {
    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
  }
}
