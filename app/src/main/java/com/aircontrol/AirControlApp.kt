package com.aircontrol

import android.app.Application
import com.google.firebase.crashlytics.FirebaseCrashlytics
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

/**
 * Application class for AirControl.
 *
 * Initializes:
 * - Firebase Crashlytics for production crash reporting (release builds)
 * - Timber for structured logging (debug builds only)
 * - LeakCanary for memory leak detection (debug builds, auto-configured by the library)
 *
 * LeakCanary is included as a debugImplementation dependency and automatically
 * initializes itself via its ContentProvider. No manual initialization needed.
 * It will detect activities, fragments, views, and ViewModels that are not
 * properly garbage collected after being destroyed.
 *
 * To verify LeakCanary is working:
 * 1. Run the debug build
 * 2. Navigate through the app (onboarding → home → settings → back)
 * 3. LeakCanary will show a notification if any leaks are detected
 * 4. Detailed heap analysis is available in the LeakCanary activity
 */
@HiltAndroidApp
class AirControlApp : Application() {

    override fun onCreate() {
        super.onCreate()
        initCrashlytics()
        initTimber()
    }

    /**
     * H-01 Fix: Initialize Firebase Crashlytics for production crash reporting.
     * 
     * Setup instructions:
     * 1. Create a Firebase project at https://console.firebase.google.com
     * 2. Add an Android app with package name: com.aircontrol
     * 3. Download google-services.json and place it in app/ directory
     * 4. Enable Crashlytics in Firebase Console
     * 5. Build and run — crashes will appear in Firebase Console → Crashlytics
     * 
     * Note: Requires INTERNET permission (not currently declared). If you want
     * to maintain the "no network" privacy promise, remove Firebase and use
     * ACRA (sends crash reports via email) instead.
     */
    private fun initCrashlytics() {
        if (!BuildConfig.DEBUG) {
            // Enable Crashlytics only in release builds
            // Debug builds use Timber for local logging
            try {
                FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(true)
                // Set custom keys for debugging
                FirebaseCrashlytics.getInstance().setCustomKey("build_type", "release")
                FirebaseCrashlytics.getInstance().setCustomKey("app_version", BuildConfig.VERSION_NAME)
            } catch (e: Exception) {
                // Firebase not configured (missing google-services.json)
                // This is expected during initial setup
                Timber.w("Firebase Crashlytics initialization failed: ${e.message}")
            }
        } else {
            // Debug builds: disable Crashlytics collection
            try {
                FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(false)
            } catch (_: Exception) {
                // Firebase not available in debug
            }
        }
    }

    private fun initTimber() {
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
            Timber.d("AirControl application initialized")
            
            // Plant a custom tree that also logs to Crashlytics in debug
            Timber.plant(object : Timber.DebugTree() {
                override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                    super.log(priority, tag, message, t)
                    // Also send errors to Crashlytics even in debug for testing
                    if (priority >= android.util.Log.ERROR && t != null) {
                        try {
                            FirebaseCrashlytics.getInstance().recordException(t)
                        } catch (_: Exception) {
                            // Firebase not available
                        }
                    }
                }
            })
        }
    }
}
