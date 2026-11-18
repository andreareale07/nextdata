package app.nextdata.state

import app.nextdata.engine.Engine
import app.nextdata.engine.EngineClient
import app.nextdata.platform.AppEnvironment
import app.nextdata.protocol.ErrInfo
import app.nextdata.protocol.InitResult
import app.nextdata.protocol.Profile
import app.nextdata.protocol.ProfileSecrets
import app.nextdata.protocol.ProtocolId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

sealed interface BootState {
    data object Loading : BootState
    data class Ready(val info: InitResult) : BootState
    data class Failed(val error: ErrInfo) : BootState
}

sealed interface ConnectionState {
    data object Idle : ConnectionState
    data class Connecting(val profile: Profile) : ConnectionState
    /** [afterTrustPrompt]: the user was asked to confirm a host key/certificate during this attempt. */
    data class Failed(val profile: Profile, val error: ErrInfo, val afterTrustPrompt: Boolean = false) : ConnectionState
}

enum class Screen { Profiles, Browser, Transfers, Settings }

/** Outcome of "Test connection": the saved profile and, if the connection failed, why. */
data class ProfileTestResult(val profile: Profile, val error: ErrInfo?, val afterTrustPrompt: Boolean = false)

/** Root of the shared UI state: boots the engine, owns navigation and the single open session. */
@OptIn(ExperimentalEncodingApi::class)
class AppController(
    val env: AppEnvironment,
    engine: Engine,
    val scope: CoroutineScope,
) {
    val client = EngineClient(engine)
    val prompts = PromptCoordinator(scope, client)
    val settings = SettingsStore(client, scope)
    val profiles = ProfilesViewModel(client, scope)
    val transfers = TransfersViewModel(client, scope, files = env.files)
    val profileTransfer = ProfileTransferService(client, env.files, profiles)
    val opener = OpenManager(client, scope, env.files, autoSaveExternal = { settings.state.value.autoSaveExternal }, mediaAvailable = { env.mediaPlayer != null })

    private val _boot = MutableStateFlow<BootState>(BootState.Loading)
    val boot: StateFlow<BootState> = _boot.asStateFlow()

    private val _screen = MutableStateFlow(listOf(Screen.Profiles))
    /** Navigation back stack; last = visible. Desktop shows profiles in a side panel regardless. */
    val backStack: StateFlow<List<Screen>> = _screen.asStateFlow()

    private val _browser = MutableStateFlow<BrowserViewModel?>(null)
    val browser: StateFlow<BrowserViewModel?> = _browser.asStateFlow()

    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    private var connectJob: Job? = null

    private val _security = MutableStateFlow<SecurityInfo?>(null)

    /** Security summary of the open connection (null when disconnected). */
    val security: StateFlow<SecurityInfo?> = _security.asStateFlow()

    /** Effective density: the user's choice, else comfortable on touch devices and compact on desktop. */
    fun effectiveDensity(): Density = settings.state.value.density
        ?: if (env.kind == app.nextdata.platform.PlatformKind.Desktop) Density.Compact else Density.Comfortable

    private val _newProfileRequested = MutableStateFlow(false)

    /** True while a host-level command (menu, onboarding) wants the "new profile" guide opened; consumed by the UI. */
    val newProfileRequested: StateFlow<Boolean> = _newProfileRequested.asStateFlow()

    fun requestNewProfile() {
        _newProfileRequested.value = true
    }

    fun consumeNewProfileRequest() {
        _newProfileRequested.value = false
    }

    private val _editProfileRequested = MutableStateFlow<Profile?>(null)

    /** The profile whose editor the UI should open (e.g. from a failed connection); consumed by the UI. */
    val editProfileRequested: StateFlow<Profile?> = _editProfileRequested.asStateFlow()

    fun requestEditProfile(profile: Profile) {
        _editProfileRequested.value = profile
    }

    fun consumeEditProfileRequest() {
        _editProfileRequested.value = null
    }

    /** Wide layouts show the profile editor in the content area instead of a dialog; [profile] null = new profile. */
    class ProfileEditorRequest(val profile: Profile?)

    private val _profileEditor = MutableStateFlow<ProfileEditorRequest?>(null)
    val profileEditor: StateFlow<ProfileEditorRequest?> = _profileEditor.asStateFlow()

    fun openProfileEditor(profile: Profile?) {
        _profileEditor.value = ProfileEditorRequest(profile)
    }

    fun closeProfileEditor() {
        _profileEditor.value = null
    }

    private val _about = MutableStateFlow(false)
    val aboutVisible: StateFlow<Boolean> = _about.asStateFlow()

    fun showAbout(visible: Boolean) {
        _about.value = visible
    }

    val currentScreen: Screen get() = _screen.value.last()

    // ------------------------------------------------------------------ boot

    private var started = false

    /** Boots once; further calls are no-ops (see [retryBoot]). */
    fun start() {
        if (started) return
        started = true
        scope.launch { boot() }
    }

    fun retryBoot() {
        scope.launch { boot() }
    }

    suspend fun boot() {
        _boot.value = BootState.Loading
        try {
            val pmk = withContext(Dispatchers.Default) { env.secretStore.loadOrCreatePmk() }
            val info = try {
                client.init(env.dataDir, Base64.encode(pmk))
            } finally {
                pmk.fill(0)
            }
            profiles.setProtocols(info.protocols)
            settings.load()
            profiles.load()
            _boot.value = BootState.Ready(info)
            scope.launch { opener.refreshPending(announce = true) }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            _boot.value = BootState.Failed(e.toErrInfo())
        }
    }

    // ------------------------------------------------------------------ navigation

    fun navigate(screen: Screen) {
        _screen.update { st ->
            when {
                screen == Screen.Profiles -> listOf(Screen.Profiles)
                st.last() == screen -> st
                else -> st + screen
            }
        }
    }

    /**
     * Bottom-bar / swipe navigation between the top-level phone screens: Transfers and Settings replace each other
     * instead of piling up, so "back" always leads to where the user came from (Profiles, or the open connection).
     */
    fun switchTab(target: Screen) {
        if (target == Screen.Profiles) {
            navigate(Screen.Profiles)
            return
        }
        _screen.update { st ->
            val base = st.dropLastWhile { it == Screen.Transfers || it == Screen.Settings }.ifEmpty { listOf(Screen.Profiles) }
            base + target
        }
    }

    /** Handles a "back" gesture/key. Returns true if consumed. */
    fun back(): Boolean {
        val st = _screen.value
        return when (st.last()) {
            Screen.Browser -> {
                val b = _browser.value
                // a failing listing must never trap the user: back then leaves the connection
                if (b != null && b.state.value.canGoUp && b.state.value.error == null) {
                    b.up()
                } else {
                    disconnect()
                }
                true
            }
            Screen.Profiles -> false
            else -> {
                _screen.update { it.dropLast(1).ifEmpty { listOf(Screen.Profiles) } }
                true
            }
        }
    }

    // ------------------------------------------------------------------ connection

    fun connect(profile: Profile, reconnectFrom: String? = null, resumeRef: String? = null) {
        if (_connection.value is ConnectionState.Connecting) return
        disconnect(navigateToProfiles = false)
        _connection.value = ConnectionState.Connecting(profile)
        prompts.resetLastAccepted()
        connectJob = scope.launch {
            when (val r = outcome { client.connect(profile.id) }) {
                is Outcome.Success -> {
                    val vm = BrowserViewModel(
                        profile, r.value.session, r.value.homeRef, client, scope, settings.state, transfers, env.files,
                        // A start folder configured in the profile always wins; the last visited folder is restored
                        // only for profiles without one (otherwise an old "last folder" sent users back to the root).
                        // On a reconnect the folder being browsed always wins (e.g. coming back from the file picker).
                        initialRef = resumeRef ?: settings.state.value.lastRefs[profile.id].takeIf { profile.basePath.isBlank() },
                        askDownloadLocation = { settings.state.value.askDownloadLocation },
                        onLocation = { ref, name -> rememberLocation(profile.id, ref, name) },
                        opener = opener,
                    )
                    _security.value = SecurityInfo.from(profile, prompts.lastAccepted)
                    opener.recover(r.value.session) // settle saves interrupted by a crash or a lost connection
                    _browser.value = vm
                    if (reconnectFrom != null) transfers.remapSession(reconnectFrom, r.value.session)
                    _connection.value = ConnectionState.Idle
                    _screen.value = listOf(Screen.Profiles, Screen.Browser)
                }
                is Outcome.Failure -> {
                    val afterPrompt = prompts.trustPromptSeen
                    prompts.clear()
                    _connection.value = ConnectionState.Failed(profile, r.error, afterPrompt)
                }
            }
        }
    }

    /** User cancelled the "connecting…" dialog: the coroutine cancellation makes the engine layer send `cancel`. */
    fun cancelConnect() {
        connectJob?.cancel()
        connectJob = null
        prompts.clear()
        _connection.value = ConnectionState.Idle
    }

    fun dismissConnectionError() {
        if (_connection.value is ConnectionState.Failed) _connection.value = ConnectionState.Idle
    }

    fun retryConnect() {
        val c = _connection.value as? ConnectionState.Failed ?: return
        _connection.value = ConnectionState.Idle
        connect(c.profile)
    }

    /** Persists the last folder of [profileId] and the recents list (most recent first, no duplicates). */
    private fun rememberLocation(profileId: String, ref: String, name: String) {
        settings.update { s ->
            if (s.lastRefs[profileId] == ref && s.recents.firstOrNull()?.let { it.profileId == profileId && it.ref == ref } == true) s
            else s.copy(
                lastRefs = s.lastRefs + (profileId to ref),
                recents = (listOf(RecentFolder(profileId, ref, name)) + s.recents.filterNot { it.profileId == profileId && it.ref == ref })
                    .take(AppSettings.MAX_RECENTS),
            )
        }
    }

    /**
     * "Test connection": saves the profile (the engine connects by profile id), connects and closes the
     * session. Prompts (trust, secrets) are shown by the usual dialogs. Returns the saved profile.
     */
    suspend fun testProfile(profile: Profile, secrets: ProfileSecrets?): Outcome<ProfileTestResult> {
        val saved = when (val s = profiles.save(profile, secrets)) {
            is Outcome.Success -> s.value
            is Outcome.Failure -> return s
        }
        prompts.resetLastAccepted()
        return when (val c = outcome { client.connect(saved.id) }) {
            is Outcome.Success -> {
                outcome { client.closeSession(c.value.session) }
                Outcome.Success(ProfileTestResult(saved, null))
            }
            is Outcome.Failure -> {
                val afterPrompt = prompts.trustPromptSeen
                prompts.clear()
                // the profile exists now: the caller keeps its id so that saving does not create a duplicate
                Outcome.Success(ProfileTestResult(saved, c.error, afterPrompt))
            }
        }
    }

    fun markOnboarded() = settings.update { it.copy(onboarded = true) }

    fun disconnect(navigateToProfiles: Boolean = true) {
        _browser.value?.close()
        _browser.value = null
        _security.value = null
        if (navigateToProfiles) _screen.value = listOf(Screen.Profiles)
    }

    /** Opens a fresh session for the profile currently browsed (after the connection was lost). */
    fun reconnect() {
        val b = _browser.value ?: return
        connect(b.profile, reconnectFrom = b.sessionId, resumeRef = b.state.value.ref)
    }

    private var probing = false

    /**
     * Call when the app comes back to the foreground: mobile systems cut idle TCP connections of background apps,
     * so the open session is probed and, if it is gone, silently re-opened (same folder, transfers resumed).
     */
    fun onAppResumed() {
        val b = _browser.value ?: return
        if (probing || _connection.value is ConnectionState.Connecting) return
        probing = true
        scope.launch {
            try {
                val err = b.probe()
                if (err != null && err.isConnectionLost() && _browser.value === b) reconnect()
            } finally {
                probing = false
            }
        }
    }

    /** Called when the profile being browsed has been deleted or edited in a way that invalidates the session. */
    fun onProfileRemoved(id: String) {
        if (_browser.value?.profile?.id == id) disconnect()
    }

    /** Drag & drop (desktop): uploads into the directory currently displayed. */
    fun uploadDropped(paths: List<String>, totalBytes: Long? = null) {
        _browser.value?.uploadPaths(paths, totalBytes)
    }

    fun isProtocolUsable(profile: Profile): Boolean {
        val info = profiles.state.value.protocolInfo(profile.protocol)
        return info?.enabled ?: (profile.protocol != ProtocolId.SMB)
    }
}
