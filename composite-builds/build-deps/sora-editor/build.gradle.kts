// src/main 和 LICENSE 取自 https://github.com/nullij/sora-editor 的固定提交
// d067cee6c6bddc329099dcced73553efcec07984（editor/src/main 树 be65d888c7cc40a32cf73aa3e319a536044404ad）。
// 与宿主共用 AGP/Kotlin；本地及 CI 都直接构建这份源码，不获取浮动的 SNAPSHOT。
plugins {
  id("com.android.library")
  id("kotlin-android")
}

android {
  namespace = "io.github.rosemoe.sora"
  compileSdk = 37
  compileSdkMinor = 2
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
}

dependencies {
  api(libs.androidx.annotation)
  // 与 r35 应用原先实际解析的版本一致，避免离线时倒退到未缓存的 1.4.5。
  implementation("androidx.collection:collection:1.5.0")
}
