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

    /**
     * Desktop (Windows) only. Starts the system drag of [refsJson] (a JSON array of remote refs of [session]) as VIRTUAL
     * files: Explorer reads them from the server while it copies and shows its own cursor and progress. Blocks the calling
     * thread until the drop is over (call it from a thread of its own). Returns the effect (1 = copied, 0 = cancelled or
     * not accepted) or a negative code: -1 failure, -2 no such session, -3 the list could not be read, -4 too many items.
     */
    @JvmStatic
    external fun dragOut(session: String, refsJson: String, token: Long): Int

    /** Progress of the drag [token] (the caller's own number, several can run at once): `[bytes copied, total bytes (0 = unknown), 1 once Explorer is copying, 1 once the drag itself is over]`. */
    @JvmStatic
    external fun dragOutProgress(token: Long): LongArray?

    /** Stops the copy of the drag [token] (Explorer is told the read failed and abandons it). */
    @JvmStatic
    external fun dragOutCancel(token: Long)
}
