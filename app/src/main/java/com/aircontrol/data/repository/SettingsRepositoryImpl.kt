package com.aircontrol.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.aircontrol.accessibility.GestureAction
import com.aircontrol.data.model.CustomGesture
import com.aircontrol.data.model.CustomGestureDirection
import com.aircontrol.data.model.CustomGesturePose
import com.aircontrol.data.model.CustomGestureTrigger
import com.aircontrol.data.model.FingerType
import com.aircontrol.data.model.GestureMapConfig
import com.aircontrol.data.model.GestureMapEntry
import com.aircontrol.data.model.HandPreference
import com.aircontrol.data.model.UserPreferences
import com.aircontrol.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.annotation.VisibleForTesting
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private object PreferencesKeys {
    val GESTURES_ENABLED = booleanPreferencesKey("gestures_enabled")
    val SENSITIVITY = intPreferencesKey("sensitivity")
    val HAND_PREFERENCE = stringPreferencesKey("hand_preference")
    val ANALYSIS_FPS = intPreferencesKey("analysis_fps")
    val CURSOR_ENABLED = booleanPreferencesKey("cursor_enabled")
    val HAPTIC_FEEDBACK = booleanPreferencesKey("haptic_feedback")
    val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
    val CURSOR_SPEED = intPreferencesKey("cursor_speed")
    val HOLD_DURATION = intPreferencesKey("hold_duration")
    val BATTERY_SAVER = booleanPreferencesKey("battery_saver")
    val START_ON_BOOT = booleanPreferencesKey("start_on_boot")
    val STATUS_PILL_ENABLED = booleanPreferencesKey("status_pill_enabled")
    val GESTURE_MAP_JSON = stringPreferencesKey("gesture_map_json")
    val GESTURE_MAP_VERSION = intPreferencesKey("gesture_map_version")
    val CALIBRATED_HAND_SIZE_MM = floatPreferencesKey("calibrated_hand_size_mm")
    val CALIBRATED_PINCH_DISTANCE_MM = floatPreferencesKey("calibrated_pinch_distance_mm")
    val IS_CALIBRATED = booleanPreferencesKey("is_calibrated")
    val CUSTOM_GESTURES_JSON = stringPreferencesKey("custom_gestures_json")
}

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    // Bug #22 Fix: Application-scoped CoroutineScope for background DataStore
    // write-back operations (e.g., persisting gesture-map migrations). Previously
    // this used GlobalScope.launch, which is an unstructured, leak-prone pattern —
    // GlobalScope coroutines have no parent job and can outlive the application's
    // meaningful lifecycle, making them impossible to cancel or test reliably.
    // The injected scope is provided by AppModule with a SupervisorJob +
    // Dispatchers.Default, so it's properly structured and cancellable.
    @ApplicationScope private val applicationScope: CoroutineScope,
) : SettingsRepository {

    override val userPreferences: Flow<UserPreferences> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Timber.e(exception, "DataStore corruption detected - resetting to defaults")
                // TODO: Emit a corruption event to notify UI
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            mapPreferences(preferences)
        }

    override val gestureMapConfig: Flow<GestureMapConfig> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Timber.e(exception, "DataStore corruption detected - resetting gesture map to defaults")
                // TODO: Emit a corruption event to notify UI
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            mapGestureMapConfig(preferences)
        }

    override suspend fun updateGesturesEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.GESTURES_ENABLED] = enabled
        }
        Timber.d("Updated gesturesEnabled: %s", enabled)
    }

    override suspend fun updateSensitivity(sensitivity: Int) {
        val clamped = sensitivity.coerceIn(0, 100)
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.SENSITIVITY] = clamped
        }
        Timber.d("Updated sensitivity: %d", clamped)
    }

    override suspend fun updateHandPreference(preference: HandPreference) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.HAND_PREFERENCE] = preference.name
        }
        Timber.d("Updated handPreference: %s", preference.name)
    }

    override suspend fun updateAnalysisFps(fps: Int) {
        val validFps = if (fps in VALID_FPS_SET) fps else DEFAULT_FPS
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.ANALYSIS_FPS] = validFps
        }
        Timber.d("Updated analysisFps: %d", validFps)
    }

    override suspend fun updateCursorEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.CURSOR_ENABLED] = enabled
        }
        Timber.d("Updated cursorEnabled: %s", enabled)
    }

    override suspend fun updateHapticFeedback(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.HAPTIC_FEEDBACK] = enabled
        }
        Timber.d("Updated hapticFeedback: %s", enabled)
    }

    override suspend fun updateOnboardingCompleted(completed: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.ONBOARDING_COMPLETED] = completed
        }
        Timber.d("Updated onboardingCompleted: %s", completed)
    }

    override suspend fun updateCursorSpeed(speed: Int) {
        val clamped = speed.coerceIn(1, 100)
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.CURSOR_SPEED] = clamped
        }
        Timber.d("Updated cursorSpeed: %d", clamped)
    }

    override suspend fun updateHoldDuration(durationMs: Int) {
        val clamped = durationMs.coerceIn(200, 2000)
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.HOLD_DURATION] = clamped
        }
        Timber.d("Updated holdDuration: %d", clamped)
    }

    override suspend fun updateBatterySaver(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.BATTERY_SAVER] = enabled
        }
        Timber.d("Updated batterySaver: %s", enabled)
    }

    override suspend fun updateStartOnBoot(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.START_ON_BOOT] = enabled
        }
        Timber.d("Updated startOnBoot: %s", enabled)
    }

    override suspend fun updateStatusPillEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.STATUS_PILL_ENABLED] = enabled
        }
        Timber.d("Updated statusPillEnabled: %s", enabled)
    }

    override suspend fun updateCalibrationData(handSizeMm: Float, pinchDistanceMm: Float) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.CALIBRATED_HAND_SIZE_MM] = handSizeMm
            preferences[PreferencesKeys.CALIBRATED_PINCH_DISTANCE_MM] = pinchDistanceMm
            preferences[PreferencesKeys.IS_CALIBRATED] = true
        }
        Timber.d("Updated calibration data: handSize=%.1fmm, pinchDist=%.1fmm", handSizeMm, pinchDistanceMm)
    }

    override suspend fun updateGestureAction(key: String, action: String) {
        val parsedAction = runCatching { GestureAction.valueOf(action) }
            .getOrElse { exception ->
                Timber.w(exception, "Invalid gesture action '%s'; storing NONE", action)
                GestureAction.NONE
            }

        dataStore.edit { preferences ->
            val currentConfig = mapGestureMapConfig(preferences)
            val updatedEntries = currentConfig.entries.map { entry ->
                if (entry.key == key) {
                    entry.copy(action = parsedAction)
                } else {
                    entry
                }
            }
            val updatedConfig = currentConfig.copy(entries = updatedEntries)
            preferences[PreferencesKeys.GESTURE_MAP_JSON] = serializeGestureMap(updatedConfig)
            preferences[PreferencesKeys.GESTURE_MAP_VERSION] = updatedConfig.schemaVersion
        }
        Timber.d("Updated gesture action: %s → %s", key, parsedAction)
    }

    override suspend fun resetGestureMapToDefaults() {
        val defaults = GestureMapConfig()
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.GESTURE_MAP_JSON] = serializeGestureMap(defaults)
            preferences[PreferencesKeys.GESTURE_MAP_VERSION] = defaults.schemaVersion
        }
        Timber.d("Reset gesture map to defaults")
    }

    // ========== Custom gestures ==========

    override val customGestures: Flow<List<CustomGesture>> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Timber.e(exception, "DataStore corruption detected - resetting custom gestures to defaults")
                // TODO: Emit a corruption event to notify UI
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            val json = preferences[PreferencesKeys.CUSTOM_GESTURES_JSON]
            if (json != null) deserializeCustomGestures(json) else emptyList()
        }

    override suspend fun addCustomGesture(gesture: CustomGesture) {
        dataStore.edit { preferences ->
            val current = (preferences[PreferencesKeys.CUSTOM_GESTURES_JSON]
                ?.let { deserializeCustomGestures(it) } ?: emptyList()).toMutableList()
            if (current.none { it.id == gesture.id }) {
                current.add(gesture)
            } else {
                Timber.w("Duplicate gesture ID, replacing: %s", gesture.id)
                val index = current.indexOfFirst { it.id == gesture.id }
                if (index >= 0) current[index] = gesture else current.add(gesture)
            }
            preferences[PreferencesKeys.CUSTOM_GESTURES_JSON] = serializeCustomGestures(current)
        }
        Timber.d("Added custom gesture: %s", gesture.name)
    }

    override suspend fun updateCustomGesture(gesture: CustomGesture) {
        dataStore.edit { preferences ->
            val current = (preferences[PreferencesKeys.CUSTOM_GESTURES_JSON]
                ?.let { deserializeCustomGestures(it) } ?: emptyList()).toMutableList()
            val index = current.indexOfFirst { it.id == gesture.id }
            if (index >= 0) {
                current[index] = gesture
                preferences[PreferencesKeys.CUSTOM_GESTURES_JSON] = serializeCustomGestures(current)
            } else {
                Timber.w("Custom gesture not found for update: %s", gesture.id)
            }
        }
        Timber.d("Updated custom gesture: %s", gesture.name)
    }

    override suspend fun deleteCustomGesture(gestureId: String) {
        dataStore.edit { preferences ->
            val current = (preferences[PreferencesKeys.CUSTOM_GESTURES_JSON]
                ?.let { deserializeCustomGestures(it) } ?: emptyList()).toMutableList()
            current.removeAll { it.id == gestureId }
            preferences[PreferencesKeys.CUSTOM_GESTURES_JSON] = serializeCustomGestures(current)
        }
        Timber.d("Deleted custom gesture: %s", gestureId)
    }

    override suspend fun enableCustomGesture(gestureId: String, enabled: Boolean) {
        dataStore.edit { preferences ->
            val current = (preferences[PreferencesKeys.CUSTOM_GESTURES_JSON]
                ?.let { deserializeCustomGestures(it) } ?: emptyList()).toMutableList()
            val index = current.indexOfFirst { it.id == gestureId }
            if (index >= 0) {
                current[index] = current[index].copy(isEnabled = enabled)
                preferences[PreferencesKeys.CUSTOM_GESTURES_JSON] = serializeCustomGestures(current)
            } else {
                Timber.w("Custom gesture not found for enable/disable: %s", gestureId)
            }
        }
        Timber.d("Custom gesture %s: enabled=%s", gestureId, enabled)
    }

    private fun mapPreferences(preferences: Preferences): UserPreferences = UserPreferences(
        gesturesEnabled = preferences[PreferencesKeys.GESTURES_ENABLED] ?: true,
        sensitivity = preferences[PreferencesKeys.SENSITIVITY] ?: 50,
        handPreference = preferences[PreferencesKeys.HAND_PREFERENCE]
            ?.let { stored -> runCatching { HandPreference.valueOf(stored) }.getOrNull() }
            ?: HandPreference.ANY,
        analysisFps = preferences[PreferencesKeys.ANALYSIS_FPS] ?: DEFAULT_FPS,
        cursorEnabled = preferences[PreferencesKeys.CURSOR_ENABLED] ?: true,
        hapticFeedback = preferences[PreferencesKeys.HAPTIC_FEEDBACK] ?: true,
        onboardingCompleted = preferences[PreferencesKeys.ONBOARDING_COMPLETED] ?: false,
        cursorSpeed = preferences[PreferencesKeys.CURSOR_SPEED] ?: 50,
        holdDuration = preferences[PreferencesKeys.HOLD_DURATION] ?: 600,
        batterySaver = preferences[PreferencesKeys.BATTERY_SAVER] ?: false,
        startOnBoot = preferences[PreferencesKeys.START_ON_BOOT] ?: false,
        statusPillEnabled = preferences[PreferencesKeys.STATUS_PILL_ENABLED] ?: true,
        calibratedHandSizeMm = preferences[PreferencesKeys.CALIBRATED_HAND_SIZE_MM] ?: 0f,
        calibratedPinchDistanceMm = preferences[PreferencesKeys.CALIBRATED_PINCH_DISTANCE_MM] ?: 0f,
        isCalibrated = preferences[PreferencesKeys.IS_CALIBRATED] ?: false,
    )

    private fun mapGestureMapConfig(preferences: Preferences): GestureMapConfig {
        val json = preferences[PreferencesKeys.GESTURE_MAP_JSON]
        val version = preferences[PreferencesKeys.GESTURE_MAP_VERSION] ?: 1

        return if (json != null) {
            val config = deserializeGestureMap(json, version)
            val migrated = GestureMapConfig.migrate(config)
            // Persist the migration result if it differs from the input
            if (migrated != config) {
                // Bug #22 Fix: Use the injected applicationScope instead of
                // GlobalScope. We can't call dataStore.edit here (not a suspend
                // function and we're inside a Flow map), so we launch it
                // asynchronously on the application-scoped coroutine. This is
                // properly structured concurrency — the scope is owned by Hilt's
                // SingletonComponent and cancelled when the application is
                // destroyed, preventing the memory leaks and untestable behavior
                // that GlobalScope caused.
                applicationScope.launch {
                    dataStore.edit { prefs ->
                        prefs[PreferencesKeys.GESTURE_MAP_JSON] = serializeGestureMap(migrated)
                        prefs[PreferencesKeys.GESTURE_MAP_VERSION] = migrated.schemaVersion
                    }
                    Timber.i("Persisted gesture map migration to schema version %d", migrated.schemaVersion)
                }
            }
            migrated
        } else {
            GestureMapConfig()
        }
    }

    /**
     * Serializes the gesture map to JSON format.
     * Robust against labels containing special characters (|, ;).
     */
    @VisibleForTesting
    internal fun serializeGestureMap(config: GestureMapConfig): String {
        val jsonArray = JSONArray()
        for (entry in config.entries) {
            val jsonObj = JSONObject().apply {
                put("key", entry.key)
                put("label", entry.label)
                put("action", entry.action.name)
            }
            jsonArray.put(jsonObj)
        }
        return jsonArray.toString()
    }

    /**
     * Deserializes the gesture map from JSON format.
     * Falls back to legacy pipe-delimited format for backward compatibility.
     */
    @VisibleForTesting
    internal fun deserializeGestureMap(json: String, version: Int): GestureMapConfig {
        // Try JSON format first
        val entries = try {
            val jsonArray = JSONArray(json)
            (0 until jsonArray.length()).mapNotNull { i ->
                try {
                    val jsonObj = jsonArray.getJSONObject(i)
                    GestureMapEntry(
                        key = jsonObj.getString("key"),
                        label = jsonObj.getString("label"),
                        action = GestureAction.valueOf(jsonObj.getString("action")),
                    )
                } catch (e: Exception) {
                    Timber.w(e, "Failed to parse gesture map entry at index %d", i)
                    null
                }
            }
        } catch (e: Exception) {
            // Fallback: try legacy pipe-delimited format for backward compatibility
            Timber.w(e, "JSON parse failed, trying legacy format")
            deserializeGestureMapLegacy(json)
        }

        return GestureMapConfig(
            schemaVersion = version,
            entries = entries,
        )
    }

    /**
     * Legacy deserializer for the old pipe-delimited format.
     * Kept for backward compatibility with existing user data.
     */
    private fun deserializeGestureMapLegacy(json: String): List<GestureMapEntry> {
        return json.split(";").mapNotNull { segment ->
            val parts = segment.split("|")
            if (parts.size == 3) {
                try {
                    GestureMapEntry(
                        key = parts[0],
                        label = parts[1],
                        action = GestureAction.valueOf(parts[2]),
                    )
                } catch (e: Exception) {
                    Timber.w(e, "Failed to parse legacy gesture map entry: %s", segment)
                    null
                }
            } else null
        }
    }

    /**
     * Serializes custom gestures to JSON.
     */
    @VisibleForTesting
    internal fun serializeCustomGestures(gestures: List<CustomGesture>): String {
        val jsonArray = JSONArray()
        for (gesture in gestures) {
            val jsonObj = JSONObject().apply {
                put("id", gesture.id)
                put("name", gesture.name)
                put("description", gesture.description)
                put("action", gesture.action.name)
                put("isEnabled", gesture.isEnabled)
                put("createdAtMs", gesture.createdAtMs)

                // Serialize trigger
                val triggerObj = when (gesture.triggerPose) {
                    is CustomGestureTrigger.PoseWithDirection -> {
                        JSONObject().apply {
                            put("type", "pose_with_direction")
                            put("pose", gesture.triggerPose.pose.name)
                            put("direction", gesture.triggerPose.direction.name)
                        }
                    }
                    is CustomGestureTrigger.FingerCount -> {
                        JSONObject().apply {
                            put("type", "finger_count")
                            put("extendedFingers", gesture.triggerPose.extendedFingers)
                            put("whichFingers", JSONArray().apply {
                                gesture.triggerPose.whichFingers.forEach { put(it.name) }
                            })
                        }
                    }
                    is CustomGestureTrigger.LandmarkTemplateTrigger -> {
                        // Bug: Custom Gestures Not Triggering Fix — Serialize the
                        // landmark template (gestureId, name, normalizedDistances).
                        JSONObject().apply {
                            put("type", "landmark_template")
                            put("templateGestureId", gesture.triggerPose.template.gestureId)
                            put("templateName", gesture.triggerPose.template.name)
                            put("templateDistances", JSONArray().apply {
                                gesture.triggerPose.template.normalizedDistances.forEach { put(it) }
                            })
                        }
                    }
                }
                put("trigger", triggerObj)
            }
            jsonArray.put(jsonObj)
        }
        return jsonArray.toString()
    }

    /**
     * Deserializes custom gestures from JSON.
     */
    @VisibleForTesting
    internal fun deserializeCustomGestures(json: String): List<CustomGesture> {
        return try {
            val jsonArray = JSONArray(json)
            (0 until jsonArray.length()).mapNotNull { i ->
                try {
                    val jsonObj = jsonArray.getJSONObject(i)
                    val triggerObj = jsonObj.getJSONObject("trigger")
                    val trigger = when (triggerObj.getString("type")) {
                        "pose_with_direction" -> CustomGestureTrigger.PoseWithDirection(
                            pose = CustomGesturePose.valueOf(triggerObj.getString("pose")),
                            direction = CustomGestureDirection.valueOf(triggerObj.getString("direction")),
                        )
                        "finger_count" -> {
                            val fingersArray = triggerObj.getJSONArray("whichFingers")
                            val fingers = (0 until fingersArray.length()).mapTo(mutableSetOf()) {
                                FingerType.valueOf(fingersArray.getString(it))
                            }
                            CustomGestureTrigger.FingerCount(
                                extendedFingers = triggerObj.getInt("extendedFingers"),
                                whichFingers = fingers,
                            )
                        }
                        "landmark_template" -> {
                            // Bug: Custom Gestures Not Triggering Fix — Deserialize
                            // the landmark template.
                            val templateGestureId = triggerObj.getString("templateGestureId")
                            val templateName = triggerObj.getString("templateName")
                            val distancesArray = triggerObj.getJSONArray("templateDistances")
                            val distances = (0 until distancesArray.length()).map { distancesArray.getDouble(it).toFloat() }
                            CustomGestureTrigger.LandmarkTemplateTrigger(
                                template = com.aircontrol.gesture.model.LandmarkTemplate(
                                    gestureId = templateGestureId,
                                    name = templateName,
                                    normalizedDistances = distances,
                                ),
                            )
                        }
                        else -> null
                    } ?: return@mapNotNull null

                    CustomGesture(
                        id = jsonObj.getString("id"),
                        name = jsonObj.getString("name"),
                        description = jsonObj.getString("description"),
                        triggerPose = trigger,
                        action = GestureAction.valueOf(jsonObj.getString("action")),
                        isEnabled = jsonObj.optBoolean("isEnabled", true),
                        createdAtMs = jsonObj.optLong("createdAtMs", System.currentTimeMillis()),
                    )
                } catch (e: Exception) {
                    Timber.w(e, "Failed to parse custom gesture at index %d", i)
                    null
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to deserialize custom gestures")
            emptyList()
        }
    }

    companion object {
        private val VALID_FPS_SET = setOf(15, 24, 30)
        private const val DEFAULT_FPS = 24
    }
}
