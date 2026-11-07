/*
 * @author Mohammed-baqer-null @ https://github.com/Mohammed-baqer-null
*/
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.tom.androidcodestudio.acsprovider"
    compileSdk = 36

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
