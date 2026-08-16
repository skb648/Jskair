package com.aircontrol.tracking

/**
 * 2D affine gaze calibration (Vision Pro / GazePointer style).
 *
 * Maps camera-space gaze ratios (iris position relative to eye corners) to
 * normalized display-space coordinates (0..1). Fitted from 5 calibration points
 * (Top-Left, Top-Right, Center, Bottom-Left, Bottom-Right) via least squares —
 * more robust than an exact 4-point homography and tolerant of a slightly
 * off-center 5th sample.
 */
class GazeCalibration(
    /** Affine coefficients [a, b, c, d, e, f]:
     *  screenX = a*gx + b*gy + c
     *  screenY = d*gx + e*gy + f
     */
    private val coeffs: FloatArray,
) {
    val isCalibrated: Boolean get() = coeffs.size == 6

    /** Maps a raw gaze ratio to normalized display coordinates (0..1). */
    fun map(gx: Float, gy: Float): Pair<Float, Float> {
        if (!isCalibrated) return gx to gy // uncalibrated passthrough
        val sx = coeffs[0] * gx + coeffs[1] * gy + coeffs[2]
        val sy = coeffs[3] * gx + coeffs[4] * gy + coeffs[5]
        return sx to sy
    }

    fun toFloatArray(): FloatArray = coeffs.copyOf()

    companion object {
        val UNAVAILABLE = GazeCalibration(FloatArray(0))

        fun fromFloatArray(coeffs: FloatArray?): GazeCalibration =
            if (coeffs != null && coeffs.size == 6) GazeCalibration(coeffs) else UNAVAILABLE

        fun fromString(raw: String?): GazeCalibration =
            if (raw.isNullOrBlank()) UNAVAILABLE else {
                fromFloatArray(raw.split(",").mapNotNull { it.toFloatOrNull() }.toFloatArray())
            }

        /**
         * Fits an affine transform from [gaze] (camera-space ratios) to [screen]
         * (normalized display points) using least squares.
         */
        fun fit(
            gaze: List<Pair<Float, Float>>,
            screen: List<Pair<Float, Float>>,
        ): GazeCalibration {
            require(gaze.size == screen.size) { "gaze and screen sample counts must match" }
            require(gaze.size >= 3) { "at least 3 points are required for an affine fit" }

            val src = gaze.map { floatArrayOf(it.first, it.second) }
            val xRow = solveAxis(src, screen.map { it.first })
            val yRow = solveAxis(src, screen.map { it.second })

            return GazeCalibration(
                floatArrayOf(xRow[0], xRow[1], xRow[2], yRow[0], yRow[1], yRow[2]),
            )
        }

        /** Least-squares solve for one output axis (3x3 normal equations → Gaussian elimination). */
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
                if (kotlin.math.abs(a[pivot][col]) < 1e-12) continue

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
                x[i] = if (kotlin.math.abs(a[i][i]) > 1e-12) b[i] / a[i][i] else 0.0
            }
            return x
        }
    }
}
