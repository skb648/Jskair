package com.aircontrol.tracking

/**
 * 2D affine gaze calibration.
 * Maps camera-space gaze ratios to normalized display-space coordinates.
 */
class GazeCalibration(
    private val coeffs: FloatArray,
) {
    val isCalibrated: Boolean get() = coeffs.size == 6 && coeffs.all { it.isFinite() }

    fun map(gx: Float, gy: Float): Pair<Float, Float> {
        if (!isCalibrated) return gx.coerceIn(0f, 1f) to gy.coerceIn(0f, 1f)
        val sx = coeffs[0] * gx + coeffs[1] * gy + coeffs[2]
        val sy = coeffs[3] * gx + coeffs[4] * gy + coeffs[5]
        return sx.coerceIn(0f, 1f) to sy.coerceIn(0f, 1f)
    }

    fun toFloatArray(): FloatArray = coeffs.copyOf()

    /**
     * Per-point error of the fit: for every calibrated target, how far (in
     * normalized display units) the mapping sends the averaged gaze from where the
     * user was actually looking. Used to refuse a fit that is mathematically valid
     * but built from a sample the user missed.
     */
    fun residuals(
        gaze: List<Pair<Float, Float>>,
        screen: List<Pair<Float, Float>>,
    ): List<Float> {
        require(gaze.size == screen.size) { "gaze and screen sample counts must match" }
        return gaze.indices.map { i ->
            val (mx, my) = map(gaze[i].first, gaze[i].second)
            val dx = mx - screen[i].first
            val dy = my - screen[i].second
            kotlin.math.sqrt(dx * dx + dy * dy)
        }
    }

    companion object {
        val UNAVAILABLE = GazeCalibration(FloatArray(0))

        fun fromFloatArray(coeffs: FloatArray?): GazeCalibration =
            if (coeffs != null && coeffs.size == 6 && coeffs.all { it.isFinite() }) {
                GazeCalibration(coeffs.copyOf())
            } else {
                UNAVAILABLE
            }

        fun fromString(raw: String?): GazeCalibration {
            if (raw.isNullOrBlank()) return UNAVAILABLE
            val parts = raw.split(",").map { it.trim() }
            if (parts.size != 6) return UNAVAILABLE
            val coeffs = parts.map { it.toFloatOrNull() ?: return UNAVAILABLE }.toFloatArray()
            return fromFloatArray(coeffs)
        }

        fun fit(
            gaze: List<Pair<Float, Float>>,
            screen: List<Pair<Float, Float>>,
        ): GazeCalibration {
            require(gaze.size == screen.size) { "gaze and screen sample counts must match" }
            require(gaze.size >= 3) { "at least 3 points are required for an affine fit" }
            require(gaze.all { it.first.isFinite() && it.second.isFinite() }) {
                "gaze samples must be finite"
            }
            require(screen.all { it.first.isFinite() && it.second.isFinite() }) {
                "screen samples must be finite"
            }

            val src = gaze.map { floatArrayOf(it.first, it.second) }
            val xRow = solveAxis(src, screen.map { it.first })
            val yRow = solveAxis(src, screen.map { it.second })

            val fitted = floatArrayOf(
                xRow[0], xRow[1], xRow[2],
                yRow[0], yRow[1], yRow[2],
            )
            require(fitted.all { it.isFinite() }) { "gaze calibration produced invalid coefficients" }
            return GazeCalibration(fitted)
        }

        private fun solveAxis(src: List<FloatArray>, dst: List<Float>): FloatArray {
            var sxx = 0.0; var sxy = 0.0; var sx = 0.0
            var syy = 0.0; var sy = 0.0
            var sxd = 0.0; var syd = 0.0; var sd = 0.0
            val n = src.size.toDouble()

            for (i in src.indices) {
                val x = src[i][0].toDouble()
                val y = src[i][1].toDouble()
                val d = dst[i].toDouble()
                sxx += x * x; sxy += x * y; sx += x
                syy += y * y; sy += y
                sxd += x * d; syd += y * d; sd += d
            }

            val m = arrayOf(
                doubleArrayOf(sxx, sxy, sx),
                doubleArrayOf(sxy, syy, sy),
                doubleArrayOf(sx, sy, n),
            )
            val rhs = doubleArrayOf(sxd, syd, sd)
            val solution = solve3x3(m, rhs)
            return floatArrayOf(solution[0].toFloat(), solution[1].toFloat(), solution[2].toFloat())
        }

        private fun solve3x3(m: Array<DoubleArray>, rhs: DoubleArray): DoubleArray {
            val a = m.map { it.copyOf() }.toTypedArray()
            val b = rhs.copyOf()

            for (col in 0 until 3) {
                var pivot = col
                for (row in col + 1 until 3) {
                    if (kotlin.math.abs(a[row][col]) > kotlin.math.abs(a[pivot][col])) pivot = row
                }
                require(kotlin.math.abs(a[pivot][col]) >= 1e-12) {
                    "Gaze calibration samples are singular or ill-conditioned"
                }

                val tmpA = a[col]; a[col] = a[pivot]; a[pivot] = tmpA
                val tmpB = b[col]; b[col] = b[pivot]; b[pivot] = tmpB

                for (row in 0 until 3) {
                    if (row == col) continue
                    val factor = a[row][col] / a[col][col]
                    for (k in col until 3) a[row][k] -= factor * a[col][k]
                    b[row] -= factor * b[col]
                }
            }

            val x = DoubleArray(3)
            for (i in 0 until 3) {
                require(kotlin.math.abs(a[i][i]) >= 1e-12) {
                    "Gaze calibration solution is singular"
                }
                x[i] = b[i] / a[i][i]
            }
            return x
        }
    }
}
