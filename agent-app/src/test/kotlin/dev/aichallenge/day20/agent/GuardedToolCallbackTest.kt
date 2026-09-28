package dev.aichallenge.day20.agent

import com.fasterxml.jackson.databind.ObjectMapper
import dev.aichallenge.day20.agent.orchestration.GuardedToolCallback
import dev.aichallenge.day20.agent.orchestration.OrchestrationObserver
import dev.aichallenge.day20.agent.orchestration.RoutingPolicy
import dev.aichallenge.day20.agent.repository.InvocationStatus
import dev.aichallenge.day20.agent.repository.OrchestrationEventRepository
import dev.aichallenge.day20.agent.repository.ToolInvocation
import dev.aichallenge.day20.agent.repository.ToolInvocationRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.definition.ToolDefinition
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

class GuardedToolCallbackTest {
    @Test
    fun `premature file save is rejected before delegate side effect`() {
        val policy = mock(RoutingPolicy::class.java)
        val invocations = mock(ToolInvocationRepository::class.java)
        val events = mock(OrchestrationEventRepository::class.java)
        val observer = mock(OrchestrationObserver::class.java)
        val calls = AtomicInteger()
        val delegate = object : ToolCallback {
            override fun getToolDefinition(): ToolDefinition = ToolDefinition.builder().name("files_save_markdown_report").description("save").inputSchema("{}").build()
            override fun call(toolInput: String): String { calls.incrementAndGet(); return "{}" }
        }
        `when`(policy.validateBeforeCall("run-1", "files_save_markdown_report", ObjectMapper().readTree("{}"))).thenReturn(
            RoutingPolicy.Decision.reject("PRECONDITION_FAILED", "forecast and summaries required")
        )
        val invocation = ToolInvocation("inv-1", "run-1", 1, "files-server", "files_save_markdown_report", null, InvocationStatus.RUNNING, Instant.now(), null, null, "hash", null, "{}", null, null, null)
        `when`(invocations.start(anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.nullable(String::class.java), anyString(), anyString())).thenReturn(invocation)
        val guarded = GuardedToolCallback(delegate, policy, invocations, events, ObjectMapper(), observer)
        val result = guarded.call("{}", ToolContext(mapOf("orchestrationRunId" to "run-1")))
        assertTrue(result.contains("PRECONDITION_FAILED"))
        assertEquals(0, calls.get())
    }
}
