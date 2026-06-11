package com.aircontrol

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.aircontrol.data.model.GestureMapConfig
import com.aircontrol.data.model.HandPreference
import com.aircontrol.data.model.UserPreferences
import com.aircontrol.data.repository.SettingsRepository
import com.aircontrol.data.repository.SettingsRepositoryImpl
import com.aircontrol.accessibility.GestureAction
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * Instrumented tests for DataStore gesture map migration.
 * Runs on a physical device or emulator to test real DataStore behavior.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class DataStoreMigrationTest {

    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Before
    fun setup() {
        hiltRule.inject()
    }

    @Test
    fun defaultGestureMapHasExpectedSchemaVersion() = runTest {
        val config = settingsRepository.gestureMapConfig.first()
        assertEquals(GestureMapConfig.CURRENT_SCHEMA_VERSION, config.schemaVersion)
    }

    @Test
    fun defaultGestureMapHasExpectedEntryCount() = runTest {
        val config = settingsRepository.gestureMapConfig.first()
        assertEquals(9, config.entries.size)
    }

    @Test
    fun defaultGestureMapContainsAllExpectedKeys() = runTest {
        val config = settingsRepository.gestureMapConfig.first()
        val keys = config.entries.map { it.key }.toSet()
        val expectedKeys = setOf(
            "swipe_left", "swipe_right", "swipe_up", "swipe_down",
            "pose_pinch", "pose_pointing", "pose_victory",
            "pose_thumb_up", "pose_thumb_down",
        )
        assertEquals(expectedKeys, keys)
    }

    @Test
    fun updateGestureActionPersistsAcrossReads() = runTest {
        settingsRepository.updateGestureAction("swipe_left", GestureAction.HOME.name)
        val config = settingsRepository.gestureMapConfig.first()
        assertEquals(GestureAction.HOME, config.entries.find { it.key == "swipe_left" }?.action)
    }

    @Test
    fun resetGestureMapRestoresDefaults() = runTest {
        // Modify a gesture action
        settingsRepository.updateGestureAction("swipe_left", GestureAction.HOME.name)

        // Reset to defaults
        settingsRepository.resetGestureMapToDefaults()

        val config = settingsRepository.gestureMapConfig.first()
        assertEquals(
            GestureAction.SCROLL_RIGHT,
            config.entries.find { it.key == "swipe_left" }?.action,
        )
    }

    @Test
    fun defaultUserPreferencesAreCorrect() = runTest {
        val prefs = settingsRepository.userPreferences.first()
        assertEquals(UserPreferences(), prefs)
    }

    @Test
    fun updateGesturesEnabledPersistsAcrossReads() = runTest {
        settingsRepository.updateGesturesEnabled(false)
        val prefs = settingsRepository.userPreferences.first()
        assertEquals(false, prefs.gesturesEnabled)
    }

    @Test
    fun updateHandPreferencePersistsAcrossReads() = runTest {
        settingsRepository.updateHandPreference(HandPreference.LEFT)
        val prefs = settingsRepository.userPreferences.first()
        assertEquals(HandPreference.LEFT, prefs.handPreference)
    }

    @Test
    fun appContextHasCorrectPackageName() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(
            "Package name should contain 'aircontrol'",
            appContext.packageName.contains("aircontrol"),
        )
    }
}
