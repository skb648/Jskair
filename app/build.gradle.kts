plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    // H-01 Fix: Firebase Crashlytics for production crash reporting and ANR detection
    // Setup: Place google-services.json in app/ directory (download from Firebase Console)
    id("com.google.gms.google-services") version "4.4.2"
    id("com.google.firebase.crashlytics") version "3.0.2"
}

// Auto versionCode from git commit count
val gitCommitCount = providers.exec {
    commandLine("git", "rev-list", "--count", "HEAD")
}.standardOutput.asText.get().trim().toIntOrNull() ?: 1

android {
    namespace = "com.aircontrol"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.aircontrol"
        minSdk = 26
        targetSdk = 36
        versionCode = gitCommitCount
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Enable R8 full mode (already default, but explicit)
            isCrunchPngs = true
            // Sign release builds if keystore is configured
            // Falls back to unsigned if env vars are not set (local dev)
            val keystoreFile = file("release.keystore")
            if (keystoreFile.exists() && !System.getenv("KEYSTORE_PASSWORD").isNullOrEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            isMinifyEnabled = false
            isDebuggable = true
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    signingConfigs {
        create("release") {
            // Release signing is configured via environment variables for CI/CD.
            // For local builds, place release.keystore in the app/ directory and
            // set KEYSTORE_PASSWORD and KEY_PASSWORD environment variables.
            // See docs/release-signing.md for setup instructions.
            storeFile = file("release.keystore")
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
            keyAlias = System.getenv("KEY_ALIAS") ?: "release"
            keyPassword = System.getenv("KEY_PASSWORD") ?: ""
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
        allWarningsAsErrors = false
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        warningsAsErrors = false
        abortOnError = true
        // L-04 Fix: Use lint baseline to track known issues
        baseline = file("lint-baseline.xml")
        // Disable lint checks that are not critical for build success
        disable += setOf(
            "MissingTranslation",
            "ExtraTranslation",
            "Typos",
            "TypographyEllipsis",
        )
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    // Compose BOM
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.runtime)
    debugImplementation(libs.compose.ui.tooling)

    // AndroidX Core
    implementation(libs.core.ktx)
    implementation(libs.activity.compose)

    // Lifecycle
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.service)

    // Navigation
    implementation(libs.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    // L-06 Fix: Use proper KSP configuration instead of string-based add("ksp", ...)
    ksp(libs.hilt.android.compiler)
    implementation(libs.hilt.navigation.compose)

    // CameraX
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)

    // MediaPipe
    implementation(libs.mediapipe.tasks.vision)

    // DataStore
    implementation(libs.datastore.preferences)

    // Accompanist
    implementation(libs.accompanist.permissions)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)

    // Gesture Engine (pure Kotlin module)
    implementation(project(":gesture-engine"))

    // Timber
    implementation(libs.timber)

    // H-01 Fix: Firebase Crashlytics for production crash reporting and ANR detection
    // This provides visibility into real-world crashes that users experience
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-crashlytics-ktx")
    implementation("com.google.firebase:firebase-analytics-ktx")

    // LeakCanary - debug only (auto-configures via ContentProvider)
    debugImplementation(libs.leakcanary.android)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.turbine)

    // Android Instrumented Testing
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.hilt.android.testing)
    // L-06 Fix: Use proper KSP configuration instead of string-based add("kspAndroidTest", ...)
    kspAndroidTest(libs.hilt.android.compiler)
    androidTestImplementation(libs.kotlinx.coroutines.test)

    // Compose test manifest for debug
    debugImplementation(libs.compose.ui.test.manifest)
}
