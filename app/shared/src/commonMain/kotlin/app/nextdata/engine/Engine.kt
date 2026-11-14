package app.nextdata.engine

import app.nextdata.protocol.EngineEvent
import app.nextdata.protocol.ErrInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.JsonObject

/**
 * The only door between the UI and the Rust engine.
 *
 * [execute] is a cold flow: collecting it submits the command with a fresh incremental `op`,
 * emits the correlated events (progress, prompt, and exactly one terminal event) and completes.
 * If the collector is cancelled before the terminal event, the implementation sends `cancel(op)`
 * to the engine (cooperative cancellation).
 */
interface Engine {
    fun execute(cmd: String, args: JsonObject = JsonObject(emptyMap())): Flow<EngineEvent>
}

/** Terminal `err` event from the engine. */
class EngineException(val error: ErrInfo) : Exception(error.code)

/** Terminal `cancelled` event received although the caller did not cancel. */
class EngineCancelledException : Exception("cancelled")

/**
 * Runs a command to completion and returns `ok.data`.
 * Non-terminal events are forwarded to [onEvent]. Cancelling the calling coroutine cancels the engine op.
 */
suspend fun Engine.request(
    cmd: String,
    args: JsonObject = JsonObject(emptyMap()),
    onEvent: (EngineEvent) -> Unit = {},
): JsonObject {
    var result: JsonObject? = null
    execute(cmd, args).collect { ev ->
        when (ev) {
            is EngineEvent.Ok -> result = ev.data
            is EngineEvent.Err -> throw EngineException(ev.error)
            is EngineEvent.Cancelled -> throw EngineCancelledException()
            else -> onEvent(ev)
        }
    }
    return result ?: throw EngineException(ErrInfo(app.nextdata.protocol.ErrorCodes.INTERNAL))
}
