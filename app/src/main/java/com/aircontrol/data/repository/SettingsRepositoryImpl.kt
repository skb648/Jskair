package com.aircontrol.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.aircontrol.accessibility.GestureAction
import com.aircontrol.data.model.GestureMapConfig
import com.aircontrol.data.model.GestureMapEntry
import com.aircontrol.data.model.HandPreference
import com.aircontrol.data.model.UserPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import androidx.annotation.VisibleForTesting
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
}

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : SettingsRepository {

    override val userPreferences: Flow<UserPreferences> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Timber.e(exception, "Error reading preferences")
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
                Timber.e(exception, "Error reading gesture map config")
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

    override suspend fun updateGestureAction(key: String, action: String) {
        dataStore.edit { preferences ->
            // Store individual action overrides as key-action pairs in the JSON
            val currentConfig = mapGestureMapConfig(preferences)
            val updatedEntries = currentConfig.entries.map { entry ->
                if (entry.key == key) {
                    entry.copy(action = GestureAction.valueOf(action))
                } else {
                    entry
                }
            }
            val updatedConfig = currentConfig.copy(entries = updatedEntries)
            preferences[PreferencesKeys.GESTURE_MAP_JSON] = serializeGestureMap(updatedConfig)
            preferences[PreferencesKeys.GESTURE_MAP_VERSION] = updatedConfig.schemaVersion
        }
        Timber.d("Updated gesture action: %s → %s", key, action)
    }

    override suspend fun resetGestureMapToDefaults() {
        val defaults = GestureMapConfig()
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.GESTURE_MAP_JSON] = serializeGestureMap(defaults)
            preferences[PreferencesKeys.GESTURE_MAP_VERSION] = defaults.schemaVersion
        }
        Timber.d("Reset gesture map to defaults")
    }

    private fun mapPreferences(preferences: Preferences): UserPreferences = UserPreferences(
        gesturesEnabled = preferences[PreferencesKeys.GESTURES_ENABLED] ?: true,
        sensitivity = preferences[PreferencesKeys.SENSITIVITY] ?: 50,
        handPreference = (preferences[PreferencesKeys.HAND_PREFERENCE] as? String)
            ?.let { HandPreference.valueOf(it) }
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
    )

    private fun mapGestureMapConfig(preferences: Preferences): GestureMapConfig {
        val json = preferences[PreferencesKeys.GESTURE_MAP_JSON] as? String
        val version = preferences[PreferencesKeys.GESTURE_MAP_VERSION] ?: 1

        return if (json != null) {
            val config = deserializeGestureMap(json, version)
            GestureMapConfig.migrate(config)
        } else {
            GestureMapConfig()
        }
    }

    @VisibleForTesting
    internal fun serializeGestureMap(config: GestureMapConfig): String {
        return config.entries.joinToString(separator = ";") { entry ->
            "${entry.key}|${entry.label}|${entry.action.name}"
        }
    }

    @VisibleForTesting
    internal fun deserializeGestureMap(json: String, version: Int): GestureMapConfig {
        val entries = json.split(";").mapNotNull { segment ->
            val parts = segment.split("|")
            if (parts.size == 3) {
                try {
                    GestureMapEntry(
                        key = parts[0],
                        label = parts[1],
                        action = GestureAction.valueOf(parts[2]),
                    )
                } catch (e: Exception) {
                    Timber.w(e, "Failed to parse gesture map entry: %s", segment)
                    null
                }
            } else null
        }

        return GestureMapConfig(
            schemaVersion = version,
            entries = entries,
        )
    }

    companion object {
        private val VALID_FPS_SET = setOf(15, 24, 30)
        private const val DEFAULT_FPS = 24
    }
}
