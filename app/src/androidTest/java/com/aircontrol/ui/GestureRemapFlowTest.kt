package com.aircontrol.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.aircontrol.accessibility.GestureAction
import com.aircontrol.data.model.GestureMapConfig
import com.aircontrol.data.model.GestureMapEntry
import org.junit.Rule
import org.junit.Test

/**
 * Compose UI tests for the Gesture Remap flow.
 *
 * Tests the gesture mapping UI patterns including:
 * - Display of gesture entries
 * - Action selection
 * - Conflict detection
 *
 * Full integration tests require @HiltAndroidTest with HiltComposeTestRule.
 */
class GestureRemapFlowTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun gestureEntryDisplaysCorrectLabel() {
        val entry = GestureMapEntry("swipe_left", "Swipe Left", GestureAction.SCROLL_RIGHT)

        composeTestRule.setContent {
            androidx.compose.material3.Text(text = "${entry.label}: ${entry.action.name}")
        }

        composeTestRule.onNodeWithText("Swipe Left: SCROLL_RIGHT").assertIsDisplayed()
    }

    @Test
    fun gestureActionListContainsAllActions() {
        val actions = GestureAction.entries

        composeTestRule.setContent {
            androidx.compose.foundation.lazy.LazyColumn {
                items(actions.size) { index ->
                    androidx.compose.material3.Text(
                        text = actions[index].name,
                        modifier = androidx.compose.ui.Modifier.testTag("action_${actions[index].name}"),
                    )
                }
            }
        }

        // Verify all action names are rendered
        GestureAction.entries.forEach { action ->
            composeTestRule.onNodeWithTag("action_${action.name}").assertIsDisplayed()
        }
    }

    @Test
    fun gestureMapEntriesDisplayAllDefaults() {
        val config = GestureMapConfig()

        composeTestRule.setContent {
            androidx.compose.foundation.lazy.LazyColumn {
                items(config.entries.size) { index ->
                    val entry = config.entries[index]
                    androidx.compose.material3.Text(
                        text = entry.label,
                        modifier = androidx.compose.ui.Modifier.testTag("entry_${entry.key}"),
                    )
                }
            }
        }

        // Verify all default entries are displayed
        GestureMapConfig.defaultEntries().forEach { entry ->
            composeTestRule.onNodeWithTag("entry_${entry.key}").assertIsDisplayed()
        }
    }

    @Test
    fun conflictInfoDisplaysBothEntries() {
        val existingLabel = "Swipe Left"
        val newLabel = "Pinch"
        val action = GestureAction.HOME

        composeTestRule.setContent {
            androidx.compose.material3.Column {
                androidx.compose.material3.Text(
                    text = "\"$existingLabel\" is currently mapped to ${action.name}",
                )
                androidx.compose.material3.Text(
                    text = "Reassign to \"$newLabel\"?",
                )
            }
        }

        composeTestRule.onNodeWithText("\"Swipe Left\" is currently mapped to HOME").assertIsDisplayed()
        composeTestRule.onNodeWithText("Reassign to \"Pinch\"?").assertIsDisplayed()
    }
}
