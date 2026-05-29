pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_PROJECT)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "heartwith-android-uploader"
include(":heartwith-android-uploader")
project(":heartwith-android-uploader").projectDir = file("clients/heartwith-android-uploader")
