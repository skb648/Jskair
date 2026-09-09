package com.aircontrol.bench

import com.aircontrol.tracking.BinocularEyeFeatures
import com.aircontrol.tracking.CanonicalEyes
import com.aircontrol.tracking.EyeLandmarkDefinition
import com.aircontrol.tracking.EyeFeatureExtractor
import com.aircontrol.tracking.FaceLandmark
import com.aircontrol.tracking.FaceLandmarkFrame
import com.aircontrol.tracking.GazeCalibrationFeatureVectorBuilder
import com.aircontrol.tracking.GazeEligibility
import com.aircontrol.tracking.GazeEligibilityPolicy
import com.aircontrol.tracking.GazeJumpPolicy
import com.aircontrol.tracking.GazeUncertainty
import com.aircontrol.tracking.LandmarkReader
import com.aircontrol.tracking.FaceLandmarkSlots
import com.aircontrol.tracking.HeadPoseEstimate
import com.aircontrol.tracking.HeadPoseEstimator
import com.aircontrol.tracking.HeadPoseNormalizer
import com.aircontrol.tracking.RawIrisGazeExtractor
import java.lang.management.ManagementFactory
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * Host-side measurement of the pure-Kotlin half of the eye pipeline (the stage cost and the
 * allocation cost of the code that runs per tracked frame between "MediaPipe handed us
 * landmarks" and "there is a cursor target").
 *
 * It exists because a claim like "the extraction is allocation-heavy" has to be a number, not an
 * impression, and because after changing the representation the numbers have to be comparable.
 * This is NOT an Android profile: no MediaPipe inference, no image conversion, no GC behaviour of
 * ART, no thermal throttling. Device numbers still have to come from a device (see
 * PERF-EYE-AUDIT.md, "measurement environment").
 *
 * Run with tools/eye-bench/run.sh.
 */
private const val FRAMES = 64
private const val REPEATS = 7
private const val WARMUP = 2_000

/** Allocated bytes on the current thread, when the JVM exposes it (HotSpot does). */
private val threadAlloc: com.sun.management.ThreadMXBean? =
    ManagementFactory.getThreadMXBean().let { it as? com.sun.management.ThreadMXBean }

private fun allocBytes(): Long = threadAlloc?.getThreadAllocatedBytes(Thread.currentThread().id) ?: -1L

private fun gcCount(): Long =
    ManagementFactory.getGarbageCollectorMXBeans().sumOf { it.collectionCount }

/** Median ns/frame and bytes/frame over [REPEATS] passes, after warming the JIT. */
private class Measured(val nsPerFrame: Double, val bytesPerFrame: Long, val gcDuring: Long)

private fun measure(name: String, frames: Int = FRAMES, body: (Int) -> Unit): Measured {
    repeat(WARMUP) { body(it) }
    var gc0 = gcCount()
    val ns = DoubleArray(REPEATS)
    val bytes = LongArray(REPEATS)
    for (r in 0 until REPEATS) {
        val a0 = allocBytes()
        val t0 = System.nanoTime()
        repeat(frames) { body(it) }
        ns[r] = (System.nanoTime() - t0) / frames.toDouble()
        bytes[r] = ((allocBytes() - a0) / frames).coerceAtLeast(0L)
    }
    val gcDuring = gcCount() - gc0
    val m = Measured(ns.sortedArray()[REPEATS / 2], bytes.sortedArray()[REPEATS / 2], gcDuring)
    println(
        "%-26s %10.1f ns/frame  %8d B/frame   %5d x1000f %6d KB   gc+%d"
            .format(name, m.nsPerFrame, m.bytesPerFrame, (m.nsPerFrame * 1000 / 1000).toInt(), 0, gcDuring)
            .replace("    0 KB", "")
            .let { s ->
                val mb = m.bytesPerFrame * 1000 / 1_048_576.0
                if (mb >= 0.01) "%s  (%.2f MB per 1000 frames)".format(s, mb) else s
            }
    )
    return m
}

/**
 * A synthetic but geometrically valid 478-landmark frame: eye patches laid out with the same
 * construction the repository's own eye tests use, plus the nose/forehead/chin landmarks head-pose
 * estimation needs, all on a slightly breathing face so every stage takes its normal branch.
 */
/** The 478 landmarks MediaPipe would hand the tracker, for this fixture step. */
private fun sourceLandmarksAt(step: Int): List<FaceLandmark> {
    val shift = (0.010f + 0.004f * sin(step * 0.11)).toFloat()
    val landmarks = MutableList(478) { i ->
        // A face-shaped ellipsoid: index determines a position around the head so that
        // no two of the landmarks the stages read land on top of each other (a
        // degenerate fixture would measure the NaN/short-circuit path instead).
        val row = i / 32
        val col = i % 32
        val u = (col / 31f - 0.5f) * 0.62f
        val v = (row / 14f - 0.5f) * 0.66f
        FaceLandmark(0.5f + u, 0.5f + v, -(u * u + v * v) * 0.3f)
    }
    fillEye(landmarks, CanonicalEyes.LEFT, (0.68f to 0.50f) to (0.80f to 0.50f), shift)
    fillEye(landmarks, CanonicalEyes.RIGHT, (0.20f to 0.50f) to (0.32f to 0.50f), -shift)
    // The three pose landmarks get the layout the repository's own HeadPoseEstimatorTest
    // uses, so the estimator takes its real fallback branch instead of bailing out.
    landmarks[1] = FaceLandmark(0.50f, 0.50f, 0f)
    landmarks[10] = FaceLandmark(0.50f, 0.25f, 0f)
    landmarks[152] = FaceLandmark(0.50f, 0.75f, 0f)
    return landmarks
}

private class ListReader(private val src: List<FaceLandmark>) : LandmarkReader {
    override val size: Int get() = src.size
    override fun x(index: Int) = src[index].x
    override fun y(index: Int) = src[index].y
    override fun z(index: Int) = src[index].z
}

/**
 * The frame exactly as the production pipeline now builds it: 25 slot reads out of a 478-landmark
 * source (audit P0-5). Before, this function *was* the `landmarks.map { FaceLandmark(...) }` copy.
 */
private fun frameAt(step: Int): FaceLandmarkFrame = checkNotNull(
    FaceLandmarkFrame.fromReader(
        frameId = step.toLong(),
        timestampNs = step * 41_000_000L,
        timestampMs = step * 41L,
        trackerWidthPx = 640,
        trackerHeightPx = 480,
        isFrontCameraMirrored = true,
        facialTransformationMatrix = null,
        reader = ListReader(sourceLandmarksAt(step)),
    ),
)

/** The retired per-frame work, kept so the AFTER table can be compared against the same source. */
private fun legacyMaterialise(src: List<FaceLandmark>): List<FaceLandmark> =
    src.map { FaceLandmark(it.x, it.y, it.z) }

private fun fillEye(
    lm: MutableList<FaceLandmark>,
    d: EyeLandmarkDefinition,
    corners: Pair<Pair<Float, Float>, Pair<Float, Float>>,
    irisShift: Float,
) {
    val (outer, inner) = corners
    val dx = inner.first - outer.first
    val dy = inner.second - outer.second
    val nx = -dy
    val ny = dx
    fun point(t: Float, n: Float) = FaceLandmark(outer.first + dx * t + nx * n, outer.second + dy * t + ny * n, 0f)
    lm[d.outerCorner] = FaceLandmark(outer.first, outer.second, 0f)
    lm[d.innerCorner] = FaceLandmark(inner.first, inner.second, 0f)
    lm[d.upperOuter] = point(0.18f, -0.025f)
    lm[d.upperInner] = point(0.82f, -0.025f)
    lm[d.lowerInner] = point(0.82f, 0.025f)
    lm[d.lowerOuter] = point(0.18f, 0.025f)
    val irisT = (0.50f + irisShift).coerceIn(0.10f, 0.90f)
    val irisCenter = point(irisT, 0f)
    lm[d.irisCenter] = irisCenter
    lm[d.irisRing[0]] = point(irisT + 0.018f, 0f)
    lm[d.irisRing[1]] = point(irisT, -0.018f)
    lm[d.irisRing[2]] = point(irisT - 0.018f, 0f)
    lm[d.irisRing[3]] = point(irisT, 0.018f)
}

private fun poseOf(frame: FaceLandmarkFrame, features: BinocularEyeFeatures?): HeadPoseEstimate? =
    features?.let { HeadPoseEstimator.estimate(frame, it) }

fun main() {
    println("Jskair eye-pipeline stage cost (host JVM, pure Kotlin, no MediaPipe)")
    println("frames per cycle=$FRAMES repeats=$REPEATS warmup=$WARMUP  java=${System.getProperty("java.version")}")
    println()

    // One shared set of inputs so every stage is measured on the same data the next one consumes.
    val frames = Array(FRAMES) { frameAt(it) }
    val extracted = Array(FRAMES) { EyeFeatureExtractor.extract(frames[it]) }
    val poses = Array(FRAMES) { poseOf(frames[it], extracted[it]) }
    val normalized = Array(FRAMES) { i ->
        val p = poses[i]
        extracted[i]?.let { e -> p?.let { HeadPoseNormalizer.normalize(e, it) } }
    }
    val vectors = Array(FRAMES) { i -> normalized[i]?.let(GazeCalibrationFeatureVectorBuilder::from) }
    val raws = Array(FRAMES) { i -> extracted[i]?.let(RawIrisGazeExtractor::from) }

    var sink = 0.0

    run {
        val f = frames[0]
        val ex = EyeFeatureExtractor.extract(f)
        val po = poseOf(f, ex)
        val raw = ex?.let(RawIrisGazeExtractor::from)
        println(
            "fixture check: features=${ex != null} valid=${ex?.isValid} " +
                "leftEye=${ex?.left != null} rightEye=${ex?.right != null} " +
                "pose=${po?.source} poseValid=${po?.isValid} eyeScale=${po?.faceScalePx} " +
                "raw=${raw != null} h=${raw?.h} v=${raw?.v} eyeQ=${raw?.eyeQuality}"
        )
        println()
    }

    val sources = Array(FRAMES) { sourceLandmarksAt(it) }
    measure("frame materialisation (legacy: 478-object copy)") { i ->
        val mapped = legacyMaterialise(sources[i % FRAMES])
        sink += mapped.size.toDouble() + mapped[i % 478].x
    }
    measure("frame materialisation (slot fill: 25 used)") { i ->
        // Same source list as the legacy row above: only the production fill is measured, not the
        // fixture construction. Two rows, one input, so the difference is the change itself.
        val f = checkNotNull(
            FaceLandmarkFrame.fromReader(
                frameId = i.toLong(),
                timestampNs = i * 41_000_000L,
                timestampMs = i * 41L,
                trackerWidthPx = 640,
                trackerHeightPx = 480,
                isFrontCameraMirrored = true,
                facialTransformationMatrix = null,
                reader = ListReader(sources[i % FRAMES]),
            ),
        )
        sink += f.trackerWidthPx.toDouble() + f.xOf(CanonicalEyes.LEFT.irisCenter) +
            FaceLandmarkSlots.USED_INDICES.size.toDouble()
    }
    measure("EyeFeatureExtractor.extract") { i ->
        val f = extracted[i % FRAMES]
        sink += (f?.let { abs(it.left!!.irisAlongAxis.toDouble()) + abs(it.right!!.irisAlongAxis.toDouble()) } ?: 0.0)
    }
    measure("HeadPoseEstimator.estimate") { i ->
        val p = poses[i % FRAMES]
        sink += p?.let { abs(it.yawDeg.toDouble()) + abs(it.pitchDeg.toDouble()) } ?: 0.0
    }
    measure("HeadPoseNormalizer.normalize") { i ->
        sink += normalized[i % FRAMES]?.let { abs(it.pose.faceScalePx.toDouble()) } ?: 0.0
    }
    measure("feature vector builder") { i ->
        sink += vectors[i % FRAMES]?.let { v -> (0 until v.values.size).sumOf { v.values[it].toDouble() } } ?: 0.0
    }
    measure("RawIrisGazeExtractor.from") { i ->
        sink += raws[i % FRAMES]?.let { abs(it.h.toDouble()) + abs(it.v.toDouble()) } ?: 0.0
    }

    val uncertainty = GazeUncertainty(
        faceDetected = true,
        eyeQuality = 0.8f,
        binocularAgreement = 0.7f,
        poseConfidence = 0.75f,
        headAngleDeg = 12f,
        poseValid = true,
        modelQuality = 0.9f,
        modelAbsent = false,
    )
    measure("GazeEligibilityPolicy.evaluate") { _ ->
        val d = GazeEligibilityPolicy.evaluate(uncertainty)
        sink += if (d.eligibility == GazeEligibility.ACTIONABLE) 1.0 else 0.0
    }

    val jump = GazeJumpPolicy()
    measure("GazeJumpPolicy.evaluate") { i ->
        val x = 0.5f + 0.02f * sin(i * 0.3f)
        val d = jump.evaluate(x, 0.5f, x - 0.5f, 0.001f)
        sink += abs(d.x.toDouble())
    }

    println()
    measure("whole eye stage (extract->target)") { i ->
        val k = i % FRAMES
        val f = frames[k]
        val ex = EyeFeatureExtractor.extract(f)
        val po = poseOf(f, ex)
        val nv = ex?.let { e -> po?.let { HeadPoseNormalizer.normalize(e, it) } }
        val fv = nv?.let(GazeCalibrationFeatureVectorBuilder::from)
        val raw = ex?.let(RawIrisGazeExtractor::from)
        val u = GazeUncertainty(
            faceDetected = true,
            eyeQuality = raw?.eyeQuality ?: 0f,
            binocularAgreement = raw?.binocularAgreement ?: 0f,
            poseConfidence = po?.confidence,
            headAngleDeg = po?.let { max(abs(it.yawDeg), abs(it.pitchDeg)) },
            poseValid = po?.isValid == true,
            modelQuality = 0.9f,
            modelAbsent = fv == null,
        )
        val el = GazeEligibilityPolicy.evaluate(u)
        val j = raw?.let { jump.evaluate(0.5f, 0.5f, it.signedTravelX, it.signedTravelY) }
        sink += (raw?.h ?: 0f).toDouble() + (j?.y ?: 0f).toDouble() +
            (fv?.values?.firstOrNull()?.toDouble() ?: 0.0) +
            (if (el.eligibility == GazeEligibility.NOTHING) 1.0 else 0.0)
    }
    println()
    val heap = ManagementFactory.getMemoryMXBean().heapMemoryUsage
    println("heap after run: used=%d MB  committed=%d MB".format(heap.used / 1048576, heap.committed / 1048576))
    println("sink=$sink")
}
