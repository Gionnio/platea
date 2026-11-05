package com.github.gionnio.platea.access

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.github.gionnio.platea.R
import com.github.gionnio.platea.data.model.JellyfinServer
import com.github.gionnio.platea.data.model.JellyfinUser
import com.github.gionnio.platea.services.SetupDestination
import com.github.gionnio.platea.ui.setup.ServerConnectionStatus
import com.github.gionnio.platea.ui.setup.ServerState
import com.github.gionnio.platea.ui.setup.ServerVersionSupported
import com.github.gionnio.platea.ui.setup.SwitchServerViewModel
import com.github.gionnio.platea.ui.setup.SwitchUserResult
import com.github.gionnio.platea.ui.setup.SwitchUserViewModel
import com.github.gionnio.platea.util.LoadingState
import com.github.gionnio.platea.home.HeroButton
import com.github.gionnio.platea.home.PlateaSessionViewModel
import com.github.gionnio.platea.ui.PlateaBrand
import com.github.gionnio.platea.ui.PlateaLoading
import com.github.gionnio.platea.ui.components.ActionMenu
import com.github.gionnio.platea.ui.components.MenuAction
import com.github.gionnio.platea.ui.components.PlateaKeyboardDialog
import com.github.gionnio.platea.ui.components.PlateaPinVerify
import com.github.gionnio.platea.ui.components.PlateaTextField
import com.github.gionnio.platea.ui.theme.PlateaColors
import com.github.gionnio.platea.ui.theme.PlateaDimens
import com.github.gionnio.platea.ui.theme.PlateaType
import kotlinx.coroutines.launch

private val ErrorRed = Color(0xFFF87171)

/** Common frame of the sign-in screens: velvet ground with a warm glow, brand in the corner, centred content. */
@Composable
private fun AccessFrame(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    footer: String? = null,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .fillMaxSize()
            .background(PlateaColors.Ground)
            .background(Brush.radialGradient(0f to Color(0xFF3A1A1F).copy(alpha = 0.55f), 1f to Color.Transparent, radius = 900f)),
    ) {
        PlateaBrand(Modifier.align(Alignment.TopStart).padding(start = PlateaDimens.SafeHorizontal, top = PlateaDimens.SafeTop))
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.align(Alignment.Center).fillMaxWidth(),
        ) {
            Text(title, style = PlateaType.HeroTitle.copy(fontSize = 28.sp), color = PlateaColors.Text)
            subtitle?.let { Text(it, style = PlateaType.Body.copy(fontSize = 13.sp), color = PlateaColors.TextSecondary, modifier = Modifier.padding(top = 6.dp)) }
            Spacer(Modifier.height(26.dp))
            content()
        }
        footer?.let {
            Text(
                it,
                style = PlateaType.Body,
                color = ErrorRed,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 96.dp, vertical = PlateaDimens.SafeTop),
            )
        }
    }
}

// --- Server ---

/** Choose (or add) the Jellyfin server. Logic: Wholphin's [SwitchServerViewModel]. */
@Composable
fun PlateaServerPage(
    modifier: Modifier = Modifier,
    viewModel: SwitchServerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(Unit) { viewModel.init() }
    val session: PlateaSessionViewModel = hiltViewModel()
    BackHandler(session.canReturn) { session.returnToApp() }
    if (state.loading != LoadingState.Success) {
        PlateaLoading(modifier.background(PlateaColors.Ground))
        return
    }
    var focused by remember { mutableStateOf<ServerState?>(null) }
    var menuFor by remember { mutableStateOf<JellyfinServer?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    LaunchedEffect(state.servers.size) { runCatching { first.requestFocus() } }

    AccessFrame(
        title = stringResource(R.string.platea_access_server_title),
        subtitle = stringResource(R.string.platea_access_server_subtitle),
        modifier = modifier,
        footer = (focused?.status as? ServerConnectionStatus.Error)?.message,
    ) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
            contentPadding = PaddingValues(horizontal = PlateaDimens.SafeHorizontal, vertical = 6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(state.servers.distinctBy { it.server.id }, key = { it.server.id }) { server ->
                ServerTile(
                    server = server,
                    modifier = if (server == state.servers.firstOrNull()) Modifier.focusRequester(first) else Modifier,
                    onFocus = { focused = server },
                    onLongClick = { menuFor = server.server },
                ) {
                    when (server.status) {
                        is ServerConnectionStatus.Success -> viewModel.switchServer(server.server)
                        is ServerConnectionStatus.Error -> viewModel.testServer(server.server)
                        ServerConnectionStatus.Pending -> Unit
                    }
                }
            }
            item {
                Tile(
                    modifier = if (state.servers.isEmpty()) Modifier.focusRequester(first) else Modifier,
                    onFocus = { focused = null },
                    onClick = { showAdd = true },
                ) {
                    Text("+", style = PlateaType.HeroTitle.copy(fontSize = 26.sp, fontWeight = FontWeight.Medium))
                    Spacer(Modifier.height(2.dp))
                    Text(stringResource(R.string.platea_access_add_server), style = PlateaType.CardTitle)
                }
            }
        }
    }

    menuFor?.let { server ->
        ActionMenu(
            title = server.name ?: server.url,
            subtitle = server.url,
            actions =
                listOf(
                    MenuAction(stringResource(R.string.platea_access_connect)) {
                        menuFor = null
                        viewModel.switchServer(server)
                    },
                    MenuAction(stringResource(R.string.platea_access_remove)) {
                        menuFor = null
                        viewModel.removeServer(server)
                    },
                ),
            onDismiss = { menuFor = null },
        )
    }
    if (showAdd) AddServerPanel(viewModel, onDismiss = { showAdd = false })
}

@Composable
private fun ServerTile(
    server: ServerState,
    modifier: Modifier,
    onFocus: () -> Unit,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
) {
    Tile(modifier = modifier, onFocus = onFocus, onLongClick = onLongClick, onClick = onClick) {
        Text(server.server.name ?: server.server.url, style = PlateaType.RowTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            server.server.url.removePrefix("http://").removePrefix("https://"),
            style = PlateaType.CardSub,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
        Spacer(Modifier.height(10.dp))
        val (color, label) =
            when (val status = server.status) {
                is ServerConnectionStatus.Success ->
                    if (server.versionSupported == ServerVersionSupported.NOT_SUPPORTED) {
                        ErrorRed to stringResource(R.string.platea_access_unsupported, status.systemInfo.version.orEmpty())
                    } else {
                        PlateaColors.OkGreen to stringResource(R.string.platea_access_reachable, status.systemInfo.version.orEmpty())
                    }
                is ServerConnectionStatus.Error -> ErrorRed to stringResource(R.string.platea_access_unreachable)
                ServerConnectionStatus.Pending -> PlateaColors.Gold to stringResource(R.string.platea_access_checking)
            }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(6.dp))
            Text(label, style = PlateaType.CardSub.copy(fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A dark card that turns white under the cursor; its text follows the content colour. */
@Composable
private fun Tile(
    modifier: Modifier = Modifier,
    onFocus: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val isFocused by interaction.collectIsFocusedAsState()
    LaunchedEffect(isFocused) { if (isFocused) onFocus() }
    Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        interactionSource = interaction,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = PlateaColors.Surface.copy(alpha = 0.85f),
                contentColor = PlateaColors.Text,
                focusedContainerColor = PlateaColors.Text,
                focusedContentColor = PlateaColors.Ground,
            ),
        border = ClickableSurfaceDefaults.border(border = Border(BorderStroke(1.dp, PlateaColors.Avatar), shape = RoundedCornerShape(10.dp))),
        scale = ClickableSurfaceDefaults.scale(focusedScale = PlateaDimens.FOCUS_SCALE),
        modifier = modifier.width(190.dp).height(104.dp),
    ) {
        Column(verticalArrangement = Arrangement.Center, modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) { content() }
    }
}

@Composable
private fun Panel(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            verticalArrangement = Arrangement.spacedBy(9.dp),
            modifier =
                Modifier
                    .width(380.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(PlateaColors.Surface)
                    .border(BorderStroke(0.5.dp, PlateaColors.Avatar), RoundedCornerShape(12.dp))
                    .padding(22.dp),
        ) { content() }
    }
}

@Composable
private fun AddServerPanel(
    viewModel: SwitchServerViewModel,
    onDismiss: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    var manual by remember { mutableStateOf(false) }
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) viewModel.discoverServers() else manual = true
        }
    LaunchedEffect(Unit) {
        viewModel.clearAddServerState()
        when {
            viewModel.hasPermission -> viewModel.discoverServers()
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN -> permission.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
        }
    }
    Panel(onDismiss = {
        viewModel.clearAddServerState()
        onDismiss()
    }) {
        Text(stringResource(R.string.platea_access_add_server), style = PlateaType.RowTitle.copy(fontSize = 17.sp), color = PlateaColors.Text)
        if (!manual) {
            Text(stringResource(R.string.platea_access_found), style = PlateaType.Kicker, color = PlateaColors.TextMeta, modifier = Modifier.padding(top = 4.dp))
            val first = remember { FocusRequester() }
            val enter = remember { FocusRequester() }
            LaunchedEffect(state.discoveredServers.size) { runCatching { if (state.discoveredServers.isEmpty()) enter.requestFocus() else first.requestFocus() } }
            if (state.discoveredServers.isEmpty()) {
                Text(stringResource(R.string.platea_access_searching), style = PlateaType.Body, color = PlateaColors.TextSecondary)
            }
            state.discoveredServers.take(5).forEachIndexed { i, server ->
                HeroButton(
                    label = (server.name?.ifBlank { null } ?: server.url) + "  ·  " + server.url.removePrefix("http://").removePrefix("https://"),
                    primary = false,
                    modifier = Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(first) else Modifier),
                ) { viewModel.addServer(server.url, showToast = false) }
            }
            ErrorText(state.addServerState)
            HeroButton(stringResource(R.string.platea_access_enter_address), primary = false, modifier = Modifier.fillMaxWidth().focusRequester(enter)) { manual = true }
        } else {
            var url by remember { mutableStateOf("") }
            var editing by remember { mutableStateOf(false) }
            val field = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { field.requestFocus() } }
            LaunchedEffect(url) { viewModel.clearAddServerState() }
            Text(stringResource(R.string.platea_access_address_hint), style = PlateaType.Body, color = PlateaColors.TextSecondary)
            PlateaTextField(url, stringResource(R.string.platea_access_address_hint), Modifier.fillMaxWidth().focusRequester(field)) { editing = true }
            if (editing) {
                PlateaKeyboardDialog(stringResource(R.string.platea_access_enter_address), url, onDone = {
                    url = it.trim()
                    editing = false
                }, onDismiss = { editing = false })
            }
            ErrorText(state.addServerState)
            if (state.addServerState == LoadingState.Loading) {
                Box(Modifier.fillMaxWidth().height(PlateaDimens.ButtonHeight)) { PlateaLoading() }
            } else {
                HeroButton(stringResource(R.string.platea_access_connect), primary = false, modifier = Modifier.fillMaxWidth()) {
                    if (url.isNotBlank()) viewModel.addServer(url, showToast = false)
                }
            }
        }
    }
}

@Composable
private fun ErrorText(state: LoadingState) {
    val error = state as? LoadingState.Error ?: return
    val text = error.message ?: error.exception?.localizedMessage ?: return
    Text(text, style = PlateaType.Body, color = ErrorRed, maxLines = 5, overflow = TextOverflow.Ellipsis)
}

// --- User ---

/** "Who is watching?": users of the server, sign-in with password or Quick Connect. Logic: [SwitchUserViewModel]. */
@Composable
fun PlateaUserPage(
    server: JellyfinServer,
    modifier: Modifier = Modifier,
    viewModel: SwitchUserViewModel =
        hiltViewModel<SwitchUserViewModel, SwitchUserViewModel.Factory>(creationCallback = { it.create(server) }),
) {
    val state by viewModel.state.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { viewModel.init() }
    val session: PlateaSessionViewModel = hiltViewModel()
    BackHandler(session.canReturn) { session.returnToApp() }

    var signIn by remember { mutableStateOf<SignInTarget?>(null) }
    var pinFor by remember { mutableStateOf<JellyfinUser?>(null) }
    var menuFor by remember { mutableStateOf<JellyfinUser?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun switchTo(user: JellyfinUser) {
        scope.launch {
            val result = viewModel.trySwitchUser(user).await()
            if (result is SwitchUserResult.Error) {
                error = result.errorMessage
                if (result.showLogin) signIn = SignInTarget(user)
            }
        }
    }

    fun choose(user: JellyfinUser) {
        error = null
        session.forgetReturn()
        when {
            user.accessToken == null || user.requireLogin -> signIn = SignInTarget(user)
            user.hasPin -> pinFor = user
            else -> switchTo(user)
        }
    }

    when (val loading = state.loading) {
        is LoadingState.Error -> {
            AccessFrame(
                title = server.name ?: server.url,
                subtitle = null,
                modifier = modifier,
                footer = loading.message ?: loading.exception?.localizedMessage,
            ) {
                val retry = remember { FocusRequester() }
                LaunchedEffect(Unit) { runCatching { retry.requestFocus() } }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HeroButton(stringResource(R.string.platea_retry), primary = false, modifier = Modifier.focusRequester(retry)) { viewModel.init() }
                    HeroButton(stringResource(R.string.platea_access_switch_server), primary = false) { viewModel.setupNavigationManager.navigateTo(SetupDestination.ServerList) }
                }
            }
            return
        }
        LoadingState.Success -> Unit
        else -> {
            PlateaLoading(modifier.background(PlateaColors.Ground))
            return
        }
    }

    val first = remember { FocusRequester() }
    LaunchedEffect(state.users.size) { runCatching { first.requestFocus() } }
    val unsupported =
        if (state.serverVersionSupported == ServerVersionSupported.NOT_SUPPORTED) {
            stringResource(R.string.platea_access_unsupported, state.serverVersion.orEmpty())
        } else {
            null
        }
    AccessFrame(
        title = stringResource(R.string.platea_access_user_title),
        subtitle = server.name ?: server.url,
        modifier = modifier,
        footer = error ?: unsupported,
    ) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterHorizontally),
            contentPadding = PaddingValues(horizontal = PlateaDimens.SafeHorizontal, vertical = 8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(state.users.distinctBy { it.user.id }, key = { it.user.id }) { entry ->
                UserBubble(
                    name = entry.user.name.orEmpty(),
                    imageUrl = entry.imageUrl,
                    modifier = if (entry == state.users.firstOrNull()) Modifier.focusRequester(first) else Modifier,
                    onLongClick = if (entry.known) ({ menuFor = entry.user }) else null,
                ) { choose(entry.user) }
            }
            item {
                UserBubble(
                    name = stringResource(R.string.platea_access_add_user),
                    imageUrl = null,
                    symbol = "+",
                    modifier = if (state.users.isEmpty()) Modifier.focusRequester(first) else Modifier,
                    onLongClick = null,
                ) {
                    error = null
                    signIn = SignInTarget(null)
                }
            }
        }
        Spacer(Modifier.height(22.dp))
        HeroButton(stringResource(R.string.platea_access_switch_server), primary = false) {
            viewModel.setupNavigationManager.navigateTo(SetupDestination.ServerList)
        }
    }

    menuFor?.let { user ->
        ActionMenu(
            title = user.name.orEmpty(),
            subtitle = server.name,
            actions =
                listOf(
                    MenuAction(stringResource(R.string.platea_access_sign_in)) {
                        menuFor = null
                        choose(user)
                    },
                    MenuAction(stringResource(R.string.platea_access_remove)) {
                        menuFor = null
                        viewModel.removeUser(user)
                    },
                ),
            onDismiss = { menuFor = null },
        )
    }
    signIn?.let { target ->
        SignInPanel(server, target.user, viewModel, onDismiss = {
            viewModel.cancelQuickConnect()
            signIn = null
        })
    }
    pinFor?.let { user ->
        PlateaPinVerify(
            expected = user.pin.orEmpty(),
            title = stringResource(R.string.platea_pin_enter),
            onSuccess = {
                pinFor = null
                switchTo(user)
            },
            onDismiss = { pinFor = null },
            onUsePassword = {
                pinFor = null
                signIn = SignInTarget(user)
            },
        )
    }
}

/** Who is signing in: a listed user, or null for a new one. */
private data class SignInTarget(
    val user: JellyfinUser?,
)

@Composable
private fun UserBubble(
    name: String,
    imageUrl: String?,
    modifier: Modifier,
    symbol: String? = null,
    onLongClick: (() -> Unit)?,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(96.dp)) {
        Surface(
            onClick = onClick,
            onLongClick = onLongClick,
            interactionSource = interaction,
            shape = ClickableSurfaceDefaults.shape(CircleShape),
            colors =
                ClickableSurfaceDefaults.colors(
                    containerColor = PlateaColors.Avatar,
                    contentColor = PlateaColors.Text,
                    focusedContainerColor = if (symbol != null) PlateaColors.Text else PlateaColors.Avatar,
                    focusedContentColor = if (symbol != null) PlateaColors.Ground else PlateaColors.Text,
                ),
            border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(2.5.dp, PlateaColors.Text), shape = CircleShape)),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
            modifier = modifier.size(84.dp),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(symbol ?: name.take(1).uppercase(), style = PlateaType.HeroTitle.copy(fontSize = 30.sp, fontWeight = if (symbol != null) FontWeight.Medium else FontWeight.ExtraBold))
                if (imageUrl != null) {
                    AsyncImage(model = imageUrl, contentDescription = name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }
        }
        Text(
            name,
            style = PlateaType.CardTitle.copy(fontSize = 12.sp, fontWeight = if (focused) FontWeight.Bold else FontWeight.SemiBold),
            color = if (focused) Color.White else PlateaColors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 9.dp),
        )
    }
}

@Composable
private fun SignInPanel(
    server: JellyfinServer,
    user: JellyfinUser?,
    viewModel: SwitchUserViewModel,
    onDismiss: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    var quick by remember { mutableStateOf(state.quickConnectEnabled) }
    LaunchedEffect(quick) {
        viewModel.clearSwitchUserState()
        if (quick) viewModel.initiateQuickConnect(server, user) else viewModel.cancelQuickConnect()
    }
    LaunchedEffect(Unit) { viewModel.resetAttempts() }
    Panel(onDismiss) {
        Text(stringResource(R.string.platea_access_sign_in_to, server.name ?: server.url), style = PlateaType.RowTitle.copy(fontSize = 17.sp), color = PlateaColors.Text)
        if (quick) {
            val other = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { other.requestFocus() } }
            Text(stringResource(R.string.platea_access_quick_title).uppercase(), style = PlateaType.Kicker, color = PlateaColors.Gold, modifier = Modifier.padding(top = 4.dp))
            val code = state.quickConnectStatus?.code
            if (code == null) {
                if (state.switchUserState !is LoadingState.Error) {
                    Text(stringResource(R.string.platea_access_quick_wait), style = PlateaType.Body, color = PlateaColors.TextSecondary)
                }
            } else {
                Text(stringResource(R.string.platea_access_quick_text), style = PlateaType.Body, color = PlateaColors.TextSecondary)
                Text(
                    code,
                    style = PlateaType.HeroTitle.copy(fontSize = 40.sp, letterSpacing = 6.sp),
                    color = PlateaColors.Gold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                )
            }
            ErrorText(state.switchUserState)
            HeroButton(stringResource(R.string.platea_access_use_password), primary = false, modifier = Modifier.fillMaxWidth().focusRequester(other)) { quick = false }
        } else {
            var username by remember { mutableStateOf(user?.name.orEmpty()) }
            var password by remember { mutableStateOf("") }
            // 0 none, 1 user name, 2 password
            var editing by remember { mutableStateOf(0) }
            val userField = remember { FocusRequester() }
            val passwordField = remember { FocusRequester() }
            val submit = { if (username.isNotBlank()) viewModel.login(server, user, username, password) }
            LaunchedEffect(Unit) { runCatching { if (username.isBlank()) userField.requestFocus() else passwordField.requestFocus() } }
            LaunchedEffect(password, username) { viewModel.clearSwitchUserState() }
            Text(stringResource(R.string.platea_access_username).uppercase(), style = PlateaType.Kicker, color = PlateaColors.TextMeta, modifier = Modifier.padding(top = 4.dp))
            PlateaTextField(username, stringResource(R.string.platea_access_username), Modifier.fillMaxWidth().focusRequester(userField)) { editing = 1 }
            Text(stringResource(R.string.platea_access_password).uppercase(), style = PlateaType.Kicker, color = PlateaColors.TextMeta, modifier = Modifier.padding(top = 4.dp))
            PlateaTextField(password, stringResource(R.string.platea_access_password), Modifier.fillMaxWidth().focusRequester(passwordField), password = true) { editing = 2 }
            when (editing) {
                1 ->
                    PlateaKeyboardDialog(stringResource(R.string.platea_access_username), username, onDone = {
                        username = it.trim()
                        editing = 0
                    }, onDismiss = { editing = 0 })
                2 ->
                    PlateaKeyboardDialog(stringResource(R.string.platea_access_password), password, password = true, onDone = {
                        password = it
                        editing = 0
                    }, onDismiss = { editing = 0 })
            }
            ErrorText(state.switchUserState)
            HeroButton(stringResource(R.string.platea_access_sign_in), primary = false, modifier = Modifier.fillMaxWidth()) { submit() }
            if (state.quickConnectEnabled) {
                HeroButton(stringResource(R.string.platea_access_use_quick), primary = false, modifier = Modifier.fillMaxWidth()) { quick = true }
            }
        }
    }
}
