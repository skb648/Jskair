package com.aircontrol.di

import com.aircontrol.accessibility.ActionDispatcher
import com.aircontrol.control.CursorController
import com.aircontrol.data.repository.SettingsRepository
import com.aircontrol.gestures.GestureDetector
import com.aircontrol.tracking.HandTracker
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Hilt entry point for AccessibilityService, which is not a standard
 * Hilt entry point (like Activity or Fragment). We use EntryPointAccessors
 * to manually obtain dependencies from the SingletonComponent.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface AccessibilityServiceEntryPoint {

    fun handTracker(): HandTracker
    fun gestureDetector(): GestureDetector
    fun actionDispatcher(): ActionDispatcher
    fun settingsRepository(): SettingsRepository
    fun cursorController(): CursorController

    companion object {
        fun getFromApplication(application: android.app.Application): AccessibilityServiceEntryPoint {
            return EntryPointAccessors.fromApplication(
                application,
                AccessibilityServiceEntryPoint::class.java,
            )
        }
    }
}
