package dev.aichallenge.day20.agent.config

import com.fasterxml.jackson.databind.ObjectMapper
import dev.aichallenge.day20.agent.orchestration.GuardedToolCallback
import dev.aichallenge.day20.agent.orchestration.OrchestrationObserver
import dev.aichallenge.day20.agent.orchestration.RoutingPolicy
import dev.aichallenge.day20.agent.repository.OrchestrationEventRepository
import dev.aichallenge.day20.agent.repository.ToolInvocationRepository
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.tool.ToolCallback
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class ChatClientConfiguration {
    @Bean
    fun guardedToolCallbacks(
        registry: McpToolRegistry,
        policy: RoutingPolicy,
        invocations: ToolInvocationRepository,
        events: OrchestrationEventRepository,
        objectMapper: ObjectMapper,
        observer: OrchestrationObserver,
    ): List<ToolCallback> = registry.callbacks.map { GuardedToolCallback(it, policy, invocations, events, objectMapper, observer) }

    @Bean("orchestrationChatClient")
    fun orchestrationChatClient(chatClientBuilder: ChatClient.Builder, guardedToolCallbacks: List<ToolCallback>): ChatClient = chatClientBuilder.clone()
        .defaultSystem(SYSTEM_PROMPT)
        .defaultToolCallbacks(guardedToolCallbacks)
        .build()

    companion object {
        const val SYSTEM_PROMPT = """You are a travel planning orchestration agent connected to three MCP servers.

Tool routing:
- weather_* tools are only for resolving locations and obtaining weather forecasts.
- guide_* tools are only for searching destination articles and obtaining article summaries.
- files_* tools are only for writing the final Markdown report.

For a complete travel report:
1. Resolve the destination with weather_resolve_location.
2. Pass the exact returned locationRef to weather_get_forecast.
3. Search destination articles with guide_search_articles.
4. Select three unique relevant articleRef values from that result.
5. Call guide_get_article_summary once for each selected articleRef.
6. Build a concise Markdown report using only facts returned by tools.
7. Include the forecast, three places, source links, and practical weather-aware advice.
8. Call files_save_markdown_report with the exact evidence references.
9. Return success only after the file tool returns status SAVED.

Never invent or modify locationRef or articleRef values. Never invent weather values, article facts, source URLs, or saved paths. Do not skip prerequisites. Do not repeat a completed side effect. If a tool returns a retryable routing error, correct the sequence. If a required external service fails, stop and identify the failed server and tool."""
    }
}
