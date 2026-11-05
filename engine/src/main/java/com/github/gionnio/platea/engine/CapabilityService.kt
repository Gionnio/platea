package com.github.gionnio.platea.engine

/**
 * Deterministic source of [DeviceCapabilities].
 *
 * Capabilities are computed once, when the display is stable (not during a refresh rate or HDR mode switch),
 * then cached and persisted. They are recomputed only when [invalidate] is called for a real event.
 */
interface CapabilityService {
    /**
     * Returns the cached capabilities, computing them first if needed.
     * Never reads the display while a mode switch is in progress.
     */
    suspend fun get(): DeviceCapabilities

    /** Last computed capabilities, or `null` if they were never computed. */
    val cached: DeviceCapabilities?

    /**
     * Drops the cache so the next [get] recomputes everything.
     *
     * @param reason the triggering event (HDMI output change, firmware update, overrides changed...), logged
     */
    fun invalidate(reason: String)

    /** Overrides currently applied: per-model exceptions plus user settings. */
    fun overrides(): List<CapabilityOverride>
}
