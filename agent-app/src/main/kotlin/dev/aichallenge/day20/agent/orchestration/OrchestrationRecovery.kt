package dev.aichallenge.day20.agent.orchestration

import com.fasterxml.jackson.databind.ObjectMapper
import dev.aichallenge.day20.agent.config.AppProperties
import dev.aichallenge.day20.agent.repository.OrchestrationEventRepository
import dev.aichallenge.day20.agent.repository.OrchestrationRunRepository
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Clock

@Component
class OrchestrationRecovery(
    private val runs: OrchestrationRunRepository,
    private val events: OrchestrationEventRepository,
    private val properties: AppProperties,
    private val clock: Clock,
    private val objectMapper: ObjectMapper,
) {
    @EventListener(ApplicationReadyEvent::class)
    fun recoverStaleRuns() {
        runs.stale(clock.instant().minus(properties.orchestration.staleRunTimeout)).forEach { runId ->
            runs.fail(runId, "PROCESS_INTERRUPTED", "Process stopped before orchestration completed")
            events.append(runId, "RUN_RECOVERED_AS_FAILED", payloadJson = objectMapper.writeValueAsString(mapOf("code" to "PROCESS_INTERRUPTED")))
        }
    }
}
