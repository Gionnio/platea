package com.github.gionnio.platea.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester

/**
 * "Back to where you were": remembers which element opened another screen, so that on return the remote
 * focus goes back to it instead of the default element. Kept in the screen's ViewModel (it survives navigation).
 */
class FocusMemory {
    var key: String? = null

    /** True when the screen is coming back from another one and has an element to restore. */
    val restoring: Boolean get() = key != null

    fun remember(key: String) {
        this.key = key
    }
}

/** Requests focus on this element when it is the one to restore; call [FocusMemory.remember] on click. */
@Composable
fun Modifier.restoreFocus(
    memory: FocusMemory,
    key: String,
): Modifier {
    val requester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (memory.key == key) {
            runCatching { requester.requestFocus() }
            memory.key = null
        }
    }
    return this.focusRequester(requester)
}
