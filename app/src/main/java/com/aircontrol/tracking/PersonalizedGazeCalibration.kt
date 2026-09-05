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
            INVALID_FEATURES, INVALID_POSE, NON_FINITE, LOW_QUALITY, INVALID_TARGET, INVALID_TIMESTAMP,
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
            if (!quality.isFinite()) return Result.Rejected(RejectionReason.NON_FINITE)
            if (quality < 0.20f) return Result.Rejected(RejectionReason.LOW_QUALITY)
            if (featureVector.values.any { !it.isFinite() }) return Result.Rejected(RejectionReason.NON_FINITE)
            return Result.Accepted(CalibrationSample(target, target.x, target.y, featureVector, timestampMs, quality))
        }
    }
}

data class RobustCalibrationSamples(
    val retained: List<CalibrationSample>,
    val rejected: List<CalibrationSample>,
)

/** Deterministic per-target robust filtering using median/MAD of complete feature vectors. */
object RobustCalibrationSampleAggregator {
    fun filter(samples: List<CalibrationSample>): RobustCalibrationSamples {
        val retained = ArrayList<CalibrationSample>()
        val rejected = ArrayList<CalibrationSample>()
        samples.groupBy { it.target }.toSortedMap(compareBy { it.ordinal }).forEach { (_, targetSamples) ->
            val ordered = targetSamples.sortedWith(sampleComparator)
            if (ordered.size <= 2) {
                retained += ordered
                return@forEach
            }
            val medians = componentMedian(ordered.map { it.features.values })
            val scales = FloatArray(GazeCalibrationFeatureSchema.DIMENSION) { dimension ->
                val absoluteDeviations = ordered.map { abs(it.features.values[dimension] - medians[dimension]) }
                max(1e-4f, median(absoluteDeviations) * 1.4826f)
            }
            val distances = ordered.map { sample -> robustDistance(sample.features.values, medians, scales) }
            val medianDistance = median(distances)
            // Fix (compile): Double * Float mixes operand types — keep the whole
            // expression in Double so max(Double, Double) applies.
            val mad = max(1e-4, median(distances.map { abs(it - medianDistance) }) * 1.4826)
            val limit = max(3.5, medianDistance + 3.5 * mad)
            ordered.forEachIndexed { index, sample ->
                if (sample.quality >= 0.20f && distances[index] <= limit) retained += sample else rejected += sample
            }
        }
        return RobustCalibrationSamples(retained.sortedWith(sampleComparator), rejected.sortedWith(sampleComparator))
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

object TargetBalancedValidationSplit {
    fun split(samples: List<CalibrationSample>, validationFraction: Double = 0.20): CalibrationDatasetSplit {
        require(validationFraction in 0.05..0.50)
        val train = ArrayList<CalibrationSample>()
        val validation = ArrayList<CalibrationSample>()
        samples.groupBy { it.target }.toSortedMap(compareBy { it.ordinal }).forEach { (_, group) ->
            val ordered = group.sortedWith(compareBy({ it.timestampMs }, { it.features.values.contentHashCode() }))
            if (ordered.size < 2) train += ordered else {
                val validationCount = min(ordered.size - 1, max(1, ceil(ordered.size * validationFraction).toInt()))
                validation += ordered.takeLast(validationCount)
                train += ordered.dropLast(validationCount)
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
        require(transformSignature.isNotBlank())
        require(coefficientsX.size == coefficientsY.size)
        require(coefficientsX.size == QuadraticPolynomialFeatures.size(standardization.means.size))
        require(coefficientsX.all { it.isFinite() } && coefficientsY.all { it.isFinite() })
        require(createdAtMs >= 0L)
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
        regularization: Double = 1e-2,
        transformSignature: String,
        screenWidthPx: Int? = null,
        screenHeightPx: Int? = null,
        validationFraction: Double = 0.20,
        createdAtMs: Long,
    ): CalibrationFitResult = try {
        if (!regularization.isFinite() || regularization <= 0.0) throw CalibrationFitException("regularization must be finite and > 0")
        if (transformSignature.isBlank()) throw CalibrationFitException("transform signature is required")
        val robust = RobustCalibrationSampleAggregator.filter(rawSamples)
        val samples = robust.retained
        if (samples.size < MIN_TOTAL_SAMPLES) throw CalibrationFitException("insufficient robust calibration samples")
        if (samples.map { it.target }.toSet().size != CalibrationTarget.entries.size) throw CalibrationFitException("all 9 targets require retained samples")
        val split = TargetBalancedValidationSplit.split(samples, validationFraction)
        if (!split.isValid) throw CalibrationFitException("validation split is empty")
        val standardization = computeStandardization(split.training)
        val basis = split.training.map { QuadraticPolynomialFeatures.expand(standardization.transform(it.features.values)) }
        val betaX = solveRidge(basis, split.training.map { it.targetX.toDouble() }, regularization)
        val betaY = solveRidge(basis, split.training.map { it.targetY.toDouble() }, regularization)
        val trainingMetrics = metrics(split.training, standardization, betaX, betaY, screenWidthPx, screenHeightPx, false)
        val validationMetrics = metrics(split.validation, standardization, betaX, betaY, screenWidthPx, screenHeightPx, true)
        require(validationMetrics.validationSampleCount > 0) { "validation metrics unavailable" }
        CalibrationFitResult(
            PersonalizedGazeCalibrationModel(
                modelVersion = PersonalizedGazeCalibrationModel.MODEL_VERSION,
                featureSchemaVersion = GazeCalibrationFeatureSchema.VERSION,
                modelType = PersonalizedGazeCalibrationModel.MODEL_TYPE,
                regularization = regularization,
                transformSignature = transformSignature,
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
        val d = GazeCalibrationFeatureSchema.DIMENSION
        if (samples.isEmpty()) throw CalibrationFitException("no training samples")
        val means = DoubleArray(d)
        for (sample in samples) for (i in 0 until d) means[i] += sample.features.values[i]
        for (i in 0 until d) means[i] /= samples.size
        val stds = DoubleArray(d)
        for (sample in samples) for (i in 0 until d) {
            val delta = sample.features.values[i] - means[i].toFloat()
            stds[i] += delta * delta
        }
        for (i in 0 until d) {
            stds[i] = sqrt(stds[i] / max(1, samples.size - 1))
            if (!stds[i].isFinite() || stds[i] < 1e-6) stds[i] = 1.0
        }
        return Standardization(means, stds)
    }

    /** Solves (X'X + lambda I) beta = X'y using pivoted elimination; no unregularized inverse is used. */
    private fun solveRidge(basis: List<DoubleArray>, target: List<Double>, lambda: Double): DoubleArray {
        if (basis.isEmpty() || basis.size != target.size) throw CalibrationFitException("invalid regression dataset")
        val p = basis.first().size
        if (basis.any { it.size != p || it.any { value -> !value.isFinite() } }) throw CalibrationFitException("invalid polynomial basis")
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
        val a = Array(n) { matrix[it].copyOf() }
        val b = rhs.copyOf()
        for (col in 0 until n) {
            var pivot = col
            for (row in col + 1 until n) if (abs(a[row][col]) > abs(a[pivot][col])) pivot = row
            val pivotAbs = abs(a[pivot][col])
            if (!pivotAbs.isFinite() || pivotAbs < 1e-12) throw CalibrationFitException("singular or ill-conditioned ridge system")
            if (pivot != col) { val row = a[pivot]; a[pivot] = a[col]; a[col] = row; val rhsValue = b[pivot]; b[pivot] = b[col]; b[col] = rhsValue }
            for (row in col + 1 until n) {
                val factor = a[row][col] / a[col][col]
                if (!factor.isFinite()) throw CalibrationFitException("numerical failure during ridge solve")
                a[row][col] = 0.0
                for (j in col + 1 until n) a[row][j] -= factor * a[col][j]
                b[row] -= factor * b[col]
            }
        }
        val solution = DoubleArray(n)
        for (i in n - 1 downTo 0) {
            var sum = b[i]
            for (j in i + 1 until n) sum -= a[i][j] * solution[j]
            solution[i] = sum / a[i][i]
            if (!solution[i].isFinite()) throw CalibrationFitException("non-finite regression coefficient")
        }
        return solution
    }

    private fun metrics(
        samples: List<CalibrationSample>,
        standardization: Standardization,
        betaX: DoubleArray,
        betaY: DoubleArray,
        screenWidthPx: Int?,
        screenHeightPx: Int?,
        validation: Boolean,
    ): CalibrationMetrics {
        if (samples.isEmpty()) throw CalibrationFitException("cannot compute empty metrics")
        val errors = samples.map { sample ->
            val basis = QuadraticPolynomialFeatures.expand(standardization.transform(sample.features.values))
            val dx = dot(betaX, basis) - sample.targetX
            val dy = dot(betaY, basis) - sample.targetY
            MetricPoint(abs(dx), abs(dy), sqrt(dx * dx + dy * dy))
        }
        val scalarErrors = errors.map { it.distance }
        fun quantile(q: Double): Double {
            val sorted = scalarErrors.sorted()
            val index = q * (sorted.size - 1)
            val low = index.toInt()
            val high = min(sorted.lastIndex, low + 1)
            val weight = index - low
            return sorted[low] * (1 - weight) + sorted[high] * weight
        }
        val pixelErrors = if (screenWidthPx != null && screenHeightPx != null && screenWidthPx > 0 && screenHeightPx > 0) {
            scalarErrors.map { it * sqrt(screenWidthPx.toDouble() * screenWidthPx + screenHeightPx.toDouble() * screenHeightPx) }
        } else null
        return CalibrationMetrics(
            meanNormalizedError = scalarErrors.average(), medianNormalizedError = quantile(0.50),
            p95NormalizedError = quantile(0.95), maxNormalizedError = scalarErrors.maxOrNull() ?: 0.0,
            horizontalMae = errors.map { it.dx }.average(), verticalMae = errors.map { it.dy }.average(),
            sampleCount = samples.size, validationSampleCount = if (validation) samples.size else 0,
            meanPixelError = pixelErrors?.average(), medianPixelError = pixelErrors?.let { quantileFrom(it, 0.50) },
            p95PixelError = pixelErrors?.let { quantileFrom(it, 0.95) }, maxPixelError = pixelErrors?.maxOrNull(),
        )
    }

    private data class MetricPoint(val dx: Double, val dy: Double, val distance: Double)
    private fun dot(a: DoubleArray, b: DoubleArray): Double { if (a.size != b.size) throw CalibrationFitException("coefficient size mismatch"); var sum = 0.0; for (i in a.indices) sum += a[i] * b[i]; return sum }
    private fun quantileFrom(values: List<Double>, q: Double): Double { val sorted = values.sorted(); val index = q * (sorted.size - 1); val low = index.toInt(); val high = min(sorted.lastIndex, low + 1); val weight = index - low; return sorted[low] * (1 - weight) + sorted[high] * weight }
    private const val MIN_TOTAL_SAMPLES = 90
}
