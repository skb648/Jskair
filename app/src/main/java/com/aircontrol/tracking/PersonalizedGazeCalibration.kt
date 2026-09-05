package com.aircontrol.tracking

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Deterministic 9-point calibration layout, independent from the UI. */
enum class CalibrationTarget(val x: Float, val y: Float) {
    TOP_LEFT(0.1f, 0.1f), TOP_CENTER(0.5f, 0.1f), TOP_RIGHT(0.9f, 0.1f),
    MIDDLE_LEFT(0.1f, 0.5f), CENTER(0.5f, 0.5f), MIDDLE_RIGHT(0.9f, 0.5f),
    BOTTOM_LEFT(0.1f, 0.9f), BOTTOM_CENTER(0.5f, 0.9f), BOTTOM_RIGHT(0.9f, 0.9f),
}

object CalibrationTargets {
    val ALL: List<CalibrationTarget> = CalibrationTarget.entries.toList()
}

data class CalibrationSample(
    val target: CalibrationTarget,
    val targetX: Float,
    val targetY: Float,
    val features: CalibrationFeatureVector,
    val timestampMs: Long,
    val quality: Float,
) {
    init {
        require(targetX in 0f..1f && targetY in 0f..1f)
        require(timestampMs >= 0L)
        require(quality.isFinite() && quality in 0f..1f)
        require(features.values.all { it.isFinite() })
        require(target.x == targetX && target.y == targetY)
    }

    companion object {
        sealed class Result {
            data class Accepted(val sample: CalibrationSample) : Result()
            data class Rejected(val reason: RejectionReason) : Result()
        }

        enum class RejectionReason {
            INVALID_FEATURES, INVALID_POSE, NON_FINITE, LOW_QUALITY, INVALID_TIMESTAMP,
        }

        fun create(
            target: CalibrationTarget,
            featureVector: CalibrationFeatureVector?,
            timestampMs: Long,
            quality: Float,
            poseValid: Boolean,
        ): Result {
            if (featureVector == null) return Result.Rejected(RejectionReason.INVALID_FEATURES)
            if (!poseValid) return Result.Rejected(RejectionReason.INVALID_POSE)
            if (timestampMs < 0L) return Result.Rejected(RejectionReason.INVALID_TIMESTAMP)
            if (!quality.isFinite() || featureVector.values.any { !it.isFinite() }) {
                return Result.Rejected(RejectionReason.NON_FINITE)
            }
            if (quality < MIN_SAMPLE_QUALITY) return Result.Rejected(RejectionReason.LOW_QUALITY)
            return Result.Accepted(CalibrationSample(target, target.x, target.y, featureVector, timestampMs, quality))
        }
    }
}

data class RobustCalibrationSamples(
    val retained: List<CalibrationSample>,
    val rejected: List<CalibrationSample>,
)

/** Robust filtering; callers must only pass the training partition. */
object RobustCalibrationSampleAggregator {
    fun filter(trainingSamples: List<CalibrationSample>): RobustCalibrationSamples {
        val retained = ArrayList<CalibrationSample>()
        val rejected = ArrayList<CalibrationSample>()
        trainingSamples.groupBy { it.target }.toSortedMap(compareBy { it.ordinal }).forEach { (_, targetSamples) ->
            val ordered = targetSamples.sortedWith(sampleComparator)
            if (ordered.size <= 2) {
                retained += ordered
                return@forEach
            }
            val medians = componentMedian(ordered.map { it.features.values })
            val scales = FloatArray(GazeCalibrationFeatureSchema.DIMENSION) { dimension ->
                val deviations = ordered.map { abs(it.features.values[dimension] - medians[dimension]) }
                max(1e-4f, median(deviations) * 1.4826f)
            }
            val distances = ordered.map { robustDistance(it.features.values, medians, scales) }
            val distanceMedian = median(distances)
            val distanceMad = max(1e-4, median(distances.map { abs(it - distanceMedian) }) * 1.4826)
            val limit = distanceMedian + 3.5 * distanceMad
            ordered.forEachIndexed { index, sample ->
                if (sample.quality >= MIN_SAMPLE_QUALITY && distances[index] <= limit) retained += sample
                else rejected += sample
            }
        }
        return RobustCalibrationSamples(
            retained = retained.sortedWith(sampleComparator),
            rejected = rejected.sortedWith(sampleComparator),
        )
    }

    private val sampleComparator = compareBy<CalibrationSample>({ it.target.ordinal }, { it.timestampMs }, { stableHash(it) })

    private fun stableHash(sample: CalibrationSample): Long = sample.features.values.fold(sample.timestampMs) { acc, value ->
        acc * 31L + value.toBits().toLong()
    }

    private fun componentMedian(vectors: List<FloatArray>): FloatArray = FloatArray(GazeCalibrationFeatureSchema.DIMENSION) { d ->
        median(vectors.map { it[d] })
    }

    private fun robustDistance(values: FloatArray, center: FloatArray, scales: FloatArray): Double {
        var sum = 0.0
        for (i in values.indices) {
            val z = (values[i] - center[i]).toDouble() / scales[i]
            sum += z * z
        }
        return sqrt(sum)
    }

    private fun median(values: List<Float>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        return if (sorted.size % 2 == 1) sorted[sorted.size / 2]
        else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) * 0.5f
    }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        return if (sorted.size % 2 == 1) sorted[sorted.size / 2]
        else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) * 0.5
    }

    private const val MIN_SAMPLE_QUALITY = 0.20f
}

/** Symmetric quadratic basis: 1, x_i, and x_i*x_j for i <= j. */
object QuadraticPolynomialFeatures {
    fun expand(input: DoubleArray): DoubleArray {
        val n = input.size
        val output = DoubleArray(size(n))
        output[0] = 1.0
        input.copyInto(output, destinationOffset = 1)
        var cursor = 1 + n
        for (i in 0 until n) for (j in i until n) output[cursor++] = input[i] * input[j]
        return output
    }

    fun size(inputDimension: Int): Int = 1 + inputDimension + inputDimension * (inputDimension + 1) / 2
}

data class CalibrationMetrics(
    val meanNormalizedError: Double,
    val medianNormalizedError: Double,
    val p95NormalizedError: Double,
    val maxNormalizedError: Double,
    val horizontalMae: Double,
    val verticalMae: Double,
    val sampleCount: Int,
    val validationSampleCount: Int,
    val meanPixelError: Double? = null,
    val medianPixelError: Double? = null,
    val p95PixelError: Double? = null,
    val maxPixelError: Double? = null,
) {
    init {
        require(meanNormalizedError.isFinite() && medianNormalizedError.isFinite() && p95NormalizedError.isFinite() && maxNormalizedError.isFinite())
        require(horizontalMae.isFinite() && verticalMae.isFinite())
        require(sampleCount >= 0 && validationSampleCount >= 0)
        require(listOfNotNull(meanPixelError, medianPixelError, p95PixelError, maxPixelError).all { it.isFinite() })
    }
}

data class CalibrationDatasetSplit(val training: List<CalibrationSample>, val validation: List<CalibrationSample>) {
    val isValid: Boolean get() = training.isNotEmpty() && validation.isNotEmpty()
}

/** Deterministic target-balanced split performed before any learned filtering/statistics. */
object TargetBalancedValidationSplit {
    fun split(samples: List<CalibrationSample>, validationFraction: Double = 0.20): CalibrationDatasetSplit {
        require(validationFraction in 0.05..0.50)
        val train = ArrayList<CalibrationSample>()
        val validation = ArrayList<CalibrationSample>()
        samples.groupBy { it.target }.toSortedMap(compareBy { it.ordinal }).forEach { (_, group) ->
            val ordered = group.sortedWith(compareBy({ it.timestampMs }))
            if (ordered.size < 2) {
                train += ordered
            } else {
                val validationCount = min(ordered.size - 1, max(1, ceil(ordered.size * validationFraction).toInt()))
                train += ordered.dropLast(validationCount)
                validation += ordered.takeLast(validationCount)
            }
        }
        return CalibrationDatasetSplit(train, validation)
    }
}

class CalibrationFitException(message: String) : Exception(message)

data class Standardization(val means: DoubleArray, val stdDevs: DoubleArray) {
    init {
        require(means.size == stdDevs.size)
        require(means.all { it.isFinite() } && stdDevs.all { it.isFinite() && it > 0.0 })
    }

    fun transform(input: FloatArray): DoubleArray {
        require(input.size == means.size)
        return DoubleArray(input.size) { i -> (input[i].toDouble() - means[i]) / stdDevs[i] }
    }
}

data class PersonalizedGazeCalibrationModel(
    val modelVersion: Int,
    val featureSchemaVersion: Int,
    val modelType: String,
    val regularization: Double,
    val transformSignature: String,
    val standardization: Standardization,
    val coefficientsX: DoubleArray,
    val coefficientsY: DoubleArray,
    val trainingMetrics: CalibrationMetrics,
    val validationMetrics: CalibrationMetrics,
    val screenWidthPx: Int? = null,
    val screenHeightPx: Int? = null,
    val createdAtMs: Long,
) {
    init {
        require(modelVersion == MODEL_VERSION)
        require(featureSchemaVersion == GazeCalibrationFeatureSchema.VERSION)
        require(modelType == MODEL_TYPE)
        require(regularization.isFinite() && regularization > 0.0)
        require(transformSignature.startsWith("ct-v$TRANSFORM_VERSION:"))
        require(coefficientsX.size == coefficientsY.size)
        require(coefficientsX.size == QuadraticPolynomialFeatures.size(standardization.means.size))
        require(coefficientsX.all { it.isFinite() } && coefficientsY.all { it.isFinite() })
        require(createdAtMs >= 0L)
        require(screenWidthPx == null || screenWidthPx > 0)
        require(screenHeightPx == null || screenHeightPx > 0)
    }

    fun predict(features: CalibrationFeatureVector): Pair<Float, Float> {
        if (features.schemaVersion != featureSchemaVersion) throw CalibrationFitException("incompatible feature schema")
        val basis = QuadraticPolynomialFeatures.expand(standardization.transform(features.values))
        val x = dot(coefficientsX, basis)
        val y = dot(coefficientsY, basis)
        if (!x.isFinite() || !y.isFinite()) throw CalibrationFitException("non-finite prediction")
        return x.toFloat() to y.toFloat()
    }

    fun toSerialized(): String = PersonalizedGazeCalibrationSerializer.serialize(this)

    companion object {
        const val MODEL_VERSION = 1
        const val MODEL_TYPE = "quadratic-ridge-v1"
        private const val TRANSFORM_VERSION = CoordinateTransform.VERSION

        private fun dot(a: DoubleArray, b: DoubleArray): Double {
            if (a.size != b.size) throw CalibrationFitException("coefficient size mismatch")
            var sum = 0.0
            for (i in a.indices) sum += a[i] * b[i]
            return sum
        }
    }
}

data class CalibrationFitResult(val model: PersonalizedGazeCalibrationModel?, val failure: String? = null) {
    val isSuccess: Boolean get() = model != null && failure == null
}

object PersonalizedGazeCalibrationFitter {
    fun fit(
        rawSamples: List<CalibrationSample>,
        transform: CoordinateTransform,
        regularization: Double = 1e-2,
        screenWidthPx: Int? = null,
        screenHeightPx: Int? = null,
        validationFraction: Double = 0.20,
        createdAtMs: Long,
    ): CalibrationFitResult = try {
        if (!regularization.isFinite() || regularization <= 0.0) throw CalibrationFitException("regularization must be finite and > 0")
        if (screenWidthPx != null && screenWidthPx <= 0) throw CalibrationFitException("screen width must be positive")
        if (screenHeightPx != null && screenHeightPx <= 0) throw CalibrationFitException("screen height must be positive")
        if (createdAtMs < 0L) throw CalibrationFitException("creation timestamp must be non-negative")

        val rawSplit = TargetBalancedValidationSplit.split(rawSamples, validationFraction)
        if (!rawSplit.isValid) throw CalibrationFitException("validation split is empty")

        // Validation is untouched. Only the training partition is robust-filtered or standardized.
        val robustTraining = RobustCalibrationSampleAggregator.filter(rawSplit.training)
        val training = robustTraining.retained
        if (training.size < MIN_TOTAL_SAMPLES) throw CalibrationFitException("insufficient training observations after robust filtering")
        val counts = training.groupingBy { it.target }.eachCount()
        if (CalibrationTarget.entries.any { counts[it] ?: 0 < MIN_SAMPLES_PER_TARGET }) {
            throw CalibrationFitException("every target requires at least $MIN_SAMPLES_PER_TARGET retained training observations")
        }
        if (rawSplit.validation.size < MIN_VALIDATION_SAMPLES) throw CalibrationFitException("insufficient held-out validation observations")

        val standardization = computeStandardization(training)
        val basis = training.map { QuadraticPolynomialFeatures.expand(standardization.transform(it.features.values)) }
        val betaX = solveRidge(basis, training.map { it.targetX.toDouble() }, regularization)
        val betaY = solveRidge(basis, training.map { it.targetY.toDouble() }, regularization)
        val trainingMetrics = metrics(training, standardization, betaX, betaY, screenWidthPx, screenHeightPx, false)
        val validationMetrics = metrics(rawSplit.validation, standardization, betaX, betaY, screenWidthPx, screenHeightPx, true)

        CalibrationFitResult(
            PersonalizedGazeCalibrationModel(
                modelVersion = PersonalizedGazeCalibrationModel.MODEL_VERSION,
                featureSchemaVersion = GazeCalibrationFeatureSchema.VERSION,
                modelType = PersonalizedGazeCalibrationModel.MODEL_TYPE,
                regularization = regularization,
                transformSignature = transform.signature,
                standardization = standardization,
                coefficientsX = betaX,
                coefficientsY = betaY,
                trainingMetrics = trainingMetrics,
                validationMetrics = validationMetrics,
                screenWidthPx = screenWidthPx,
                screenHeightPx = screenHeightPx,
                createdAtMs = createdAtMs,
            ),
        )
    } catch (e: Exception) {
        CalibrationFitResult(null, e.message ?: "calibration fit failed")
    }

    private fun computeStandardization(samples: List<CalibrationSample>): Standardization {
        if (samples.isEmpty()) throw CalibrationFitException("no training samples")
        val d = GazeCalibrationFeatureSchema.DIMENSION
        val means = DoubleArray(d)
        for (sample in samples) for (i in 0 until d) means[i] += sample.features.values[i]
        for (i in 0 until d) means[i] /= samples.size
        val stds = DoubleArray(d)
        for (sample in samples) for (i in 0 until d) {
            val delta = sample.features.values[i].toDouble() - means[i]
            stds[i] += delta * delta
        }
        for (i in 0 until d) {
            stds[i] = sqrt(stds[i] / max(1, samples.size - 1))
            if (!stds[i].isFinite() || stds[i] < 1e-6) stds[i] = 1.0
        }
        return Standardization(means, stds)
    }

    /** Solves (X'X + lambda I) beta = X'y using pivoted elimination. */
    private fun solveRidge(basis: List<DoubleArray>, target: List<Double>, lambda: Double): DoubleArray {
        if (basis.isEmpty() || basis.size != target.size) throw CalibrationFitException("invalid regression dataset")
        val p = basis.first().size
        if (basis.any { it.size != p || it.any { value -> !value.isFinite() } }) throw CalibrationFitException("invalid polynomial basis")
        if (p != MODEL_TERM_COUNT) throw CalibrationFitException("unexpected model basis size")
        val a = Array(p) { DoubleArray(p) }
        val b = DoubleArray(p)
        for (r in basis.indices) {
            val row = basis[r]
            val y = target[r]
            if (!y.isFinite()) throw CalibrationFitException("non-finite regression target")
            for (i in 0 until p) {
                b[i] += row[i] * y
                for (j in i until p) a[i][j] += row[i] * row[j]
            }
        }
        for (i in 0 until p) {
            for (j in 0 until i) a[i][j] = a[j][i]
            a[i][i] += lambda
        }
        return gaussianSolve(a, b)
    }

    private fun gaussianSolve(matrix: Array<DoubleArray>, rhs: DoubleArray): DoubleArray {
        val n = rhs.size
        val augmented = Array(n) { i -> matrix[i].copyOf().also { row ->
            require(row.all { it.isFinite() }) { "non-finite regression matrix" }
        }.let { row -> row + rhs[i] } }
        for (column in 0 until n) {
            var pivot = column
            var pivotAbs = abs(augmented[column][column])
            for (row in column + 1 until n) {
                val candidate = abs(augmented[row][column])
                if (candidate > pivotAbs) { pivot = row; pivotAbs = candidate }
            }
            if (!pivotAbs.isFinite() || pivotAbs < 1e-12) throw CalibrationFitException("degenerate or ill-conditioned regression system")
            if (pivot != column) { val tmp = augmented[column]; augmented[column] = augmented[pivot]; augmented[pivot] = tmp }
            val diagonal = augmented[column][column]
            for (j in column until n + 1) augmented[column][j] /= diagonal
            for (row in 0 until n) if (row != column) {
                val factor = augmented[row][column]
                if (abs(factor) > 1e-15) for (j in column until n + 1) augmented[row][j] -= factor * augmented[column][j]
            }
        }
        return DoubleArray(n) { i -> augmented[i][n] }.also { result ->
            if (result.any { !it.isFinite() }) throw CalibrationFitException("non-finite regression coefficients")
        }
    }

    private fun metrics(
        samples: List<CalibrationSample>,
        standardization: Standardization,
        betaX: DoubleArray,
        betaY: DoubleArray,
        screenWidthPx: Int?,
        screenHeightPx: Int?,
        isValidation: Boolean,
    ): CalibrationMetrics {
        if (samples.isEmpty()) throw CalibrationFitException("cannot compute metrics on empty dataset")
        val errors = ArrayList<Double>(samples.size)
        val horizontal = ArrayList<Double>(samples.size)
        val vertical = ArrayList<Double>(samples.size)
        val pixelErrors = if (screenWidthPx != null && screenHeightPx != null) ArrayList(samples.size) else null
        for (sample in samples) {
            val basis = QuadraticPolynomialFeatures.expand(standardization.transform(sample.features.values))
            val px = dot(betaX, basis)
            val py = dot(betaY, basis)
            if (!px.isFinite() || !py.isFinite()) throw CalibrationFitException("non-finite metric prediction")
            val dx = px - sample.targetX
            val dy = py - sample.targetY
            horizontal += abs(dx)
            vertical += abs(dy)
            errors += sqrt(dx * dx + dy * dy)
            if (pixelErrors != null) {
                val dxPx = dx * screenWidthPx!!
                val dyPx = dy * screenHeightPx!!
                pixelErrors += sqrt(dxPx * dxPx + dyPx * dyPx)
            }
        }
        val sorted = errors.sorted()
        val pixels = pixelErrors?.sorted()
        return CalibrationMetrics(
            meanNormalizedError = errors.average(),
            medianNormalizedError = quantile(sorted, 0.50),
            p95NormalizedError = quantile(sorted, 0.95),
            maxNormalizedError = sorted.last(),
            horizontalMae = horizontal.average(),
            verticalMae = vertical.average(),
            sampleCount = samples.size,
            validationSampleCount = if (isValidation) samples.size else 0,
            meanPixelError = pixels?.average(),
            medianPixelError = pixels?.let { quantile(it, 0.50) },
            p95PixelError = pixels?.let { quantile(it, 0.95) },
            maxPixelError = pixels?.lastOrNull(),
        )
    }

    private fun dot(a: DoubleArray, b: DoubleArray): Double {
        var sum = 0.0
        for (i in a.indices) sum += a[i] * b[i]
        return sum
    }

    private fun quantile(sorted: List<Double>, q: Double): Double {
        val index = q * (sorted.size - 1)
        val low = index.toInt()
        val high = min(sorted.lastIndex, low + 1)
        val weight = index - low
        return sorted[low] * (1 - weight) + sorted[high] * weight
    }

    const val MODEL_TERM_COUNT = 300
    const val MIN_SAMPLES_PER_TARGET = 80
    const val MIN_TOTAL_SAMPLES = 900
    const val MIN_VALIDATION_SAMPLES = 180
}
