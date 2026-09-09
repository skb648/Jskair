package com.aircontrol.tracking

/**
 * A single face-landmark observation in tracker-image coordinates.
 *
 * x/y are normalized to the tracker image: x in [0,1] across tracker width and
 * y in [0,1] across tracker height. z is the MediaPipe landmark depth value and
 * is intentionally left in the model's native relative scale.
 */
data class FaceLandmark(
    val x: Float,
    val y: Float,
    val z: Float = 0f,
) {
    fun isFinite(): Boolean = x.isFinite() && y.isFinite() && z.isFinite()
}

/**
 * Immutable face-landmark observation used by the eye-tracking pipeline.
 *
 * Landmark IDs remain MediaPipe semantic/anatomical IDs even when the source image was mirrored for
 * a front camera. Consumers must never infer anatomical left/right from x position in the image.
 *
 * Storage is a **primitive buffer**, not a list of objects (audit P0-5). The previous shape ran
 * `landmarks.map { FaceLandmark(it.x(), it.y(), it.z()) }` once per frame — measured at 18 232 B and
 * 7.7 us per frame, roughly three times the allocation of the entire rest of the eye path put
 * together, for a line whose only purpose was reshaping data that was already in memory. In
 * [isSlotMode] only [FaceLandmarkSlots.USED_INDICES] are captured, so a frame carries the
 * coordinates the pipeline reads (25 * 3 floats) instead of 478 heap objects.
 *
 * [facialTransformationMatrix] is optional preserved MediaPipe facial geometry metadata, stored
 * verbatim as a 4x4 column-major matrix when available; pose estimation validates it before use and
 * never trusts an invalid matrix.
 */
class FaceLandmarkFrame private constructor(
    val frameId: Long,
    val timestampNs: Long,
    val timestampMs: Long,
    val trackerWidthPx: Int,
    val trackerHeightPx: Int,
    val isFrontCameraMirrored: Boolean,
    val facialTransformationMatrix: FloatArray?,
    /** Primitive storage: three parallel arrays, x/y/z per captured landmark. */
    private val xs: FloatArray,
    private val ys: FloatArray,
    private val zs: FloatArray,
    private val slotMode: Boolean,
) {

    init {
        require(frameId >= 0L) { "frameId must be non-negative" }
        require(timestampNs >= 0L) { "timestampNs must be non-negative" }
        require(timestampMs >= 0L) { "timestampMs must be non-negative" }
        require(trackerWidthPx > 0) { "trackerWidthPx must be > 0" }
        require(trackerHeightPx > 0) { "trackerHeightPx must be > 0" }
        require(xs.size == ys.size && ys.size == zs.size) { "coordinate arrays must be parallel" }
        require(!slotMode || xs.size == FaceLandmarkSlots.USED_INDICES.size) {
            "a slot-mode frame holds exactly the used indices"
        }
        require(facialTransformationMatrix == null || facialTransformationMatrix.size == 16) {
            "facialTransformationMatrix must contain exactly 16 values"
        }
    }

    /**
     * The list-shaped view of the same data, so anything outside the frame path (tests, debug
     * dumps, a consumer that genuinely wants all 478) keeps a `List<FaceLandmark>`. Reading through
     * it allocates one [FaceLandmark] per access; the hot path must use [xOf], [yOf] and [zOf].
     * In slot mode an un-captured id reads as `NaN`, never as a silent zero at the image corner.
     */
    val landmarks: List<FaceLandmark> = LandmarkListView(xs, ys, zs, slotMode)

    /** True when only [FaceLandmarkSlots.USED_INDICES] are captured (the production shape). */
    val isSlotMode: Boolean get() = slotMode

    /**
     * List-shaped constructor: the caller's landmarks are copied into the primitive buffer once, so
     * the frame retains no `FaceLandmark` objects. This is what tests and debug tooling use; the
     * tracker uses [fromReader], which never materialises the objects at all.
     */
    constructor(
        frameId: Long,
        timestampNs: Long,
        timestampMs: Long,
        trackerWidthPx: Int,
        trackerHeightPx: Int,
        isFrontCameraMirrored: Boolean,
        landmarks: List<FaceLandmark>,
        facialTransformationMatrix: FloatArray? = null,
    ) : this(
        frameId = frameId,
        timestampNs = timestampNs,
        timestampMs = timestampMs,
        trackerWidthPx = trackerWidthPx,
        trackerHeightPx = trackerHeightPx,
        isFrontCameraMirrored = isFrontCameraMirrored,
        facialTransformationMatrix = facialTransformationMatrix,
        xs = FloatArray(landmarks.size) { i -> landmarks[i].x },
        ys = FloatArray(landmarks.size) { i -> landmarks[i].y },
        zs = FloatArray(landmarks.size) { i -> landmarks[i].z },
        slotMode = false,
    )

    /**
     * Alloc-free reads. This is the point of the buffer: consumers used to allocate a
     * [FaceLandmark] per lookup (11 per eye, plus the pose anchors) purely to read three floats.
     */
    fun xOf(index: Int): Float = read(xs, index)
    fun yOf(index: Int): Float = read(ys, index)
    fun zOf(index: Int): Float = read(zs, index)

    private fun read(values: FloatArray, index: Int): Float {
        if (!slotMode) return values.getOrNull(index) ?: Float.NaN
        val slot = FaceLandmarkSlots.SEMANTIC_TO_SLOT.getOrNull(index) ?: return Float.NaN
        return if (slot < 0) Float.NaN else values[slot]
    }

    fun isLandmarkIndexValid(index: Int): Boolean = if (!slotMode) {
        index in xs.indices
    } else {
        (FaceLandmarkSlots.SEMANTIC_TO_SLOT.getOrNull(index) ?: -1) >= 0
    }

    /** Allocates; for callers outside the frame path. [xOf]/[yOf]/[zOf] are the hot-path reads. */
    fun landmark(index: Int): FaceLandmark? =
        if (isLandmarkIndexValid(index)) FaceLandmark(xOf(index), yOf(index), zOf(index)) else null

    /**
     * Copies the captured coordinates into [out] in **planar** layout — all x values for
     * [FaceLandmarkSlots.USED_INDICES], then all y, then all z. Planar because that is how the frame
     * stores them, which lets a slot-mode frame copy in bulk instead of touching each landmark, and
     * because a consumer that wants one axis for every index (the calibration feature vector, a
     * debug dump) reads it without striding across unrelated values.
     *
     * [out] must hold `3 * usedCoordinateStride` floats. No objects, no intermediate lists.
     */
    fun copyUsedCoordinates(out: FloatArray) {
        val count = FaceLandmarkSlots.USED_INDICES.size
        require(out.size >= count * 3) { "out must hold 3 blocks of $count floats" }
        if (slotMode) {
            System.arraycopy(xs, 0, out, 0, count)
            System.arraycopy(ys, 0, out, count, count)
            System.arraycopy(zs, 0, out, 2 * count, count)
            return
        }
        val used = FaceLandmarkSlots.USED_INDICES
        for (slot in 0 until count) {
            val index = used[slot]
            out[slot] = xs.getOrNull(index) ?: Float.NaN
            out[count + slot] = ys.getOrNull(index) ?: Float.NaN
            out[2 * count + slot] = zs.getOrNull(index) ?: Float.NaN
        }
    }

    /** Number of landmarks a [copyUsedCoordinates] block covers. */
    val usedCoordinateStride: Int get() = FaceLandmarkSlots.USED_INDICES.size

    /** Same frame, different landmark set (used by mirror/NaN perturbation tests and debug tools). */
    fun copy(
        frameId: Long = this.frameId,
        timestampNs: Long = this.timestampNs,
        timestampMs: Long = this.timestampMs,
        trackerWidthPx: Int = this.trackerWidthPx,
        trackerHeightPx: Int = this.trackerHeightPx,
        isFrontCameraMirrored: Boolean = this.isFrontCameraMirrored,
        landmarks: List<FaceLandmark> = this.landmarks,
        facialTransformationMatrix: FloatArray? = this.facialTransformationMatrix,
    ): FaceLandmarkFrame = FaceLandmarkFrame(
        frameId = frameId,
        timestampNs = timestampNs,
        timestampMs = timestampMs,
        trackerWidthPx = trackerWidthPx,
        trackerHeightPx = trackerHeightPx,
        isFrontCameraMirrored = isFrontCameraMirrored,
        landmarks = landmarks,
        facialTransformationMatrix = facialTransformationMatrix,
    )

    override fun toString(): String =
        "FaceLandmarkFrame(frameId=$frameId, tsMs=$timestampMs, ${trackerWidthPx}x$trackerHeightPx, " +
            "captured=${xs.size}, slotMode=$slotMode, mirrored=$isFrontCameraMirrored)"

    companion object {
        /**
         * Builds the production (slot-mode) frame by reading only the landmarks the pipeline uses.
         *
         * [reader] is the source's own accessor — MediaPipe's `NormalizedLandmark` list, a test
         * fixture, anything. Slot mode means one frame costs `USED_INDICES.size * 3` floats
         * (25 * 3 = 300 B) instead of 478 objects, and the fill loop is 25 reads instead of 478.
         * Returns null when the source is too short to contain the canonical face mesh.
         */
        fun fromReader(
            frameId: Long,
            timestampNs: Long,
            timestampMs: Long,
            trackerWidthPx: Int,
            trackerHeightPx: Int,
            isFrontCameraMirrored: Boolean,
            facialTransformationMatrix: FloatArray?,
            reader: LandmarkReader,
        ): FaceLandmarkFrame? {
            if (reader.size < CanonicalEyes.MIN_LANDMARK_COUNT) return null
            val used = FaceLandmarkSlots.USED_INDICES
            val count = used.size
            val xs = FloatArray(count)
            val ys = FloatArray(count)
            val zs = FloatArray(count)
            for (slot in 0 until count) {
                val index = used[slot]
                if (index < reader.size) {
                    xs[slot] = reader.x(index)
                    ys[slot] = reader.y(index)
                    zs[slot] = reader.z(index)
                } else {
                    xs[slot] = Float.NaN; ys[slot] = Float.NaN; zs[slot] = Float.NaN
                }
            }
            return FaceLandmarkFrame(
                frameId = frameId,
                timestampNs = timestampNs,
                timestampMs = timestampMs,
                trackerWidthPx = trackerWidthPx,
                trackerHeightPx = trackerHeightPx,
                isFrontCameraMirrored = isFrontCameraMirrored,
                facialTransformationMatrix = facialTransformationMatrix,
                xs = xs,
                ys = ys,
                zs = zs,
                slotMode = true,
            )
        }
    }
}

/**
 * Read-only [List] view over the primitive arrays. It exists so the frame keeps its old `List`
 * contract without keeping the old per-frame object graph: elements are materialised on demand.
 */
private class LandmarkListView(
    private val xs: FloatArray,
    private val ys: FloatArray,
    private val zs: FloatArray,
    private val slotMode: Boolean,
) : AbstractList<FaceLandmark>() {

    override val size: Int get() = if (slotMode) CanonicalEyes.MIN_LANDMARK_COUNT else xs.size

    override fun get(index: Int): FaceLandmark {
        if (index !in 0 until size) throw IndexOutOfBoundsException("index $index out of $size")
        if (!slotMode) return FaceLandmark(xs[index], ys[index], zs[index])
        val slot = FaceLandmarkSlots.SEMANTIC_TO_SLOT.getOrNull(index) ?: -1
        return if (slot < 0) {
            FaceLandmark(Float.NaN, Float.NaN, Float.NaN)
        } else {
            FaceLandmark(xs[slot], ys[slot], zs[slot])
        }
    }
}

/** Where a frame's landmark coordinates come from. Implementations must not mutate during a read. */
interface LandmarkReader {
    /** How many landmarks the source provides (478 for a full face mesh, including irises). */
    val size: Int
    fun x(index: Int): Float
    fun y(index: Int): Float
    fun z(index: Int): Float
}

/**
 * The semantic landmark ids the eye pipeline actually reads, resolved once.
 *
 * Everything a frame stores is derived from this set, so "which landmarks are captured" has one
 * definition instead of being implied by which loops happen to index which ids. Adding a landmark
 * to an extractor means adding it here, and the equivalence test proves both frame modes agree on
 * what the extractors see.
 */
object FaceLandmarkSlots {

    /** Both eyes' definitions plus the head-pose anchors, sorted and deduplicated. */
    val USED_INDICES: IntArray = run {
        val ids = sortedSetOf<Int>()
        for (index in CanonicalEyes.LEFT.requiredIndices) ids += index
        for (index in CanonicalEyes.RIGHT.requiredIndices) ids += index
        for (index in CanonicalEyes.LEFT.earPoints) ids += index
        for (index in CanonicalEyes.RIGHT.earPoints) ids += index
        ids += FacePoseLandmarks.NOSE_TIP
        ids += FacePoseLandmarks.FOREHEAD
        ids += FacePoseLandmarks.CHIN
        ids.toIntArray()
    }

    /** Semantic id -> slot index, or -1 when that landmark is not captured by a slot-mode frame. */
    val SEMANTIC_TO_SLOT: IntArray = IntArray(CanonicalEyes.MIN_LANDMARK_COUNT).apply {
        fill(-1)
        USED_INDICES.forEachIndexed { slot, id -> if (id in indices) this[id] = slot }
    }

    /** Floats the pipeline keeps per frame — the number the allocation benchmark watches. */
    val FLOATS_PER_FRAME: Int get() = USED_INDICES.size * 3

    /** Bytes per frame for the coordinate buffer alone (no object headers, no list nodes). */
    val BYTES_PER_FRAME: Int get() = FLOATS_PER_FRAME * 4
}

/**
 * Canonical MediaPipe Face Mesh / Face Landmarker landmark definitions.
 *
 * IMPORTANT: these groups describe ANATOMICAL identity, not screen side.
 * Anatomical LEFT therefore uses MediaPipe's 263/362 eye corners and iris 473;
 * anatomical RIGHT uses 33/133 and iris 468. This remains true for selfie/front
 * camera inputs after horizontal mirroring because the semantic landmark IDs are
 * preserved by the face model.
 */
data class EyeLandmarkDefinition(
    val outerCorner: Int,
    val innerCorner: Int,
    val upperOuter: Int,
    val upperInner: Int,
    val lowerInner: Int,
    val lowerOuter: Int,
    val irisCenter: Int,
    val irisRing: IntArray,
) {
    val earPoints: IntArray
        get() = intArrayOf(
            outerCorner,
            upperOuter,
            upperInner,
            innerCorner,
            lowerInner,
            lowerOuter,
        )

    val requiredIndices: IntArray
        get() = intArrayOf(
            outerCorner,
            innerCorner,
            upperOuter,
            upperInner,
            lowerInner,
            lowerOuter,
            irisCenter,
            *irisRing,
        )
}

object CanonicalEyes {
    /** Anatomical left eye: image-right side for a typical mirrored selfie frame. */
    val LEFT = EyeLandmarkDefinition(
        outerCorner = 263,
        innerCorner = 362,
        upperOuter = 387,
        upperInner = 385,
        lowerInner = 380,
        lowerOuter = 373,
        irisCenter = 473,
        irisRing = intArrayOf(474, 475, 476, 477),
    )

    /** Anatomical right eye: image-left side for a typical mirrored selfie frame. */
    val RIGHT = EyeLandmarkDefinition(
        outerCorner = 33,
        innerCorner = 133,
        upperOuter = 160,
        upperInner = 158,
        lowerInner = 153,
        lowerOuter = 144,
        irisCenter = 468,
        irisRing = intArrayOf(469, 470, 471, 472),
    )

    const val MIN_LANDMARK_COUNT = 478
}
