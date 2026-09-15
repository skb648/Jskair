package com.aircontrol.runtime

import timber.log.Timber
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Startup-stage diagnostics that survive a release build.
 *
 * Root-cause context (release-only "accessibility service does nothing"):
 * every startup step of the accessibility service, the camera service and the
 * MediaPipe trackers logged through `Timber.d`/`Timber.i`. In release, `Timber.d`
 * is removed by R8 (`-assumenosideeffects`) and `Timber.i` is dropped by
 * `ReleaseTree` (WARN+). The result was a service whose entire boot sequence was
 * invisible in logcat, so a stuck stage (DI, overlay, foreground start, model
 * load, camera bind) was indistinguishable from "nothing happened".
 *
 * Every record here is emitted at WARN (success) or ERROR (failure) under the
 * single tag [TAG], so `adb logcat -s AirControlStage` tells the full story on a
 * signed release build. Records also go into a small bounded ring for the debug
 * screen / bug reports. No per-frame callers: this is boot/lifecycle only.
 */
object StageLog {

    const val TAG = "AirControlStage"

    /** Canonical stage names. Keep these stable: they are what people grep for. */
    object Stage {
        const val SERVICE_CREATE = "SERVICE_CREATE"
        const val SERVICE_CONNECTED = "SERVICE_CONNECTED"
        const val DI_INIT = "DI_INIT"
        const val DISPATCHER_ATTACH = "DISPATCHER_ATTACH"
        const val OVERLAY_INIT = "OVERLAY_INIT"
        const val PIPELINE_INIT = "PIPELINE_INIT"
        const val SERVICE_READY = "SERVICE_READY"
        const val CAMERA_INIT_FOREGROUND = "CAMERA_INIT_FOREGROUND"
        const val CAMERA_INIT_BIND = "CAMERA_INIT_BIND"
        const val CAMERA_INIT_RUNNING = "CAMERA_INIT_RUNNING"
        const val CAMERA_INIT_DEFERRED = "CAMERA_INIT_DEFERRED"
        const val HAND_TRACKER_INIT = "HAND_TRACKER_INIT"
        const val FACE_TRACKER_INIT = "FACE_TRACKER_INIT"
    }

    /** One stage transition. [ok] false means the stage failed or was blocked. */
    data class Record(
        val stage: String,
        val component: String,
        val ok: Boolean,
        val detail: String?,
        val errorClass: String?,
        val errorMessage: String?,
        val elapsedRealtimeMs: Long,
    ) {
        fun format(): String = buildString {
            append(stage)
            append(' ')
            append(if (ok) "OK" else "FAIL")
            append(" component=")
            append(component)
            if (!detail.isNullOrBlank()) {
                append(" detail=")
                append(detail)
            }
            if (errorClass != null) {
                append(" error=")
                append(errorClass)
                if (!errorMessage.isNullOrBlank()) {
                    append(": ")
                    append(errorMessage)
                }
            }
        }
    }

    private const val RECENT_LIMIT = 48

    private val recent = ConcurrentLinkedDeque<Record>()

    /** Newest first. */
    val recentRecords: List<Record> get() = recent.toList()

    /**
     * Replaceable output for JVM tests. Defaults to Timber under [TAG] at WARN
     * (success) / ERROR (failure), which passes both the R8 strip list and
     * `ReleaseTree`.
     */
    @Volatile
    var sink: (Record, Throwable?) -> Unit = { record, error ->
        val tree = Timber.tag(TAG)
        if (record.ok) tree.w(record.format())
        else if (error != null) tree.e(error, record.format())
        else tree.e(record.format())
    }

    @Volatile
    var clock: () -> Long = { android.os.SystemClock.elapsedRealtime() }

    fun success(stage: String, component: String, detail: String? = null) {
        emit(stage, component, true, detail, null)
    }

    fun failure(stage: String, component: String, detail: String? = null, error: Throwable? = null) {
        emit(stage, component, false, detail, error)
    }

    private fun emit(stage: String, component: String, ok: Boolean, detail: String?, error: Throwable?) {
        val record = Record(
            stage = stage,
            component = component,
            ok = ok,
            detail = detail,
            errorClass = error?.javaClass?.name,
            errorMessage = error?.message,
            elapsedRealtimeMs = clock(),
        )
        recent.addFirst(record)
        while (recent.size > RECENT_LIMIT) recent.pollLast()
        sink(record, error)
    }

    /** Test hook. */
    internal fun clearForTest() {
        recent.clear()
    }
}
