package dev.aichallenge.weather.agent.config

import io.modelcontextprotocol.spec.McpSchema
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.mcp.McpConnectionInfo

class McpSecurityConfigurationTest {
    @Test
    fun `allowlist exposes exactly three agent tools`() {
        val filter = McpToolFilterConfiguration().agentToolAllowlist()
        val connection = mock(McpConnectionInfo::class.java)
        val accepted = listOf(
            "search_weather_forecast", "summarize_weather_forecast", "save_weather_report",
            "create_pipeline_run", "get_pipeline_run", "future_internal_tool",
        ).filter { name ->
            filter.test(connection, McpSchema.Tool.builder().name(name).description(name).inputSchema(emptyMap()).build())
        }.toSet()
        assertEquals(McpToolFilterConfiguration.AGENT_TOOLS, accepted)
        assertFalse("create_pipeline_run" in accepted)
    }

    @Test
    fun `converter passes correlation metadata but not arbitrary context`() {
        val converted = PipelineToolContextConverter().convert(
            ToolContext(mapOf("pipelineRunId" to "run-1", "conversationId" to "conversation-1", "secret" to "drop")),
        )
        assertEquals(setOf("pipelineRunId", "conversationId"), converted.keys)
        assertTrue("secret" !in converted)
    }
}
