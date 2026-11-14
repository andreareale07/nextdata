package app.nextdata.bridge

import app.nextdata.engine.Engine
import app.nextdata.engine.NativeTransport
import app.nextdata.engine.TransportEngine
import app.nextdata.protocol.EngineEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.JsonObject

/** [NativeTransport] backed by the JNI [NativeBridge]. */
class NativeBridgeTransport : NativeTransport {
    override fun submit(commandJson: String) {
        NativeBridge.submit(commandJson)
    }

    override fun setListener(listener: ((String) -> Unit)?) {
        NativeBridge.setListener(listener?.let { cb -> EventListener { json -> if (System.getenv("ND_TRACE_ERRORS") != null || json.contains("\"ev\":\"err\"")) System.err.println("ND-EVENT " + json.take(600)); cb(json) } })
    }

    override fun cancel(op: Long) {
        NativeBridge.cancel(op)
    }
}

/**
 * The real engine: the JSON protocol over JNI. Correlation, progress/prompt delivery and
 * cancellation (`cancel(op)` when the collecting coroutine is cancelled) come from [TransportEngine].
 * The native library must already be loaded ([NativeLoader.ensureLoaded]).
 */
class JniEngine(
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    transport: NativeTransport = NativeBridgeTransport(),
) : Engine {
    private val delegate = TransportEngine(transport, scope)

    override fun execute(cmd: String, args: JsonObject): Flow<EngineEvent> = delegate.execute(cmd, args)

    fun close() = delegate.close()

    companion object {
        fun version(): String = NativeBridge.version()
    }
}
