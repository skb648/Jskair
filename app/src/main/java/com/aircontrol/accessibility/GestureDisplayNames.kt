package com.aircontrol.accessibility

import androidx.annotation.StringRes
import com.aircontrol.R
import com.aircontrol.data.model.CustomGestureDirection
import com.aircontrol.data.model.CustomGesturePose

/**
 * Central mapping from enum values to string-resource IDs for user-visible
 * labels. Keeping the mapping here prevents duplicated `when` blocks across
 * GestureMapScreen, CustomGestureScreen, and SettingsScreen.
 */
@StringRes
fun GestureAction.displayNameRes(): Int = when (this) {
    GestureAction.NONE -> R.string.action_none
    GestureAction.SCROLL_UP -> R.string.action_scroll_up
    GestureAction.SCROLL_DOWN -> R.string.action_scroll_down
    GestureAction.SCROLL_LEFT -> R.string.action_scroll_left
    GestureAction.SCROLL_RIGHT -> R.string.action_scroll_right
    GestureAction.BACK -> R.string.action_back
    GestureAction.HOME -> R.string.action_home
    GestureAction.RECENTS -> R.string.action_recents
    GestureAction.NOTIFICATIONS -> R.string.action_notifications
    GestureAction.QUICK_SETTINGS -> R.string.action_quick_settings
    GestureAction.VOLUME_UP -> R.string.action_volume_up
    GestureAction.VOLUME_DOWN -> R.string.action_volume_down
    GestureAction.MEDIA_PLAY_PAUSE -> R.string.action_media_play_pause
    GestureAction.SCREENSHOT -> R.string.action_screenshot
    GestureAction.LOCK_SCREEN -> R.string.action_lock_screen
    GestureAction.TAP -> R.string.action_tap
    GestureAction.DOUBLE_TAP -> R.string.action_double_tap
    GestureAction.LONG_PRESS -> R.string.action_long_press
    GestureAction.DRAG -> R.string.action_drag
}

@StringRes
fun CustomGesturePose.displayNameRes(): Int = when (this) {
    CustomGesturePose.OPEN_PALM -> R.string.pose_open_palm
    CustomGesturePose.FIST -> R.string.pose_fist
    CustomGesturePose.PINCH -> R.string.pose_pinch
    CustomGesturePose.POINTING -> R.string.pose_pointing
    CustomGesturePose.VICTORY -> R.string.pose_victory
    CustomGesturePose.THUMB_UP -> R.string.pose_thumb_up
    CustomGesturePose.THUMB_DOWN -> R.string.pose_thumb_down
    CustomGesturePose.THREE_FINGERS -> R.string.pose_three_fingers
    CustomGesturePose.FOUR_FINGERS -> R.string.pose_four_fingers
}

@StringRes
fun CustomGestureDirection.displayNameRes(): Int = when (this) {
    CustomGestureDirection.NONE -> R.string.direction_none
    CustomGestureDirection.LEFT -> R.string.direction_left
    CustomGestureDirection.RIGHT -> R.string.direction_right
    CustomGestureDirection.UP -> R.string.direction_up
    CustomGestureDirection.DOWN -> R.string.direction_down
}
