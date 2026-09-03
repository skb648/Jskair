package com.aircontrol.accessibility

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * Compatibility bridge for the accessibility service's camera-FGS start gate.
 *
 * The service intentionally must not rely on a background Activity reference. This
 * bridge keeps only a boolean and derives it from Application lifecycle callbacks,
 * so the service can safely know whether AirControl is currently visible without
 * leaking an Activity instance.
 */
internal class MainActivity private constructor() {
    companion object {
        @Volatile
        var isVisible: Boolean = false
            private set

        @Volatile
        private var registered = false

        private const val MAIN_ACTIVITY_NAME = "com.aircontrol.MainActivity"

        fun install(application: Application) {
            if (registered) return
            synchronized(this) {
                if (registered) return
                application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
                    override fun onActivityStarted(activity: Activity) {
                        if (activity.javaClass.name == MAIN_ACTIVITY_NAME) isVisible = true
                    }
                    override fun onActivityResumed(activity: Activity) {
                        if (activity.javaClass.name == MAIN_ACTIVITY_NAME) isVisible = true
                    }
                    override fun onActivityPaused(activity: Activity) {
                        if (activity.javaClass.name == MAIN_ACTIVITY_NAME) isVisible = false
                    }
                    override fun onActivityStopped(activity: Activity) {
                        if (activity.javaClass.name == MAIN_ACTIVITY_NAME) isVisible = false
                    }
                    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                    override fun onActivityDestroyed(activity: Activity) {
                        if (activity.javaClass.name == MAIN_ACTIVITY_NAME) isVisible = false
                    }
                })
                registered = true
            }
        }
    }
}
