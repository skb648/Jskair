package com.aircontrol.gesture.model

/**
 * Bug: Custom Gestures Not Triggering Fix — A user-recorded hand landmark
 * template that the [com.aircontrol.gesture.detection.StaticPoseClassifier]
 * matches against live [HandInput] frames.
 *
 * A template is created by capturing a snapshot of the hand's normalized
 * inter-landmark distances at record time. At runtime, the classifier computes
 * the same distances from the live MediaPipe landmarks and compares them
 * against the template using a Euclidean distance error metric. If the total
 * error is below [MATCH_TOLERANCE], the template is considered matched.
 *
 * Design decisions:
 * - **Distances, not raw coordinates**: Raw landmark X/Y/Z coordinates are
 *   position-dependent (where the hand is in the camera frame). Inter-landmark
 *   distances are translation-invariant — the same hand shape produces the
 *   same distances regardless of where the hand is. This makes templates
 *   reusable across the entire camera field of view.
 * - **Normalized by hand size**: All distances are divided by the wrist-to-
 *   middle-MCP distance (hand size) so templates are scale-invariant — the
 *   same hand shape produces the same normalized distances regardless of how
 *   close the hand is to the camera.
 * - **Subset of landmark pairs**: We don't store all 21×20/2 = 210 pairwise
 *   distances. We store a curated subset (fingertip-to-fingertip, fingertip-
 *   to-wrist, fingertip-to-MCP) that captures the essential geometry of hand
 *   poses. This keeps the template compact (~20 values) and the matching
 *   computation fast.
 *
 * @param gestureId Unique identifier matching the [com.aircontrol.accessibility.GestureAction]
 *   mapping in the app layer. The engine emits this ID in a
 *   [GestureEvent.CustomGestureTriggered] event; the ActionDispatcher looks
 *   up the corresponding action.
 * @param name Human-readable name (for debugging/logging only).
 * @param normalizedDistances Curated set of normalized inter-landmark distances
 *   captured at record time. See [TEMPLATE_LANDMARK_PAIRS] for the pairs used.
 */
data class LandmarkTemplate(
    val gestureId: String,
    val name: String,
    val normalizedDistances: List<Float>,
) {
    init {
        require(gestureId.isNotBlank()) { "gestureId must not be blank" }
        require(normalizedDistances.isNotEmpty()) { "normalizedDistances must not be empty" }
    }

    companion object {
        /**
         * Maximum total Euclidean error (sum of per-pair absolute differences)
         * for a template to be considered matched. 0.05 means the average
         * per-pair error must be below 0.05 / numPairs. For 20 pairs, that's
         * 0.0025 per pair — strict enough to distinguish distinct hand shapes,
         * loose enough to tolerate natural hand jitter.
         */
        const val MATCH_TOLERANCE = 0.05f

        /**
         * The curated set of landmark index pairs whose normalized distances
         * are stored in the template. These pairs capture:
         * - Fingertip-to-fingertip (hand shape / finger spread)
         * - Fingertip-to-wrist (finger extension / curl)
         * - Thumb-to-fingertip (thumb opposition)
         *
         * Indices correspond to the MediaPipe hand-landmarker model:
         * 0=WRIST, 4=THUMB_TIP, 8=INDEX_TIP, 12=MIDDLE_TIP, 16=RING_TIP,
         * 20=PINKY_TIP, 5=INDEX_MCP, 9=MIDDLE_MCP, 13=RING_MCP, 17=PINKY_MCP,
         * 2=THUMB_MCP.
         */
        val TEMPLATE_LANDMARK_PAIRS: List<Pair<Int, Int>> = listOf(
            // Fingertip-to-fingertip (finger spread geometry)
            4 to 8,   // thumb_tip → index_tip
            4 to 12,  // thumb_tip → middle_tip
            4 to 16,  // thumb_tip → ring_tip
            4 to 20,  // thumb_tip → pinky_tip
            8 to 12,  // index_tip → middle_tip
            8 to 16,  // index_tip → ring_tip
            8 to 20,  // index_tip → pinky_tip
            12 to 16, // middle_tip → ring_tip
            12 to 20, // middle_tip → pinky_tip
            16 to 20, // ring_tip → pinky_tip
            // Fingertip-to-wrist (finger extension / curl)
            0 to 4,   // wrist → thumb_tip
            0 to 8,   // wrist → index_tip
            0 to 12,  // wrist → middle_tip
            0 to 16,  // wrist → ring_tip
            0 to 20,  // wrist → pinky_tip
            // Fingertip-to-MCP (finger curl relative to palm)
            5 to 8,   // index_mcp → index_tip
            9 to 12,  // middle_mcp → middle_tip
            13 to 16, // ring_mcp → ring_tip
            17 to 20, // pinky_mcp → pinky_tip
            2 to 4,   // thumb_mcp → thumb_tip
        )

        /**
         * Number of distance values in a template. Equals
         * [TEMPLATE_LANDMARK_PAIRS].size (20).
         */
        val EXPECTED_DISTANCE_COUNT: Int = TEMPLATE_LANDMARK_PAIRS.size
    }
}
