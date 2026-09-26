package com.raclicker.app.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// ─── DataStore singleton (one per process) ───────────────────────────────────
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "ra_clicker_prefs")

/**
 * All persisted settings for Ra Clicker.
 *
 * Stored values:
 *  - Target X / Y  : screen coordinates where taps will be injected
 *  - Trigger X / Y : last position of the floating trigger button
 *  - Tap interval  : milliseconds between successive DOWN→UP tap cycles
 *
 * Coordinates are stored as Float (fraction of screen width/height is NOT used
 * here — raw pixel values are stored so the overlay restores to the exact pixel).
 *
 * All reads are exposed as cold Flows so the UI can observe changes reactively.
 */
class SettingsRepository(private val context: Context) {

    // ─── Preference keys ─────────────────────────────────────────────────────

    companion object {
        val KEY_TARGET_X       = floatPreferencesKey("target_x")
        val KEY_TARGET_Y       = floatPreferencesKey("target_y")
        val KEY_TRIGGER_X      = floatPreferencesKey("trigger_x")
        val KEY_TRIGGER_Y      = floatPreferencesKey("trigger_y")
        val KEY_TAP_INTERVAL   = intPreferencesKey("tap_interval_ms")
        val KEY_TARGET_SET     = intPreferencesKey("target_set")   // 0 = not set, 1 = set

        // Sensible defaults
        const val DEFAULT_TAP_INTERVAL_MS = 50
        const val DEFAULT_TARGET_X        = -1f   // sentinel: not set
        const val DEFAULT_TARGET_Y        = -1f
        const val DEFAULT_TRIGGER_X       = 100f
        const val DEFAULT_TRIGGER_Y       = 600f
    }

    // ─── Read flows ──────────────────────────────────────────────────────────

    val targetX: Flow<Float> = context.dataStore.data
        .map { it[KEY_TARGET_X] ?: DEFAULT_TARGET_X }

    val targetY: Flow<Float> = context.dataStore.data
        .map { it[KEY_TARGET_Y] ?: DEFAULT_TARGET_Y }

    val triggerX: Flow<Float> = context.dataStore.data
        .map { it[KEY_TRIGGER_X] ?: DEFAULT_TRIGGER_X }

    val triggerY: Flow<Float> = context.dataStore.data
        .map { it[KEY_TRIGGER_Y] ?: DEFAULT_TRIGGER_Y }

    val tapIntervalMs: Flow<Int> = context.dataStore.data
        .map { it[KEY_TAP_INTERVAL] ?: DEFAULT_TAP_INTERVAL_MS }

    /** True when the user has explicitly set a target coordinate. */
    val isTargetSet: Flow<Boolean> = context.dataStore.data
        .map { (it[KEY_TARGET_SET] ?: 0) == 1 }

    // Convenience: snapshot of all settings in one object
    val settings: Flow<RaSettings> = context.dataStore.data.map { prefs ->
        RaSettings(
            targetX       = prefs[KEY_TARGET_X]     ?: DEFAULT_TARGET_X,
            targetY       = prefs[KEY_TARGET_Y]     ?: DEFAULT_TARGET_Y,
            triggerX      = prefs[KEY_TRIGGER_X]    ?: DEFAULT_TRIGGER_X,
            triggerY      = prefs[KEY_TRIGGER_Y]    ?: DEFAULT_TRIGGER_Y,
            tapIntervalMs = prefs[KEY_TAP_INTERVAL] ?: DEFAULT_TAP_INTERVAL_MS,
            isTargetSet   = (prefs[KEY_TARGET_SET]  ?: 0) == 1
        )
    }

    // ─── Write helpers ───────────────────────────────────────────────────────

    suspend fun saveTarget(x: Float, y: Float) {
        context.dataStore.edit { prefs ->
            prefs[KEY_TARGET_X]   = x
            prefs[KEY_TARGET_Y]   = y
            prefs[KEY_TARGET_SET] = 1
        }
    }

    suspend fun saveTriggerPosition(x: Float, y: Float) {
        context.dataStore.edit { prefs ->
            prefs[KEY_TRIGGER_X] = x
            prefs[KEY_TRIGGER_Y] = y
        }
    }

    suspend fun saveTapInterval(intervalMs: Int) {
        context.dataStore.edit { prefs ->
            prefs[KEY_TAP_INTERVAL] = intervalMs.coerceIn(10, 5000)
        }
    }

    suspend fun clearTarget() {
        context.dataStore.edit { prefs ->
            prefs[KEY_TARGET_X]   = DEFAULT_TARGET_X
            prefs[KEY_TARGET_Y]   = DEFAULT_TARGET_Y
            prefs[KEY_TARGET_SET] = 0
        }
    }
}

// ─── Immutable snapshot data class ───────────────────────────────────────────

/**
 * Snapshot of all persisted settings — passed between components via
 * intents or in-memory shared state so no DataStore read is needed at runtime.
 */
data class RaSettings(
    val targetX: Float       = SettingsRepository.DEFAULT_TARGET_X,
    val targetY: Float       = SettingsRepository.DEFAULT_TARGET_Y,
    val triggerX: Float      = SettingsRepository.DEFAULT_TRIGGER_X,
    val triggerY: Float      = SettingsRepository.DEFAULT_TRIGGER_Y,
    val tapIntervalMs: Int   = SettingsRepository.DEFAULT_TAP_INTERVAL_MS,
    val isTargetSet: Boolean = false
) {
    /** Derived: taps per second for display purposes. */
    val tapsPerSecond: Float
        get() = if (tapIntervalMs > 0) 1000f / tapIntervalMs else 0f
}
