// Plugins are put on the root classpath so the Kotlin plugin and the Android
// plugin share one classloader. The Android plugin is only added when an SDK is
// present, so the engine can be built and tested on a plain JVM.
buildscript {
    val hasAndroidSdk = System.getenv("ANDROID_HOME") != null ||
        System.getenv("ANDROID_SDK_ROOT") != null ||
        java.io.File(rootDir, "local.properties").exists()
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("androidx.*")
                includeGroupByRegex("com\\.google.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        if (hasAndroidSdk) classpath("com.android.tools.build:gradle:8.7.3")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.21")
        classpath("org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.0.21")
    }
}
