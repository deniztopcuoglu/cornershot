package io.github.deniztopcuoglu.cornershot

import android.content.Context

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
    private const val KEY_CAPTURE_CORNER = "capture_button_corner"
    private const val KEY_CAPTURE_ACTIVE_AT = "capture_workflow_active_at"
    private const val KEY_SELECTION_LAUNCHED = "capture_selection_launched"
    private const val ACTIVE_TIMEOUT_MS = 12L * 60L * 60L * 1000L

    private fun prefs(context: Context) = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun captureButtonEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_CAPTURE_ENABLED, false)

    fun setCaptureButtonEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_CAPTURE_ENABLED, enabled).apply()
    }

    fun captureCorner(context: Context): CaptureCorner =
        CaptureCorner.fromStored(prefs(context).getString(KEY_CAPTURE_CORNER, CaptureCorner.BOTTOM_RIGHT.name))

    fun setCaptureCorner(context: Context, corner: CaptureCorner) {
        prefs(context).edit().putString(KEY_CAPTURE_CORNER, corner.name).apply()
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
}
