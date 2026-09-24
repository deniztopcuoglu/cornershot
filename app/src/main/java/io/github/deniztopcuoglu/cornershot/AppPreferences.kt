package io.github.deniztopcuoglu.cornershot

import android.content.Context
import androidx.core.content.edit

enum class CaptureCorner {
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT;

    companion object {
        fun fromStored(value: String?): CaptureCorner =
            entries.firstOrNull { it.name == value } ?: BOTTOM_RIGHT
    }
}

/** Small, synchronous preferences used by the activity and accessibility service. */
object AppPreferences {
    private const val FILE_NAME = "corner_shot_preferences"
    private const val KEY_CAPTURE_ENABLED = "capture_button_enabled"
    private const val KEY_LEGACY_CAPTURE_CORNER = "capture_button_corner"
    private const val KEY_OVERLAY_X = "capture_button_normalized_x"
    private const val KEY_OVERLAY_Y = "capture_button_normalized_y"
    private const val KEY_CAPTURE_BUTTON_SIZE_DP = "capture_button_size_dp"
    private const val KEY_CAPTURE_ACTIVE_AT = "capture_workflow_active_at"
    private const val KEY_SELECTION_LAUNCHED = "capture_selection_launched"
    private const val ACTIVE_TIMEOUT_MS = 12L * 60L * 60L * 1000L

    private fun prefs(context: Context) = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun captureButtonEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_CAPTURE_ENABLED, false)

    fun setCaptureButtonEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_CAPTURE_ENABLED, enabled).apply()
    }

    fun captureButtonSizeDp(context: Context): Int {
        val preferences = prefs(context)
        val stored = preferences.getInt(KEY_CAPTURE_BUTTON_SIZE_DP, ButtonSizeGeometry.DEFAULT_VISIBLE_DP)
        val sanitized = ButtonSizeGeometry.sanitizeVisibleSizeDp(stored)
        if (stored != sanitized) {
            preferences.edit { putInt(KEY_CAPTURE_BUTTON_SIZE_DP, sanitized) }
        }
        return sanitized
    }

    fun setCaptureButtonSizeDp(context: Context, sizeDp: Int) {
        prefs(context).edit {
            putInt(KEY_CAPTURE_BUTTON_SIZE_DP, ButtonSizeGeometry.sanitizeVisibleSizeDp(sizeDp))
        }
    }

    internal fun normalizedOverlayPosition(context: Context): NormalizedOverlayPosition {
        val preferences = prefs(context)
        if (!preferences.contains(KEY_OVERLAY_X) || !preferences.contains(KEY_OVERLAY_Y)) {
            val legacyCorner = CaptureCorner.fromStored(preferences.getString(KEY_LEGACY_CAPTURE_CORNER, null))
            val migrated = OverlayPositionGeometry.preset(legacyCorner)
            preferences.edit {
                putFloat(KEY_OVERLAY_X, migrated.x)
                putFloat(KEY_OVERLAY_Y, migrated.y)
                remove(KEY_LEGACY_CAPTURE_CORNER)
            }
            return migrated
        }
        return OverlayPositionGeometry.clampNormalized(
            NormalizedOverlayPosition(
                x = preferences.getFloat(KEY_OVERLAY_X, DEFAULT_OVERLAY_POSITION.x),
                y = preferences.getFloat(KEY_OVERLAY_Y, DEFAULT_OVERLAY_POSITION.y)
            )
        )
    }

    internal fun setNormalizedOverlayPosition(context: Context, position: NormalizedOverlayPosition) {
        val normalized = OverlayPositionGeometry.clampNormalized(position)
        prefs(context).edit {
            putFloat(KEY_OVERLAY_X, normalized.x)
            putFloat(KEY_OVERLAY_Y, normalized.y)
            remove(KEY_LEGACY_CAPTURE_CORNER)
        }
    }

    internal fun setOverlayPositionPreset(context: Context, corner: CaptureCorner) {
        setNormalizedOverlayPosition(context, OverlayPositionGeometry.preset(corner))
    }

    fun beginCaptureWorkflow(context: Context) {
        prefs(context).edit()
            .putLong(KEY_CAPTURE_ACTIVE_AT, System.currentTimeMillis())
            .putBoolean(KEY_SELECTION_LAUNCHED, false)
            .commit()
    }

    fun markSelectionLaunched(context: Context) {
        prefs(context).edit().putBoolean(KEY_SELECTION_LAUNCHED, true).commit()
    }

    fun selectionLaunched(context: Context): Boolean = prefs(context).getBoolean(KEY_SELECTION_LAUNCHED, false)

    fun captureWorkflowStarted(context: Context): Boolean = prefs(context).getLong(KEY_CAPTURE_ACTIVE_AT, 0L) != 0L

    fun clearCaptureWorkflow(context: Context) {
        prefs(context).edit()
            .remove(KEY_CAPTURE_ACTIVE_AT)
            .remove(KEY_SELECTION_LAUNCHED)
            .commit()
    }

    fun finishCaptureWorkflow(context: Context) {
        clearCaptureWorkflow(context)
    }

    fun captureWorkflowActive(context: Context): Boolean {
        val activeAt = prefs(context).getLong(KEY_CAPTURE_ACTIVE_AT, 0L)
        if (activeAt == 0L) return false
        if (System.currentTimeMillis() - activeAt > ACTIVE_TIMEOUT_MS) {
            finishCaptureWorkflow(context)
            return false
        }
        return true
    }

    private val DEFAULT_OVERLAY_POSITION = OverlayPositionGeometry.preset(CaptureCorner.BOTTOM_RIGHT)
}
