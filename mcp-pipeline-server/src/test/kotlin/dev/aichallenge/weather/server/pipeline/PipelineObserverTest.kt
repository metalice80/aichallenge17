package dev.aichallenge.weather.server.pipeline

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.observation.ObservationRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.slf4j.MDC
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class PipelineObserverTest {
    private val now = Instant.parse("2026-09-28T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val store = mock(PipelineStore::class.java)
    private val meters = SimpleMeterRegistry()
    private val observer = PipelineObserver(store, clock, meters, ObservationRegistry.create())
    private val step = PipelineStep("step-1", "run-1", 1, StepName.SEARCH.toolName, StepStatus.RUNNING, null, null, "fp", now, null, null, 1, null, null)

    @Test
    fun `success records low-cardinality metrics and clears MDC`() {
        `when`(store.beginStep("run-1", StepName.SEARCH, "fp", null)).thenReturn(PipelineStore.StepStart.Proceed(step, now))
        val artifact = PipelineArtifact("artifact-1", "run-1", ArtifactType.SEARCH_RESULT, null, "application/json", "{}", Hashing.sha256("{}"), now)
        val result = observer.execute("run-1", StepName.SEARCH, "fp", replay = { "unused" }) {
            assertEquals("run-1", MDC.get("runId"))
            PipelineObserver.Outcome("ok", artifact)
        }
        assertEquals("ok", result)
        assertNull(MDC.get("runId"))
        assertEquals(1, meters.find("pipeline.step.duration").tag("tool", StepName.SEARCH.toolName).timer()!!.count())
        assertEquals(1.0, meters.find("pipeline.artifacts").tag("type", "SEARCH_RESULT").counter()!!.count())
        verify(store).succeedStep("run-1", StepName.SEARCH, artifact, 0, null)
    }

    @Test
    fun `failure is journaled measured and clears MDC`() {
        `when`(store.beginStep("run-1", StepName.SEARCH, "fp", null)).thenReturn(PipelineStore.StepStart.Proceed(step, now))
        val error = assertThrows<PipelineException> {
            observer.execute("run-1", StepName.SEARCH, "fp", replay = { "unused" }) {
                throw PipelineException(ErrorCode.CITY_NOT_FOUND, "City was not found")
            }
        }
        assertEquals(ErrorCode.CITY_NOT_FOUND, error.code)
        assertNull(MDC.get("runId"))
        assertEquals(1.0, meters.find("pipeline.step.failures").tag("error_code", "CITY_NOT_FOUND").counter()!!.count())
        verify(store).failStep("run-1", StepName.SEARCH, error, now)
    }
}
