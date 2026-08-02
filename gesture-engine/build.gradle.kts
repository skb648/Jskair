plugins {
    alias(libs.plugins.kotlin.jvm)
}

group = "com.aircontrol"
version = "1.0.0"

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    useJUnit()
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions {
        // L-03 Fix: Enable warnings-as-errors for the pure-Kotlin gesture-engine module.
        // This catches issues early in the most critical part of the codebase.
        // The app module keeps this disabled because Android/Compose generates
        // many unavoidable warnings from the framework itself.
        allWarningsAsErrors = true
    }
}
