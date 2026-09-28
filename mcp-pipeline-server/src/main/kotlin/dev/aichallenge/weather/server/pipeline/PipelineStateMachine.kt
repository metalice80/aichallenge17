package dev.aichallenge.weather.server.pipeline

import org.springframework.stereotype.Component

@Component
class PipelineStateMachine {
    fun validateAgentStart(run: PipelineRun) {
        if (run.status != RunStatus.CREATED) invalid("Run must be CREATED before agent start")
    }

    fun validateStep(run: PipelineRun, steps: List<PipelineStep>, requested: StepName) {
        if (run.status != RunStatus.RUNNING) invalid("Run is not RUNNING")
        val current = steps.single { it.sequenceNumber == requested.sequence }
        if (current.status !in setOf(StepStatus.PENDING, StepStatus.SUCCEEDED)) {
            invalid("Step ${requested.name} cannot start from ${current.status}")
        }
        steps.filter { it.sequenceNumber < requested.sequence }.forEach {
            if (it.status != StepStatus.SUCCEEDED) invalid("Step ${requested.name} requires successful ${it.toolName}")
        }
        steps.filter { it.sequenceNumber > requested.sequence }.forEach {
            if (it.status != StepStatus.PENDING) invalid("A later step has already started")
        }
    }

    fun validateCompletion(steps: List<PipelineStep>) {
        if (steps.any { it.status != StepStatus.SUCCEEDED }) invalid("All required steps must succeed before completion")
    }

    private fun invalid(message: String): Nothing = throw PipelineException(ErrorCode.INVALID_PIPELINE_STATE, message)
}
