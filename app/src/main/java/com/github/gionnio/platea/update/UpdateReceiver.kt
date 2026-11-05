package com.github.gionnio.platea.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat
import com.github.gionnio.platea.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import javax.inject.Inject

/**
 * Answers of the package installer for a self-update, and the restart once Platea has been replaced.
 */
@AndroidEntryPoint
class UpdateReceiver : BroadcastReceiver() {
    @Inject lateinit var updater: PlateaUpdater

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        when (intent.action) {
            ACTION_STATUS -> status(context, intent)
            // Platea was just replaced: reopen it if the update was started from Platea a moment ago
            Intent.ACTION_MY_PACKAGE_REPLACED ->
                if (updater.consumeJustUpdated()) {
                    runCatching {
                        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }.onFailure { Timber.w(it, "Reopen after update") }
                }
        }
    }

    private fun status(
        context: Context,
        intent: Intent,
    ) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        Timber.i("Update installer status $status $message")
        when (status) {
            // The system asks the user (first update, or Android older than 12): show its confirmation
            PackageInstaller.STATUS_PENDING_USER_ACTION ->
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)?.let {
                    runCatching { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        .onFailure { e -> updater.installFailed(e.message ?: "installer") }
                }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> updater.installFailed(message ?: "status $status")
        }
    }

    companion object {
        const val ACTION_STATUS = "com.github.gionnio.platea.UPDATE_STATUS"
    }
}
