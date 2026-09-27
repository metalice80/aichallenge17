package com.example.weatheragent.config

import io.modelcontextprotocol.spec.McpSchema
import org.springframework.ai.mcp.McpConnectionInfo
import org.springframework.ai.mcp.McpToolFilter
import org.springframework.stereotype.Component

@Component
class AgentMcpToolFilter : McpToolFilter {
    override fun test(connectionInfo: McpConnectionInfo, tool: McpSchema.Tool): Boolean =
        tool.name() in ALLOWED_AGENT_TOOLS

    companion object {
        val ALLOWED_AGENT_TOOLS = setOf(
            "get_current_weather",
            "schedule_weather_summary",
            "get_weather_schedule_status",
            "get_latest_weather_summary",
            "cancel_weather_schedule",
        )
    }
}
