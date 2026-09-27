package com.example.weatheragent

import com.example.weatheragent.config.AgentMcpToolFilter
import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.spec.McpSchema
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider

class AgentMcpToolFilterTest {
    @Test
    fun `callback provider exposes only agent allowlist`() {
        val client = mock<McpSyncClient>()
        val allNames = AgentMcpToolFilter.ALLOWED_AGENT_TOOLS + setOf(
            "claim_pending_weather_summaries",
            "complete_weather_summary_delivery",
            "fail_weather_summary_delivery",
            "list_weather_schedules",
            "list_delivered_weather_summaries",
        )
        val tools = allNames.map { name ->
            McpSchema.Tool.builder(name)
                .description("test tool")
                .inputSchema(mapOf("type" to "object", "properties" to emptyMap<String, Any>()))
                .build()
        }
        whenever(client.listTools()).thenReturn(McpSchema.ListToolsResult(tools, null))
        whenever(client.clientInfo).thenReturn(McpSchema.Implementation("test-client", "1"))
        whenever(client.serverInfo).thenReturn(McpSchema.Implementation("weather-mcp-server", "2"))
        whenever(client.clientCapabilities).thenReturn(McpSchema.ClientCapabilities.builder().build())
        whenever(client.currentInitializationResult).thenReturn(
            McpSchema.InitializeResult(
                "2025-11-25",
                McpSchema.ServerCapabilities.builder().tools(true).build(),
                McpSchema.Implementation("weather-mcp-server", "2"),
                "test",
            ),
        )

        val provider = SyncMcpToolCallbackProvider(AgentMcpToolFilter(), listOf(client))
        val exposed = provider.toolCallbacks.map { it.toolDefinition.name() }.toSet()

        assertEquals(AgentMcpToolFilter.ALLOWED_AGENT_TOOLS, exposed)
    }
}
