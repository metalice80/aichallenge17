package dev.aichallenge.day20.agent.web

import dev.aichallenge.day20.agent.config.AppProperties
import dev.aichallenge.day20.agent.repository.OrchestrationEventRepository
import dev.aichallenge.day20.agent.repository.OrchestrationRunRepository
import dev.aichallenge.day20.agent.repository.RunStatus
import jakarta.annotation.PreDestroy
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@Component
class OrchestrationEventStream(
    private val events: OrchestrationEventRepository,
    private val runs: OrchestrationRunRepository,
    private val properties: AppProperties,
) {
    private val scheduler = Executors.newScheduledThreadPool(2) { runnable -> Thread(runnable, "orchestration-sse").apply { isDaemon = true } }

    fun open(runId: String, after: Int): SseEmitter {
        val emitter = SseEmitter(properties.orchestration.sseTimeout.toMillis())
        val cursor = AtomicInteger(after)
        lateinit var task: ScheduledFuture<*>
        val poll = Runnable {
            try {
                val batch = events.list(runId, cursor.get(), 100)
                batch.forEach { event ->
                    emitter.send(SseEmitter.event().id(event.sequenceNumber.toString()).name(event.eventType).data(event))
                    cursor.set(event.sequenceNumber)
                }
                if (batch.isEmpty()) emitter.send(SseEmitter.event().comment("heartbeat"))
                val status = runs.find(runId)?.status
                if (status in setOf(RunStatus.COMPLETED, RunStatus.FAILED, RunStatus.CANCELLED) && events.list(runId, cursor.get(), 1).isEmpty()) {
                    emitter.complete()
                    task.cancel(false)
                }
            } catch (_: Exception) {
                emitter.complete()
                task.cancel(false)
            }
        }
        task = scheduler.scheduleWithFixedDelay(poll, 0, properties.orchestration.eventPollInterval.toMillis(), TimeUnit.MILLISECONDS)
        emitter.onCompletion { task.cancel(false) }
        emitter.onTimeout { task.cancel(false); emitter.complete() }
        emitter.onError { task.cancel(false) }
        return emitter
    }

    @PreDestroy
    fun close() {
        scheduler.shutdownNow()
    }
}
