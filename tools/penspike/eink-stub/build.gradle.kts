plugins {
    id("com.android.library")
}
android {
    namespace = "local.penspike.einkstub"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
