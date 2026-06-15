package com.aircontrol

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

/**
 * Application class for AirControl.
 *
 * Initializes:
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
        initTimber()
    }

    private fun initTimber() {
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
            Timber.d("AirControl application initialized")
        }
    }
}
