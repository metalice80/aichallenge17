package dev.aichallenge.weather.agent.pipeline

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.Instant

data class CreatePipelineRequest(@field:NotBlank @field:Size(max = 2000) val message: String)
data class CreatePipelineResponse(val runId: String, val status: String, val statusUrl: String, val eventsUrl: String)

data class McpRunResult(val run: McpRun, val steps: List<McpStep>)
data class McpRun(
    val id: String,
    val requestText: String,
    val status: String,
    val currentStep: String?,
    val modelId: String?,
    val createdAt: Instant,
    val startedAt: Instant?,
    val updatedAt: Instant,
    val finishedAt: Instant?,
    val resultFile: String?,
    val errorCode: String?,
    val errorMessage: String?,
    val version: Long,
)
data class McpStep(
    val id: String,
    val runId: String,
    val sequenceNumber: Int,
    val toolName: String,
    val status: String,
    val inputArtifactId: String?,
    val outputArtifactId: String?,
    val requestFingerprint: String?,
    val startedAt: Instant?,
    val finishedAt: Instant?,
    val durationMs: Long?,
    val attempt: Int,
    val errorCode: String?,
    val errorMessage: String?,
)
data class McpEventList(val runId: String, val events: List<McpEvent>)
data class McpEvent(
    val id: String,
    val runId: String,
    val stepId: String?,
    val sequenceNumber: Long,
    val eventType: String,
    val occurredAt: Instant,
    val payloadJson: String,
)
data class McpReport(val runId: String, val fileName: String, val markdown: String)

data class PipelineView(
    val runId: String,
    val status: String,
    val currentStep: String?,
    val modelId: String?,
    val createdAt: Instant,
    val startedAt: Instant?,
    val finishedAt: Instant?,
    val durationMs: Long?,
    val resultFile: String?,
    val error: PipelineError?,
    val steps: List<StepView>,
)
data class StepView(
    val id: String,
    val sequence: Int,
    val tool: String,
    val status: String,
    val inputArtifactId: String?,
    val outputArtifactId: String?,
    val durationMs: Long?,
    val error: PipelineError?,
)
data class PipelineError(val code: String, val message: String, val retryable: Boolean = false, val runId: String? = null, val step: String? = null)
data class ApiError(val code: String, val message: String, val runId: String? = null)

class PipelineNotFoundException(val runId: String) : RuntimeException("Pipeline run was not found")
class AgentConfigurationException(message: String) : RuntimeException(message)
class PipelineSubmissionException(val runId: String, message: String) : RuntimeException(message)
