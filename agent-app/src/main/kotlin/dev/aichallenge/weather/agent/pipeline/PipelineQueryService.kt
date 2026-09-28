package dev.aichallenge.weather.agent.pipeline

import org.springframework.stereotype.Service
import java.time.Duration

@Service
class PipelineQueryService(private val mcp: McpPipelineClient) {
    fun get(runId: String): PipelineView {
        val result = callOrNotFound(runId) {
            mcp.call("get_pipeline_run", mapOf("runId" to runId), McpRunResult::class.java)
        }
        val run = result.run
        val end = run.finishedAt ?: run.updatedAt
        return PipelineView(
            run.id, run.status, run.currentStep, run.modelId, run.createdAt, run.startedAt, run.finishedAt,
            run.startedAt?.let { Duration.between(it, end).toMillis().coerceAtLeast(0) }, run.resultFile,
            run.errorCode?.let { PipelineError(it, run.errorMessage ?: "Pipeline failed", runId = run.id, step = run.currentStep) },
            result.steps.map { step ->
                StepView(step.id, step.sequenceNumber, step.toolName, step.status, step.inputArtifactId,
                    step.outputArtifactId, step.durationMs,
                    step.errorCode?.let { PipelineError(it, step.errorMessage ?: "Step failed", runId = run.id, step = step.toolName) })
            },
        )
    }

    fun events(runId: String, after: Long, limit: Int): McpEventList = callOrNotFound(runId) {
        mcp.call(
            "list_pipeline_events",
            mapOf("runId" to runId, "afterSequence" to after, "limit" to limit),
            McpEventList::class.java,
        )
    }

    fun report(runId: String): McpReport = callOrNotFound(runId) {
        mcp.call("get_pipeline_report", mapOf("runId" to runId), McpReport::class.java)
    }

    private fun <T> callOrNotFound(runId: String, call: () -> T): T = try {
        call()
    } catch (ex: McpCallException) {
        if (ex.safeMessage.contains("PIPELINE_NOT_FOUND")) throw PipelineNotFoundException(runId)
        throw ex
    }
}
