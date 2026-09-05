package com.aircontrol.tracking

import org.json.JSONArray
import org.json.JSONObject

sealed class CalibrationLoadResult {
    data class Loaded(val model: PersonalizedGazeCalibrationModel) : CalibrationLoadResult()
    data class Invalid(val reason: String) : CalibrationLoadResult()
}

object PersonalizedGazeCalibrationSerializer {
    fun serialize(model: PersonalizedGazeCalibrationModel): String = JSONObject()
        .put("modelVersion", model.modelVersion)
        .put("featureSchemaVersion", model.featureSchemaVersion)
        .put("modelType", model.modelType)
        .put("regularization", model.regularization)
        .put("transformSignature", model.transformSignature)
        .put("means", model.standardization.means.toJsonArray())
        .put("stdDevs", model.standardization.stdDevs.toJsonArray())
        .put("coefficientsX", model.coefficientsX.toJsonArray())
        .put("coefficientsY", model.coefficientsY.toJsonArray())
        .put("trainingMetrics", metricsToJson(model.trainingMetrics))
        .put("validationMetrics", metricsToJson(model.validationMetrics))
        .put("screenWidthPx", model.screenWidthPx ?: JSONObject.NULL)
        .put("screenHeightPx", model.screenHeightPx ?: JSONObject.NULL)
        .put("createdAtMs", model.createdAtMs)
        .toString()

    fun deserialize(
        raw: String?,
        expectedTransform: CoordinateTransform,
        expectedModelVersion: Int = PersonalizedGazeCalibrationModel.MODEL_VERSION,
        expectedFeatureSchemaVersion: Int = GazeCalibrationFeatureSchema.VERSION,
    ): CalibrationLoadResult {
        if (raw.isNullOrBlank()) return CalibrationLoadResult.Invalid("serialized model is empty")
        return try {
            val root = JSONObject(raw)
            val modelVersion = root.getInt("modelVersion")
            val featureVersion = root.getInt("featureSchemaVersion")
            val modelType = root.getString("modelType")
            val signature = root.getString("transformSignature")
            if (modelVersion != expectedModelVersion) return CalibrationLoadResult.Invalid("incompatible model version")
            if (featureVersion != expectedFeatureSchemaVersion) return CalibrationLoadResult.Invalid("incompatible feature schema version")
            if (modelType != PersonalizedGazeCalibrationModel.MODEL_TYPE) return CalibrationLoadResult.Invalid("incompatible model type")
            if (signature != expectedTransform.signature) return CalibrationLoadResult.Invalid("incompatible transform signature")

            val means = root.getJSONArray("means").toDoubleArrayStrict()
            val stds = root.getJSONArray("stdDevs").toDoubleArrayStrict()
            val cx = root.getJSONArray("coefficientsX").toDoubleArrayStrict()
            val cy = root.getJSONArray("coefficientsY").toDoubleArrayStrict()
            if (means.size != GazeCalibrationFeatureSchema.DIMENSION || stds.size != means.size) {
                return CalibrationLoadResult.Invalid("incompatible standardization dimensions")
            }
            if (cx.size != cy.size || cx.size != QuadraticPolynomialFeatures.size(means.size)) {
                return CalibrationLoadResult.Invalid("incompatible coefficient dimensions")
            }
            val width = root.optIntNullable("screenWidthPx")
            val height = root.optIntNullable("screenHeightPx")
            if ((width == null) != (height == null)) return CalibrationLoadResult.Invalid("screen dimensions must be paired")

            val model = PersonalizedGazeCalibrationModel(
                modelVersion = modelVersion,
                featureSchemaVersion = featureVersion,
                modelType = modelType,
                regularization = root.getDouble("regularization").also { require(it.isFinite()) },
                transformSignature = signature,
                standardization = Standardization(means, stds),
                coefficientsX = cx,
                coefficientsY = cy,
                trainingMetrics = metricsFromJson(root.getJSONObject("trainingMetrics")),
                validationMetrics = metricsFromJson(root.getJSONObject("validationMetrics")),
                screenWidthPx = width,
                screenHeightPx = height,
                createdAtMs = root.getLong("createdAtMs"),
            )
            CalibrationLoadResult.Loaded(model)
        } catch (e: Exception) {
            CalibrationLoadResult.Invalid(e.message ?: "corrupt or invalid serialized model")
        }
    }

    private fun metricsToJson(m: CalibrationMetrics): JSONObject = JSONObject()
        .put("meanNormalizedError", m.meanNormalizedError)
        .put("medianNormalizedError", m.medianNormalizedError)
        .put("p95NormalizedError", m.p95NormalizedError)
        .put("maxNormalizedError", m.maxNormalizedError)
        .put("horizontalMae", m.horizontalMae)
        .put("verticalMae", m.verticalMae)
        .put("sampleCount", m.sampleCount)
        .put("validationSampleCount", m.validationSampleCount)
        .put("meanPixelError", m.meanPixelError ?: JSONObject.NULL)
        .put("medianPixelError", m.medianPixelError ?: JSONObject.NULL)
        .put("p95PixelError", m.p95PixelError ?: JSONObject.NULL)
        .put("maxPixelError", m.maxPixelError ?: JSONObject.NULL)

    private fun metricsFromJson(j: JSONObject): CalibrationMetrics = CalibrationMetrics(
        j.getDouble("meanNormalizedError").also { require(it.isFinite()) },
        j.getDouble("medianNormalizedError").also { require(it.isFinite()) },
        j.getDouble("p95NormalizedError").also { require(it.isFinite()) },
        j.getDouble("maxNormalizedError").also { require(it.isFinite()) },
        j.getDouble("horizontalMae").also { require(it.isFinite()) },
        j.getDouble("verticalMae").also { require(it.isFinite()) },
        j.getInt("sampleCount"),
        j.getInt("validationSampleCount"),
        j.optDoubleNullable("meanPixelError"),
        j.optDoubleNullable("medianPixelError"),
        j.optDoubleNullable("p95PixelError"),
        j.optDoubleNullable("maxPixelError"),
    )

    private fun DoubleArray.toJsonArray(): JSONArray = JSONArray().also { array -> for (value in this) array.put(value.also { require(it.isFinite()) }) }
    private fun JSONArray.toDoubleArrayStrict(): DoubleArray = DoubleArray(length()) { i -> getDouble(i).also { require(it.isFinite()) } }
    private fun JSONObject.optDoubleNullable(key: String): Double? = if (isNull(key)) null else getDouble(key).also { require(it.isFinite()) }
    private fun JSONObject.optIntNullable(key: String): Int? = if (isNull(key)) null else getInt(key)
}
