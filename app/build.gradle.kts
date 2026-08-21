plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

// Fix #77/#78: deterministic version code that does not depend on git.
// Uses a hardcoded base plus CI-provided VERSION_CODE when present.
// Do NOT use `git rev-list --count HEAD`: it is non-monotonic across branches
// and breaks in source-zip builds where .git is absent.
val versionCodeBase = 1
val versionCodeFromEnv = System.getenv("VERSION_CODE")?.toIntOrNull()
val resolvedVersionCode = versionCodeFromEnv ?: versionCodeBase

android {
    namespace = "com.aircontrol"
    compileSdk = 35

    signingConfigs {
        create("release") {
            val ksFile = file("release.keystore")
            if (ksFile.exists()) {
                storeFile = ksFile
            }
            val ksPass = System.getenv("KEYSTORE_PASSWORD") ?: ""
            val keyPass = System.getenv("KEY_PASSWORD") ?: ""
            val keyAlias = System.getenv("KEY_ALIAS") ?: "release"
            storePassword = ksPass
            keyPassword = keyPass
            this.keyAlias = keyAlias
            enableV3Signing = true
            enableV4Signing = true
        }
    }

    defaultConfig {
        applicationId = "com.aircontrol"
        minSdk = 26
        targetSdk = 35
        versionCode = resolvedVersionCode
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
            isCrunchPngs = true
            val keystoreFile = file("release.keystore")
            val ksPassword = System.getenv("KEYSTORE_PASSWORD")
            // Fix #79: fail loudly when signing isn't configured, rather than
            // silently producing an unsigned release APK.
            val hasKeystore = keystoreFile.exists() && !ksPassword.isNullOrEmpty()
            if (hasKeystore) {
                signingConfig = signingConfigs.getByName("release")
            } else if (gradle.startParameter.taskNames.any { "Release" in it }) {
                throw GradleException(
                    "Release build requires release.keystore + KEYSTORE_PASSWORD env var. " +
                        "See CONTRIBUTING.md for release signing instructions.",
                )
            }
        }
        debug {
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

    kotlinOptions {
        jvmTarget = "17"
        allWarningsAsErrors = false
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
        jniLibs {
            useLegacyPackaging = false
        }
    }

    lint {
        warningsAsErrors = false
        abortOnError = true
        // Fix #86: remove lint-baseline.xml (it hid real issues) and re-enable
        // MissingTranslation/ExtraTranslation so missing translations don't slip in.
        checkDependencies = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            // Fix #85: isReturnDefaultValues = true makes unstubbed Android calls
            // silently return null/0, hiding real failures. Turned off.
            isReturnDefaultValues = false
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

    implementation(libs.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    // Fix #91: remove unused camera-view dependency (it was declared in the
    // catalog but not used in production code; only the debug screen uses it
    // via AndroidView<PreviewView>, so keep it).
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

    androidTestImplementation(composeBom)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
    // Fix #89: use the BOM-managed Compose test version (remove pinned version
    // from catalog in a moment).
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.android.compiler)
    androidTestImplementation(libs.kotlinx.coroutines.test)

    debugImplementation(libs.compose.ui.test.manifest)
}
