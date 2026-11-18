package app.nextdata.desktop

import app.nextdata.protocol.ErrorCodes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DragOutTest {
    @Test
    fun effectsAreNotErrorsAndFailuresMapToAMessage() {
        assertNull(DesktopEntryDrag.failure(0)) // cancelled by the user
        assertNull(DesktopEntryDrag.failure(1)) // copied
        assertEquals(ErrorCodes.LIMIT_EXCEEDED, DesktopEntryDrag.failure(-4)?.code)
        assertEquals(ErrorCodes.SESSION_CLOSED, DesktopEntryDrag.failure(-2)?.code)
        assertEquals(ErrorCodes.IO, DesktopEntryDrag.failure(-3)?.code)
    }
}
