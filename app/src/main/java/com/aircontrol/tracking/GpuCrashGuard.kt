package com.aircontrol.tracking

import android.content.Context
import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger

/**
 * Detects GPU delegate crashes that kill the whole process.
 *
 * OEM GPU drivers can abort inside native MediaPipe code (SIGSEGV/SIGABRT). Kotlin cannot catch
 * that, so the app simply disappears. Before every GPU attempt a "pending" flag is written to
 * disk synchronously. It is cleared after the GPU graph has produced [STABLE_RESULTS] results or
 * on a graceful close. If the flag is still set on the next attempt, the previous GPU session
 * killed the process, and GPU is blocked permanently for that tracker on this device.
 */
internal class GpuCrashGuard(context: Context, private val key: String) {
    private val prefs = runCatching {
        context.applicationContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?: context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }.getOrNull()
    private val results = AtomicInteger(0)
    @Volatile private var pending = false

    val isBlocked: Boolean
        get() = prefs?.getBoolean("${key}_blocked", false) == true

    /** Returns true if a GPU attempt is allowed now. */
    fun beforeGpuAttempt(): Boolean {
        val p = prefs ?: return false
        if (p.getBoolean("${key}_blocked", false)) return false
        if (p.getBoolean("${key}_pending", false)) {
            Timber.w("GPU delegate for %s crashed the app last time; using CPU from now on", key)
            p.edit().putBoolean("${key}_blocked", true).putBoolean("${key}_pending", false).commit()
            return false
        }
        results.set(0)
        pending = true
        // commit(), not apply(): the flag must be on disk before native code can crash.
        p.edit().putBoolean("${key}_pending", true).commit()
        return true
    }

    fun onGpuResult() {
        if (!pending) return
        if (results.incrementAndGet() >= STABLE_RESULTS) clearPending()
    }

    fun onGracefulClose() {
        if (pending) clearPending()
    }

    fun markFailed() {
        pending = false
        prefs?.edit()?.putBoolean("${key}_blocked", true)?.putBoolean("${key}_pending", false)?.apply()
    }

    private fun clearPending() {
        pending = false
        prefs?.edit()?.putBoolean("${key}_pending", false)?.apply()
    }

    companion object {
        private const val PREFS = "aircontrol_gpu_guard"
        private const val STABLE_RESULTS = 60
    }
}
