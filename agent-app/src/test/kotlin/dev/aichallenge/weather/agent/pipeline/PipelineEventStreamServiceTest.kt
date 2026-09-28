package dev.aichallenge.weather.agent.pipeline

import dev.aichallenge.weather.agent.config.AgentProperties
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.timeout
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import java.time.Duration
import java.time.Instant

class PipelineEventStreamServiceTest {
    private val scheduler = ThreadPoolTaskScheduler().apply { poolSize = 1; initialize() }

    @AfterEach fun close() { scheduler.shutdown() }

    @Test
    fun `polls after reconnect cursor and stops on terminal event`() {
        val queries = mock(PipelineQueryService::class.java)
        val now = Instant.parse("2026-09-28T00:00:00Z")
        val view = PipelineView("run-1", "RUNNING", null, "model", now, now, null, 0, null, null, emptyList())
        `when`(queries.get("run-1")).thenReturn(view)
        `when`(queries.events("run-1", 8, 100)).thenReturn(
            McpEventList("run-1", listOf(McpEvent("e9", "run-1", null, 9, "RUN_COMPLETED", now, "{}"))),
        )
        val properties = AgentProperties(pipeline = AgentProperties.Pipeline(eventPollInterval = Duration.ofMillis(10), sseTimeout = Duration.ofSeconds(1)))
        val emitter = PipelineEventStreamService(queries, properties, scheduler).stream("run-1", 8)
        emitter.onError { }
        verify(queries, timeout(1000)).events("run-1", 8, 100)
        assertEquals(1_000L, emitter.timeout)
    }
}
