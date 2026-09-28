package dev.aichallenge.weather.agent.config

import org.springframework.ai.mcp.ToolContextToMcpMetaConverter
import org.springframework.ai.chat.model.ToolContext
import org.springframework.stereotype.Component

@Component
class PipelineToolContextConverter : ToolContextToMcpMetaConverter {
    override fun convert(toolContext: ToolContext): Map<String, Any> {
        val context = toolContext.context
        return listOf("pipelineRunId", "conversationId", "traceparent")
            .mapNotNull { key -> context[key]?.let { key to it } }
            .toMap()
    }
}
