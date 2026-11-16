package app.nextdata.desktop

import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DragOutTest {
    @Test
    fun theFilesAreProducedOnlyWhenTheDropTargetAsks() {
        var calls = 0
        val t = FilesOnDemand { calls++; listOf(File("a.txt")) }
        // describing the drag (what a drop target inspects while hovering) must not download anything
        assertTrue(t.isDataFlavorSupported(DataFlavor.javaFileListFlavor))
        assertEquals(1, t.transferDataFlavors.size)
        assertEquals(0, calls)
        @Suppress("UNCHECKED_CAST")
        val files = t.getTransferData(DataFlavor.javaFileListFlavor) as List<File>
        assertEquals(listOf(File("a.txt")), files)
        assertEquals(1, calls)
    }

    @Test
    fun otherFormatsAndFailuresAreRefused() {
        val t = FilesOnDemand { null }
        assertFalse(t.isDataFlavorSupported(DataFlavor.stringFlavor))
        assertFailsWith<UnsupportedFlavorException> { t.getTransferData(DataFlavor.stringFlavor) }
        assertFailsWith<IOException> { t.getTransferData(DataFlavor.javaFileListFlavor) }
    }

    @Test
    fun droppedItemsInsideADroppedFolderAreNotUploadedTwice() {
        val tmp = kotlin.io.path.createTempDirectory("dr").toFile()
        try {
            val dir = File(tmp, "d").apply { mkdirs() }
            val inner = File(dir, "x.txt").apply { writeText("x") }
            val other = File(tmp, "y.txt").apply { writeText("y") }
            val roots = dropRoots(listOf(dir.absolutePath, inner.absolutePath, other.absolutePath, other.absolutePath)).map { File(it).name }
            assertEquals(listOf("d", "y.txt"), roots)
        } finally { tmp.deleteRecursively() }
    }
}
