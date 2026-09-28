package dev.aichallenge.weather.agent.config

import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Lazy

@Configuration(proxyBeanMethods = false)
class ChatClientConfiguration {
    @Bean("pipelineChatClient")
    @Lazy
    fun pipelineChatClient(chatModel: ChatModel, callbacks: SyncMcpToolCallbackProvider): ChatClient {
        val tools = callbacks.toolCallbacks
        val names = tools.map { it.toolDefinition.name() }.toSet()
        require(names == McpToolFilterConfiguration.AGENT_TOOLS) {
            "Pipeline ChatClient must expose exactly ${McpToolFilterConfiguration.AGENT_TOOLS}; found $names"
        }
        return ChatClient.builder(chatModel)
            .defaultSystem(SYSTEM_PROMPT)
            .defaultAdvisors(ToolCallingAdvisor.builder().build())
            .defaultTools(*tools)
            .build()
    }

    companion object {
        const val SYSTEM_PROMPT = """You are a weather report pipeline agent.

When the user asks to find weather data, summarize it, and save a report,
you must complete exactly this dependency chain:
1. Call search_weather_forecast.
2. Read artifactId from the successful result.
3. Call summarize_weather_forecast with that exact artifactId.
4. Read artifactId from the successful summary result.
5. Call save_weather_report with that exact artifactId and the requested safe .md file name.
6. Return a final success message only after save_weather_report returns status SAVED.
Never invent, shorten, translate, or otherwise modify artifact identifiers.
Never skip a step. Never claim that a report was saved if the save tool did not succeed.
If any tool returns an error, stop the chain and explain which stage failed."""
    }
}
