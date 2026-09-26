package com.raclicker.app.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.raclicker.app.R

private const val TAG = "RaClicker"

/**
 * TargetOverlay
 *
 * A floating crosshair/pin that shows the user exactly where taps will land.
 *
 * Modes:
 *  - PLACEMENT mode (isPlacementMode = true):
 *      The overlay covers the full screen with a semi-transparent touch-interceptor.
 *      Wherever the user taps, that coordinate becomes the new target.
 *      After one tap the overlay dismisses itself and calls [onTargetSelected].
 *
 *  - INDICATOR mode (isPlacementMode = false):
 *      A small crosshair icon floating at the saved target coordinate.
 *      It is NOT interactive — touches pass through to the underlying app
 *      (FLAG_NOT_TOUCHABLE).
 *      This lets the user see the target while holding the trigger.
 *
 * Coordinate note
 * ───────────────
 * Coordinates stored in settings are raw screen pixels from the top-left
 * corner of the display (same space as WindowManager x/y in Gravity.TOP|START).
 * This is exactly what dispatchGesture expects, so no conversion is needed.
 */
class TargetOverlay(
    private val context: Context,
    private val wm: WindowManager
) {

    // ─── Placement overlay (full-screen touch catcher) ────────────────────────

    private var placementView: View? = null
    private val placementParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
    }

    // ─── Indicator overlay (small crosshair at target) ────────────────────────

    private var indicatorView: View? = null
    private val indicatorParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
    }

    // ─── Placement mode ───────────────────────────────────────────────────────

    /**
     * Show the full-screen placement overlay.
     * The user taps anywhere to set the target.
     * [onTargetSelected] is called with the raw screen x,y of the tap.
     * [onCancelled] is called if the user cancels (back button or external dismiss).
     */
    @SuppressLint("ClickableViewAccessibility")
    fun showPlacementMode(
        onTargetSelected: (x: Float, y: Float) -> Unit,
        onCancelled: () -> Unit = {}
    ) {
        removePlacementView()

        val v = LayoutInflater.from(context)
            .inflate(R.layout.overlay_target_placement, null)

        v.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                val rawX = event.rawX
                val rawY = event.rawY
                Log.d(TAG, "Target selected at ($rawX, $rawY)")
                removePlacementView()
                onTargetSelected(rawX, rawY)
                true
            } else false
        }

        placementView = v
        wm.addView(v, placementParams)
        Log.d(TAG, "TargetOverlay placement mode shown")
    }

    fun dismissPlacementMode() {
        removePlacementView()
    }

    private fun removePlacementView() {
        placementView?.let {
            try {
                it.setOnTouchListener(null)
                wm.removeView(it)
            } catch (_: Exception) {}
            placementView = null
        }
    }

    // ─── Indicator mode ───────────────────────────────────────────────────────

    /**
     * Show (or update) the crosshair indicator at ([x], [y]).
     * Coordinates are raw screen pixels.
     */
    fun showIndicator(x: Float, y: Float) {
        val ix = x.toInt()
        val iy = y.toInt()

        if (indicatorView == null) {
            val v = LayoutInflater.from(context)
                .inflate(R.layout.overlay_target_indicator, null)
            indicatorView = v
            indicatorParams.x = ix
            indicatorParams.y = iy
            wm.addView(v, indicatorParams)
            Log.d(TAG, "Target indicator shown at ($ix, $iy)")
        } else {
            indicatorParams.x = ix
            indicatorParams.y = iy
            try { wm.updateViewLayout(indicatorView, indicatorParams) } catch (_: Exception) {}
        }
    }

    fun hideIndicator() {
        indicatorView?.let {
            try { wm.removeView(it) } catch (_: Exception) {}
            indicatorView = null
            Log.d(TAG, "Target indicator hidden")
        }
    }

    // ─── Full detach ──────────────────────────────────────────────────────────

    fun detach() {
        removePlacementView()
        hideIndicator()
    }

    val isPlacementModeActive: Boolean get() = placementView != null
    val isIndicatorVisible: Boolean     get() = indicatorView != null
}
