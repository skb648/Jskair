plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

// Release/update safety: explicit VERSION_CODE wins; CI falls back to the
// workflow run number so every CI-built artifact has a monotonically increasing
// Android versionCode. Local builds retain a stable baseline.
val versionCodeBase = 2
val versionCodeFromEnv = System.getenv("VERSION_CODE")?.toIntOrNull()
val ciRunNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
val resolvedVersionCode = versionCodeFromEnv ?: ciRunNumber ?: versionCodeBase

require(resolvedVersionCode > 0) { "versionCode must be positive" }

android {
    namespace = "com.aircontrol"
    compileSdk = 37

    signingConfigs {
        create("debugConfig") {
            val debugKs = file("${rootDir}/debug.keystore")
            if (debugKs.exists()) {
                storeFile = debugKs
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
        create("release") {
            val ksFile = file("release.keystore")
            if (ksFile.exists()) storeFile = ksFile
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
            keyPassword = System.getenv("KEY_PASSWORD") ?: ""
            this.keyAlias = System.getenv("KEY_ALIAS") ?: "release"
            enableV3Signing = true
            enableV4Signing = true
        }
    }

    defaultConfig {
        applicationId = "com.aircontrol"
        minSdk = 26
        targetSdk = 37
        versionCode = resolvedVersionCode
        versionName = "1.0.1"
        testInstrumentationRunner = "com.aircontrol.HiltTestRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            isCrunchPngs = true
            val keystoreFile = file("release.keystore")
            val ksPassword = System.getenv("KEYSTORE_PASSWORD")
            val hasKeystore = keystoreFile.exists() && !ksPassword.isNullOrEmpty()
            if (hasKeystore) {
                signingConfig = signingConfigs.getByName("release")
            } else if (gradle.startParameter.taskNames.any { "Release" in it } && System.getenv("CI") != "true") {
                throw GradleException("Release build requires release.keystore + KEYSTORE_PASSWORD env var. See CONTRIBUTING.md for release signing instructions.")
            }
        }
        debug {
            if (file("${rootDir}/debug.keystore").exists()) {
                signingConfig = signingConfigs.getByName("debugConfig")
            }
            isMinifyEnabled = false
            isDebuggable = true
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = false
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
            )
        }
        jniLibs { useLegacyPackaging = false }
    }

    lint {
        warningsAsErrors = false
        abortOnError = true
        checkDependencies = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = false
            all {
                it.maxHeapSize = "256m"
                it.jvmArgs("-XX:+EnableDynamicAgentLoading")
            }
        }
    }
}

dependencies {
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.runtime)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.core.ktx)
    implementation(libs.core.splashscreen)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.service)
    implementation(libs.lifecycle.process)
    implementation(libs.navigation.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.mediapipe.tasks.vision)
    implementation(libs.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)
    implementation(project(":gesture-engine"))
    implementation(libs.timber)
    debugImplementation(libs.leakcanary.android)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    androidTestImplementation(composeBom)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.android.compiler)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    debugImplementation(libs.compose.ui.test.manifest)
}
