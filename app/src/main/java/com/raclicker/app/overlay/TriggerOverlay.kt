package com.raclicker.app.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.raclicker.app.R
import com.raclicker.app.accessibility.RaAccessibilityService

private const val TAG = "RaClicker"

/**
 * TriggerOverlay
 *
 * A single floating button drawn on top of all other windows via
 * WindowManager + TYPE_APPLICATION_OVERLAY.
 *
 * Drag behaviour
 * ──────────────
 * The button is draggable. We differentiate drag from tap using a threshold:
 * if the total displacement while the finger is down exceeds [DRAG_THRESHOLD_PX]
 * we treat the gesture as a drag and do NOT start/stop turbo — we only update
 * the window position.
 *
 * If displacement stays below the threshold the gesture is a "hold":
 *  ACTION_DOWN  → start turbo
 *  ACTION_MOVE  → continue (no action needed from overlay side)
 *  ACTION_UP    → stop turbo
 *  ACTION_CANCEL→ stop turbo
 *
 * Critical design note
 * ─────────────────────
 * WindowManager.LayoutParams uses FLAG_NOT_FOCUSABLE so the overlay never
 * steals keyboard focus from the target app. We also set FLAG_NOT_TOUCH_MODAL
 * so touches outside the button reach the underlying app.
 *
 * @param context   Service context (OverlayService)
 * @param wm        WindowManager obtained by the owning service
 * @param onPositionChanged  Called whenever the user finishes dragging so the
 *                           owning service can persist the new position.
 */
class TriggerOverlay(
    private val context: Context,
    private val wm: WindowManager,
    private val onPositionChanged: (x: Float, y: Float) -> Unit
) {

    // ─── View ─────────────────────────────────────────────────────────────────

    private val view: View = LayoutInflater.from(context)
        .inflate(R.layout.overlay_trigger, null)

    // ─── Layout params ────────────────────────────────────────────────────────

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 100
        y = 600
    }

    // ─── Drag state ───────────────────────────────────────────────────────────

    private var initialX = 0
    private var initialY = 0
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var isDragging  = false
    private var turboActive = false

    companion object {
        private const val DRAG_THRESHOLD_PX = 10f
    }

    // ─── Vibrator ─────────────────────────────────────────────────────────────

    private val vibrator: Vibrator by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    private fun vibrateShort() {
        try {
            vibrator.vibrate(
                VibrationEffect.createOneShot(30L, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        } catch (_: Exception) { /* vibration is non-critical */ }
    }

    // ─── Touch handling ───────────────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private val touchListener = View.OnTouchListener { _, event ->
        when (event.actionMasked) {

            MotionEvent.ACTION_DOWN -> {
                initialX    = params.x
                initialY    = params.y
                touchStartX = event.rawX
                touchStartY = event.rawY
                isDragging  = false
                // Tentatively start turbo — will cancel if it turns out to be a drag
                startTurbo()
                true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - touchStartX
                val dy = event.rawY - touchStartY

                if (!isDragging && (kotlin.math.abs(dx) > DRAG_THRESHOLD_PX ||
                            kotlin.math.abs(dy) > DRAG_THRESHOLD_PX)) {
                    // Transition to drag mode — stop turbo immediately
                    isDragging = true
                    stopTurbo()
                    Log.d(TAG, "Trigger drag started")
                }

                if (isDragging) {
                    params.x = (initialX + dx).toInt()
                    params.y = (initialY + dy).toInt()
                    try { wm.updateViewLayout(view, params) } catch (_: Exception) {}
                }
                true
            }

            MotionEvent.ACTION_UP -> {
                if (isDragging) {
                    // Persist new position
                    onPositionChanged(params.x.toFloat(), params.y.toFloat())
                    Log.d(TAG, "Trigger moved to (${params.x}, ${params.y})")
                } else {
                    stopTurbo()
                }
                isDragging = false
                true
            }

            MotionEvent.ACTION_CANCEL -> {
                Log.d(TAG, "Trigger ACTION_CANCEL — stopping turbo")
                stopTurbo()
                isDragging = false
                true
            }

            else -> false
        }
    }

    // ─── Turbo control ────────────────────────────────────────────────────────

    private fun startTurbo() {
        val service = RaAccessibilityService.instance
        if (service == null) {
            Log.w(TAG, "startTurbo: AccessibilityService not connected")
            return
        }
        val overlayService = OverlayService.instance
        if (overlayService == null) {
            Log.w(TAG, "startTurbo: OverlayService not available")
            return
        }
        val settings = overlayService.currentSettings
        if (!settings.isTargetSet) {
            Log.w(TAG, "startTurbo: target not set")
            return
        }
        vibrateShort()
        turboActive = true
        service.startTurbo(settings.targetX, settings.targetY, settings.tapIntervalMs)
        Log.d(TAG, "Turbo started | target=(${settings.targetX}, ${settings.targetY}) | interval=${settings.tapIntervalMs}ms")
    }

    private fun stopTurbo() {
        if (!turboActive) return
        turboActive = false
        RaAccessibilityService.instance?.stopTurbo()
        Log.d(TAG, "Turbo stopped")
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    fun attach(x: Int = params.x, y: Int = params.y) {
        params.x = x
        params.y = y
        view.setOnTouchListener(touchListener)
        wm.addView(view, params)
        Log.d(TAG, "TriggerOverlay attached at ($x, $y)")
    }

    fun detach() {
        stopTurbo()
        try {
            view.setOnTouchListener(null)
            wm.removeView(view)
        } catch (_: Exception) {}
        Log.d(TAG, "TriggerOverlay detached")
    }

    /** Update position programmatically (e.g. after restoring saved position). */
    fun moveTo(x: Int, y: Int) {
        params.x = x
        params.y = y
        try { wm.updateViewLayout(view, params) } catch (_: Exception) {}
    }

    val currentX: Int get() = params.x
    val currentY: Int get() = params.y
}
