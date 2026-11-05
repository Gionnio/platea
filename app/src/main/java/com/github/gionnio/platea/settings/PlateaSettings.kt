package com.github.gionnio.platea.settings

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.datastore.core.DataStore
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.SingletonImageLoader
import com.github.gionnio.platea.BuildConfig
import com.github.gionnio.platea.R
import com.github.gionnio.platea.data.JellyfinServerDao
import com.github.gionnio.platea.data.ServerRepository
import com.github.gionnio.platea.preferences.AppChoicePreference
import com.github.gionnio.platea.preferences.AppDestinationPreference
import com.github.gionnio.platea.preferences.AppPreference
import com.github.gionnio.platea.preferences.AppPreferences
import com.github.gionnio.platea.preferences.AppSliderPreference
import com.github.gionnio.platea.preferences.AppSwitchPreference
import com.github.gionnio.platea.preferences.ScreensaverPreference
import com.github.gionnio.platea.preferences.UserProfileSettings
import com.github.gionnio.platea.services.NavigationManager
import com.github.gionnio.platea.ui.nav.Destination
import com.github.gionnio.platea.ui.preferences.PreferenceScreenOption
import com.github.gionnio.platea.util.PlateaDispatchers
import com.github.gionnio.platea.PlateaPreferences
import com.github.gionnio.platea.details.TrackFormat
import com.github.gionnio.platea.ui.PlateaBrand
import com.github.gionnio.platea.ui.components.PlateaPinCreate
import com.github.gionnio.platea.ui.components.PlateaPinVerify
import com.github.gionnio.platea.ui.theme.PlateaColors
import com.github.gionnio.platea.ui.theme.PlateaDimens
import com.github.gionnio.platea.ui.theme.PlateaType
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

/** Saves settings through the inherited definitions and stores (same data Wholphin's screens used). */
@HiltViewModel
class PlateaSettingsViewModel
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val store: DataStore<AppPreferences>,
        private val serverRepository: ServerRepository,
        private val serverDao: JellyfinServerDao,
        val navigationManager: NavigationManager,
        private val nasSettings: com.github.gionnio.platea.nas.NasSettings,
        val updater: com.github.gionnio.platea.update.PlateaUpdater,
    ) : ViewModel() {
        fun installUpdate(state: com.github.gionnio.platea.update.UpdateState.Available) {
            viewModelScope.launch { updater.install(state.release, state.apk) }
        }

        /** Direct reading from the NAS (device-only settings, see NasSettings). */
        val nas = nasSettings.config

        fun setNas(block: (com.github.gionnio.platea.nas.NasConfig) -> com.github.gionnio.platea.nas.NasConfig) = nasSettings.update(block)

        /** Opens the share with the current settings: the number of entries at its top, or why it failed. */
        suspend fun testNas(): Result<Int> =
            kotlinx.coroutines.withContext(PlateaDispatchers.IO) {
                runCatching {
                    val config = nasSettings.current
                    val parts =
                        com.github.gionnio.platea.engine.NasPath.parse(config.smbRoot.trimEnd('/') + "/x")
                            ?: throw IllegalArgumentException(config.smbRoot)
                    com.github.gionnio.platea.nas.SmbConnections.share(parts, config).list("").size
                }.onFailure {
                    Timber.w(it, "NAS test")
                    com.github.gionnio.platea.nas.SmbConnections.forget()
                }
            }
        /** Scroll position and "came back from the advanced page" survive while that page is open. */
        val listState = LazyListState()
        var returnToAdvanced = false

        /** Live values: the page must show a change as soon as it is saved. */
        val preferences = store.data
        val user = serverRepository.currentUserFlow
        val userDto = serverRepository.currentUserDtoFlow

        fun update(block: (AppPreferences) -> AppPreferences) {
            viewModelScope.launch { store.updateData(block) }
        }

        fun setPin(pin: String?) {
            val user = serverRepository.currentUser ?: return
            viewModelScope.launch(PlateaDispatchers.IO) { runCatching { serverRepository.updateUserAuth(user, pin, false) }.onFailure { Timber.w(it) } }
        }

        /** Interface language of this profile; null follows the device. Applied at once (UserSwitchListener). */
        fun setLanguage(tag: String?) {
            val current = serverRepository.current.value ?: return
            viewModelScope.launch(PlateaDispatchers.IO) {
                runCatching { serverRepository.changeUser(current.server, current.user.copy(uiLanguage = tag)) }.onFailure { Timber.w(it) }
            }
        }

        fun setPreferredLanguage(
            audio: Boolean,
            value: String,
        ) {
            val user = serverRepository.currentUser ?: return
            viewModelScope.launch(PlateaDispatchers.IO) {
                val prefs = if (audio) user.appPreferences.copy(preferredAudioLanguage = value) else user.appPreferences.copy(preferredSubtitleLanguage = value)
                runCatching { serverDao.updateUser(user.copy(appPreferences = prefs)) }.onFailure { Timber.w(it) }
            }
        }

        fun imageCacheMb(): Long = (SingletonImageLoader.get(context).diskCache?.size ?: 0L) / (1024 * 1024)

        fun clearImageCache() {
            SingletonImageLoader.get(context).let {
                it.memoryCache?.clear()
                it.diskCache?.clear()
            }
        }
    }

/** A line of a settings page: an inherited preference, or one of the entries Platea handles itself. */
private sealed interface Entry {
    data class Pref(
        val pref: AppPreference<AppPreferences, *>,
    ) : Entry

    data object Protection : Entry

    data object Language : Entry

    data object AudioLanguage : Entry

    data object SubtitleLanguage : Entry

    data object ClearCache : Entry

    data object NasEnabled : Entry

    data object NasRoot : Entry

    data object NasPrefix : Entry

    data object NasUser : Entry

    data object NasPassword : Entry

    data object NasTest : Entry

    data object UpdateCheck : Entry
}

private fun prefs(vararg p: AppPreference<AppPreferences, *>): List<Entry> = p.map { Entry.Pref(it) }

/** The settings of everyday use: the first page. */
private val MAIN: List<Pair<Int, List<Entry>>> =
    listOf(
        R.string.platea_settings_playback to
            prefs(PlateaPreferences.PlateaProfile, AppPreference.RefreshRateSwitching, AppPreference.SkipForward, AppPreference.SkipBack),
        R.string.platea_nas_section to
            listOf(Entry.NasEnabled, Entry.NasRoot, Entry.NasPrefix, Entry.NasUser, Entry.NasPassword, Entry.NasTest),
        R.string.platea_audio_subtitles to
            listOf(Entry.AudioLanguage, Entry.SubtitleLanguage) + prefs(PlateaPreferences.ForeignAudioSubtitles, AppPreference.SubtitleStyle),
        R.string.platea_settings_episodes to
            prefs(AppPreference.AutoPlayNextUp, AppPreference.AutoPlayNextDelay, AppPreference.SkipIntros, AppPreference.SkipOutros),
        R.string.platea_settings_interface to
            prefs(PlateaPreferences.HomeCache, AppPreference.PlayThemeMusic, AppPreference.SignInAuto),
        R.string.platea_updates_section to prefs(PlateaPreferences.AutoUpdate) + Entry.UpdateCheck,
    )

/**
 * "Advanced settings": everything else that has an effect in Platea, nothing repeated from the first page.
 * Left out on purpose: options that do nothing with the Platea interface (colour theme, Wholphin home and
 * drawer, backdrop style, debug overlay, MPV…) or belong to features Platea does not have (photos, live TV,
 * Seerr, updater), "require the password at every sign-in", Quick Connect authorisation and log upload.
 */
private val ADVANCED: List<Pair<Int, List<Entry>>> =
    listOf(
        R.string.platea_settings_profile to listOf(Entry.Protection, Entry.Language),
        R.string.platea_settings_playback to
            prefs(
                PlateaPreferences.AutoTranscode,
                PlateaPreferences.ExcludeDoviHdr10Plus,
                AppPreference.ResolutionSwitching,
                com.github.gionnio.platea.preferences.ExperimentalPreference.VideoTunneling,
                AppPreference.MaxBitrate,
                AppPreference.GlobalContentScale,
                AppPreference.SkipBackOnResume,
                AppPreference.OneClickPause,
                AppPreference.ControllerTimeout,
                AppPreference.CinemaMode,
            ),
        R.string.platea_audio_subtitles to prefs(AppPreference.DownMixStereo, AppPreference.DirectPlayPgs, AppPreference.AssSubtitleMode),
        R.string.platea_settings_episodes to
            prefs(AppPreference.ShowNextUpTiming, AppPreference.PassOutProtection, AppPreference.SkipRecaps, AppPreference.SkipPreviews),
        R.string.screensaver to prefs(ScreensaverPreference.Enabled, ScreensaverPreference.StartDelay),
        R.string.platea_settings_system to
            listOf<Entry>(Entry.ClearCache) + prefs(AppPreference.ImageDiskCacheSize, AppPreference.DebugLogging, AppPreference.OssLicenseInfo),
    )

/** Platea settings: the main page or the advanced one, same look; left/right or OK change a value. */
@Composable
fun PlateaSettingsPage(
    preferences: AppPreferences,
    modifier: Modifier = Modifier,
    /** The second page ("Advanced settings"). */
    advanced: Boolean = false,
    viewModel: PlateaSettingsViewModel = hiltViewModel(),
) {
    val sections = if (advanced) ADVANCED else MAIN
    val first = remember { FocusRequester() }
    val last = remember { FocusRequester() }
    var firstFocused by remember { mutableStateOf(false) }
    var lastFocused by remember { mutableStateOf(false) }
    val listState = viewModel.listState
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        // Back from the advanced page: the cursor returns on its button, otherwise it starts at the top
        val target = if (viewModel.returnToAdvanced) last else first
        viewModel.returnToAdvanced = false
        for (attempt in 1..10) {
            if (runCatching { target.requestFocus() }.isSuccess) break
            delay(50)
        }
    }
    val preferences by viewModel.preferences.collectAsState(initial = preferences)
    val user by viewModel.user.collectAsState(initial = null)
    val userDto by viewModel.userDto.collectAsState(initial = null)
    var dialog by remember { mutableStateOf<Entry?>(null) }
    val nas by viewModel.nas.collectAsState()
    var nasTest by remember { mutableStateOf<String?>(null) }
    val nasOk = stringResource(R.string.platea_nas_test_ok)
    val nasFailedText = stringResource(R.string.platea_nas_test_failed)
    val lastSection = sections.lastIndex
    Box(modifier.fillMaxSize().background(PlateaColors.Ground)) {
        PlateaBrand(Modifier.align(Alignment.TopStart).padding(start = PlateaDimens.SafeHorizontal, top = PlateaDimens.SafeTop))
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(5.dp),
            contentPadding = PaddingValues(top = 64.dp, bottom = PlateaDimens.SafeTop + 8.dp),
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .width(600.dp)
                    // Up on the first row goes to the last and down on the last to the first, but only on a
                    // fresh press: holding the key stops at the end of the page instead of looping
                    .onPreviewKeyEvent { event ->
                        val up = event.key == Key.DirectionUp && firstFocused
                        val down = event.key == Key.DirectionDown && lastFocused
                        if (!up && !down) return@onPreviewKeyEvent false
                        if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount == 0) {
                            scope.launch {
                                listState.scrollToItem(if (up) listState.layoutInfo.totalItemsCount - 1 else 0)
                                val target = if (up) last else first
                                for (attempt in 1..10) {
                                    if (runCatching { target.requestFocus() }.isSuccess) break
                                    delay(30)
                                }
                            }
                        }
                        true
                    },
        ) {
            item { Text(stringResource(if (advanced) R.string.platea_settings_advanced else R.string.platea_settings), style = PlateaType.HeroTitle.copy(fontSize = 26.sp), color = PlateaColors.Text, modifier = Modifier.padding(bottom = 6.dp)) }
            sections.forEachIndexed { s, (title, entries) ->
                item { SectionTitle(stringResource(title)) }
                entries.forEachIndexed { i, entry ->
                    item {
                        val isFirst = s == 0 && i == 0
                        // On the advanced page the last setting is the last row; on the main page it is the button below
                        val isLast = advanced && s == lastSection && i == entries.lastIndex
                        val rowModifier =
                            when {
                                isFirst -> Modifier.focusRequester(first).onFocusChanged { firstFocused = it.hasFocus }
                                isLast -> Modifier.focusRequester(last).onFocusChanged { lastFocused = it.hasFocus }
                                else -> Modifier
                            }
                        when (entry) {
                            is Entry.Pref ->
                                PrefRow(entry.pref, preferences, rowModifier, onChange = { new -> viewModel.update { new(it) } }, onNavigate = viewModel.navigationManager::navigateTo)
                            Entry.Protection ->
                                Row_(
                                    stringResource(R.string.platea_settings_protection),
                                    stringResource(R.string.platea_settings_protection_summary),
                                    stringResource(if (user?.hasPin == true) R.string.platea_settings_protection_pin else R.string.platea_settings_protection_none),
                                    rowModifier,
                                    onStep = null,
                                ) { dialog = entry }
                            Entry.Language ->
                                Row_(
                                    stringResource(R.string.platea_settings_language),
                                    null,
                                    INTERFACE_LANGUAGES.firstOrNull { it.first == user?.uiLanguage }?.second ?: stringResource(R.string.platea_settings_language_system),
                                    rowModifier,
                                    onStep = null,
                                ) { dialog = entry }
                            Entry.AudioLanguage, Entry.SubtitleLanguage -> {
                                val audio = entry == Entry.AudioLanguage
                                val value = user?.appPreferences?.let { if (audio) it.preferredAudioLanguage else it.preferredSubtitleLanguage }.orEmpty()
                                val fromServer = userDto?.configuration?.let { if (audio) it.audioLanguagePreference else it.subtitleLanguagePreference }
                                Row_(
                                    stringResource(if (audio) R.string.platea_settings_audio_language else R.string.platea_settings_subtitle_language),
                                    null,
                                    preferredLanguageLabel(value, fromServer),
                                    rowModifier,
                                    onStep = null,
                                ) { dialog = entry }
                            }
                            Entry.NasEnabled ->
                                Row_(
                                    stringResource(R.string.platea_nas_enabled),
                                    stringResource(R.string.platea_nas_enabled_summary),
                                    null,
                                    rowModifier,
                                    switch = nas.enabled,
                                    onStep = { viewModel.setNas { it.copy(enabled = !it.enabled) } },
                                ) { viewModel.setNas { it.copy(enabled = !it.enabled) } }
                            Entry.NasRoot ->
                                Row_(stringResource(R.string.platea_nas_root), stringResource(R.string.platea_nas_root_summary), nas.smbRoot, rowModifier, onStep = null) { dialog = entry }
                            Entry.NasPrefix ->
                                Row_(stringResource(R.string.platea_nas_prefix), stringResource(R.string.platea_nas_prefix_summary), nas.serverPrefix, rowModifier, onStep = null) { dialog = entry }
                            Entry.NasUser ->
                                Row_(
                                    stringResource(R.string.platea_nas_user),
                                    null,
                                    nas.username.ifBlank { stringResource(R.string.platea_nas_guest) },
                                    rowModifier,
                                    onStep = null,
                                ) { dialog = entry }
                            Entry.NasPassword ->
                                Row_(stringResource(R.string.platea_nas_password), null, if (nas.password.isEmpty()) "—" else "••••••", rowModifier, onStep = null) { dialog = entry }
                            Entry.NasTest ->
                                Row_(stringResource(R.string.platea_nas_test), nasTest ?: stringResource(R.string.platea_nas_test_summary), null, rowModifier, onStep = null) {
                                    nasTest = "…"
                                    scope.launch {
                                        nasTest =
                                            viewModel.testNas().fold(
                                                onSuccess = { n -> nasOk.format(n) },
                                                onFailure = { e -> nasFailedText.format(e.message ?: e.javaClass.simpleName) },
                                            )
                                    }
                                }
                            Entry.UpdateCheck -> {
                                val update by viewModel.updater.state.collectAsState()
                                var checking by remember { mutableStateOf(false) }
                                val version = viewModel.updater.installedVersion
                                val u = update
                                val summary =
                                    when {
                                        !viewModel.updater.available -> stringResource(R.string.platea_update_dev_build)
                                        checking -> stringResource(R.string.platea_update_checking)
                                        u is com.github.gionnio.platea.update.UpdateState.UpToDate -> stringResource(R.string.platea_update_up_to_date, version)
                                        u is com.github.gionnio.platea.update.UpdateState.Available -> stringResource(R.string.platea_update_available_version, u.release.versionName, version)
                                        u is com.github.gionnio.platea.update.UpdateState.Downloading -> stringResource(R.string.platea_update_downloading, u.percent)
                                        u is com.github.gionnio.platea.update.UpdateState.Installing -> stringResource(R.string.platea_update_installing)
                                        u is com.github.gionnio.platea.update.UpdateState.Failed -> stringResource(R.string.platea_update_failed, u.message)
                                        else -> stringResource(R.string.platea_update_installed, version)
                                    }
                                val available = update as? com.github.gionnio.platea.update.UpdateState.Available
                                Row_(
                                    stringResource(if (available != null) R.string.platea_update_install else R.string.platea_update_check),
                                    summary,
                                    null,
                                    rowModifier,
                                    onStep = null,
                                ) {
                                    when {
                                        !viewModel.updater.available -> Unit
                                        available != null -> viewModel.installUpdate(available)
                                        update is com.github.gionnio.platea.update.UpdateState.Downloading || update is com.github.gionnio.platea.update.UpdateState.Installing -> Unit
                                        !checking ->
                                            scope.launch {
                                                checking = true
                                                viewModel.updater.check(manual = true)
                                                checking = false
                                            }
                                    }
                                }
                            }
                            Entry.ClearCache -> {
                                var used by remember { mutableStateOf(viewModel.imageCacheMb()) }
                                Row_(
                                    stringResource(R.string.platea_settings_clear_cache),
                                    stringResource(R.string.platea_settings_cache_used, used),
                                    null,
                                    rowModifier,
                                    onStep = null,
                                ) {
                                    viewModel.clearImageCache()
                                    used = 0
                                }
                            }
                        }
                    }
                }
            }
            if (!advanced) {
                item {
                    Row_(
                        stringResource(R.string.platea_settings_advanced),
                        stringResource(R.string.platea_settings_advanced_summary),
                        "›",
                        Modifier.padding(top = 14.dp).focusRequester(last).onFocusChanged { lastFocused = it.hasFocus },
                        onStep = null,
                    ) {
                        viewModel.returnToAdvanced = true
                        viewModel.navigationManager.navigateTo(Destination.Settings(PreferenceScreenOption.ADVANCED))
                    }
                }
            }
            item {
                Text(
                    "Platea ${BuildConfig.VERSION_NAME}",
                    style = PlateaType.CardSub,
                    color = PlateaColors.TextTertiary,
                    modifier = Modifier.padding(top = 12.dp, start = 12.dp),
                )
            }
        }
    }

    when (dialog) {
        Entry.Protection -> {
            val pin = user?.pin
            if (pin.isNullOrBlank()) {
                PlateaPinCreate(onCreated = {
                    viewModel.setPin(it)
                    dialog = null
                }, onDismiss = { dialog = null })
            } else {
                PlateaPinVerify(pin, stringResource(R.string.platea_pin_remove), onSuccess = {
                    viewModel.setPin(null)
                    dialog = null
                }, onDismiss = { dialog = null })
            }
        }
        Entry.Language -> {
            val options = listOf<Pair<String?, String>>(null to stringResource(R.string.platea_settings_language_system)) + INTERFACE_LANGUAGES
            Picker(stringResource(R.string.platea_settings_language), options.map { it.second }, options.indexOfFirst { it.first == user?.uiLanguage }.coerceAtLeast(0), onPick = {
                dialog = null
                viewModel.setLanguage(options[it].first)
            }, onDismiss = { dialog = null })
        }
        Entry.AudioLanguage, Entry.SubtitleLanguage -> {
            val audio = dialog == Entry.AudioLanguage
            val value = user?.appPreferences?.let { if (audio) it.preferredAudioLanguage else it.preferredSubtitleLanguage }.orEmpty()
            val fromServer = userDto?.configuration?.let { if (audio) it.audioLanguagePreference else it.subtitleLanguagePreference }
            val codes = listOf(UserProfileSettings.USE_USER_PROFILE, UserProfileSettings.PREFER_ANY_LANGUAGE) + MEDIA_LANGUAGES
            Picker(
                stringResource(if (audio) R.string.platea_settings_audio_language else R.string.platea_settings_subtitle_language),
                codes.map { preferredLanguageLabel(it, fromServer) },
                codes.indexOf(value).coerceAtLeast(0),
                onPick = {
                    dialog = null
                    viewModel.setPreferredLanguage(audio, codes[it])
                },
                onDismiss = { dialog = null },
            )
        }
        Entry.NasRoot, Entry.NasPrefix, Entry.NasUser, Entry.NasPassword -> {
            val d = dialog
            val (title, initial) =
                when (d) {
                    Entry.NasRoot -> stringResource(R.string.platea_nas_root) to nas.smbRoot
                    Entry.NasPrefix -> stringResource(R.string.platea_nas_prefix) to nas.serverPrefix
                    Entry.NasUser -> stringResource(R.string.platea_nas_user) to nas.username
                    else -> stringResource(R.string.platea_nas_password) to ""
                }
            com.github.gionnio.platea.ui.components.PlateaKeyboardDialog(
                title = title,
                initial = initial,
                password = d == Entry.NasPassword,
                onDone = { value ->
                    val v = value.trim()
                    viewModel.setNas {
                        when (d) {
                            Entry.NasRoot -> it.copy(smbRoot = v)
                            Entry.NasPrefix -> it.copy(serverPrefix = v)
                            Entry.NasUser -> it.copy(username = v)
                            else -> it.copy(password = value)
                        }
                    }
                    nasTest = null
                    dialog = null
                },
                onDismiss = { dialog = null },
            )
        }
        else -> Unit
    }
}

private val INTERFACE_LANGUAGES = listOf<Pair<String?, String>>("it" to "Italiano", "en" to "English")

/** Languages offered for audio and subtitles (ISO 639-2, as Jellyfin stores them). */
private val MEDIA_LANGUAGES = listOf("ita", "eng", "jpn", "fra", "deu", "spa", "por", "kor", "zho", "rus")

@Composable
private fun preferredLanguageLabel(
    value: String,
    fromServer: String?,
): String =
    when (value) {
        UserProfileSettings.USE_USER_PROFILE ->
            stringResource(R.string.platea_settings_as_jellyfin) + (fromServer?.takeIf { it.isNotBlank() }?.let { " · " + (TrackFormat.language(it) ?: it) } ?: "")
        UserProfileSettings.PREFER_ANY_LANGUAGE -> stringResource(R.string.platea_settings_any_language)
        else -> TrackFormat.language(value) ?: value
    }

@Composable
private fun SectionTitle(text: String) {
    Text(text.uppercase(), style = PlateaType.Kicker, color = PlateaColors.Gold, modifier = Modifier.padding(top = 14.dp, bottom = 3.dp, start = 12.dp))
}

/** A list to pick one value from (language…): gold on the current one, OK chooses. */
@Composable
private fun Picker(
    title: String,
    options: List<String>,
    selected: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        val focus = remember { FocusRequester() }
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(16.dp),
            modifier = Modifier.width(340.dp).heightIn(max = 420.dp).clip(RoundedCornerShape(12.dp)).background(PlateaColors.Surface),
        ) {
            item { Text(title, style = PlateaType.RowTitle.copy(fontSize = 16.sp), color = PlateaColors.Text, modifier = Modifier.padding(start = 8.dp, bottom = 8.dp)) }
            items(options.size) { i ->
                Surface(
                    onClick = { onPick(i) },
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(PlateaDimens.ButtonRadius)),
                    colors =
                        ClickableSurfaceDefaults.colors(
                            containerColor = Color.Transparent,
                            contentColor = if (i == selected) PlateaColors.Gold else PlateaColors.Text,
                            focusedContainerColor = PlateaColors.Text,
                            focusedContentColor = PlateaColors.Ground,
                        ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
                    modifier = Modifier.fillMaxWidth().then(if (i == selected) Modifier.focusRequester(focus) else Modifier),
                ) {
                    Text(options[i], style = PlateaType.Button.copy(fontSize = 13.sp), modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                }
            }
        }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    }
}

/** How a slider value is shown, where the inherited text is unclear ("5,00 secondi", "15m"). */
private fun <P> sliderLabel(
    pref: AppSliderPreference<P>,
    value: Long,
): String =
    when {
        pref === AppPreference.ControllerTimeout -> String.format(Locale.ITALY, "%.1f s", value / 1000.0)
        else -> (pref.summarizer?.invoke(value) ?: value.toString()).replace(Regex("^(\\d+)m$"), "$1 min").replace(Regex("^(\\d+)s$"), "$1 s").replace(Regex("^(\\d+)h$"), "$1 h")
    }

/** Draws an inherited preference as a Platea row, for any settings store ([P]). */
@Composable
internal fun <P> PrefRow(
    pref: AppPreference<P, *>,
    preferences: P,
    modifier: Modifier,
    /** Receives the change to apply to the stored value. */
    onChange: ((P) -> P) -> Unit,
    onNavigate: (Destination) -> Unit,
    onClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val title = stringResource(pref.title)
    when (pref) {
        is AppSwitchPreference<P> -> {
            val value = pref.getter(preferences)
            Row_(title, pref.summary(context, value), null, modifier, switch = value, onStep = null) { onChange { pref.setter(it, !pref.getter(it)) } }
        }
        is AppSliderPreference<P> -> {
            val value = pref.getter(preferences)
            val step = { direction: Int -> onChange { pref.setter(it, (pref.getter(it) + direction * pref.interval).coerceIn(pref.min, pref.max)) } }
            Row_(title, pref.summary?.let { stringResource(it) }, "‹  ${sliderLabel(pref, value)}  ›", modifier, onStep = step) { step(1) }
        }
        is AppChoicePreference<P, *> -> ChoiceRow(pref, preferences, title, modifier, onChange)
        is AppDestinationPreference<P> -> Row_(title, pref.summary?.let { stringResource(it) }, "›", modifier, onStep = null) { onNavigate(pref.destination) }
        else -> Row_(title, null, null, modifier, onStep = null) { onClick?.invoke() }
    }
}

@Composable
private fun <P, T> ChoiceRow(
    pref: AppChoicePreference<P, T>,
    preferences: P,
    title: String,
    modifier: Modifier,
    onChange: ((P) -> P) -> Unit,
) {
    val labels = stringArrayResource(pref.displayValues)
    val size = labels.size.coerceAtLeast(1)
    val index = pref.valueToIndex(pref.getter(preferences)).coerceIn(0, size - 1)
    val step = { direction: Int -> onChange { pref.setter(it, pref.indexToValue((pref.valueToIndex(pref.getter(it)).coerceIn(0, size - 1) + direction).mod(size))) } }
    Row_(title, null, "‹  ${labels.getOrElse(index) { "" }}  ›", modifier, onStep = step) { step(1) }
}

/**
 * One setting: title and explanation on the left, value on the right. White only under the cursor.
 * [onStep]: left/right change the value (sliders, choices); OK does [onClick].
 */
@Composable
internal fun Row_(
    title: String,
    summary: String?,
    value: String?,
    modifier: Modifier,
    switch: Boolean? = null,
    onStep: ((Int) -> Unit)?,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = PlateaColors.Surface.copy(alpha = 0.6f),
                contentColor = PlateaColors.Text,
                focusedContainerColor = PlateaColors.Text,
                focusedContentColor = PlateaColors.Ground,
            ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.015f),
        modifier =
            modifier.fillMaxWidth().onPreviewKeyEvent { event ->
                if (onStep == null || (event.key != Key.DirectionLeft && event.key != Key.DirectionRight)) return@onPreviewKeyEvent false
                if (event.type == KeyEventType.KeyDown) onStep(if (event.key == Key.DirectionRight) 1 else -1)
                true
            },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) {
            Column(Modifier.weight(1f)) {
                Text(title, style = PlateaType.Button.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold))
                summary?.takeIf { it.isNotBlank() }?.let { Text(it, style = PlateaType.CardSub.copy(fontSize = 10.sp), modifier = Modifier.padding(top = 2.dp)) }
            }
            Spacer(Modifier.width(14.dp))
            when {
                switch != null -> {
                    // Gold when on, hollow when off
                    Box(
                        Modifier
                            .width(34.dp)
                            .height(18.dp)
                            .clip(CircleShape)
                            .background(if (switch) PlateaColors.Gold else PlateaColors.Border),
                    ) {
                        Box(
                            Modifier
                                .align(if (switch) Alignment.CenterEnd else Alignment.CenterStart)
                                .padding(horizontal = 3.dp)
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(if (switch) PlateaColors.Ground else PlateaColors.TextSecondary),
                        )
                    }
                }
                value != null -> Text(value, style = PlateaType.Button.copy(fontSize = 12.sp), color = Color.Unspecified)
            }
        }
    }
}
