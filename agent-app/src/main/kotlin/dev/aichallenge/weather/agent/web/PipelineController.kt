package dev.aichallenge.weather.agent.web

import dev.aichallenge.weather.agent.pipeline.CreatePipelineRequest
import dev.aichallenge.weather.agent.pipeline.CreatePipelineResponse
import dev.aichallenge.weather.agent.pipeline.McpEventList
import dev.aichallenge.weather.agent.pipeline.McpReport
import dev.aichallenge.weather.agent.pipeline.PipelineEventStreamService
import dev.aichallenge.weather.agent.pipeline.PipelineExecutionService
import dev.aichallenge.weather.agent.pipeline.PipelineQueryService
import dev.aichallenge.weather.agent.pipeline.PipelineView
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

@RestController
@RequestMapping("/api/pipelines")
@Validated
class PipelineController(
    private val execution: PipelineExecutionService,
    private val queries: PipelineQueryService,
    private val streams: PipelineEventStreamService,
) {
    @PostMapping
    fun create(@Valid @RequestBody request: CreatePipelineRequest): ResponseEntity<CreatePipelineResponse> =
        ResponseEntity.status(HttpStatus.ACCEPTED).body(execution.submit(request.message))

    @GetMapping("/{runId}")
    fun get(@PathVariable runId: String): PipelineView = queries.get(runId)

    @GetMapping("/{runId}/events")
    fun events(
        @PathVariable runId: String,
        @RequestParam(defaultValue = "0") @Min(0) after: Long,
        @RequestParam(defaultValue = "100") @Min(1) @Max(500) limit: Int,
    ): McpEventList = queries.events(runId, after, limit)

    @GetMapping("/{runId}/events/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun stream(
        @PathVariable runId: String,
        @RequestHeader("Last-Event-ID", required = false) lastEventId: String?,
    ): SseEmitter = streams.stream(runId, lastEventId?.toLongOrNull())

    @GetMapping("/{runId}/report", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun report(@PathVariable runId: String): McpReport = queries.report(runId)
}
