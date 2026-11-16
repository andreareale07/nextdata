package app.nextdata.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PersonAddAlt
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.nextdata.i18n.ProvideAppLanguage
import app.nextdata.platform.PlatformKind
import app.nextdata.platform.systemLanguageTag
import app.nextdata.shared.resources.*
import app.nextdata.state.AppController
import app.nextdata.state.BootState
import app.nextdata.state.Density
import app.nextdata.state.Languages
import app.nextdata.state.Screen
import org.jetbrains.compose.resources.stringResource

/** Width from which the two-pane layout (navigation + content) is used: tablets, landscape phones, desktop. */
private val WideBreakpoint = 600.dp

/**
 * Root composable shared by Android and desktop. [modifier] lets the host attach platform behaviour
 * (e.g. file drag & drop on desktop).
 */
@Composable
fun NextDataApp(controller: AppController, modifier: Modifier = Modifier) {
    val tag = rememberAppLanguage(controller)
    LaunchedEffect(controller) { controller.start() }
    val settings by controller.settings.state.collectAsState()
    val density = when (settings.density ?: controller.effectiveDensity()) {
        Density.Compact -> CompactSpec
        Density.Comfortable -> ComfortableSpec
    }

    val forceDark = when (settings.theme) {
        app.nextdata.state.ThemeChoice.Light -> false
        app.nextdata.state.ThemeChoice.Dark -> true
        app.nextdata.state.ThemeChoice.System -> null
    }
    NextDataTheme(density = density, reduceMotion = controller.env.reduceMotion(), forceDark = forceDark) {
        ProvideAppLanguage(tag) {
            Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                val boot by controller.boot.collectAsState()
                // Edge-to-edge: the surface fills the screen; sides/bottom/IME are padded here, the top inset is
                // handled by each top app bar (so its colour extends under the status bar) or by top-level screens.
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))) {
                when (val b = boot) {
                    BootState.Loading -> Splash()
                    is BootState.Failed -> Box(Modifier.fillMaxSize().statusBarsPadding()) {
                        ErrorState(b.error, onRetry = controller::retryBoot)
                    }
                    is BootState.Ready -> {
                        Shell(controller)
                        ConnectionDialogs(controller)
                        ViewerHost(controller)
                        val about by controller.aboutVisible.collectAsState()
                        if (about) AboutDialog(controller.env.appVersion, b.info.version.ifEmpty { null }, onDismiss = { controller.showAbout(false) })
                    }
                }
                }
            }
        }
    }
}

@Composable
private fun Splash() {
    val desc = stringResource(Res.string.loading)
    Box(Modifier.fillMaxSize().statusBarsPadding().semantics { contentDescription = desc }, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.Cloud, null, Modifier.height(56.dp).width(56.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(12.dp))
            Text(stringResource(Res.string.app_name), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(24.dp))
            CircularProgressIndicator()
        }
    }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, ExperimentalAnimationApi::class)
@Composable
private fun Shell(controller: AppController) {
    val stack by controller.backStack.collectAsState()
    val screen = stack.last()
    val browser by controller.browser.collectAsState()
    val settings by controller.settings.state.collectAsState()
    val profiles by controller.profiles.state.collectAsState()
    val touch = controller.env.kind == PlatformKind.Android

    BackHandler(enabled = screen != Screen.Profiles) { controller.back() }

    val showOnboarding = !settings.onboarded && profiles.profiles.isEmpty()
    if (showOnboarding) {
        Onboarding(controller)
        return
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= WideBreakpoint) {
            var showTransfers by remember { mutableStateOf(false) }
            var navWidth by remember { mutableStateOf(settings.navWidthDp.coerceIn(220, 460)) }
            val density = LocalDensity.current
            Row(Modifier.fillMaxSize().statusBarsPadding()) {
                ProfilesPane(controller, Modifier.width(if (touch) 280.dp else navWidth.dp).fillMaxHeight())
                if (touch) VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                else ResizeHandle(
                    onDrag = { dx -> navWidth = (navWidth + with(density) { dx.toDp().value.toInt() }).coerceIn(220, 460) },
                    onDone = { controller.settings.update { it.copy(navWidthDp = navWidth) } },
                )
                val editorRequest by controller.profileEditor.collectAsState()
                LaunchedEffect(screen, browser?.profile?.id) { controller.closeProfileEditor() }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    val req = editorRequest
                    if (req != null) {
                        key(req) { ProfileEditorPage(controller, req.profile, onDismiss = controller::closeProfileEditor) }
                        return@Box
                    }
                    Crossfade(screen, animationSpec = tween(motionMs(160)), label = "content") { s ->
                        when {
                            s == Screen.Settings -> SettingsScreen(controller, onBack = { controller.back() })
                            s == Screen.Transfers -> TransfersScreen(controller, onBack = { controller.back() })
                            s == Screen.Browser && browser != null -> Column(Modifier.fillMaxSize()) {
                                Box(Modifier.weight(1f)) {
                                    BrowserScreen(
                                        controller, browser!!, compact = false,
                                        transfersExpanded = showTransfers, onToggleTransfers = { showTransfers = !showTransfers },
                                    )
                                }
                                AnimatedVisibility(
                                    showTransfers,
                                    enter = expandVertically(tween(motionMs(200))) + fadeIn(tween(motionMs(200))),
                                    exit = shrinkVertically(tween(motionMs(160))) + fadeOut(tween(motionMs(160))),
                                ) {
                                    TransfersPanel(controller, Modifier.height(240.dp))
                                }
                            }
                            else -> Welcome(controller)
                        }
                    }
                }
            }
        } else {
            // Single pane: forward navigation slides in from the end, back slides out (predictive back friendly).
            val dur = motionMs(200)
            val editorRequest by controller.profileEditor.collectAsState()
            val transfers by controller.transfers.items.collectAsState()
            LaunchedEffect(screen, browser?.profile?.id) { controller.closeProfileEditor() }
            // Disconnecting clears `browser` at once, while the browser screen is still sliding out: keep drawing the
            // last one for the exit animation, otherwise the outgoing page flips to a second copy of Profiles (a visible jump).
            var lastBrowser by remember { mutableStateOf(browser) }
            LaunchedEffect(browser) { if (browser != null) lastBrowser = browser }
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        AnimatedContent(
                            targetState = screen,
                            transitionSpec = {
                                val forward = targetState.ordinal > initialState.ordinal
                                val d = dur
                                (slideInHorizontally(tween(d)) { w -> if (forward) w / 6 else -w / 6 } + fadeIn(tween(d))) togetherWith
                                    (slideOutHorizontally(tween(d)) { w -> if (forward) -w / 6 else w / 6 } + fadeOut(tween(d)))
                            },
                            label = "screen",
                        ) { s ->
                            when {
                                s == Screen.Settings -> SettingsScreen(controller, onBack = { controller.back() })
                                s == Screen.Transfers -> Box(Modifier.fillMaxSize().horizontalSwipe(onRight = { controller.back() })) { // back = the screen it was opened from (folder, profiles)
                                    TransfersScreen(controller, onBack = { controller.back() })
                                }
                                s == Screen.Browser && (browser ?: lastBrowser) != null -> BrowserScreen(controller, (browser ?: lastBrowser)!!, compact = true)
                                else -> Box(Modifier.fillMaxSize().horizontalSwipe(onLeft = { controller.switchTab(Screen.Transfers) })) {
                                    ProfilesScreen(controller)
                                }
                            }
                        }
                    }
                    if (screen == Screen.Profiles || screen == Screen.Settings) TransfersStrip(controller)
                    AnimatedVisibility(
                        screen in PhoneTabs,
                        enter = expandVertically(tween(motionMs(200))) + fadeIn(tween(motionMs(200))),
                        exit = shrinkVertically(tween(motionMs(160))) + fadeOut(tween(motionMs(160))),
                    ) {
                        PhoneNavBar(screen, transfers.count { it.status.isActive }, controller::switchTab)
                    }
                }
                // the editor covers the whole screen, bottom bar included
                editorRequest?.let { req ->
                    key(req) { ProfileEditorDialog(controller, req.profile, onDismiss = controller::closeProfileEditor) }
                }
            }
        }
    }
}

/** Draggable divider between the navigation pane and the content (desktop). */
@Composable
private fun ResizeHandle(onDrag: (Float) -> Unit, onDone: () -> Unit) {
    Box(
        Modifier
            .width(6.dp)
            .fillMaxHeight()
            .pointerInput(Unit) {
                detectHorizontalDragGestures(onDragEnd = onDone, onDragCancel = onDone) { change, dx ->
                    change.consume()
                    onDrag(dx)
                }
            },
        contentAlignment = Alignment.Center,
    ) { Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant)) }
}

@Composable
private fun Welcome(controller: AppController) {
    val profiles by controller.profiles.state.collectAsState()
    val empty = profiles.profiles.isEmpty()
    EmptyState(
        icon = Icons.Outlined.Storage,
        title = stringResource(if (empty) Res.string.welcome_title_empty else Res.string.welcome_title),
        body = stringResource(if (empty) Res.string.welcome_body_empty else Res.string.welcome_body),
        action = if (empty) ({ Button(onClick = controller::requestNewProfile) { Text(stringResource(Res.string.profile_add)) } }) else null,
    )
}

/** First-run screen: what NextData is, how to add a profile, and what it does (not) do with data. */
@Composable
private fun Onboarding(controller: AppController) {
    Box(Modifier.fillMaxSize().statusBarsPadding(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 520.dp).fillMaxWidth().fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        ) {
            Box(Modifier.size(80.dp).clip(MaterialTheme.shapes.extraLarge).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Cloud, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Spacer(Modifier.height(20.dp))
            Text(stringResource(Res.string.onboarding_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(8.dp))
            Text(stringResource(Res.string.onboarding_subtitle), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Spacer(Modifier.height(28.dp))
            OnboardingPoint(Icons.Outlined.FolderOpen, Res.string.onboarding_what_title, Res.string.onboarding_what_body)
            OnboardingPoint(Icons.Outlined.PersonAddAlt, Res.string.onboarding_how_title, Res.string.onboarding_how_body)
            OnboardingPoint(Icons.Outlined.Shield, Res.string.onboarding_privacy_title, Res.string.onboarding_privacy_body)
            Spacer(Modifier.height(24.dp))
            Button(onClick = { controller.markOnboarded(); controller.requestNewProfile() }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(Res.string.onboarding_start))
            }
            TextButton(onClick = controller::markOnboarded) { Text(stringResource(Res.string.onboarding_skip)) }
        }
    }
}

@Composable
private fun OnboardingPoint(icon: androidx.compose.ui.graphics.vector.ImageVector, title: org.jetbrains.compose.resources.StringResource, body: org.jetbrains.compose.resources.StringResource) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Effective UI language tag ("it"/"en") for the current setting; also applies it to the platform
 * locale so localized resources (including host-provided menus) resolve in it.
 */
@Composable
fun rememberAppLanguage(controller: AppController): String {
    val settings by controller.settings.state.collectAsState()
    val systemTag = remember { systemLanguageTag() }
    val tag = Languages.effectiveTag(settings.language, systemTag)
    remember(tag) {
        app.nextdata.platform.applyAppLocale(tag)
        tag
    }
    return tag
}
