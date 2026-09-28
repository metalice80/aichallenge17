package dev.aichallenge.weather.agent.config

import org.springframework.ai.mcp.McpToolFilter
import org.springframework.ai.mcp.McpToolNamePrefixGenerator
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class McpToolFilterConfiguration {
    @Bean
    fun agentToolAllowlist(): McpToolFilter = McpToolFilter { _, tool -> tool.name() in AGENT_TOOLS }

    @Bean
    fun mcpToolNamePrefixGenerator(): McpToolNamePrefixGenerator = McpToolNamePrefixGenerator.noPrefix()

    companion object {
        val AGENT_TOOLS = setOf("search_weather_forecast", "summarize_weather_forecast", "save_weather_report")
    }
}
