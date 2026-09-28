package dev.aichallenge.weather.server.file

import dev.aichallenge.weather.server.config.PipelineServerProperties
import dev.aichallenge.weather.server.pipeline.ErrorCode
import dev.aichallenge.weather.server.pipeline.Hashing
import dev.aichallenge.weather.server.pipeline.PipelineException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID

@Component
class SafeReportWriter(properties: PipelineServerProperties) {
    private val reportsDirectory: Path = Path.of(properties.reports.directory).toAbsolutePath().normalize().also(Files::createDirectories)

    data class SavedFile(val fileName: String, val relativePath: String, val sizeBytes: Long, val sha256: String)

    fun save(fileName: String, content: String): SavedFile {
        validate(fileName)
        val target = reportsDirectory.resolve(fileName).normalize()
        if (!target.startsWith(reportsDirectory)) unsafe()
        val hash = Hashing.sha256(content)
        if (Files.exists(target)) return existing(target, fileName, hash)
        val temp = reportsDirectory.resolve(".${fileName}.${UUID.randomUUID()}.tmp")
        try {
            Files.writeString(temp, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE)
            } catch (ex: AtomicMoveNotSupportedException) {
                logger.warn("event=file.atomic_move.unsupported fileName={}", fileName)
                Files.move(temp, target)
            } catch (ex: FileAlreadyExistsException) {
                return existing(target, fileName, hash)
            }
        } catch (ex: PipelineException) {
            throw ex
        } catch (ex: Exception) {
            throw PipelineException(ErrorCode.FILE_WRITE_FAILED, "Report file could not be written")
        } finally {
            Files.deleteIfExists(temp)
        }
        return result(target, fileName, hash)
    }

    private fun existing(target: Path, fileName: String, expectedHash: String): SavedFile {
        val existingHash = try { Hashing.sha256(Files.readString(target)) } catch (ex: Exception) {
            throw PipelineException(ErrorCode.FILE_WRITE_FAILED, "Existing report file could not be read")
        }
        if (existingHash != expectedHash) {
            throw PipelineException(ErrorCode.FILE_ALREADY_EXISTS, "A different report already exists with this file name")
        }
        return result(target, fileName, expectedHash)
    }

    private fun result(target: Path, fileName: String, hash: String) = SavedFile(
        fileName, "${reportsDirectory.fileName}/$fileName", Files.size(target), hash,
    )

    private fun validate(fileName: String) {
        if (fileName.isBlank() || fileName.length > 120 || !fileName.endsWith(".md", ignoreCase = false) ||
            fileName.contains('/') || fileName.contains('\\') || fileName.contains("..") ||
            fileName.any { it == '\u0000' || it.isISOControl() } || Path.of(fileName).fileName.toString() != fileName
        ) unsafe()
    }

    private fun unsafe(): Nothing = throw PipelineException(ErrorCode.UNSAFE_FILE_NAME, "Only a simple safe .md file name is allowed")

    companion object { private val logger = LoggerFactory.getLogger(SafeReportWriter::class.java) }
}
