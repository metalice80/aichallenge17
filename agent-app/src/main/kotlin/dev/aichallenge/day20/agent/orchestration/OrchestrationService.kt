package dev.aichallenge.day20.agent.orchestration

import com.fasterxml.jackson.databind.ObjectMapper
import dev.aichallenge.day20.agent.config.AppProperties
import dev.aichallenge.day20.agent.config.McpToolRegistry
import dev.aichallenge.day20.agent.repository.OrchestrationEventRepository
import dev.aichallenge.day20.agent.repository.OrchestrationRunRepository
import jakarta.annotation.PreDestroy
import org.springframework.ai.chat.client.ChatClient
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@Service
class OrchestrationService(
    @Qualifier("orchestrationChatClient") private val chatClient: ChatClient,
    private val runs: OrchestrationRunRepository,
    private val events: OrchestrationEventRepository,
    private val states: RoutingStateLoader,
    private val registry: McpToolRegistry,
    @Qualifier("orchestrationExecutor") private val executor: ExecutorService,
    @Qualifier("orchestrationTimeoutScheduler") private val timeoutScheduler: ScheduledExecutorService,
    private val properties: AppProperties,
    private val objectMapper: ObjectMapper,
    private val observer: OrchestrationObserver,
) {
    private val tasks = ConcurrentHashMap<String, Future<*>>()
    val active = AtomicInteger()

    fun start(request: String): String {
        val normalized = request.trim()
        require(normalized.isNotEmpty() && normalized.length <= 3000) { "VALIDATION_ERROR: message must contain 1 to 3000 characters" }
        registry.requireReady()
        val runId = UUID.randomUUID().toString()
        runs.create(runId, normalized, properties.agent.model)
        events.append(runId, "RUN_CREATED")
        try {
            tasks[runId] = executor.submit { execute(runId, normalized) }
        } catch (_: RejectedExecutionException) {
            fail(runId, "EXECUTOR_REJECTED", "Orchestration queue is full")
        }
        return runId
    }

    private fun execute(runId: String, request: String) {
        active.incrementAndGet()
        val timedOut = AtomicBoolean(false)
        val executingThread = Thread.currentThread()
        val timeoutTask = timeoutScheduler.schedule({
            timedOut.set(true)
            executingThread.interrupt()
        }, properties.agent.executionTimeout.toMillis(), TimeUnit.MILLISECONDS)
        try {
            runs.markRunning(runId)
            events.append(runId, "RUN_STARTED", payloadJson = objectMapper.writeValueAsString(mapOf("model" to properties.agent.model)))
            if (properties.agent.apiKeyConfigured.isBlank()) {
                fail(runId, "CONFIGURATION_ERROR", "OPENAI_API_KEY is required for live orchestration")
                return
            }
            events.append(runId, "MODEL_ITERATION_STARTED")
            val answer = chatClient.prompt()
                .user(request)
                .toolContext(mapOf("orchestrationRunId" to runId, "conversationId" to runId))
                .call()
                .content()
                ?: ""
            val state = states.load(runId)
            if (state.forecastedLocationRefs.isEmpty() || state.summarizedArticleRefs.size < 3 || !state.savedReport || state.savedReportPath.isNullOrBlank()) {
                fail(runId, "ORCHESTRATION_INCOMPLETE", "The model finished before forecast, three summaries, and report save completed")
                return
            }
            val finalAnswer = if (answer.contains(state.savedReportPath!!)) answer else "$answer\n\nReport saved: ${state.savedReportPath}"
            runs.complete(runId, finalAnswer, state.savedReportPath!!)
            events.append(runId, "RUN_COMPLETED", payloadJson = objectMapper.writeValueAsString(mapOf("reportPath" to state.savedReportPath)))
            observer.runFinished("COMPLETED")
        } catch (exception: InterruptedException) {
            val code = if (timedOut.get()) "OPENAI_TIMEOUT" else "PROCESS_INTERRUPTED"
            fail(runId, code, if (timedOut.get()) "Orchestration execution timeout exceeded" else "Orchestration was interrupted")
        } catch (exception: Exception) {
            val code = when {
                timedOut.get() -> "OPENAI_TIMEOUT"
                exception.message?.contains("timeout", ignoreCase = true) == true -> "OPENAI_TIMEOUT"
                exception.message?.contains("api key", ignoreCase = true) == true -> "CONFIGURATION_ERROR"
                else -> "OPENAI_UNAVAILABLE"
            }
            fail(runId, code, exception.message ?: "Orchestration failed")
        } finally {
            timeoutTask.cancel(false)
            Thread.interrupted()
            active.decrementAndGet()
            tasks.remove(runId)
        }
    }

    private fun fail(runId: String, code: String, message: String) {
        runs.fail(runId, code, message)
        events.append(runId, "RUN_FAILED", payloadJson = objectMapper.writeValueAsString(mapOf("code" to code, "message" to message.take(500))))
        observer.runFinished("FAILED")
    }

    @PreDestroy
    fun stopRunningTasks() {
        tasks.forEach { (runId, future) ->
            future.cancel(true)
            fail(runId, "PROCESS_INTERRUPTED", "Application is shutting down")
        }
        tasks.clear()
    }
}
