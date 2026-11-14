package app.nextdata.engine

import app.nextdata.protocol.Codec
import app.nextdata.protocol.EngineEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Raw JSON transport; implemented by the JNI bridge (and by fakes in tests). */
interface NativeTransport {
    /** Sends one command line. Must not block. */
    fun submit(commandJson: String)

    /** Registers the (single) listener that receives event lines, on an arbitrary engine thread. */
    fun setListener(listener: ((String) -> Unit)?)

    fun cancel(op: Long)
}

/**
 * [Engine] over a [NativeTransport]: allocates incremental ops, correlates event lines to the
 * collector of the matching op, and turns collector cancellation into `cancel(op)`.
 *
 * Event lines arrive on engine threads; they are pushed (non-blocking) into an unbounded inbox
 * consumed by a single router coroutine, so the registry needs no locks.
 */
@OptIn(ExperimentalAtomicApi::class)
class TransportEngine(
    private val transport: NativeTransport,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    firstOp: Long = 1,
) : Engine {
    private sealed interface Msg {
        class Register(val op: Long, val ch: Channel<EngineEvent>) : Msg
        class Unregister(val op: Long) : Msg
        class Line(val json: String) : Msg
    }

    private val inbox = Channel<Msg>(Channel.UNLIMITED)
    private val counter = AtomicLong(firstOp - 1)

    init {
        scope.launch {
            val routes = HashMap<Long, Channel<EngineEvent>>()
            for (msg in inbox) {
                when (msg) {
                    is Msg.Register -> routes[msg.op] = msg.ch
                    is Msg.Unregister -> routes.remove(msg.op)
                    is Msg.Line -> {
                        val ev = Codec.decodeEvent(msg.json) ?: continue
                        val ch = routes[ev.op] ?: continue
                        ch.trySend(ev)
                        if (ev.isTerminal) routes.remove(ev.op)
                    }
                }
            }
        }
        transport.setListener { line -> inbox.trySend(Msg.Line(line)) }
    }

    override fun execute(cmd: String, args: JsonObject): Flow<EngineEvent> = flow {
        val op = counter.addAndFetch(1L)
        val ch = Channel<EngineEvent>(Channel.UNLIMITED)
        inbox.trySend(Msg.Register(op, ch))
        var terminal = false
        try {
            transport.submit(Codec.encodeCommand(op, cmd, args))
            for (ev in ch) {
                emit(ev)
                if (ev.isTerminal) {
                    terminal = true
                    break
                }
            }
        } finally {
            inbox.trySend(Msg.Unregister(op))
            if (!terminal) transport.cancel(op)
        }
    }

    fun close() {
        transport.setListener(null)
        inbox.close()
    }
}
