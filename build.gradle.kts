// Top-level build file where you can add configuration options common to all sub-projects/modules.
buildscript {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        // Use implementation artifacts directly to avoid plugin-marker resolution
        // failures on some restricted mirrors.
        classpath("com.google.dagger:hilt-android-gradle-plugin:2.53.1")
        classpath("com.google.devtools.ksp:symbol-processing-gradle-plugin:2.0.21-1.0.28")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
