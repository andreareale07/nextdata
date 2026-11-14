package app.nextdata

import app.nextdata.engine.EngineException
import app.nextdata.engine.TransportEngine
import app.nextdata.engine.request
import app.nextdata.protocol.EngineEvent
import app.nextdata.protocol.ErrorCodes
import app.nextdata.protocol.Prompt
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TransportEngineTest {
    private fun TestScope.engine(t: FakeTransport) = TransportEngine(t, backgroundScope)

    private fun opOf(line: String) = app.nextdata.protocol.Codec.json.parseToJsonElement(line).jsonObject["op"]!!.jsonPrimitive.long

    @Test
    fun opsAreIncrementalAndEventsAreCorrelated() = runTest {
        val t = FakeTransport()
        val e = engine(t)
        val a = async { e.request("profiles.list") }
        val b = async { e.request("settings.get") }
        runCurrent()
        assertEquals(listOf(1L, 2L), t.submitted.map(::opOf))
        // answer out of order
        t.emit("""{"op":2,"ev":"ok","data":{"settings":{"show_hidden":true}}}""")
        t.emit("""{"op":1,"ev":"ok","data":{"profiles":[]}}""")
        runCurrent()
        assertTrue(b.await().containsKey("settings"))
        assertTrue(a.await().containsKey("profiles"))
        assertTrue(t.cancelled.isEmpty(), "completed ops must not be cancelled")
    }

    @Test
    fun progressAndPromptAreForwardedBeforeTerminal() = runTest {
        val t = FakeTransport()
        val e = engine(t)
        val seen = mutableListOf<EngineEvent>()
        val r = async { e.request("transfer.download", JsonObject(emptyMap())) { seen += it } }
        runCurrent()
        t.emit("""{"op":1,"ev":"prompt","data":{"prompt_id":"p1","kind":"password","profile_name":"x"}}""")
        t.emit("""{"op":1,"ev":"progress","data":{"bytes":1,"total":2,"item":"f","items_done":0,"items_total":1}}""")
        t.emit("""{"op":1,"ev":"ok","data":{"local_paths":["a"]}}""")
        runCurrent()
        assertTrue(r.await().containsKey("local_paths"))
        assertEquals(2, seen.size)
        assertIs<Prompt.Secret>((seen[0] as EngineEvent.PromptRequest).prompt)
        assertIs<EngineEvent.Progress>(seen[1])
    }

    @Test
    fun errEventBecomesEngineException() = runTest {
        val t = FakeTransport()
        val e = engine(t)
        val r = async { runCatching { e.request("session.connect") } }
        runCurrent()
        t.emit("""{"op":1,"ev":"err","err":{"code":"AUTH_FAILED","params":{},"retryable":false}}""")
        runCurrent()
        val ex = r.await().exceptionOrNull()
        assertIs<EngineException>(ex)
        assertEquals(ErrorCodes.AUTH_FAILED, ex.error.code)
    }

    @Test
    fun cancellingTheCoroutineSendsCancelForThatOpOnly() = runTest {
        val t = FakeTransport()
        val e = engine(t)
        val keep = async { e.request("a") }
        val doomed = launch { e.request("transfer.upload") }
        runCurrent()
        assertEquals(listOf(1L, 2L), t.submitted.map(::opOf))
        doomed.cancel()
        runCurrent()
        assertEquals(listOf(2L), t.cancelled)
        // the engine confirms with `cancelled`: ignored, and op 1 is unaffected
        t.emit("""{"op":2,"ev":"cancelled"}""")
        t.emit("""{"op":1,"ev":"ok","data":{}}""")
        runCurrent()
        keep.await()
        assertEquals(listOf(2L), t.cancelled)
    }

    @Test
    fun cancelAfterTerminalIsNotSent() = runTest {
        val t = FakeTransport()
        val e = engine(t)
        val job = launch { e.request("x") }
        runCurrent()
        t.emit("""{"op":1,"ev":"ok","data":{}}""")
        runCurrent()
        job.join()
        assertTrue(t.cancelled.isEmpty())
    }

    @Test
    fun stoppingTheFlowEarlyCancelsTheOp() = runTest {
        val t = FakeTransport()
        val e = engine(t)
        val first = async { e.execute("transfer.download").first() }
        runCurrent()
        t.emit("""{"op":1,"ev":"progress","data":{"bytes":1,"total":2,"item":"f","items_done":0,"items_total":1}}""")
        runCurrent()
        assertIs<EngineEvent.Progress>(first.await())
        assertEquals(listOf(1L), t.cancelled)
    }

    @Test
    fun eventsForUnknownOpsAndGarbageAreIgnored() = runTest {
        val t = FakeTransport()
        val e = engine(t)
        val r = async { e.request("x") }
        runCurrent()
        t.emit("garbage")
        t.emit("""{"op":99,"ev":"ok","data":{}}""")
        t.emit("""{"op":1,"ev":"ok","data":{"v":5}}""")
        runCurrent()
        assertEquals(5, r.await()["v"]!!.jsonPrimitive.int)
    }

    @Test
    fun cancelledEventThrowsWhenNotRequested() = runTest {
        val t = FakeTransport()
        val e = engine(t)
        val r = async { runCatching { e.request("x") } }
        runCurrent()
        t.emit("""{"op":1,"ev":"cancelled"}""")
        runCurrent()
        assertFailsWith<app.nextdata.engine.EngineCancelledException> { r.await().getOrThrow() }
    }
}
