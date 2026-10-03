package org.wardriver.pwnpal

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class ReviewRegressionTest {
    @Test fun stderrOnlyFloodIsBounded() {
        val stream=BoundedOutputStream(1024)
        repeat(100){stream.write(ByteArray(8192))}
        assertTrue(stream.exceeded)
        assertEquals(1024,stream.size())
    }
    @Test fun exactLimitAndSingleByteOverflow() {
        val stream=BoundedOutputStream(3)
        stream.write("abc".toByteArray())
        assertFalse(stream.exceeded)
        stream.write(100)
        assertTrue(stream.exceeded)
        assertEquals("abc",stream.text())
    }
    @Test fun exportSurvivesNewOwnerInstance() {
        val folder=Files.createTempDirectory("pwnpal-export").toFile()
        try {
            val id=PendingExport(folder).create("configuration snapshot")
            assertEquals("configuration snapshot",String(PendingExport(folder).read(id)))
            PendingExport(folder).remove(id)
            assertTrue(runCatching{PendingExport(folder).read(id)}.isFailure)
        } finally {folder.deleteRecursively()}
    }
    @Test fun missingOrEmptyExportCannotBeRead() {
        val folder=Files.createTempDirectory("pwnpal-export").toFile()
        try {
            val store=PendingExport(folder)
            assertTrue(runCatching{store.create("")}.isFailure)
            assertTrue(runCatching{store.read("../elsewhere")}.isFailure)
            val id=store.create("before")
            java.io.File(folder,id).writeText("")
            assertTrue(runCatching{store.read(id)}.isFailure)
        } finally {folder.deleteRecursively()}
    }
}
