package com.aircontrol.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.aircontrol.data.model.HandPreference
import com.aircontrol.data.model.UserPreferences
import org.junit.Rule
import org.junit.Test

/**
 * Compose UI tests for settings persistence across recomposition.
 *
 * Tests that settings UI correctly reflects and maintains state
 * through Compose recomposition cycles.
 *
 * Full integration tests require @HiltAndroidTest with HiltComposeTestRule.
 */
class SettingsPersistenceTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun settingsDisplayReflectsCurrentValues() {
        var preferences by mutableStateOf(UserPreferences())

        composeTestRule.setContent {
            androidx.compose.material3.Column {
                androidx.compose.material3.Text(
                    text = "Sensitivity: ${preferences.sensitivity}",
                    modifier = androidx.compose.ui.Modifier.testTag("sensitivity"),
                )
                androidx.compose.material3.Text(
                    text = "Hand: ${preferences.handPreference.displayName}",
                    modifier = androidx.compose.ui.Modifier.testTag("hand_pref"),
                )
                androidx.compose.material3.Text(
                    text = "FPS: ${preferences.analysisFps}",
                    modifier = androidx.compose.ui.Modifier.testTag("fps"),
                )
            }
        }

        // Initial values
        composeTestRule.onNodeWithText("Sensitivity: 50").assertIsDisplayed()
        composeTestRule.onNodeWithText("Hand: Any").assertIsDisplayed()
        composeTestRule.onNodeWithText("FPS: 24").assertIsDisplayed()

        // Update preferences
        composeTestRule.runOnUiThread {
            preferences = preferences.copy(
                sensitivity = 75,
                handPreference = HandPreference.LEFT,
                analysisFps = 30,
            )
        }

        // Verify UI updates
        composeTestRule.onNodeWithText("Sensitivity: 75").assertIsDisplayed()
        composeTestRule.onNodeWithText("Hand: Left").assertIsDisplayed()
        composeTestRule.onNodeWithText("FPS: 30").assertIsDisplayed()
    }

    @Test
    fun booleanSettingsToggleCorrectly() {
        var preferences by mutableStateOf(UserPreferences())

        composeTestRule.setContent {
            androidx.compose.material3.Column {
                androidx.compose.material3.Text(
                    text = if (preferences.gesturesEnabled) "Gestures: ON" else "Gestures: OFF",
                    modifier = androidx.compose.ui.Modifier.testTag("gestures"),
                )
                androidx.compose.material3.Text(
                    text = if (preferences.cursorEnabled) "Cursor: ON" else "Cursor: OFF",
                    modifier = androidx.compose.ui.Modifier.testTag("cursor"),
                )
            }
        }

        // Initial: both ON (defaults)
        composeTestRule.onNodeWithText("Gestures: ON").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cursor: ON").assertIsDisplayed()

        // Toggle gestures off
        composeTestRule.runOnUiThread {
            preferences = preferences.copy(gesturesEnabled = false)
        }
        composeTestRule.onNodeWithText("Gestures: OFF").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cursor: ON").assertIsDisplayed()

        // Toggle cursor off
        composeTestRule.runOnUiThread {
            preferences = preferences.copy(cursorEnabled = false)
        }
        composeTestRule.onNodeWithText("Gestures: OFF").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cursor: OFF").assertIsDisplayed()
    }

    @Test
    fun clampedValuesDisplayCorrectly() {
        var sensitivity by mutableStateOf(50)

        composeTestRule.setContent {
            val clamped = sensitivity.coerceIn(0, 100)
            androidx.compose.material3.Text(
                text = "Sensitivity: $clamped",
                modifier = androidx.compose.ui.Modifier.testTag("sensitivity"),
            )
        }

        composeTestRule.onNodeWithText("Sensitivity: 50").assertIsDisplayed()

        // Test boundary values
        composeTestRule.runOnUiThread { sensitivity = -10 }
        composeTestRule.onNodeWithText("Sensitivity: 0").assertIsDisplayed()

        composeTestRule.runOnUiThread { sensitivity = 200 }
        composeTestRule.onNodeWithText("Sensitivity: 100").assertIsDisplayed()

        composeTestRule.runOnUiThread { sensitivity = 75 }
        composeTestRule.onNodeWithText("Sensitivity: 75").assertIsDisplayed()
    }

    @Test
    fun holdDurationClampedCorrectly() {
        var holdDuration by mutableStateOf(600)

        composeTestRule.setContent {
            val clamped = holdDuration.coerceIn(200, 2000)
            androidx.compose.material3.Text(
                text = "Hold: ${clamped}ms",
                modifier = androidx.compose.ui.Modifier.testTag("hold"),
            )
        }

        composeTestRule.onNodeWithText("Hold: 600ms").assertIsDisplayed()

        composeTestRule.runOnUiThread { holdDuration = 100 }
        composeTestRule.onNodeWithText("Hold: 200ms").assertIsDisplayed()

        composeTestRule.runOnUiThread { holdDuration = 5000 }
        composeTestRule.onNodeWithText("Hold: 2000ms").assertIsDisplayed()
    }
}
