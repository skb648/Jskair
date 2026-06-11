package com.aircontrol.data.model

import com.aircontrol.accessibility.GestureAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureMapConfigTest {

    // ========== defaultEntries() ==========

    @Test
    fun `defaultEntries returns all 9 expected gesture entries`() {
        val entries = GestureMapConfig.defaultEntries()
        assertEquals(9, entries.size)
    }

    @Test
    fun `defaultEntries contains all expected keys`() {
        val entries = GestureMapConfig.defaultEntries()
        val keys = entries.map { it.key }.toSet()
        val expectedKeys = setOf(
            "swipe_left",
            "swipe_right",
            "swipe_up",
            "swipe_down",
            "pose_pinch",
            "pose_pointing",
            "pose_victory",
            "pose_thumb_up",
            "pose_thumb_down",
        )
        assertEquals(expectedKeys, keys)
    }

    @Test
    fun `defaultEntries have non-empty labels`() {
        val entries = GestureMapConfig.defaultEntries()
        entries.forEach { entry ->
            assertTrue("Label for ${entry.key} should not be empty", entry.label.isNotEmpty())
        }
    }

    @Test
    fun `defaultEntries have expected default action mappings`() {
        val entries = GestureMapConfig.defaultEntries()
        val byKey = entries.associateBy { it.key }

        assertEquals(GestureAction.SCROLL_RIGHT, byKey["swipe_left"]?.action)
        assertEquals(GestureAction.SCROLL_LEFT, byKey["swipe_right"]?.action)
        assertEquals(GestureAction.SCROLL_DOWN, byKey["swipe_up"]?.action)
        assertEquals(GestureAction.SCROLL_UP, byKey["swipe_down"]?.action)
        assertEquals(GestureAction.TAP, byKey["pose_pinch"]?.action)
        assertEquals(GestureAction.NONE, byKey["pose_pointing"]?.action)
        assertEquals(GestureAction.MEDIA_PLAY_PAUSE, byKey["pose_victory"]?.action)
        assertEquals(GestureAction.VOLUME_UP, byKey["pose_thumb_up"]?.action)
        assertEquals(GestureAction.VOLUME_DOWN, byKey["pose_thumb_down"]?.action)
    }

    // ========== CURRENT_SCHEMA_VERSION ==========

    @Test
    fun `current schema version is 2`() {
        assertEquals(2, GestureMapConfig.CURRENT_SCHEMA_VERSION)
    }

    // ========== Default constructor ==========

    @Test
    fun `default constructor uses current schema version and default entries`() {
        val config = GestureMapConfig()
        assertEquals(GestureMapConfig.CURRENT_SCHEMA_VERSION, config.schemaVersion)
        assertEquals(GestureMapConfig.defaultEntries(), config.entries)
    }

    // ========== migrate() - same version returns same config ==========

    @Test
    fun `migrate with current version returns same config`() {
        val original = GestureMapConfig(
            schemaVersion = GestureMapConfig.CURRENT_SCHEMA_VERSION,
            entries = listOf(
                GestureMapEntry("swipe_left", "Swipe Left", GestureAction.BACK),
            ),
        )
        val migrated = GestureMapConfig.migrate(original)
        assertEquals(original, migrated)
    }

    @Test
    fun `migrate with newer version returns same config`() {
        val original = GestureMapConfig(
            schemaVersion = GestureMapConfig.CURRENT_SCHEMA_VERSION + 1,
            entries = listOf(
                GestureMapEntry("swipe_left", "Swipe Left", GestureAction.HOME),
            ),
        )
        val migrated = GestureMapConfig.migrate(original)
        assertEquals(original, migrated)
    }

    // ========== migrate() - adds new entries from defaults ==========

    @Test
    fun `migrate adds new entries that dont exist in old config`() {
        val oldConfig = GestureMapConfig(
            schemaVersion = 1,
            entries = listOf(
                GestureMapEntry("swipe_left", "Swipe Left", GestureAction.SCROLL_RIGHT),
                GestureMapEntry("swipe_right", "Swipe Right", GestureAction.SCROLL_LEFT),
            ),
        )
        val migrated = GestureMapConfig.migrate(oldConfig)

        // Should have all default entries now
        assertEquals(GestureMapConfig.defaultEntries().size, migrated.entries.size)
        val migratedKeys = migrated.entries.map { it.key }.toSet()
        val defaultKeys = GestureMapConfig.defaultEntries().map { it.key }.toSet()
        assertEquals(defaultKeys, migratedKeys)
    }

    @Test
    fun `migrate updates schema version to current`() {
        val oldConfig = GestureMapConfig(
            schemaVersion = 1,
            entries = listOf(
                GestureMapEntry("swipe_left", "Swipe Left", GestureAction.SCROLL_RIGHT),
            ),
        )
        val migrated = GestureMapConfig.migrate(oldConfig)
        assertEquals(GestureMapConfig.CURRENT_SCHEMA_VERSION, migrated.schemaVersion)
    }

    // ========== migrate() - preserves user-customized actions ==========

    @Test
    fun `migrate preserves user-customized actions for existing keys`() {
        val oldConfig = GestureMapConfig(
            schemaVersion = 1,
            entries = listOf(
                GestureMapEntry("swipe_left", "Swipe Left", GestureAction.BACK),
                GestureMapEntry("pose_pinch", "Pinch", GestureAction.HOME),
            ),
        )
        val migrated = GestureMapConfig.migrate(oldConfig)

        val swipeLeftEntry = migrated.entries.find { it.key == "swipe_left" }
        val pinchEntry = migrated.entries.find { it.key == "pose_pinch" }

        assertEquals(GestureAction.BACK, swipeLeftEntry?.action)
        assertEquals(GestureAction.HOME, pinchEntry?.action)
    }

    @Test
    fun `migrate updates labels from defaults while preserving actions`() {
        val oldConfig = GestureMapConfig(
            schemaVersion = 1,
            entries = listOf(
                GestureMapEntry("swipe_left", "Old Label", GestureAction.BACK),
            ),
        )
        val migrated = GestureMapConfig.migrate(oldConfig)

        val entry = migrated.entries.find { it.key == "swipe_left" }
        // Label should be updated from defaults
        assertEquals("Swipe Left", entry?.label)
        // But action should be preserved
        assertEquals(GestureAction.BACK, entry?.action)
    }

    // ========== migrate() - removes entries not in defaults ==========

    @Test
    fun `migrate removes entries that no longer exist in defaults`() {
        val oldConfig = GestureMapConfig(
            schemaVersion = 1,
            entries = listOf(
                GestureMapEntry("swipe_left", "Swipe Left", GestureAction.SCROLL_RIGHT),
                GestureMapEntry("deprecated_gesture", "Deprecated", GestureAction.TAP),
            ),
        )
        val migrated = GestureMapConfig.migrate(oldConfig)

        val deprecatedEntry = migrated.entries.find { it.key == "deprecated_gesture" }
        assertEquals(null, deprecatedEntry)
    }

    @Test
    fun `migrate produces only entries that exist in defaults`() {
        val oldConfig = GestureMapConfig(
            schemaVersion = 1,
            entries = listOf(
                GestureMapEntry("custom_entry", "Custom", GestureAction.NONE),
                GestureMapEntry("another_custom", "Another", GestureAction.TAP),
                GestureMapEntry("swipe_left", "Swipe Left", GestureAction.BACK),
            ),
        )
        val migrated = GestureMapConfig.migrate(oldConfig)

        val defaultKeys = GestureMapConfig.defaultEntries().map { it.key }.toSet()
        val migratedKeys = migrated.entries.map { it.key }.toSet()
        assertEquals(defaultKeys, migratedKeys)
    }

    // ========== migrate() - full round-trip with mixed changes ==========

    @Test
    fun `migrate handles combination of added, removed, and preserved entries`() {
        val oldConfig = GestureMapConfig(
            schemaVersion = 1,
            entries = listOf(
                // Preserved with custom action
                GestureMapEntry("swipe_left", "Swipe Left", GestureAction.HOME),
                // Preserved with default action
                GestureMapEntry("pose_pinch", "Pinch", GestureAction.TAP),
                // Removed (not in defaults)
                GestureMapEntry("old_gesture", "Old", GestureAction.NONE),
            ),
        )
        val migrated = GestureMapConfig.migrate(oldConfig)

        // Should have exactly the default set of keys
        assertEquals(GestureMapConfig.defaultEntries().size, migrated.entries.size)

        // Custom action preserved
        assertEquals(GestureAction.HOME, migrated.entries.find { it.key == "swipe_left" }?.action)
        // Default action preserved
        assertEquals(GestureAction.TAP, migrated.entries.find { it.key == "pose_pinch" }?.action)
        // New entries get default actions
        assertEquals(
            GestureAction.SCROLL_LEFT,
            migrated.entries.find { it.key == "swipe_right" }?.action,
        )
        // Removed entry is gone
        assertEquals(null, migrated.entries.find { it.key == "old_gesture" }?.action)
    }

    // ========== GestureMapEntry ==========

    @Test
    fun `GestureMapEntry data class equality works`() {
        val entry1 = GestureMapEntry("swipe_left", "Swipe Left", GestureAction.SCROLL_RIGHT)
        val entry2 = GestureMapEntry("swipe_left", "Swipe Left", GestureAction.SCROLL_RIGHT)
        val entry3 = GestureMapEntry("swipe_left", "Different Label", GestureAction.SCROLL_RIGHT)

        assertEquals(entry1, entry2)
        assertNotEquals(entry1, entry3)
    }

    @Test
    fun `GestureMapEntry copy preserves fields`() {
        val original = GestureMapEntry("pose_pinch", "Pinch", GestureAction.TAP)
        val modified = original.copy(action = GestureAction.HOME)
        assertEquals("pose_pinch", modified.key)
        assertEquals("Pinch", modified.label)
        assertEquals(GestureAction.HOME, modified.action)
    }

    // ========== GestureAction enum ==========

    @Test
    fun `GestureAction enum has all expected values`() {
        val expectedActions = setOf(
            "NONE", "SCROLL_UP", "SCROLL_DOWN", "SCROLL_LEFT", "SCROLL_RIGHT",
            "BACK", "HOME", "RECENTS", "NOTIFICATIONS", "QUICK_SETTINGS",
            "VOLUME_UP", "VOLUME_DOWN", "MEDIA_PLAY_PAUSE", "SCREENSHOT",
            "LOCK_SCREEN", "TAP", "LONG_PRESS", "DRAG",
        )
        val actualActions = GestureAction.entries.map { it.name }.toSet()
        assertEquals(expectedActions, actualActions)
    }

    @Test
    fun `GestureAction valueOf returns correct enum for all values`() {
        GestureAction.entries.forEach { action ->
            assertEquals(action, GestureAction.valueOf(action.name))
        }
    }

    // ========== HandPreference enum ==========

    @Test
    fun `HandPreference enum has expected values`() {
        val expected = setOf("LEFT", "RIGHT", "ANY")
        val actual = HandPreference.entries.map { it.name }.toSet()
        assertEquals(expected, actual)
    }

    @Test
    fun `HandPreference has correct display names`() {
        assertEquals("Left", HandPreference.LEFT.displayName)
        assertEquals("Right", HandPreference.RIGHT.displayName)
        assertEquals("Any", HandPreference.ANY.displayName)
    }
}
