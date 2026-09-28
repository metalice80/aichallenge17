package dev.aichallenge.weather.server.pipeline

import com.fasterxml.jackson.databind.ObjectMapper
import dev.aichallenge.weather.server.repository.PipelineArtifactRepository
import dev.aichallenge.weather.server.repository.PipelineEventRepository
import dev.aichallenge.weather.server.repository.PipelineRunRepository
import dev.aichallenge.weather.server.repository.PipelineStepRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Service
class PipelineStore(
    private val runs: PipelineRunRepository,
    private val steps: PipelineStepRepository,
    private val artifacts: PipelineArtifactRepository,
    private val events: PipelineEventRepository,
    private val stateMachine: PipelineStateMachine,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) {
    sealed interface StepStart {
        data class Proceed(val step: PipelineStep, val startedAt: Instant) : StepStart
        data class Reuse(val step: PipelineStep) : StepStart
    }

    @Transactional
    fun createRun(requestText: String, modelId: String?): RunView {
        if (requestText.isBlank() || requestText.length > 2000) {
            throw PipelineException(ErrorCode.VALIDATION_ERROR, "Request text must contain 1 to 2000 characters")
        }
        val now = clock.instant()
        val run = PipelineRun(
            "run-${UUID.randomUUID()}", requestText, RunStatus.CREATED, null, modelId,
            now, null, now, null, null, null, null, 0,
        )
        runs.insert(run)
        StepName.entries.forEach { name ->
            steps.insert(
                PipelineStep(
                    "step-${UUID.randomUUID()}", run.id, name.sequence, name.toolName, StepStatus.PENDING,
                    null, null, null, null, null, null, 1, null, null,
                ),
            )
        }
        append(run.id, null, EventType.RUN_CREATED, mapOf("status" to RunStatus.CREATED.name, "modelId" to modelId))
        return getRun(run.id)
    }

    @Transactional
    fun markAgentStarted(runId: String): RunView {
        val run = requireRun(runId)
        stateMachine.validateAgentStart(run)
        val now = clock.instant()
        if (!runs.markRunning(runId, run.version, now)) optimisticFailure()
        append(runId, null, EventType.RUN_STARTED, mapOf("status" to RunStatus.RUNNING.name), now)
        append(runId, null, EventType.AGENT_STARTED, mapOf("modelId" to run.modelId), now)
        return getRun(runId)
    }

    @Transactional
    fun beginStep(runId: String, stepName: StepName, fingerprint: String, inputArtifactId: String?): StepStart {
        val run = requireRun(runId)
        val allSteps = steps.findByRun(runId)
        val step = allSteps.single { it.sequenceNumber == stepName.sequence }
        if (step.status == StepStatus.SUCCEEDED) {
            if (step.requestFingerprint != fingerprint) {
                throw PipelineException(
                    ErrorCode.STEP_ALREADY_COMPLETED_WITH_DIFFERENT_INPUT,
                    "Step ${stepName.name} already completed with different input",
                    step = stepName,
                )
            }
            append(runId, step.id, EventType.STEP_REUSED, mapOf("toolName" to step.toolName, "outputArtifactId" to step.outputArtifactId))
            return StepStart.Reuse(step)
        }
        stateMachine.validateStep(run, allSteps, stepName)
        val now = clock.instant()
        if (!runs.markStepStarted(runId, run.version, stepName.name, now)) optimisticFailure()
        if (!steps.markRunning(step.id, fingerprint, inputArtifactId, now)) optimisticFailure()
        append(runId, step.id, EventType.TOOL_REQUESTED, mapOf("toolName" to step.toolName, "inputArtifactId" to inputArtifactId), now)
        append(runId, step.id, EventType.TOOL_STARTED, mapOf("toolName" to step.toolName), now)
        return StepStart.Proceed(steps.find(runId, stepName.sequence)!!, now)
    }

    @Transactional
    fun succeedStep(
        runId: String,
        stepName: StepName,
        artifact: PipelineArtifact,
        durationMs: Long,
        resultFile: String? = null,
    ) {
        val step = steps.find(runId, stepName.sequence)
            ?: throw PipelineException(ErrorCode.PIPELINE_NOT_FOUND, "Pipeline step was not found")
        artifacts.insert(artifact)
        if (steps.markSucceeded(step.id, artifact.id, clock.instant(), durationMs) != 1) optimisticFailure()
        append(runId, step.id, EventType.ARTIFACT_CREATED, mapOf("artifactId" to artifact.id, "artifactType" to artifact.type.name, "sha256" to artifact.sha256))
        append(runId, step.id, EventType.TOOL_SUCCEEDED, mapOf("toolName" to step.toolName, "durationMs" to durationMs, "outputArtifactId" to artifact.id))
        if (stepName == StepName.SAVE) {
            val allSteps = steps.findByRun(runId)
            stateMachine.validateCompletion(allSteps)
            val run = requireRun(runId)
            val file = resultFile ?: throw PipelineException(ErrorCode.INTERNAL_ERROR, "Saved report path is missing")
            append(runId, step.id, EventType.FILE_SAVED, mapOf("relativePath" to file, "sha256" to artifact.sha256))
            if (!runs.markCompleted(runId, run.version, file, clock.instant())) optimisticFailure()
            append(runId, null, EventType.RUN_COMPLETED, mapOf("status" to RunStatus.COMPLETED.name, "resultFile" to file))
        }
    }

    @Transactional
    fun failStep(runId: String, stepName: StepName, error: PipelineException, startedAt: Instant?) {
        val now = clock.instant()
        val step = steps.find(runId, stepName.sequence)
        val duration = startedAt?.let { java.time.Duration.between(it, now).toMillis().coerceAtLeast(0) }
        step?.let {
            steps.markFailed(it.id, error.code.name, error.safeMessage, now, duration)
            append(runId, it.id, EventType.TOOL_FAILED, mapOf("toolName" to it.toolName, "errorCode" to error.code.name, "durationMs" to duration), now)
        }
        if (runs.markFailed(runId, error.code.name, error.safeMessage, now) == 1) {
            append(runId, step?.id, EventType.RUN_FAILED, mapOf("errorCode" to error.code.name, "message" to error.safeMessage), now)
        }
    }

    @Transactional
    fun failRun(runId: String, code: ErrorCode, message: String): RunView {
        val run = requireRun(runId)
        if (run.status in setOf(RunStatus.COMPLETED, RunStatus.FAILED, RunStatus.CANCELLED)) return getRun(runId)
        val now = clock.instant()
        if (runs.markFailed(runId, code.name, message, now) == 1) {
            steps.markActiveFailed(runId, code.name, message, now)
            append(runId, null, EventType.RUN_FAILED, mapOf("errorCode" to code.name, "message" to message), now)
        }
        return getRun(runId)
    }

    fun getRun(runId: String): RunView = RunView(requireRun(runId), steps.findByRun(runId))

    fun listEvents(runId: String, after: Long, limit: Int): EventsView {
        requireRun(runId)
        if (after < 0 || limit !in 1..500) throw PipelineException(ErrorCode.VALIDATION_ERROR, "after must be non-negative and limit must be between 1 and 500")
        return EventsView(runId, events.list(runId, after, limit))
    }

    fun requireArtifact(runId: String, artifactId: String, type: ArtifactType): PipelineArtifact {
        val artifact = artifacts.find(artifactId)
            ?: throw PipelineException(ErrorCode.INVALID_ARTIFACT, "Artifact was not found")
        if (artifact.runId != runId) throw PipelineException(ErrorCode.ARTIFACT_FROM_ANOTHER_RUN, "Artifact belongs to another pipeline run")
        if (artifact.type != type) throw PipelineException(ErrorCode.INVALID_ARTIFACT, "Expected artifact type ${type.name}")
        return artifact
    }

    fun findArtifact(id: String): PipelineArtifact? = artifacts.find(id)
    fun artifactsForRun(runId: String): List<PipelineArtifact> = artifacts.findByRun(runId)

    private fun requireRun(runId: String): PipelineRun = runs.find(runId)
        ?: throw PipelineException(ErrorCode.PIPELINE_NOT_FOUND, "Pipeline run was not found")

    private fun append(runId: String, stepId: String?, type: EventType, payload: Map<String, Any?>, now: Instant = clock.instant()) {
        events.append(runId, stepId, type, now, objectMapper.writeValueAsString(payload))
    }

    private fun optimisticFailure(): Nothing = throw PipelineException(ErrorCode.INVALID_PIPELINE_STATE, "Pipeline was modified concurrently")
}
