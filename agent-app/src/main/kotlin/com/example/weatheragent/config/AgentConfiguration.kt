package com.example.weatheragent.config

import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties
import org.springframework.ai.model.openai.autoconfigure.OpenAiCommonProperties
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.event.ContextClosedEvent
import org.springframework.context.event.EventListener
import java.net.URI

@Configuration
class AgentConfiguration {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Bean
    fun weatherChatClient(
        chatModel: ChatModel,
        mcpTools: SyncMcpToolCallbackProvider,
    ): ChatClient = ChatClient.builder(chatModel)
        .defaultSystem(
            """
            Ты погодный ассистент.
            Для любых вопросов о текущей погоде обязательно используй get_current_weather.
            Для создания периодического мониторинга используй schedule_weather_summary.
            Для проверки, последней сводки и отмены используй соответствующие weather schedule tools.
            Не выдумывай температуру, интервалы, идентификаторы или состояние расписаний.
            После создания сообщи scheduleId и времена ближайшего сбора и сводки.
            Если инструмент вернул ошибку, честно сообщи об этом пользователю.
            Отвечай кратко и на языке пользователя.
            """.trimIndent(),
        )
        .defaultTools(mcpTools)
        .build()

    @Bean
    fun summaryChatClient(chatModel: ChatModel): ChatClient = ChatClient.builder(chatModel)
        .defaultSystem(
            """
            Ты формируешь краткую погодную сводку по уже рассчитанным данным.
            Не изменяй числа, не добавляй отсутствующие факты и не вызывай инструменты.
            Укажи город, период, диапазон температуры, среднюю температуру,
            максимальный ветер и число измерений. Отвечай на русском языке.
            """.trimIndent(),
        )
        .build()

    @Bean
    fun verifyMcpTool(
        mcpTools: SyncMcpToolCallbackProvider,
        properties: AgentProperties,
        openAiCommonProperties: OpenAiCommonProperties,
        openAiChatProperties: OpenAiChatProperties,
    ): ApplicationRunner = ApplicationRunner {
        validateOpenAiApiKey(openAiCommonProperties.apiKey)
        logger.info("Using OpenAI model {}", properties.model)
        val baseUrl = openAiChatProperties.baseUrl?.takeIf(String::isNotBlank)
            ?: openAiCommonProperties.baseUrl?.takeIf(String::isNotBlank)
            ?: DEFAULT_OPENAI_BASE_URL
        logger.info("Using OpenAI endpoint {}", safeEndpoint(baseUrl))
        logger.info("Connecting to weather MCP server")
        val toolNames = mcpTools.toolCallbacks.map { it.toolDefinition.name() }.toSet()
        check(toolNames == AgentMcpToolFilter.ALLOWED_AGENT_TOOLS) {
            "Unexpected model-facing MCP tools: expected ${AgentMcpToolFilter.ALLOWED_AGENT_TOOLS}, found $toolNames"
        }
        logger.info("Discovered model-facing MCP tools {}", toolNames.sorted())
    }

    @EventListener(ContextClosedEvent::class)
    fun onClosed() {
        logger.info("Weather agent stopping; closing MCP client lifecycle")
    }

    internal fun validateOpenAiApiKey(rawApiKey: String?) {
        val apiKey = rawApiKey.orEmpty().trim()
        check(apiKey.isNotEmpty() && !(apiKey.startsWith("\${") && apiKey.endsWith("}"))) {
            "OPENAI_API_KEY must contain a real API key; unresolved placeholders are not accepted"
        }
    }

    private fun safeEndpoint(baseUrl: String): String = runCatching {
        val uri = URI.create(baseUrl)
        URI(uri.scheme, null, uri.host, uri.port, uri.path, null, null).toString()
    }.getOrDefault("<invalid endpoint>")

    private companion object {
        const val DEFAULT_OPENAI_BASE_URL = "https://api.openai.com/v1"
        const val REQUIRED_TOOL = "get_current_weather"
    }
}
