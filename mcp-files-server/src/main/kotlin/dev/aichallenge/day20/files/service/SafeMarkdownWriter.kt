package dev.aichallenge.day20.files.service

import dev.aichallenge.day20.files.config.FilesProperties
import dev.aichallenge.day20.files.model.SaveReportResult
import org.springframework.stereotype.Service
import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

@Service
class SafeMarkdownWriter(private val properties: FilesProperties) {
    private val root: Path = properties.reportsDirectory.toAbsolutePath().normalize().also { Files.createDirectories(it) }.toRealPath()

    fun save(
        fileName: String,
        content: String,
        weatherLocationRef: String,
        articleRefs: List<String>,
        sourceUrls: List<String>,
    ): SaveReportResult {
        validate(fileName, content, weatherLocationRef, articleRefs, sourceUrls)
        val bytes = content.toByteArray(StandardCharsets.UTF_8)
        val hash = sha256(bytes)
        val target = root.resolve(fileName).normalize()
        if (!target.startsWith(root)) throw FileToolException("UNSAFE_FILE_NAME", "Destination escapes reports directory")
        if (Files.exists(target)) return existingResult(target, bytes, hash)

        val temporary = Files.createTempFile(root, ".report-", ".tmp")
        try {
            Files.write(temporary, bytes)
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target)
            } catch (_: FileAlreadyExistsException) {
                return existingResult(target, bytes, hash)
            }
        } catch (error: FileToolException) {
            throw error
        } catch (error: Exception) {
            throw FileToolException("FILE_WRITE_FAILED", "Unable to save Markdown report")
        } finally {
            Files.deleteIfExists(temporary)
        }
        return result(fileName, bytes.size, hash)
    }

    private fun validate(fileName: String, content: String, weatherLocationRef: String, articleRefs: List<String>, sourceUrls: List<String>) {
        if (fileName.isBlank() || fileName.length > 120 || !fileName.endsWith(".md") || fileName.contains("..") || fileName.any { it == '/' || it == '\\' || it == '\u0000' || it.isISOControl() } || Path.of(fileName).fileName.toString() != fileName) {
            throw FileToolException("UNSAFE_FILE_NAME", "fileName must be a safe Markdown basename")
        }
        if (content.isBlank()) throw FileToolException("INVALID_REPORT_CONTENT", "Report content must not be empty")
        if (content.toByteArray(StandardCharsets.UTF_8).size > properties.maxContentBytes) throw FileToolException("REPORT_TOO_LARGE", "Report exceeds configured UTF-8 size limit")
        if (weatherLocationRef.isBlank()) throw FileToolException("INVALID_REPORT_CONTENT", "weatherLocationRef is required")
        if (articleRefs.size != 3 || articleRefs.toSet().size != 3 || articleRefs.any { it.isBlank() }) throw FileToolException("INVALID_REPORT_CONTENT", "Exactly three unique articleRefs are required")
        if (sourceUrls.size > 10 || sourceUrls.any { runCatching { URI.create(it).scheme.equals("https", ignoreCase = true) }.getOrDefault(false).not() }) {
            throw FileToolException("INVALID_REPORT_CONTENT", "Only up to ten HTTPS source URLs are allowed")
        }
    }

    private fun existingResult(target: Path, expected: ByteArray, hash: String): SaveReportResult {
        val existing = Files.readAllBytes(target)
        if (!MessageDigest.isEqual(existing, expected)) throw FileToolException("FILE_ALREADY_EXISTS", "A report with different content already exists")
        return result(target.fileName.toString(), existing.size, hash)
    }

    private fun result(fileName: String, size: Int, hash: String) = SaveReportResult(fileName, "reports/$fileName", size, hash)

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

class FileToolException(val code: String, message: String) : RuntimeException("$code: $message")
