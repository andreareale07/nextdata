package app.nextdata.state

import app.nextdata.engine.EngineClient
import app.nextdata.protocol.ErrInfo
import app.nextdata.protocol.ExportOptions
import app.nextdata.protocol.ImportResult
import app.nextdata.protocol.Profile
import app.nextdata.protocol.ProfileSecrets
import app.nextdata.protocol.ProtocolId
import app.nextdata.protocol.ProtocolInfo
import app.nextdata.platform.PlatformFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

data class ProfilesState(
    val profiles: List<Profile> = emptyList(),
    val protocols: List<ProtocolInfo> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
    val error: ErrInfo? = null,
) {
    fun protocolInfo(id: String): ProtocolInfo? = protocols.firstOrNull { it.id == id }

    /** Protocols the editor offers: the known ones the engine reports, SMB included (disabled). */
    fun offeredProtocols(): List<ProtocolInfo> = ProtocolId.known.mapNotNull { id ->
        val info = protocolInfo(id)
        when {
            id == ProtocolId.FAKE -> info?.takeIf { it.enabled }
            info != null -> info
            id == ProtocolId.SMB -> ProtocolInfo(ProtocolId.SMB, false, null)
            else -> null
        }
    }
}

enum class ExportOutcome { Saved, Cancelled }

class ProfilesViewModel(private val client: EngineClient, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(ProfilesState())
    val state: StateFlow<ProfilesState> = _state.asStateFlow()

    fun setProtocols(protocols: List<ProtocolInfo>) = _state.update { it.copy(protocols = protocols) }

    fun refresh() {
        scope.launch { load() }
    }

    suspend fun load() {
        _state.update { it.copy(loading = true, error = null) }
        when (val r = outcome { client.profilesList() }) {
            is Outcome.Success -> _state.update { it.copy(profiles = r.value, loading = false, loaded = true) }
            is Outcome.Failure -> _state.update { it.copy(loading = false, loaded = true, error = r.error) }
        }
    }

    /** Saves a profile; secrets (if any) go to the engine in this call only. Returns the saved profile or the error. */
    suspend fun save(profile: Profile, secrets: ProfileSecrets?): Outcome<Profile> {
        val r = outcome { client.profilesSave(profile, secrets) }
        if (r is Outcome.Success) {
            _state.update { st ->
                val idx = st.profiles.indexOfFirst { it.id == r.value.id }
                st.copy(profiles = if (idx >= 0) st.profiles.toMutableList().also { it[idx] = r.value } else st.profiles + r.value)
            }
        }
        return r
    }

    suspend fun delete(id: String): ErrInfo? {
        val r = outcome { client.profilesDelete(id) }
        if (r is Outcome.Success) _state.update { st -> st.copy(profiles = st.profiles.filterNot { it.id == id }) }
        return r.errorOrNull
    }

    /** Next default colour for a new profile. */
    fun nextColor(): String = ProfileVisuals.defaultColorFor(_state.value.profiles.size)
}

/** Export/import of `.ndprofile` files (encryption is done by the engine; the UI only moves bytes). */
@OptIn(ExperimentalEncodingApi::class)
class ProfileTransferService(
    private val client: EngineClient,
    private val files: PlatformFiles,
    private val profiles: ProfilesViewModel,
) {
    suspend fun export(passphrase: String, options: ExportOptions, suggestedName: String): Outcome<ExportOutcome> {
        val r = outcome { client.profilesExport(passphrase, options) }
        if (r is Outcome.Failure) return r
        val bytes = Base64.decode((r as Outcome.Success).value)
        val saved = files.saveBytes(suggestedName, bytes)
        return Outcome.Success(if (saved) ExportOutcome.Saved else ExportOutcome.Cancelled)
    }

    /** Lets the user pick a file; null if cancelled. */
    suspend fun pickImportFile(): ByteArray? = files.pickBytes(listOf("ndprofile"))

    suspend fun preview(file: ByteArray, passphrase: String): Outcome<ImportResult> =
        outcome { client.profilesImport(Base64.encode(file), passphrase, apply = false) }

    suspend fun apply(file: ByteArray, passphrase: String): Outcome<ImportResult> {
        val r = outcome { client.profilesImport(Base64.encode(file), passphrase, apply = true) }
        if (r is Outcome.Success) profiles.load()
        return r
    }
}
