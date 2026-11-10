plugins {
    id("com.android.application")
}
android {
    namespace = "local.penspike"
    compileSdk = 37
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "local.penspike"
        minSdk = 29
        targetSdk = 29
        versionCode = 1
        versionName = "0.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    implementation("com.google.code.gson:gson:2.15.0")
    implementation("androidx.core:core:1.19.1")
    // android.eink.* liegt auf dem Geraet in /system/framework/eink.framework.jar (BOOTCLASSPATH).
    // Nur zum Kompilieren, wird nicht in die APK gepackt.
    compileOnly(project(":eink-stub"))
}
