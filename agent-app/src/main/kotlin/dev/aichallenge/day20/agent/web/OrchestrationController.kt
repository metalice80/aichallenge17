package dev.aichallenge.day20.agent.web

import dev.aichallenge.day20.agent.config.AppProperties
import dev.aichallenge.day20.agent.orchestration.OrchestrationService
import dev.aichallenge.day20.agent.repository.OrchestrationEventRepository
import dev.aichallenge.day20.agent.repository.OrchestrationRunRepository
import dev.aichallenge.day20.agent.repository.RunStatus
import dev.aichallenge.day20.agent.repository.ToolInvocationRepository
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.nio.file.Files

@RestController
@RequestMapping("/api/orchestrations")
class OrchestrationController(
    private val service: OrchestrationService,
    private val runs: OrchestrationRunRepository,
    private val invocations: ToolInvocationRepository,
    private val events: OrchestrationEventRepository,
    private val eventStream: OrchestrationEventStream,
    private val properties: AppProperties,
) {
    data class StartRequest(val message: String?)

    @PostMapping
    fun start(@RequestBody request: StartRequest): ResponseEntity<Map<String, String>> {
        val message = request.message?.trim().orEmpty()
        if (message.isEmpty() || message.length > 3000) throw ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "message must contain 1 to 3000 characters")
        val runId = try { service.start(message) } catch (exception: IllegalStateException) {
            throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, "MCP_SERVER_UNAVAILABLE", exception.message ?: "MCP unavailable")
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(mapOf(
            "runId" to runId,
            "status" to "CREATED",
            "statusUrl" to "/api/orchestrations/$runId",
            "eventsUrl" to "/api/orchestrations/$runId/events/stream",
        ))
    }

    @GetMapping("/{runId}")
    fun status(@PathVariable runId: String): Map<String, Any?> {
        val run = runs.find(runId) ?: throw ApiException(HttpStatus.NOT_FOUND, "ORCHESTRATION_NOT_FOUND", "Unknown orchestration run")
        return linkedMapOf(
            "id" to run.id, "status" to run.status, "model" to run.modelId, "createdAt" to run.createdAt,
            "startedAt" to run.startedAt, "finishedAt" to run.finishedAt, "finalAnswer" to run.finalAnswer,
            "reportPath" to run.reportPath, "errorCode" to run.errorCode, "errorMessage" to run.errorMessage,
            "toolInvocations" to invocations.list(runId),
        )
    }

    @GetMapping("/{runId}/events")
    fun listEvents(@PathVariable runId: String, @RequestParam(defaultValue = "0") after: Int, @RequestParam(defaultValue = "100") limit: Int): Any {
        if (runs.find(runId) == null) throw ApiException(HttpStatus.NOT_FOUND, "ORCHESTRATION_NOT_FOUND", "Unknown orchestration run")
        return events.list(runId, after.coerceAtLeast(0), limit)
    }

    @GetMapping("/{runId}/events/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun stream(
        @PathVariable runId: String,
        @RequestParam(defaultValue = "0") after: Int,
        @RequestHeader(name = "Last-Event-ID", required = false) lastEventId: String?,
    ): SseEmitter {
        if (runs.find(runId) == null) throw ApiException(HttpStatus.NOT_FOUND, "ORCHESTRATION_NOT_FOUND", "Unknown orchestration run")
        return eventStream.open(runId, maxOf(after, lastEventId?.toIntOrNull() ?: 0))
    }

    @GetMapping("/{runId}/report", produces = ["text/markdown;charset=UTF-8"])
    fun report(@PathVariable runId: String): ResponseEntity<String> {
        val run = runs.find(runId) ?: throw ApiException(HttpStatus.NOT_FOUND, "ORCHESTRATION_NOT_FOUND", "Unknown orchestration run")
        if (run.status != RunStatus.COMPLETED || run.reportPath.isNullOrBlank()) throw ApiException(HttpStatus.CONFLICT, "ORCHESTRATION_INCOMPLETE", "Report is not available")
        val root = properties.files.reportsDirectory.toAbsolutePath().normalize()
        val fileName = run.reportPath.substringAfterLast('/')
        val path = root.resolve(fileName).normalize()
        if (!path.startsWith(root) || !Files.isRegularFile(path)) throw ApiException(HttpStatus.NOT_FOUND, "FILE_WRITE_FAILED", "Saved report file is unavailable")
        return ResponseEntity.ok(Files.readString(path))
    }
}
