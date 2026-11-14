package app.nextdata.bridge

/** Receives engine event lines (one JSON document per call) on an engine thread: never block here. */
fun interface EventListener {
    fun onEvent(json: String)
}

/**
 * JNI surface of the Rust engine (`nd_engine`), exactly as specified in docs/protocol.md.
 * The library must be loaded first with [NativeLoader.ensureLoaded].
 */
object NativeBridge {
    /** Submits a command line; returns the op id (== `op` of the command). */
    @JvmStatic
    external fun submit(commandJson: String): Long

    /** Registers the single event listener (null unregisters). Called on engine threads. */
    @JvmStatic
    external fun setListener(listener: EventListener?)

    @JvmStatic
    external fun cancel(op: Long)

    @JvmStatic
    external fun version(): String
}
