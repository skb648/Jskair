package com.aircontrol.data.model

import com.aircontrol.accessibility.GestureAction

/**
 * Represents a single gesture trigger and its mapped action.
 */
data class GestureMapEntry(
    val key: String,
    val label: String,
    val action: GestureAction,
)

/**
 * Versioned gesture map configuration.
 * Schema version allows migration when new gesture triggers are added.
 */
data class GestureMapConfig(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val entries: List<GestureMapEntry> = defaultEntries(),
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 2

        fun defaultEntries(): List<GestureMapEntry> = listOf(
            GestureMapEntry("swipe_left", "Swipe Left", GestureAction.SCROLL_RIGHT),
            GestureMapEntry("swipe_right", "Swipe Right", GestureAction.SCROLL_LEFT),
            GestureMapEntry("swipe_up", "Swipe Up", GestureAction.SCROLL_DOWN),
            GestureMapEntry("swipe_down", "Swipe Down", GestureAction.SCROLL_UP),
            GestureMapEntry("pose_pinch", "Pinch", GestureAction.TAP),
            GestureMapEntry("pose_pointing", "Pointing", GestureAction.NONE),
            GestureMapEntry("pose_victory", "Victory", GestureAction.MEDIA_PLAY_PAUSE),
            GestureMapEntry("pose_thumb_up", "Thumb Up", GestureAction.VOLUME_UP),
            GestureMapEntry("pose_thumb_down", "Thumb Down", GestureAction.VOLUME_DOWN),
        )

        /**
         * Migrates an old config to the current schema.
         * Adds any new entries that don't exist in the old config.
         * Removes entries that no longer exist in the default set.
         */
        fun migrate(oldConfig: GestureMapConfig): GestureMapConfig {
            if (oldConfig.schemaVersion >= CURRENT_SCHEMA_VERSION) return oldConfig

            val defaults = defaultEntries()
            val existingByKey = oldConfig.entries.associateBy { it.key }

            val migrated = defaults.map { default ->
                existingByKey[default.key]?.copy(label = default.label) ?: default
            }

            return GestureMapConfig(
                schemaVersion = CURRENT_SCHEMA_VERSION,
                entries = migrated,
            )
        }
    }
}
