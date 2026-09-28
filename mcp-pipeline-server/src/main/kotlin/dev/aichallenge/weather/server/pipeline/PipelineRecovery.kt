package dev.aichallenge.weather.server.pipeline

import com.fasterxml.jackson.databind.ObjectMapper
import dev.aichallenge.weather.server.config.PipelineServerProperties
import dev.aichallenge.weather.server.repository.PipelineEventRepository
import dev.aichallenge.weather.server.repository.PipelineRunRepository
import dev.aichallenge.weather.server.repository.PipelineStepRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

@Component
class PipelineRecovery(
    private val runs: PipelineRunRepository,
    private val steps: PipelineStepRepository,
    private val events: PipelineEventRepository,
    private val properties: PipelineServerProperties,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) {
    @EventListener(ApplicationReadyEvent::class)
    @Transactional
    fun recover() {
        val now = clock.instant()
        runs.findStale(now.minus(properties.pipeline.staleRunTimeout)).forEach { run ->
            val code = ErrorCode.PROCESS_INTERRUPTED.name
            val message = "Pipeline was interrupted before completion"
            steps.markActiveFailed(run.id, code, message, now)
            if (runs.markFailed(run.id, code, message, now) == 1) {
                events.append(
                    run.id, null, EventType.RUN_RECOVERED_AS_FAILED, now,
                    objectMapper.writeValueAsString(mapOf("errorCode" to code, "message" to message)),
                )
                logger.warn("event=pipeline.recovered runId={} status=FAILED errorCode={}", run.id, code)
            }
        }
    }

    companion object { private val logger = LoggerFactory.getLogger(PipelineRecovery::class.java) }
}
