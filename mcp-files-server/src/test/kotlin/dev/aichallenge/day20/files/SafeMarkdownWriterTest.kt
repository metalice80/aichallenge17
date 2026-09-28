package dev.aichallenge.day20.files

import dev.aichallenge.day20.files.config.FilesProperties
import dev.aichallenge.day20.files.service.FileToolException
import dev.aichallenge.day20.files.service.SafeMarkdownWriter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SafeMarkdownWriterTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `write is atomic confined and idempotent`() {
        val writer = SafeMarkdownWriter(FilesProperties(directory, 1024))
        val refs = listOf("a", "b", "c")
        val urls = listOf("https://example.test/a")
        val first = writer.save("trip.md", "# Trip", "loc-1", refs, urls)
        val second = writer.save("trip.md", "# Trip", "loc-1", refs, urls)
        assertEquals(first.sha256, second.sha256)
        assertEquals("# Trip", Files.readString(directory.resolve("trip.md")))
        assertTrue(directory.resolve("trip.md").toRealPath().startsWith(directory.toRealPath()))
        assertThrows(FileToolException::class.java) { writer.save("trip.md", "different", "loc-1", refs, urls) }
    }

    @Test
    fun `unsafe paths invalid evidence and oversized content are rejected`() {
        val writer = SafeMarkdownWriter(FilesProperties(directory, 8))
        val refs = listOf("a", "b", "c")
        listOf("../trip.md", "/tmp/trip.md", "sub/trip.md", "trip.txt", "bad\u0000.md").forEach { name ->
            assertThrows(FileToolException::class.java) { writer.save(name, "ok", "loc", refs, emptyList()) }
        }
        assertThrows(FileToolException::class.java) { writer.save("trip.md", "ok", "loc", listOf("a", "a", "b"), emptyList()) }
        assertThrows(FileToolException::class.java) { writer.save("trip.md", "123456789", "loc", refs, emptyList()) }
        assertThrows(FileToolException::class.java) { writer.save("trip.md", "ok", "loc", refs, listOf("http://unsafe.test")) }
    }
}
