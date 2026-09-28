package dev.aichallenge.weather.agent.pipeline

import dev.aichallenge.weather.agent.config.AgentProperties
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.stereotype.Service
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.Duration
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

@Service
class PipelineEventStreamService(
    private val queries: PipelineQueryService,
    private val properties: AgentProperties,
    @Qualifier("pipelineEventScheduler") private val scheduler: ThreadPoolTaskScheduler,
) {
    fun stream(runId: String, lastEventId: Long?): SseEmitter {
        val snapshot = queries.get(runId)
        val emitter = SseEmitter(properties.pipeline.sseTimeout.toMillis())
        emitter.send(SseEmitter.event().name("snapshot").data(snapshot))
        val cursor = AtomicLong(lastEventId ?: 0L)
        val heartbeatAt = AtomicLong(System.nanoTime())
        val future = AtomicReference<ScheduledFuture<*>?>()
        val cancel = { future.getAndSet(null)?.cancel(false); Unit }
        emitter.onCompletion(cancel)
        emitter.onTimeout { cancel(); emitter.complete() }
        emitter.onError { cancel() }
        future.set(scheduler.scheduleAtFixedRate({
            try {
                val batch = queries.events(runId, cursor.get(), 100)
                var terminal = false
                batch.events.forEach { event ->
                    emitter.send(SseEmitter.event().id(event.sequenceNumber.toString()).name("pipeline-event").data(event))
                    cursor.set(event.sequenceNumber)
                    terminal = event.eventType in TERMINAL_EVENTS
                }
                val now = System.nanoTime()
                if (Duration.ofNanos(now - heartbeatAt.get()) >= Duration.ofSeconds(15)) {
                    emitter.send(SseEmitter.event().comment("heartbeat"))
                    heartbeatAt.set(now)
                }
                if (terminal) { cancel(); emitter.complete() }
            } catch (ex: Exception) {
                cancel()
                emitter.completeWithError(ex)
            }
        }, properties.pipeline.eventPollInterval))
        return emitter
    }

    companion object { private val TERMINAL_EVENTS = setOf("RUN_COMPLETED", "RUN_FAILED", "RUN_RECOVERED_AS_FAILED") }
}
