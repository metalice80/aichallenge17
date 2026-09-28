package dev.aichallenge.weather.server.pipeline

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant

class PipelineStateMachineTest {
    private val machine = PipelineStateMachine()
    private val now = Instant.parse("2026-09-28T00:00:00Z")

    @Test
    fun `allows only strict search summary save order`() {
        val run = run(RunStatus.RUNNING)
        val pending = steps()
        assertDoesNotThrow { machine.validateStep(run, pending, StepName.SEARCH) }
        assertThrows<PipelineException> { machine.validateStep(run, pending, StepName.SUMMARY) }
        val afterSearch = pending.map { if (it.sequenceNumber == 1) it.copy(status = StepStatus.SUCCEEDED) else it }
        assertDoesNotThrow { machine.validateStep(run, afterSearch, StepName.SUMMARY) }
        assertThrows<PipelineException> { machine.validateStep(run, afterSearch, StepName.SAVE) }
    }

    @Test
    fun `terminal run cannot start another step`() {
        val error = assertThrows<PipelineException> { machine.validateStep(run(RunStatus.FAILED), steps(), StepName.SEARCH) }
        assertEquals(ErrorCode.INVALID_PIPELINE_STATE, error.code)
    }

    private fun run(status: RunStatus) = PipelineRun("run", "request", status, null, "model", now, now, now, null, null, null, null, 0)
    private fun steps() = StepName.entries.map { name ->
        PipelineStep("step-${name.sequence}", "run", name.sequence, name.toolName, StepStatus.PENDING, null, null, null, null, null, null, 1, null, null)
    }
}
