package com.raclicker.app.turbo

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "RaClicker"

/**
 * TurboEngine — the core rapid-tap loop.
 *
 * Responsibilities:
 *  1. Keep a private coroutine scope backed by a SupervisorJob so that
 *     cancelling one tap loop never kills the engine itself.
 *  2. On [start]: launch a cancellable loop that calls [tapCallback] once per
 *     [intervalMs] milliseconds until [stop] is called.
 *  3. On [stop]: cancel the loop Job immediately — no waiting for the next
 *     interval tick. If the interval is 1 000 ms and the user releases after
 *     100 ms, the loop terminates within a few milliseconds.
 *
 * Thread-safety:
 *  All public methods are safe to call from any thread (the internal Job
 *  reference is @Volatile).
 *
 * The [tapCallback] receives the target coordinates and is responsible for
 * the actual gesture injection (handled by RaAccessibilityService).
 * TurboEngine itself is gesture-agnostic.
 *
 * Lifecycle note:
 *  Call [destroy] when the owning service is destroyed to cancel the scope
 *  and release resources.
 */
class TurboEngine {

    // ─── Internal coroutine scope ─────────────────────────────────────────

    private val engineJob   = SupervisorJob()
    private val engineScope = CoroutineScope(Dispatchers.Default + engineJob)

    // ─── Active tap-loop job ──────────────────────────────────────────────

    @Volatile
    private var tapJob: Job? = null

    // ─── Public state ─────────────────────────────────────────────────────

    val isRunning: Boolean
        get() = tapJob?.isActive == true

    // ─── Control API ──────────────────────────────────────────────────────

    /**
     * Start the rapid-tap loop.
     *
     * @param targetX    Screen X of the injection point (pixels).
     * @param targetY    Screen Y of the injection point (pixels).
     * @param intervalMs Delay between successive taps in milliseconds.
     * @param tapCallback  Called on each iteration with (x, y) — must be fast
     *                     (non-blocking). The actual gesture dispatch happens
     *                     inside the callback.
     */
    fun start(
        targetX: Float,
        targetY: Float,
        intervalMs: Int,
        tapCallback: (x: Float, y: Float) -> Unit
    ) {
        // Guard: don't stack loops
        if (isRunning) {
            Log.w(TAG, "TurboEngine.start() called while already running — ignored")
            return
        }

        Log.d(TAG, "Turbo started | target=($targetX, $targetY) | interval=${intervalMs}ms")

        tapJob = engineScope.launch {
            while (isActive) {
                tapCallback(targetX, targetY)
                Log.v(TAG, "Tap dispatched → ($targetX, $targetY)")
                // Delay is cancellable — coroutine exits immediately when Job
                // is cancelled, even if the delay hasn't elapsed yet.
                delay(intervalMs.toLong())
            }
            Log.d(TAG, "Turbo loop exited cleanly")
        }
    }

    /**
     * Stop the rapid-tap loop immediately.
     *
     * Cancels the tap-loop Job. Because [delay] inside the loop is cooperative,
     * the coroutine stops within microseconds — it does NOT wait for the next
     * interval boundary.
     */
    fun stop() {
        if (!isRunning) return
        tapJob?.cancel()
        tapJob = null
        Log.d(TAG, "Turbo stopped")
    }

    /**
     * Release the engine's coroutine scope.
     * Must be called from the owning Service's onDestroy().
     */
    fun destroy() {
        stop()
        engineJob.cancel()
        Log.d(TAG, "TurboEngine destroyed")
    }
}
