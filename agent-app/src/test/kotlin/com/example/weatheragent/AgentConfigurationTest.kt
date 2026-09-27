package com.example.weatheragent

import com.example.weatheragent.config.AgentConfiguration
import com.example.weatheragent.config.AgentProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.springframework.ai.chat.messages.AssistantMessage
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.model.tool.ToolCallingChatOptions
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.MutablePropertySources
import org.springframework.core.env.PropertySourcesPropertyResolver
import org.springframework.core.io.ClassPathResource

class AgentConfigurationTest {
    private val contextRunner = ApplicationContextRunner()
        .withUserConfiguration(PropertiesConfiguration::class.java)
        .withPropertyValues(
            "app.agent.model=test-model",
            "app.agent.max-message-length=2000",
        )

    @Test
    fun `official OpenAI base URL contains API v1 path`() {
        val propertySources = MutablePropertySources()
        YamlPropertySourceLoader()
            .load("application", ClassPathResource("application.yaml"))
            .forEach(propertySources::addLast)
        val resolver = PropertySourcesPropertyResolver(propertySources)

        assertEquals(
            "https://api.openai.com/v1",
            resolver.getProperty("spring.ai.openai.base-url"),
        )
    }

    @Test
    fun `loads configured model and supports override`() {
        contextRunner
            .withPropertyValues("app.agent.model=override-model")
            .run { context ->
                assertEquals("override-model", context.getBean(AgentProperties::class.java).model)
            }
    }

    @Test
    fun `rejects blank model id`() {
        contextRunner
            .withPropertyValues("app.agent.model= ")
            .run { context ->
                assertNotNull(context.startupFailure)
            }
    }

    @Test
    fun `chat client obtains MCP tool callbacks`() {
        val chatModel = mock<ChatModel>()
        val mcpTools = mock<SyncMcpToolCallbackProvider>()
        whenever(mcpTools.toolCallbacks).thenReturn(emptyArray())
        whenever(chatModel.options).thenReturn(ToolCallingChatOptions.builder().build())
        whenever(chatModel.call(any<Prompt>())).thenReturn(
            ChatResponse(listOf(Generation(AssistantMessage("ok")))),
        )

        val answer = AgentConfiguration()
            .weatherChatClient(chatModel, mcpTools)
            .prompt()
            .user("hello")
            .call()
            .content()

        assertEquals("ok", answer)
        verify(mcpTools).toolCallbacks
    }

    @Test
    fun `summary chat client calls model without MCP callbacks`() {
        val chatModel = mock<ChatModel>()
        whenever(chatModel.options).thenReturn(ToolCallingChatOptions.builder().build())
        whenever(chatModel.call(any<Prompt>())).thenReturn(
            ChatResponse(listOf(Generation(AssistantMessage("summary")))),
        )

        val answer = AgentConfiguration()
            .summaryChatClient(chatModel)
            .prompt()
            .user("aggregate")
            .call()
            .content()

        assertEquals("summary", answer)
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AgentProperties::class)
    class PropertiesConfiguration
}
