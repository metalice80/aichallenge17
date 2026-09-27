package com.example.weatheragent

import com.example.weatheragent.scheduler.WeatherSchedulerMcpClient
import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.spec.McpSchema
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import tools.jackson.databind.ObjectMapper

class WeatherSchedulerMcpClientLifecycleTest {
    @Test
    fun `closes selected client exactly once`() {
        val client = mock<McpSyncClient>()
        whenever(client.isInitialized).thenReturn(true)
        whenever(client.serverInfo).thenReturn(McpSchema.Implementation("weather-mcp-server", "2.0.0"))
        whenever(client.closeGracefully()).thenReturn(true)
        val adapter = WeatherSchedulerMcpClient(listOf(client), mock<ObjectMapper>())

        adapter.close()
        adapter.close()

        verify(client, times(1)).closeGracefully()
    }
}
