package com.raclicker.app.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import com.raclicker.app.turbo.TurboEngine

private const val TAG = "RaClicker"

/**
 * RaAccessibilityService
 *
 * This service does two things:
 *
 * 1. Exposes a singleton [instance] so other components (OverlayService,
 *    TriggerOverlay) can call [injectTap] directly without IPC.
 *
 * 2. Hosts the [TurboEngine] and wires its tap callback to [injectTap] so
 *    every engine tick produces a real DOWN→UP gesture via [dispatchGesture].
 *
 * Touch injection strategy
 * ─────────────────────────
 * Each "tap" is modelled as a GestureDescription containing one StrokeDescription:
 *
 *   Path  : single point (moveTo target)
 *   Start : 0 ms
 *   Duration: TAP_DURATION_MS (short, to look like a real tap)
 *
 * dispatchGesture() converts the stroke to ACTION_DOWN at t=0 and ACTION_UP
 * at t=TAP_DURATION_MS. This is a real touch event seen by the target app —
 * not a performClick() accessibility action.
 *
 * The GestureResultCallback is intentionally minimal; we only log failures.
 * We do NOT wait for the callback before issuing the next tap — the engine
 * controls timing via its coroutine delay, which is acceptable because
 * dispatchGesture is asynchronous by design.
 *
 * Inter-component communication
 * ──────────────────────────────
 * OverlayService sends a LocalBroadcast with action [ACTION_STOP_TURBO] when
 * the overlay service is destroyed, ensuring turbo stops if the service dies.
 * The engine is also exposed directly for in-process calls.
 */
class RaAccessibilityService : AccessibilityService() {

    // ─── Engine ───────────────────────────────────────────────────────────────

    val turboEngine = TurboEngine()

    // ─── Broadcast receiver (stop turbo from other components) ───────────────

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_STOP_TURBO) {
                turboEngine.stop()
                Log.d(TAG, "Turbo stopped via broadcast")
            }
        }
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Accessibility connected")

        // Register stop-turbo receiver
        val filter = IntentFilter(ACTION_STOP_TURBO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stopReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(stopReceiver, filter)
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.d(TAG, "Accessibility disconnected")
        turboEngine.stop()
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        turboEngine.destroy()
        try { unregisterReceiver(stopReceiver) } catch (_: Exception) {}
        instance = null
        super.onDestroy()
        Log.d(TAG, "Accessibility service destroyed")
    }

    // AccessibilityService requires this override even if we don't use events
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() {
        turboEngine.stop()
        Log.d(TAG, "Accessibility interrupted — Turbo stopped")
    }

    // ─── Touch injection ──────────────────────────────────────────────────────

    /**
     * Inject a single DOWN→UP tap at ([x], [y]) using dispatchGesture.
     *
     * This is the callback supplied to [TurboEngine.start]. It runs on
     * Dispatchers.Default (engine's coroutine thread) — dispatchGesture is
     * thread-safe and internally posts to the main looper.
     *
     * TAP_DURATION_MS is kept intentionally short (1 ms) so the gesture looks
     * like a quick tap rather than a long-press to the target app. Games and
     * apps distinguish taps from long-presses by duration threshold (~500 ms),
     * so 1 ms is always interpreted as a tap.
     */
    fun injectTap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }

        val stroke = GestureDescription.StrokeDescription(
            path,
            /* startTime  */ 0L,
            /* duration   */ TAP_DURATION_MS
        )

        val gesture = GestureDescription.Builder()
            .addStroke(stroke)
            .build()

        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "Gesture cancelled at ($x, $y)")
            }
            override fun onCompleted(gestureDescription: GestureDescription?) {
                // No-op — engine timing is not driven by gesture completion
            }
        }, null /* handler — null means main thread */)

        if (!dispatched) {
            Log.w(TAG, "dispatchGesture returned false at ($x, $y) — service may be disconnecting")
        }
    }

    /**
     * Start the turbo loop.
     * Called by TriggerOverlay on ACTION_DOWN.
     */
    fun startTurbo(targetX: Float, targetY: Float, intervalMs: Int) {
        Log.d(TAG, "Turbo started | target=($targetX, $targetY) | interval=${intervalMs}ms")
        turboEngine.start(targetX, targetY, intervalMs) { x, y ->
            injectTap(x, y)
        }
    }

    /**
     * Stop the turbo loop.
     * Called by TriggerOverlay on ACTION_UP / ACTION_CANCEL.
     */
    fun stopTurbo() {
        turboEngine.stop()
        Log.d(TAG, "Turbo stopped")
    }

    val isTurboRunning: Boolean get() = turboEngine.isRunning

    // ─── Companion ────────────────────────────────────────────────────────────

    companion object {
        /** Tap duration in ms — short enough to be read as a tap, not a long-press. */
        private const val TAP_DURATION_MS = 1L

        /** Broadcast action to force-stop turbo from outside the service. */
        const val ACTION_STOP_TURBO = "com.raclicker.app.ACTION_STOP_TURBO"

        /**
         * Singleton reference — non-null only while the Accessibility Service
         * is connected. Always null-check before using.
         */
        @Volatile
        var instance: RaAccessibilityService? = null
            private set

        /** True if the service is currently connected and available. */
        val isConnected: Boolean get() = instance != null
    }
}
