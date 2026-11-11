package com.ipvio.stream

/**
 * OnAir: colour-setting migration from upstream IPVIO to OnAir (pure, tested in LookMigrationLogicTest).
 *
 * Upstream's settings (version ≤ 6) know 12 presets with "IPVIO green" at index 0 and the custom colour at index 12.
 * OnAir puts its red at index 0 (the default) and appends the green as a 13th preset, so the custom colour moves to
 * index 13 ([custom] = Look.CUSTOM).
 */
object LookMigrationLogic {
    /** Colour-settings version written by OnAir (upstream stops at 6). */
    const val VERSION = 7
    /** Upstream's index of the custom colour. */
    const val OLD_CUSTOM = 12

    /** Nothing stored yet: skip every legacy migration (they would only force old defaults). */
    fun isFresh(hasVersion: Boolean, hasAccent: Boolean, hasCustom: Boolean): Boolean = !hasVersion && !hasAccent && !hasCustom

    /**
     * The OnAir step for settings older than [VERSION]: returns the new (accent index, custom colour).
     * - accent 0 (was IPVIO green) stays 0 = OnAir red, the new default;
     * - the old custom index moves to [customIdx];
     * - a custom colour still at upstream's green default becomes [defaultCustom].
     */
    fun toOnAir(accent: Int, customRgb: Int, customIdx: Int, upstreamGreen: Int, defaultCustom: Int): Pair<Int, Int> {
        val a = if (accent == OLD_CUSTOM) customIdx else accent
        val c = if ((customRgb and 0xFFFFFF) == upstreamGreen) defaultCustom else customRgb and 0xFFFFFF
        return a to c
    }
}
