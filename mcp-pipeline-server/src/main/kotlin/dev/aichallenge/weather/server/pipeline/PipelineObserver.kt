package dev.aichallenge.weather.server.pipeline

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration

@Component
class PipelineObserver(
    private val store: PipelineStore,
    private val clock: Clock,
    private val meterRegistry: MeterRegistry,
    private val observationRegistry: ObservationRegistry,
) {
    data class Outcome<T>(val value: T, val artifact: PipelineArtifact, val resultFile: String? = null)

    fun <T> execute(
        runId: String,
        stepName: StepName,
        inputFingerprint: String,
        inputArtifactId: String? = null,
        replay: (PipelineStep) -> T,
        operation: () -> Outcome<T>,
    ): T {
        val start = try {
            store.beginStep(runId, stepName, inputFingerprint, inputArtifactId)
        } catch (error: PipelineException) {
            if (error.code != ErrorCode.STEP_ALREADY_COMPLETED_WITH_DIFFERENT_INPUT) {
                runCatching { store.failStep(runId, stepName, error, null) }
            }
            meterRegistry.counter("pipeline.step.failures", "tool", stepName.toolName, "error_code", error.code.name).increment()
            throw error
        }
        if (start is PipelineStore.StepStart.Reuse) return replay(start.step)
        start as PipelineStore.StepStart.Proceed
        MDC.put("runId", runId)
        MDC.put("stepId", start.step.id)
        MDC.put("toolName", stepName.toolName)
        val observation = Observation.start("mcp.${stepName.toolName}", observationRegistry)
        return try {
            val outcome = operation()
            val duration = Duration.between(start.startedAt, clock.instant()).toMillis().coerceAtLeast(0)
            store.succeedStep(runId, stepName, outcome.artifact, duration, outcome.resultFile)
            Timer.builder("pipeline.step.duration")
                .tag("tool", stepName.toolName).tag("status", "SUCCEEDED")
                .register(meterRegistry).record(duration, java.util.concurrent.TimeUnit.MILLISECONDS)
            meterRegistry.counter("pipeline.artifacts", "type", outcome.artifact.type.name).increment()
            logger.info(
                "event=pipeline.step.completed status=SUCCEEDED durationMs={} inputArtifactId={} outputArtifactId={}",
                duration, inputArtifactId, outcome.artifact.id,
            )
            outcome.value
        } catch (throwable: Throwable) {
            val error = if (throwable is PipelineException) throwable else PipelineException(ErrorCode.INTERNAL_ERROR, "Unexpected tool failure", false, stepName)
            observation.error(throwable)
            store.failStep(runId, stepName, error, start.startedAt)
            meterRegistry.counter("pipeline.step.failures", "tool", stepName.toolName, "error_code", error.code.name).increment()
            logger.error("event=pipeline.step.failed status=FAILED errorCode={}", error.code.name, throwable)
            throw error
        } finally {
            observation.stop()
            MDC.remove("toolName")
            MDC.remove("stepId")
            MDC.remove("runId")
        }
    }

    companion object { private val logger = LoggerFactory.getLogger(PipelineObserver::class.java) }
}
