package com.aircontrol.tracking

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Raw single-hand detection from MediaPipe before temporal association.
 */
data class RawHandDetection(
    val landmarks: List<Landmark3D>,
    val handedness: Handedness,
    val confidence: Float,
)

/**
 * Multi-hand temporal associator that maintains up to two independent hand tracks
 * across frames with stable [TrackedHand.trackId]s, handedness hysteresis,
 * crossing/overlap ambiguity detection, and deterministic primary-hand ownership.
 */
class MultiHandAssociator {

    private data class TrackState(
        val trackId: Int,
        var canonicalHandedness: Handedness,
        var handednessStability: Int,
        var palmX: Float,
        var palmY: Float,
        var wristX: Float,
        var wristY: Float,
        var vx: Float,
        var vy: Float,
        var palmScale: Float,
        var lastSeenTimestampMs: Long,
    )

    private data class EnrichedDetection(
        val raw: RawHandDetection,
        val palmX: Float,
        val palmY: Float,
        val wristX: Float,
        val wristY: Float,
        val palmScale: Float,
        val quality: Float,
    )

    private val activeTracks = mutableListOf<TrackState>()
    private var nextTrackId = 1
    private var lastFrameTimestampMs = Long.MIN_VALUE
    var primaryTrackId: Int? = null
        private set
    private var lastPrimaryWristX = -1f
    private var lastPrimaryWristY = -1f

    @Synchronized
    fun reset() {
        activeTracks.clear()
        primaryTrackId = null
        lastPrimaryWristX = -1f
        lastPrimaryWristY = -1f
        lastFrameTimestampMs = Long.MIN_VALUE
    }

    @Synchronized
    fun onPreferredHandChanged() {
        primaryTrackId = null
        lastPrimaryWristX = -1f
        lastPrimaryWristY = -1f
    }

    @Synchronized
    fun associate(
        rawDetections: List<RawHandDetection>,
        timestampMs: Long,
        frameAspectRatio: Float,
        preferredHand: Handedness,
    ): HandFrame {
        val aspect = frameAspectRatio.takeIf { it.isFinite() && it in 0.25f..4.0f } ?: 1f

        if (lastFrameTimestampMs != Long.MIN_VALUE && timestampMs < lastFrameTimestampMs) {
            // Monotonic clock reset or new session
            activeTracks.clear()
            primaryTrackId = null
        }
        lastFrameTimestampMs = timestampMs

        // Prune expired tracks
        activeTracks.removeAll { timestampMs - it.lastSeenTimestampMs > MAX_TRACK_GAP_MS }

        if (rawDetections.isEmpty()) {
            primaryTrackId = null
            lastPrimaryWristX = -1f
            lastPrimaryWristY = -1f
            return HandFrame(
                landmarks = emptyList(),
                handedness = Handedness.UNKNOWN,
                timestampMs = timestampMs,
                confidence = 0f,
                frameAspectRatio = aspect,
                landmarkQuality = 0f,
                hands = emptyList(),
                primaryTrackId = null,
            )
        }

        val enriched = rawDetections
            .filter { it.landmarks.size == HandFrame.LANDMARK_COUNT }
            .map { det -> enrichDetection(det, aspect) }

        val deduplicated = deduplicateDetections(enriched, aspect)
            .take(MAX_HANDS)

        if (deduplicated.isEmpty()) {
            primaryTrackId = null
            return HandFrame(
                landmarks = emptyList(),
                handedness = Handedness.UNKNOWN,
                timestampMs = timestampMs,
                confidence = 0f,
                frameAspectRatio = aspect,
                landmarkQuality = 0f,
                hands = emptyList(),
                primaryTrackId = null,
            )
        }

        // Solve track-to-detection assignment
        val assignment = assignTracksToDetections(deduplicated, timestampMs, aspect)
        val crossingAmbiguous = isCrossingOrAmbiguous(
            detections = deduplicated,
            assignmentAmbiguous = assignment.isAmbiguousAssignment,
            aspect = aspect,
        )

        val trackedHands = ArrayList<TrackedHand>(deduplicated.size)
        for (detIdx in deduplicated.indices) {
            val det = deduplicated[detIdx]
            val matchedTrack = assignment.detectionToTrack[detIdx]
            val resolvedTrack = if (matchedTrack != null) {
                updateExistingTrack(matchedTrack, det, timestampMs, deduplicated.size, crossingAmbiguous)
            } else {
                createNewTrack(det, timestampMs)
            }

            // Guarantee unique handedness when two distinct non-ambiguous hands are visible
            val effectiveHandedness = resolvedTrack.canonicalHandedness
            trackedHands.add(
                TrackedHand(
                    trackId = resolvedTrack.trackId,
                    landmarks = det.raw.landmarks,
                    handedness = effectiveHandedness,
                    confidence = det.raw.confidence,
                    timestampMs = timestampMs,
                    frameAspectRatio = aspect,
                    landmarkQuality = det.quality,
                    isAmbiguous = crossingAmbiguous,
                ),
            )
        }

        // If 2 hands are present and not crossing-ambiguous, but one had UNKNOWN or collided handedness
        // while the other has high-confidence LEFT/RIGHT, disambiguate only if spatially separated.
        val finalHands = resolveDualHandednessLabels(trackedHands)

        // Select primary hand according to preferredHand + temporal ownership continuity
        val eligibleForPrimary = finalHands.filter { hand ->
            hand.isValid && (preferredHand == Handedness.UNKNOWN || hand.handedness == preferredHand)
        }

        val selectedPrimary = selectPrimaryHand(eligibleForPrimary)
        if (selectedPrimary != null) {
            primaryTrackId = selectedPrimary.trackId
            val wrist = selectedPrimary.landmarks[0]
            lastPrimaryWristX = wrist.x
            lastPrimaryWristY = wrist.y
        } else {
            primaryTrackId = null
            lastPrimaryWristX = -1f
            lastPrimaryWristY = -1f
        }

        return HandFrame.fromTrackedHands(
            hands = finalHands,
            primaryHand = selectedPrimary,
            timestampMs = timestampMs,
            frameAspectRatio = aspect,
        )
    }

    private fun selectPrimaryHand(eligible: List<TrackedHand>): TrackedHand? {
        if (eligible.isEmpty()) return null
        if (eligible.size == 1) return eligible[0]

        // 1. Stick to the existing primaryTrackId if it is still valid & eligible
        val currentPrimaryId = primaryTrackId
        if (currentPrimaryId != null) {
            eligible.firstOrNull { it.trackId == currentPrimaryId }?.let { return it }
        }

        // 2. Otherwise pick the hand closest to the last primary wrist
        if (lastPrimaryWristX >= 0f && lastPrimaryWristY >= 0f) {
            return eligible.minByOrNull { hand ->
                val wrist = hand.landmarks[0]
                val dx = wrist.x - lastPrimaryWristX
                val dy = wrist.y - lastPrimaryWristY
                dx * dx + dy * dy
            }
        }

        // 3. Fallback: highest confidence * landmarkQuality
        return eligible.maxByOrNull { it.confidence * it.landmarkQuality }
    }

    private data class AssignmentResult(
        val detectionToTrack: Map<Int, TrackState>,
        val isAmbiguousAssignment: Boolean,
    )

    private fun assignTracksToDetections(
        detections: List<EnrichedDetection>,
        timestampMs: Long,
        aspect: Float,
    ): AssignmentResult {
        if (activeTracks.isEmpty()) {
            return AssignmentResult(emptyMap(), false)
        }

        val numTracks = activeTracks.size
        val numDets = detections.size

        // Cost matrix: cost[t][d]
        val costs = Array(numTracks) { tIdx ->
            val track = activeTracks[tIdx]
            FloatArray(numDets) { dIdx ->
                computeAssociationCost(track, detections[dIdx], timestampMs, aspect, numDets)
            }
        }

        val matches = mutableMapOf<Int, TrackState>()
        var ambiguous = false

        if (numTracks == 1 && numDets == 1) {
            if (costs[0][0] <= MAX_ASSOCIATION_COST) {
                matches[0] = activeTracks[0]
            }
        } else if (numTracks == 1 && numDets == 2) {
            val c0 = costs[0][0]
            val c1 = costs[0][1]
            if (c0 <= c1 && c0 <= MAX_ASSOCIATION_COST) {
                matches[0] = activeTracks[0]
            } else if (c1 < c0 && c1 <= MAX_ASSOCIATION_COST) {
                matches[1] = activeTracks[0]
            }
        } else if (numTracks >= 2 && numDets == 1) {
            val c0 = costs[0][0]
            val c1 = costs[1][0]
            if (c0 <= c1 && c0 <= MAX_ASSOCIATION_COST) {
                matches[0] = activeTracks[0]
            } else if (c1 < c0 && c1 <= MAX_ASSOCIATION_COST) {
                matches[0] = activeTracks[1]
            }
        } else if (numTracks >= 2 && numDets >= 2) {
            // 2x2 exact assignment: option A = (t0->d0, t1->d1), option B = (t0->d1, t1->d0)
            val costA = costs[0][0] + costs[1][1]
            val costB = costs[0][1] + costs[1][0]
            val margin = abs(costA - costB)
            val palmDist = hypot(
                (detections[0].palmX - detections[1].palmX) * aspect,
                detections[0].palmY - detections[1].palmY,
            )
            if (margin < ASSIGNMENT_AMBIGUITY_MARGIN && palmDist < CLOSE_HANDS_DISTANCE) {
                ambiguous = true
            }
            if (costA <= costB) {
                if (costs[0][0] <= MAX_ASSOCIATION_COST) matches[0] = activeTracks[0]
                if (costs[1][1] <= MAX_ASSOCIATION_COST) matches[1] = activeTracks[1]
            } else {
                if (costs[0][1] <= MAX_ASSOCIATION_COST) matches[1] = activeTracks[0]
                if (costs[1][0] <= MAX_ASSOCIATION_COST) matches[0] = activeTracks[1]
            }
        }

        return AssignmentResult(matches, ambiguous)
    }

    private fun computeAssociationCost(
        track: TrackState,
        det: EnrichedDetection,
        timestampMs: Long,
        aspect: Float,
        numDetectionsInFrame: Int,
    ): Float {
        val dtSec = ((timestampMs - track.lastSeenTimestampMs).coerceIn(0L, 120L)) / 1000f
        val predPalmX = (track.palmX + track.vx * dtSec).coerceIn(0f, 1f)
        val predPalmY = (track.palmY + track.vy * dtSec).coerceIn(0f, 1f)

        val palmDist = hypot((det.palmX - predPalmX) * aspect, det.palmY - predPalmY)
        val wristDist = hypot((det.wristX - track.wristX) * aspect, det.wristY - track.wristY)
        val posDist = 0.65f * palmDist + 0.35f * wristDist

        if (posDist > MAX_SPATIAL_JUMP) {
            return Float.MAX_VALUE / 4f
        }

        val scaleDiff = abs(det.palmScale - track.palmScale) * 0.35f
        val handednessPenalty = when {
            track.canonicalHandedness == Handedness.UNKNOWN || det.raw.handedness == Handedness.UNKNOWN -> 0f
            track.canonicalHandedness == det.raw.handedness -> 0f
            numDetectionsInFrame == 1 && posDist < SINGLE_HAND_FLIP_TOLERANCE_DIST -> 0.06f
            else -> 0.25f
        }
        return posDist + scaleDiff + handednessPenalty
    }

    private fun updateExistingTrack(
        track: TrackState,
        det: EnrichedDetection,
        timestampMs: Long,
        numDetections: Int,
        crossingAmbiguous: Boolean,
    ): TrackState {
        val dtMs = (timestampMs - track.lastSeenTimestampMs).coerceAtLeast(1L)
        if (!crossingAmbiguous && dtMs <= 150L) {
            val instVx = (det.palmX - track.palmX) / (dtMs / 1000f)
            val instVy = (det.palmY - track.palmY) / (dtMs / 1000f)
            track.vx = 0.5f * track.vx + 0.5f * instVx
            track.vy = 0.5f * track.vy + 0.5f * instVy
        } else {
            track.vx = 0f
            track.vy = 0f
        }

        val posDist = hypot(det.palmX - track.palmX, det.palmY - track.palmY)
        val incomingHandedness = det.raw.handedness
        if (incomingHandedness != Handedness.UNKNOWN) {
            if (track.canonicalHandedness == Handedness.UNKNOWN) {
                track.canonicalHandedness = incomingHandedness
                track.handednessStability = 1
            } else if (track.canonicalHandedness == incomingHandedness) {
                track.handednessStability = (track.handednessStability + 1).coerceAtMost(10)
            } else {
                // Handedness label flipped relative to established track.
                // Require at least 2 consecutive opposing frames when a single hand stays in place.
                val stationarySingleHand = numDetections == 1 && posDist < SINGLE_HAND_FLIP_TOLERANCE_DIST
                if (stationarySingleHand) {
                    track.handednessStability--
                    if (track.handednessStability <= 0) {
                        track.canonicalHandedness = incomingHandedness
                        track.handednessStability = 2
                    }
                } else {
                    track.handednessStability -= 2
                    if (track.handednessStability <= 0) {
                        track.canonicalHandedness = incomingHandedness
                        track.handednessStability = 2
                    }
                }
            }
        }

        track.palmX = det.palmX
        track.palmY = det.palmY
        track.wristX = det.wristX
        track.wristY = det.wristY
        track.palmScale = det.palmScale
        track.lastSeenTimestampMs = timestampMs
        return track
    }

    private fun createNewTrack(det: EnrichedDetection, timestampMs: Long): TrackState {
        val allocatedId = nextTrackId++
        val newTrack = TrackState(
            trackId = allocatedId,
            canonicalHandedness = det.raw.handedness,
            handednessStability = if (det.raw.handedness != Handedness.UNKNOWN) 2 else 0,
            palmX = det.palmX,
            palmY = det.palmY,
            wristX = det.wristX,
            wristY = det.wristY,
            vx = 0f,
            vy = 0f,
            palmScale = det.palmScale,
            lastSeenTimestampMs = timestampMs,
        )
        if (activeTracks.size >= MAX_HANDS) {
            activeTracks.sortBy { it.lastSeenTimestampMs }
            activeTracks.removeAt(0)
        }
        activeTracks.add(newTrack)
        return newTrack
    }

    private fun isCrossingOrAmbiguous(
        detections: List<EnrichedDetection>,
        assignmentAmbiguous: Boolean,
        aspect: Float,
    ): Boolean {
        if (detections.size < 2) return false
        if (assignmentAmbiguous) return true

        val d0 = detections[0]
        val d1 = detections[1]
        val palmDist = hypot((d0.palmX - d1.palmX) * aspect, d0.palmY - d1.palmY)
        val wristDist = hypot((d0.wristX - d1.wristX) * aspect, d0.wristY - d1.wristY)
        return minOf(palmDist, wristDist) < CROSSING_AMBIGUITY_DISTANCE
    }

    private fun resolveDualHandednessLabels(hands: List<TrackedHand>): List<TrackedHand> {
        if (hands.size != 2) return hands
        val h0 = hands[0]
        val h1 = hands[1]
        if (h0.isAmbiguous || h1.isAmbiguous) return hands
        if (h0.handedness != h1.handedness && h0.handedness != Handedness.UNKNOWN && h1.handedness != Handedness.UNKNOWN) {
            return hands
        }
        // If MediaPipe gave both separated hands the same label, keep the higher-confidence
        // hand's label and assign the complementary label to the other track so both left
        // and right hands remain distinctly addressable when clearly separated.
        val wrist0 = h0.landmarks[0]
        val wrist1 = h1.landmarks[0]
        val separated = hypot(wrist0.x - wrist1.x, wrist0.y - wrist1.y) >= MIN_DUAL_SEPARATION_FOR_COMPLEMENT
        if (!separated) return hands

        return if (h0.confidence >= h1.handednessConfidenceOrZero()) {
            val primaryLabel = if (h0.handedness != Handedness.UNKNOWN) h0.handedness else Handedness.RIGHT
            val secondaryLabel = if (primaryLabel == Handedness.LEFT) Handedness.RIGHT else Handedness.LEFT
            listOf(h0.copy(handedness = primaryLabel), h1.copy(handedness = secondaryLabel))
        } else {
            val primaryLabel = if (h1.handedness != Handedness.UNKNOWN) h1.handedness else Handedness.RIGHT
            val secondaryLabel = if (primaryLabel == Handedness.LEFT) Handedness.RIGHT else Handedness.LEFT
            listOf(h0.copy(handedness = secondaryLabel), h1.copy(handedness = primaryLabel))
        }
    }

    private fun TrackedHand.handednessConfidenceOrZero(): Float =
        if (handedness == Handedness.UNKNOWN) 0f else confidence

    private fun deduplicateDetections(
        detections: List<EnrichedDetection>,
        aspect: Float,
    ): List<EnrichedDetection> {
        if (detections.size <= 1) return detections
        val result = mutableListOf<EnrichedDetection>()
        for (candidate in detections.sortedByDescending { it.raw.confidence * it.quality }) {
            val isDuplicate = result.any { kept ->
                val palmDist = hypot((candidate.palmX - kept.palmX) * aspect, candidate.palmY - kept.palmY)
                val wristDist = hypot((candidate.wristX - kept.wristX) * aspect, candidate.wristY - kept.wristY)
                palmDist < DUPLICATE_HAND_DISTANCE && wristDist < DUPLICATE_HAND_DISTANCE
            }
            if (!isDuplicate) {
                result.add(candidate)
            }
        }
        return result
    }

    private fun enrichDetection(det: RawHandDetection, aspect: Float): EnrichedDetection {
        val lms = det.landmarks
        val wrist = lms[0]
        val indexMcp = lms[5]
        val middleMcp = lms[9]
        val ringMcp = lms[13]
        val pinkyMcp = lms[17]
        val palmX = (wrist.x + indexMcp.x + middleMcp.x + ringMcp.x + pinkyMcp.x) / 5f
        val palmY = (wrist.y + indexMcp.y + middleMcp.y + ringMcp.y + pinkyMcp.y) / 5f
        val palmScale = hypot((middleMcp.x - wrist.x) * aspect, middleMcp.y - wrist.y)
        val quality = HandFrame.computeLandmarkQuality(lms)
        return EnrichedDetection(
            raw = det,
            palmX = palmX,
            palmY = palmY,
            wristX = wrist.x,
            wristY = wrist.y,
            palmScale = palmScale,
            quality = quality,
        )
    }

    companion object {
        private const val MAX_HANDS = 2
        private const val MAX_TRACK_GAP_MS = 500L
        private const val MAX_SPATIAL_JUMP = 0.42f
        private const val MAX_ASSOCIATION_COST = 0.55f
        private const val SINGLE_HAND_FLIP_TOLERANCE_DIST = 0.11f
        private const val DUPLICATE_HAND_DISTANCE = 0.045f
        private const val CROSSING_AMBIGUITY_DISTANCE = 0.10f
        private const val CLOSE_HANDS_DISTANCE = 0.14f
        private const val ASSIGNMENT_AMBIGUITY_MARGIN = 0.03f
        private const val MIN_DUAL_SEPARATION_FOR_COMPLEMENT = 0.12f
    }
}
