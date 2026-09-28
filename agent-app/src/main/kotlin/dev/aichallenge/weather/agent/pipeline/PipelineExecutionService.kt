package dev.aichallenge.weather.agent.pipeline

import com.openai.errors.NotFoundException
import com.openai.errors.UnauthorizedException
import dev.aichallenge.weather.agent.config.AgentProperties
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Lazy
import org.springframework.core.task.TaskRejectedException
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.stereotype.Service
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@Service
class PipelineExecutionService(
    private val mcp: McpPipelineClient,
    @Qualifier("pipelineChatClient") @Lazy private val chatClient: ChatClient,
    @Qualifier("pipelineExecutor") private val executor: ThreadPoolTaskExecutor,
    private val properties: AgentProperties,
    private val meterRegistry: MeterRegistry,
    private val observationRegistry: ObservationRegistry,
    @Value("\${spring.ai.openai.api-key:}") private val apiKey: String,
) {
    private val active = AtomicInteger()

    init {
        Gauge.builder("pipeline.active", active) { it.get().toDouble() }.register(meterRegistry)
    }

    fun submit(message: String): CreatePipelineResponse {
        val request = message.trim()
        if (request.isEmpty() || request.length > 2000) throw IllegalArgumentException("message must contain 1 to 2000 characters")
        if (apiKey.isBlank()) throw AgentConfigurationException("OPENAI_API_KEY is not configured")
        val created = mcp.call(
            "create_pipeline_run", mapOf("requestText" to request, "modelId" to properties.agent.model), McpRunResult::class.java,
        )
        val runId = created.run.id
        try {
            executor.execute { execute(runId, request) }
        } catch (ex: TaskRejectedException) {
            fail(runId, "EXECUTOR_REJECTED", "Pipeline executor queue is full")
            throw PipelineSubmissionException(runId, "Pipeline executor queue is full")
        }
        return CreatePipelineResponse(runId, "CREATED", "/api/pipelines/$runId", "/api/pipelines/$runId/events/stream")
    }

    private fun execute(runId: String, message: String) {
        active.incrementAndGet()
        MDC.put("runId", runId)
        val observation = Observation.start("pipeline.run", observationRegistry)
        try {
            mcp.call("mark_pipeline_agent_started", mapOf("runId" to runId), McpRunResult::class.java)
            val optionsBuilder = OpenAiChatOptions.builder().model(properties.agent.model)
            if (properties.agent.reasoningEffort.isNotBlank()) {
                optionsBuilder.reasoningEffort(properties.agent.reasoningEffort)
            }
            val answer = chatClient.prompt(message)
                .options(optionsBuilder)
                .toolContext(mapOf("pipelineRunId" to runId, "conversationId" to "conversation-${UUID.randomUUID()}"))
                .call().content()
            val final = mcp.call("get_pipeline_run", mapOf("runId" to runId), McpRunResult::class.java)
            if (final.run.status != "COMPLETED") {
                recordTerminal(fail(runId, "PIPELINE_INCOMPLETE", "Agent finished before all required tools succeeded") ?: final)
            } else {
                recordTerminal(final)
                logger.info("event=pipeline.completed status=COMPLETED answerLength={}", answer?.length ?: 0)
            }
        } catch (ex: Throwable) {
            observation.error(ex)
            val failure = classifyAgentFailure(ex, properties.agent.model)
            fail(runId, failure.code, failure.message)?.let(::recordTerminal)
            logger.error("event=pipeline.failed status=FAILED errorCode={}", failure.code, ex)
        } finally {
            observation.stop()
            active.decrementAndGet()
            MDC.remove("runId")
        }
    }

    private fun fail(runId: String, code: String, message: String): McpRunResult? =
        runCatching {
            mcp.call(
                "fail_pipeline_run", mapOf("runId" to runId, "errorCode" to code, "errorMessage" to message), McpRunResult::class.java,
            )
        }.onFailure { logger.error("event=pipeline.fail_recording_failed runId={} errorCode={}", runId, code, it) }.getOrNull()

    private fun recordTerminal(result: McpRunResult) {
        meterRegistry.counter("pipeline.runs", "status", result.run.status).increment()
        result.steps.forEach { step ->
            step.durationMs?.let { duration ->
                meterRegistry.timer("pipeline.step.duration", "tool", step.toolName, "status", step.status)
                    .record(duration, TimeUnit.MILLISECONDS)
            }
            if (step.status == "FAILED") {
                meterRegistry.counter(
                    "pipeline.step.failures", "tool", step.toolName, "error_code", step.errorCode ?: "INTERNAL_ERROR",
                ).increment()
            }
            if (step.status == "SUCCEEDED") {
                val artifactType = when (step.sequenceNumber) { 1 -> "SEARCH_RESULT"; 2 -> "WEATHER_SUMMARY"; else -> "SAVED_REPORT" }
                meterRegistry.counter("pipeline.artifacts", "type", artifactType).increment()
            }
        }
    }

    companion object { private val logger = LoggerFactory.getLogger(PipelineExecutionService::class.java) }

}

internal data class AgentFailure(val code: String, val message: String)

internal fun classifyAgentFailure(ex: Throwable, model: String): AgentFailure {
    val timeout = ex.javaClass.simpleName.contains("timeout", true) || ex.message.orEmpty().contains("timeout", true)
    return when {
        ex is UnauthorizedException -> AgentFailure(
            "OPENAI_AUTHENTICATION_FAILED",
            "OpenAI-compatible API rejected authentication; set OPENAI_API_KEY to a valid key for OPENAI_BASE_URL",
        )
        ex is NotFoundException -> AgentFailure(
            "OPENAI_MODEL_NOT_FOUND",
            "OpenAI model '$model' is unavailable; set OPENAI_MODEL to a model enabled for this API project",
        )
        timeout -> AgentFailure("OPENAI_TIMEOUT", "OpenAI request timed out")
        else -> AgentFailure("OPENAI_UNAVAILABLE", "Agent execution failed")
    }
}
