package dev.aichallenge.weather.agent.config

import dev.aichallenge.weather.agent.pipeline.McpPipelineClient
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component

@Component
class McpStartupVerifier(private val mcp: McpPipelineClient) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        val names = mcp.toolNames()
        require(names.containsAll(REQUIRED_TOOLS)) { "MCP pipeline server is missing required tools: ${REQUIRED_TOOLS - names}" }
    }

    companion object {
        private val REQUIRED_TOOLS = setOf(
            "search_weather_forecast", "summarize_weather_forecast", "save_weather_report",
            "create_pipeline_run", "mark_pipeline_agent_started", "fail_pipeline_run",
            "get_pipeline_run", "list_pipeline_events", "get_pipeline_report",
        )
    }
}
