/*
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
*/
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.tom.androidcodestudio.acsprovider"
    // 与主项目统一（core:common 等已升到 37.2，AAR metadata 要求依赖方
    // compileSdk 不低于它）；用 BuildConfig 避免以后再漂移。
    compileSdk = com.tom.rv2ide.build.config.BuildConfig.compileSdk
    compileSdkMinor = com.tom.rv2ide.build.config.BuildConfig.compileSdkMinor

}

dependencies {

    implementation(projects.core.common)
    
    // Coroutines
    implementation(libs.common.kotlin.coroutines.core)
    implementation(libs.common.kotlin.coroutines.android)

    // OkHttp
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // Gson for JSON parsing
    implementation(libs.gson)

    // SLF4J for logging
    implementation(libs.tooling.slf4j)

    // JVM 单测：ACSProvider.findPackageEntry 的选包逻辑（纯 JSON + 数据类，无 Android 依赖）。
    // packageId 传 null 即可单测，因此不需要 Mockito。
    testImplementation(libs.tests.junit)
}
