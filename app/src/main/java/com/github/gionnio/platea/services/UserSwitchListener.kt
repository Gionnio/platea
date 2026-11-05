package com.github.gionnio.platea.services

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.lifecycleScope
import com.github.gionnio.platea.data.ServerRepository
import com.github.gionnio.platea.ui.launchDefault
import com.github.gionnio.platea.util.PlateaDispatchers
import dagger.hilt.android.qualifiers.ActivityContext
import dagger.hilt.android.scopes.ActivityScoped
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

/**
 * Applies what belongs to the signed-in user when the user changes: the interface language.
 */
@ActivityScoped
class UserSwitchListener
    @Inject
    constructor(
        @param:ActivityContext private val context: Context,
        private val serverRepository: ServerRepository,
    ) {
        init {
            context as AppCompatActivity
            context.lifecycleScope.launchDefault {
                serverRepository.currentUserFlow.collect { user ->
                    if (user == null) return@collect
                    // The user's choice, or the system language when there is none
                    val localeList =
                        user.uiLanguage?.let { LocaleListCompat.forLanguageTags(it) }
                            ?: LocaleListCompat.getEmptyLocaleList()
                    Timber.i("Switching locale to %s", localeList)
                    withContext(PlateaDispatchers.Main) {
                        AppCompatDelegate.setApplicationLocales(localeList)
                    }
                }
            }
        }
    }
