package com.aircontrol.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import app.cash.turbine.test
import com.aircontrol.accessibility.GestureAction
import com.aircontrol.data.model.GestureMapConfig
import com.aircontrol.data.model.GestureMapEntry
import com.aircontrol.data.model.HandPreference
import com.aircontrol.data.model.UserPreferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SettingsRepositoryImplTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var testDataStore: DataStore<Preferences>
    private lateinit var repository: SettingsRepositoryImpl
    private val testScope = TestScope(UnconfinedTestDispatcher())

    @Before
    fun setup() {
        val dataStoreFile = File(tempFolder.root, "test_preferences.preferences_pb")
        testDataStore = PreferenceDataStoreFactory.createWithPath(
            produceFile = { dataStoreFile.toPath() },
        )
        repository = SettingsRepositoryImpl(testDataStore)
    }

    // ========== Serialization Tests (JSON format) ==========

    @Test
    fun `serializeGestureMap produces JSON array format`() {
        val config = GestureMapConfig(
            entries = listOf(
                GestureMapEntry("swipe_left", "Swipe Left", GestureAction.SCROLL_LEFT),
                GestureMapEntry("pose_pinch", "Pinch", GestureAction.TAP),
            ),
        )
        val serialized = repository.serializeGestureMap(config)

        // Should be valid JSON array
        assertTrue("Should start with [", serialized.startsWith("["))
        assertTrue("Should end with ]", serialized.endsWith("]"))
        assertTrue("Should contain swipe_left", serialized.contains("swipe_left"))
        assertTrue("Should contain SCROLL_LEFT", serialized.contains("SCROLL_LEFT"))
        assertTrue("Should contain pose_pinch", serialized.contains("pose_pinch"))
        assertTrue("Should contain TAP", serialized.contains("TAP"))
    }

    @Test
    fun `serializeGestureMap with single entry produces valid JSON`() {
        val config = GestureMapConfig(
            entries = listOf(
                GestureMapEntry("swipe_left", "Swipe Left", GestureAction.BACK),
            ),
        )
        val serialized = repository.serializeGestureMap(config)
        assertTrue("Should contain swipe_left", serialized.contains("swipe_left"))
        assertTrue("Should contain BACK", serialized.contains("BACK"))
    }

    @Test
    fun `serializeGestureMap with empty entries returns empty JSON array`() {
        val config = GestureMapConfig(entries = emptyList())
        val serialized = repository.serializeGestureMap(config)
        assertEquals("[]", serialized)
    }

    @Test
    fun `serializeGestureMap with all default entries produces valid JSON`() {
        val config = GestureMapConfig()
        val serialized = repository.serializeGestureMap(config)

        // Should be a valid JSON array with all entries
        val jsonArray = org.json.JSONArray(serialized)
        assertEquals(GestureMapConfig.defaultEntries().size, jsonArray.length())
    }

    // ========== Deserialization Tests ==========

    @Test
    fun `deserializeGestureMap correctly parses JSON format`() {
        val json = """[{"key":"swipe_left","label":"Swipe Left","action":"SCROLL_LEFT"},{"key":"pose_pinch","label":"Pinch","action":"TAP"}]"""
        val config = repository.deserializeGestureMap(json, 3)

        assertEquals(3, config.schemaVersion)
        assertEquals(2, config.entries.size)
        assertEquals("swipe_left", config.entries[0].key)
        assertEquals("Swipe Left", config.entries[0].label)
        assertEquals(GestureAction.SCROLL_LEFT, config.entries[0].action)
        assertEquals("pose_pinch", config.entries[1].key)
        assertEquals("Pinch", config.entries[1].label)
        assertEquals(GestureAction.TAP, config.entries[1].action)
    }

    @Test
    fun `deserializeGestureMap handles empty JSON array`() {
        val json = "[]"
        val config = repository.deserializeGestureMap(json, 1)
        assertEquals(1, config.schemaVersion)
        assertTrue("Empty array should produce empty entries", config.entries.isEmpty())
    }

    @Test
    fun `deserializeGestureMap handles invalid entry gracefully`() {
        val json = """[{"key":"swipe_left","label":"Swipe Left","action":"INVALID_ACTION"},{"key":"pose_pinch","label":"Pinch","action":"TAP"}]"""
        val config = repository.deserializeGestureMap(json, 1)

        // Invalid action should be skipped, valid one kept
        assertEquals(1, config.entries.size)
        assertEquals("pose_pinch", config.entries[0].key)
    }

    @Test
    fun `deserializeGestureMap falls back to legacy pipe format`() {
        val json = "swipe_left|Swipe Left|SCROLL_LEFT;pose_pinch|Pinch|TAP"
        val config = repository.deserializeGestureMap(json, 2)

        assertEquals(2, config.entries.size)
        assertEquals("swipe_left", config.entries[0].key)
        assertEquals(GestureAction.SCROLL_LEFT, config.entries[0].action)
    }

    @Test
    fun `deserializeGestureMap preserves version`() {
        val json = """[{"key":"swipe_left","label":"Swipe Left","action":"SCROLL_LEFT"}]"""
        val config = repository.deserializeGestureMap(json, 1)
        assertEquals(1, config.schemaVersion)
    }

    @Test
    fun `deserializeGestureMap with single JSON entry`() {
        val json = """[{"key":"pose_pinch","label":"Pinch","action":"TAP"}]"""
        val config = repository.deserializeGestureMap(json, 2)
        assertEquals(1, config.entries.size)
        assertEquals("pose_pinch", config.entries[0].key)
        assertEquals(GestureAction.TAP, config.entries[0].action)
    }

    @Test
    fun `deserializeGestureMap with all default entries serialized string`() {
        val config = GestureMapConfig()
        val serialized = repository.serializeGestureMap(config)
        val deserialized = repository.deserializeGestureMap(serialized, config.schemaVersion)

        assertEquals(config.entries.size, deserialized.entries.size)
        config.entries.forEachIndexed { index, expected ->
            assertEquals(expected.key, deserialized.entries[index].key)
            assertEquals(expected.action, deserialized.entries[index].action)
        }
    }

    // ========== Round-trip: serialize then deserialize ==========

    @Test
    fun `round-trip serialization preserves all entries`() {
        val originalConfig = GestureMapConfig()
        val serialized = repository.serializeGestureMap(originalConfig)
        val deserialized = repository.deserializeGestureMap(serialized, originalConfig.schemaVersion)

        assertEquals(originalConfig.entries.size, deserialized.entries.size)
        originalConfig.entries.forEachIndexed { index, original ->
            val restored = deserialized.entries[index]
            assertEquals(original.key, restored.key)
            assertEquals(original.label, restored.label)
            assertEquals(original.action, restored.action)
        }
    }

    @Test
    fun `round-trip serialization with custom actions preserves actions`() {
        val customConfig = GestureMapConfig(
            schemaVersion = 3,
            entries = listOf(
                GestureMapEntry("swipe_left", "Swipe Left", GestureAction.HOME),
                GestureMapEntry("pose_pinch", "Pinch", GestureAction.BACK),
                GestureMapEntry("pose_victory", "Victory", GestureAction.SCREENSHOT),
            ),
        )
        val serialized = repository.serializeGestureMap(customConfig)
        val deserialized = repository.deserializeGestureMap(serialized, customConfig.schemaVersion)

        assertEquals(GestureAction.HOME, deserialized.entries[0].action)
        assertEquals(GestureAction.BACK, deserialized.entries[1].action)
        assertEquals(GestureAction.SCREENSHOT, deserialized.entries[2].action)
    }

    @Test
    fun `round-trip with all GestureAction values`() {
        // Verify every GestureAction can be serialized and deserialized
        GestureAction.entries.forEach { action ->
            val config = GestureMapConfig(
                entries = listOf(
                    GestureMapEntry("test_key", "Test Label", action),
                ),
            )
            val serialized = repository.serializeGestureMap(config)
            val deserialized = repository.deserializeGestureMap(serialized, config.schemaVersion)

            assertEquals(
                "Round-trip failed for action: $action",
                action,
                deserialized.entries[0].action,
            )
        }
    }

    // ========== DataStore Integration Tests ==========

    @Test
    fun `default user preferences are returned when DataStore is empty`() = testScope.runTest {
        val prefs = repository.userPreferences.first()
        assertEquals(UserPreferences(), prefs)
    }

    @Test
    fun `default gesture map config is returned when DataStore is empty`() = testScope.runTest {
        val config = repository.gestureMapConfig.first()
        assertEquals(GestureMapConfig.CURRENT_SCHEMA_VERSION, config.schemaVersion)
        assertEquals(GestureMapConfig.defaultEntries().size, config.entries.size)
    }

    @Test
    fun `updateGestureAction persists and reads back correctly`() = testScope.runTest {
        repository.updateGestureAction("swipe_left", GestureAction.HOME.name)
        val config = repository.gestureMapConfig.first()

        val swipeLeft = config.entries.find { it.key == "swipe_left" }
        assertEquals(GestureAction.HOME, swipeLeft?.action)
    }

    @Test
    fun `resetGestureMapToDefaults restores default actions`() = testScope.runTest {
        // First, change an action
        repository.updateGestureAction("swipe_left", GestureAction.HOME.name)

        // Verify the change
        val modified = repository.gestureMapConfig.first()
        assertEquals(GestureAction.HOME, modified.entries.find { it.key == "swipe_left" }?.action)

        // Reset
        repository.resetGestureMapToDefaults()

        // Verify defaults restored (now SCROLL_LEFT, not SCROLL_RIGHT)
        val reset = repository.gestureMapConfig.first()
        assertEquals(
            GestureAction.SCROLL_LEFT,
            reset.entries.find { it.key == "swipe_left" }?.action,
        )
    }

    @Test
    fun `updateGesturesEnabled persists value`() = testScope.runTest {
        repository.updateGesturesEnabled(false)
        val prefs = repository.userPreferences.first()
        assertEquals(false, prefs.gesturesEnabled)
    }

    @Test
    fun `updateSensitivity clamps to 0-100 range`() = testScope.runTest {
        repository.updateSensitivity(-10)
        assertEquals(0, repository.userPreferences.first().sensitivity)

        repository.updateSensitivity(200)
        assertEquals(100, repository.userPreferences.first().sensitivity)

        repository.updateSensitivity(75)
        assertEquals(75, repository.userPreferences.first().sensitivity)
    }

    @Test
    fun `updateHandPreference persists value`() = testScope.runTest {
        repository.updateHandPreference(HandPreference.LEFT)
        assertEquals(HandPreference.LEFT, repository.userPreferences.first().handPreference)

        repository.updateHandPreference(HandPreference.RIGHT)
        assertEquals(HandPreference.RIGHT, repository.userPreferences.first().handPreference)
    }

    @Test
    fun `updateAnalysisFps falls back to default for invalid values`() = testScope.runTest {
        repository.updateAnalysisFps(15)
        assertEquals(15, repository.userPreferences.first().analysisFps)

        repository.updateAnalysisFps(30)
        assertEquals(30, repository.userPreferences.first().analysisFps)

        // Invalid value should fall back to default (24)
        repository.updateAnalysisFps(60)
        assertEquals(24, repository.userPreferences.first().analysisFps)

        repository.updateAnalysisFps(0)
        assertEquals(24, repository.userPreferences.first().analysisFps)
    }

    @Test
    fun `updateCursorSpeed clamps to 1-100 range`() = testScope.runTest {
        repository.updateCursorSpeed(0)
        assertEquals(1, repository.userPreferences.first().cursorSpeed)

        repository.updateCursorSpeed(200)
        assertEquals(100, repository.userPreferences.first().cursorSpeed)

        repository.updateCursorSpeed(50)
        assertEquals(50, repository.userPreferences.first().cursorSpeed)
    }

    @Test
    fun `updateHoldDuration clamps to 200-2000 range`() = testScope.runTest {
        repository.updateHoldDuration(100)
        assertEquals(200, repository.userPreferences.first().holdDuration)

        repository.updateHoldDuration(5000)
        assertEquals(2000, repository.userPreferences.first().holdDuration)

        repository.updateHoldDuration(800)
        assertEquals(800, repository.userPreferences.first().holdDuration)
    }

    @Test
    fun `multiple boolean preferences persist independently`() = testScope.runTest {
        repository.updateGesturesEnabled(false)
        repository.updateCursorEnabled(false)
        repository.updateHapticFeedback(false)
        repository.updateBatterySaver(true)
        repository.updateStartOnBoot(true)

        val prefs = repository.userPreferences.first()
        assertEquals(false, prefs.gesturesEnabled)
        assertEquals(false, prefs.cursorEnabled)
        assertEquals(false, prefs.hapticFeedback)
        assertEquals(true, prefs.batterySaver)
        assertEquals(true, prefs.startOnBoot)
    }

    @Test
    fun `gesture map config flow emits updates when action changes`() = testScope.runTest {
        repository.gestureMapConfig.test {
            // Initial emission should be defaults (SCROLL_LEFT for swipe_left)
            val initial = awaitItem()
            assertEquals(GestureAction.SCROLL_LEFT, initial.entries.find { it.key == "swipe_left" }?.action)

            // Update should trigger new emission
            repository.updateGestureAction("swipe_left", GestureAction.HOME.name)

            val updated = awaitItem()
            assertEquals(GestureAction.HOME, updated.entries.find { it.key == "swipe_left" }?.action)
        }
    }
}
