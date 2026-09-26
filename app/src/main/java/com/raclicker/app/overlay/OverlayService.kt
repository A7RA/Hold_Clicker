package com.raclicker.app.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.raclicker.app.MainActivity
import com.raclicker.app.R
import com.raclicker.app.accessibility.RaAccessibilityService
import com.raclicker.app.settings.RaSettings
import com.raclicker.app.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val TAG = "RaClicker"

/**
 * OverlayService — foreground service that owns and manages all overlay windows.
 *
 * Responsibilities:
 *  1. Run as a foreground service with a persistent notification (required by
 *     Android to keep the process alive while overlays are shown).
 *  2. Create and attach [TriggerOverlay] and [TargetOverlay] to WindowManager.
 *  3. Hold the latest [RaSettings] snapshot so [TriggerOverlay] can read it
 *     synchronously without suspending during a touch event.
 *  4. Expose commands via Intent actions so MainActivity can control the service:
 *      - ACTION_START          : start service, attach overlays
 *      - ACTION_STOP           : stop overlays, stop foreground
 *      - ACTION_SET_TARGET     : enter target-placement mode
 *      - ACTION_UPDATE_INTERVAL: update tap interval live
 *
 * Singleton access
 * ─────────────────
 * [instance] provides a live reference for in-process callers (TriggerOverlay).
 * Always null-check; the service may not be running.
 */
class OverlayService : Service() {

    // ─── Overlay objects ──────────────────────────────────────────────────────

    private lateinit var wm: WindowManager
    private var triggerOverlay: TriggerOverlay? = null
    private var targetOverlay: TargetOverlay? = null

    // ─── Settings ─────────────────────────────────────────────────────────────

    private lateinit var repo: SettingsRepository

    /** Latest snapshot — updated whenever settings change, read synchronously
     *  by TriggerOverlay during touch events. */
    @Volatile
    var currentSettings: RaSettings = RaSettings()
        private set

    // ─── Coroutine scope ──────────────────────────────────────────────────────

    private val serviceJob   = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Main + serviceJob)

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        instance = this
        wm   = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        repo = SettingsRepository(applicationContext)

        // Keep currentSettings up-to-date reactively
        serviceScope.launch {
            repo.settings.collect { settings ->
                currentSettings = settings
                // If target indicator is visible, update its position
                if (settings.isTargetSet) {
                    targetOverlay?.showIndicator(settings.targetX, settings.targetY)
                }
            }
        }

        Log.d(TAG, "OverlayService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START           -> handleStart()
            ACTION_STOP            -> handleStop()
            ACTION_SET_TARGET      -> handleSetTarget()
            ACTION_UPDATE_INTERVAL -> {
                val ms = intent.getIntExtra(EXTRA_INTERVAL_MS, 50)
                handleUpdateInterval(ms)
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d(TAG, "OverlayService destroyed")
        // Stop turbo via broadcast (service may already be gone by the time
        // AccessibilityService processes this, but belt-and-suspenders)
        sendBroadcast(Intent(RaAccessibilityService.ACTION_STOP_TURBO).apply {
            setPackage(packageName)
        })
        detachOverlays()
        serviceJob.cancel()
        instance = null
        super.onDestroy()
    }

    // ─── Command handlers ─────────────────────────────────────────────────────

    private fun handleStart() {
        startForegroundWithNotification()
        attachOverlays()
        Log.d(TAG, "OverlayService started — overlays attached")
    }

    private fun handleStop() {
        detachOverlays()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.d(TAG, "OverlayService stopped by command")
    }

    private fun handleSetTarget() {
        // Show placement overlay; when user taps, save coords and show indicator
        targetOverlay?.showPlacementMode(
            onTargetSelected = { x, y ->
                serviceScope.launch {
                    repo.saveTarget(x, y)
                    Log.d(TAG, "Target saved at ($x, $y)")
                    // Notify MainActivity so UI refreshes
                    sendBroadcast(Intent(ACTION_TARGET_UPDATED).apply {
                        setPackage(packageName)
                        putExtra(EXTRA_TARGET_X, x)
                        putExtra(EXTRA_TARGET_Y, y)
                    })
                }
            },
            onCancelled = {
                Log.d(TAG, "Target placement cancelled")
            }
        )
    }

    private fun handleUpdateInterval(ms: Int) {
        serviceScope.launch {
            repo.saveTapInterval(ms)
            Log.d(TAG, "Tap interval updated to ${ms}ms")
        }
    }

    // ─── Overlay management ───────────────────────────────────────────────────

    private fun attachOverlays() {
        // Load saved position then attach
        serviceScope.launch {
            val settings = repo.settings.first()
            currentSettings = settings

            // Trigger overlay
            if (triggerOverlay == null) {
                triggerOverlay = TriggerOverlay(
                    context = this@OverlayService,
                    wm      = wm,
                    onPositionChanged = { x, y ->
                        serviceScope.launch { repo.saveTriggerPosition(x, y) }
                    }
                ).also { overlay ->
                    overlay.attach(
                        x = settings.triggerX.toInt(),
                        y = settings.triggerY.toInt()
                    )
                }
            }

            // Target overlay + indicator if target is already set
            if (targetOverlay == null) {
                targetOverlay = TargetOverlay(
                    context = this@OverlayService,
                    wm      = wm
                )
            }
            if (settings.isTargetSet) {
                targetOverlay?.showIndicator(settings.targetX, settings.targetY)
            }
        }
    }

    private fun detachOverlays() {
        triggerOverlay?.detach()
        triggerOverlay = null
        targetOverlay?.detach()
        targetOverlay = null
        Log.d(TAG, "Overlays detached")
    }

    // ─── Foreground notification ──────────────────────────────────────────────

    private fun startForegroundWithNotification() {
        createNotificationChannel()

        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, OverlayService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_trigger)
            .setContentIntent(pendingIntent)
            .addAction(
                R.drawable.ic_stop,
                getString(R.string.notification_stop),
                stopIntent
            )
            .setOngoing(true)
            .setSilent(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    // ─── Companion ────────────────────────────────────────────────────────────

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID      = "ra_clicker_overlay"

        const val ACTION_START           = "com.raclicker.app.OVERLAY_START"
        const val ACTION_STOP            = "com.raclicker.app.OVERLAY_STOP"
        const val ACTION_SET_TARGET      = "com.raclicker.app.SET_TARGET"
        const val ACTION_UPDATE_INTERVAL = "com.raclicker.app.UPDATE_INTERVAL"
        const val ACTION_TARGET_UPDATED  = "com.raclicker.app.TARGET_UPDATED"

        const val EXTRA_INTERVAL_MS = "interval_ms"
        const val EXTRA_TARGET_X    = "target_x"
        const val EXTRA_TARGET_Y    = "target_y"

        @Volatile
        var instance: OverlayService? = null
            private set

        val isRunning: Boolean get() = instance != null

        // ── Convenience start/stop helpers for MainActivity ──────────────────

        fun start(context: Context) {
            val intent = Intent(context, OverlayService::class.java).apply {
                action = ACTION_START
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, OverlayService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun requestSetTarget(context: Context) {
            val intent = Intent(context, OverlayService::class.java).apply {
                action = ACTION_SET_TARGET
            }
            context.startService(intent)
        }

        fun updateInterval(context: Context, intervalMs: Int) {
            val intent = Intent(context, OverlayService::class.java).apply {
                action = ACTION_UPDATE_INTERVAL
                putExtra(EXTRA_INTERVAL_MS, intervalMs)
            }
            context.startService(intent)
        }
    }
}
