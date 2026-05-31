plugins {
    alias(libs.plugins.android.library)
}

group = "com.heartwith"
version = findProperty("heartwithClientVersionName") as String? ?: "1.0.0"

android {
    namespace = "com.heartwith.uploader"
    compileSdk = 37

    defaultConfig {
        minSdk = 23
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
