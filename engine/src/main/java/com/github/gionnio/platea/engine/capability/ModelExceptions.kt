package com.github.gionnio.platea.engine.capability

/**
 * Known per-model quirks. They are only suggestions shown in the settings and in the logs:
 * nothing here is applied unless the user enables the matching override.
 */
object ModelExceptions {
    /**
     * Models listed by jellyfin-androidtv / Wholphin as unable to play HEVC Dolby Vision + HDR10+.
     * On AFTKRT (Fire OS 8, build RS8182.3811N) Dolby Vision 8.1 + HDR10+ plays fine from the original MKV
     * (verified with a stock Media3 player on 2026-10-07), so the exclusion is off by default.
     */
    val doviHdr10PlusIssue =
        setOf(
            "AFTKRT", // Fire TV Stick 4K Max (2nd gen)
            "AFTKA", // Fire TV Stick 4K Max (1st gen)
            "AFTKM", // Fire TV Stick 4K (2nd gen)
        )

    /** Models with a 32-bit userspace even if the CPU is 64-bit (informational). */
    val userspace32Bit = setOf("AFTKRT")

    fun notesFor(model: String): List<String> =
        buildList {
            if (model in doviHdr10PlusIssue) add("listed with a DV + HDR10+ playback issue (override available)")
            if (model in userspace32Bit) add("32-bit userspace")
        }
}
