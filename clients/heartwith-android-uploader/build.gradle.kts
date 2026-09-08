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

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
}
