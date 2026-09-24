package io.github.deniztopcuoglu.cornershot

/** Size rules shared by the settings screen, preferences, and floating overlay. */
internal object ButtonSizeGeometry {
    const val MIN_VISIBLE_DP = 24
    const val MAX_VISIBLE_DP = 64
    const val STEP_DP = 2
    const val DEFAULT_VISIBLE_DP = 36
    const val MIN_TOUCH_TARGET_DP = 48

    fun sanitizeVisibleSizeDp(sizeDp: Int): Int {
        val clamped = sizeDp.coerceIn(MIN_VISIBLE_DP, MAX_VISIBLE_DP)
        val steps = ((clamped - MIN_VISIBLE_DP) + STEP_DP / 2) / STEP_DP
        return (MIN_VISIBLE_DP + steps * STEP_DP).coerceIn(MIN_VISIBLE_DP, MAX_VISIBLE_DP)
    }

    fun touchTargetSizeDp(visibleSizeDp: Int): Int =
        maxOf(MIN_TOUCH_TARGET_DP, sanitizeVisibleSizeDp(visibleSizeDp))
}
