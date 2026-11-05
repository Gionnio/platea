package com.github.gionnio.platea.services

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Collects a server message stream for as long as the scope lives. When the stream ends or fails (the
 * connection dropped, the server restarted) it is opened again after a pause, instead of staying silent
 * until the app is restarted.
 */
fun <T> CoroutineScope.keepSubscribed(
    name: String,
    retryMillis: Long = 5_000,
    subscribe: () -> Flow<T>,
    handle: suspend (T) -> Unit,
): Job =
    launch {
        while (isActive) {
            try {
                subscribe().collect { handle(it) }
                Timber.d("%s subscription ended, opening it again", name)
            } catch (ex: CancellationException) {
                throw ex
            } catch (ex: Exception) {
                Timber.w(ex, "%s subscription failed, opening it again", name)
            }
            delay(retryMillis)
        }
    }
