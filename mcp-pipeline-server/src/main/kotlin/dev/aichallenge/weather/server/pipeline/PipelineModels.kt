package dev.aichallenge.weather.server.pipeline

import java.time.Instant

enum class RunStatus { CREATED, RUNNING, COMPLETED, FAILED, CANCELLED }
enum class StepStatus { PENDING, RUNNING, SUCCEEDED, FAILED, SKIPPED }
enum class ArtifactType { SEARCH_RESULT, WEATHER_SUMMARY, SAVED_REPORT }
enum class StepName(val sequence: Int, val toolName: String) {
    SEARCH(1, "search_weather_forecast"),
    SUMMARY(2, "summarize_weather_forecast"),
    SAVE(3, "save_weather_report");

    companion object {
        fun fromTool(toolName: String) = entries.first { it.toolName == toolName }
    }
}
enum class EventType {
    RUN_CREATED, RUN_STARTED, AGENT_STARTED, TOOL_REQUESTED, TOOL_STARTED, TOOL_SUCCEEDED,
    TOOL_FAILED, ARTIFACT_CREATED, FILE_SAVED, RUN_COMPLETED, RUN_FAILED,
    RUN_RECOVERED_AS_FAILED, STEP_REUSED
}

data class PipelineRun(
    val id: String,
    val requestText: String,
    val status: RunStatus,
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

data class PipelineStep(
    val id: String,
    val runId: String,
    val sequenceNumber: Int,
    val toolName: String,
    val status: StepStatus,
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

data class PipelineArtifact(
    val id: String,
    val runId: String,
    val type: ArtifactType,
    val sourceArtifactId: String?,
    val mediaType: String,
    val content: String,
    val sha256: String,
    val createdAt: Instant,
)

data class PipelineEvent(
    val id: String,
    val runId: String,
    val stepId: String?,
    val sequenceNumber: Long,
    val eventType: EventType,
    val occurredAt: Instant,
    val payloadJson: String,
)

data class RunView(val run: PipelineRun, val steps: List<PipelineStep>)
data class EventsView(val runId: String, val events: List<PipelineEvent>)
