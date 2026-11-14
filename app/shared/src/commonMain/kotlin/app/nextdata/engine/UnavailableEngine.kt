package app.nextdata.engine

import app.nextdata.protocol.EngineEvent
import app.nextdata.protocol.ErrInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.JsonObject

/** Stand-in used when the native engine cannot be loaded: every command fails with [error], so the UI shows a localized message. */
class UnavailableEngine(private val error: ErrInfo) : Engine {
    private var op = 0L
    override fun execute(cmd: String, args: JsonObject): Flow<EngineEvent> = flowOf(EngineEvent.Err(++op, error))
}
