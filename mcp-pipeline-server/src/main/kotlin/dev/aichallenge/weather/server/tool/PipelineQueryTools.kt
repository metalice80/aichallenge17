package dev.aichallenge.weather.server.tool

import dev.aichallenge.weather.server.pipeline.ArtifactType
import dev.aichallenge.weather.server.pipeline.ErrorCode
import dev.aichallenge.weather.server.pipeline.PipelineException
import dev.aichallenge.weather.server.pipeline.PipelineStore
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component

@Component
class PipelineQueryTools(private val store: PipelineStore) {
    @McpTool(name = "create_pipeline_run", description = "INTERNAL — never expose to the language model. Create a pipeline run and its pending steps.", generateOutputSchema = true)
    fun create(
        @McpToolParam(description = "Original user request", required = true) requestText: String,
        @McpToolParam(description = "Configured chat model identifier", required = true) modelId: String,
    ): RunResult = store.createRun(requestText, modelId).let { RunResult(it.run, it.steps) }

    @McpTool(name = "mark_pipeline_agent_started", description = "INTERNAL — never expose to the language model. Mark a created run as running.", generateOutputSchema = true)
    fun start(@McpToolParam(description = "Pipeline run identifier", required = true) runId: String): RunResult =
        store.markAgentStarted(runId).let { RunResult(it.run, it.steps) }

    @McpTool(name = "fail_pipeline_run", description = "INTERNAL — never expose to the language model. Fail a non-terminal run without overwriting a terminal result.", generateOutputSchema = true)
    fun fail(
        @McpToolParam(description = "Pipeline run identifier", required = true) runId: String,
        @McpToolParam(description = "Stable error code", required = true) errorCode: String,
        @McpToolParam(description = "Safe error message", required = true) errorMessage: String,
    ): RunResult {
        val code = runCatching { ErrorCode.valueOf(errorCode) }.getOrDefault(ErrorCode.INTERNAL_ERROR)
        return store.failRun(runId, code, errorMessage.take(500)).let { RunResult(it.run, it.steps) }
    }

    @McpTool(name = "get_pipeline_run", description = "INTERNAL — never expose to the language model. Read run state and steps without artifact content.", generateOutputSchema = true)
    fun get(@McpToolParam(description = "Pipeline run identifier", required = true) runId: String): RunResult =
        store.getRun(runId).let { RunResult(it.run, it.steps) }

    @McpTool(name = "list_pipeline_events", description = "INTERNAL — never expose to the language model. List append-only events in ascending sequence order.", generateOutputSchema = true)
    fun events(
        @McpToolParam(description = "Pipeline run identifier", required = true) runId: String,
        @McpToolParam(description = "Return events after this sequence", required = true) afterSequence: Long,
        @McpToolParam(description = "Maximum result count from 1 to 500", required = true) limit: Int,
    ): EventListResult = store.listEvents(runId, afterSequence, limit).let { EventListResult(it.runId, it.events) }

    @McpTool(name = "get_pipeline_report", description = "INTERNAL — never expose to the language model. Return the saved report for a completed run.", generateOutputSchema = true)
    fun report(@McpToolParam(description = "Pipeline run identifier", required = true) runId: String): ReportResult {
        val run = store.getRun(runId).run
        val artifact = store.artifactsForRun(runId).lastOrNull { it.type == ArtifactType.SAVED_REPORT }
            ?: throw PipelineException(ErrorCode.INVALID_ARTIFACT, "Saved report is not available")
        if (artifact.content.length > 200_000) throw PipelineException(ErrorCode.INVALID_ARTIFACT, "Saved report is too large to preview")
        return ReportResult(runId, run.resultFile?.substringAfterLast('/') ?: "report.md", artifact.content)
    }
}
