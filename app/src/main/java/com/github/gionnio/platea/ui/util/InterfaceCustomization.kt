package com.github.gionnio.platea.ui.util

import androidx.compose.runtime.staticCompositionLocalOf
import com.github.gionnio.platea.preferences.AppPreferences
import com.github.gionnio.platea.preferences.DisplayToggle
import java.util.EnumSet

/**
 * Represents various UI preferences made available via [LocalInterfaceCustomization]
 */
data class InterfaceCustomization(
    val enabledDisplayToggles: EnumSet<DisplayToggle>,
) {
    constructor(prefs: AppPreferences) : this(
        prefs.interfacePreferences.displayTogglesList.let {
            if (it.isEmpty()) {
                EnumSet.noneOf(DisplayToggle::class.java)
            } else {
                EnumSet.copyOf(it)
            }
        },
    )
}

val LocalInterfaceCustomization =
    staticCompositionLocalOf<InterfaceCustomization> {
        InterfaceCustomization(EnumSet.allOf(DisplayToggle::class.java))
    }
