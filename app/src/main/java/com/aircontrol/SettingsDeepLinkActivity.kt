package com.aircontrol

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Fix #98: the system Accessibility settings screen links to
 * `android.accessibilityservice.action.SETTINGS` for each service. Previously
 * this pointed at the splash-themed [MainActivity] which could show onboarding.
 * This activity simply forwards to [MainActivity] with an extra that jumps
 * straight to Settings.
 */
class SettingsDeepLinkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = Intent(this, MainActivity::class.java).apply {
            action = MainActivity.ACTION_OPEN_SETTINGS
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(intent)
        finish()
    }
}
