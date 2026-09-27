package com.example.weatheragent.agent

import com.openai.errors.NotFoundException

import com.example.weatheragent.config.AgentProperties

import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Service

@Service
class WeatherAgent(
    @Qualifier("weatherChatClient")
    private val weatherChatClient: ChatClient,
    private val properties: AgentProperties,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun ask(message: String): String {
        logger.info("Starting chat request")
        return try {
            val answer = weatherChatClient.prompt()
                .user(message)
                .call()
                .content()
                ?.takeIf(String::isNotBlank)
                ?: throw AgentUpstreamException("OpenAI returned an empty response")
            logger.info("Chat request completed")
            answer
        } catch (exception: AgentUpstreamException) {
            throw exception
        } catch (exception: RuntimeException) {
            exception.findNotFound()?.let { notFound ->
                logger.error(
                    "OpenAI returned HTTP {} for model {} (code={}, type={}, param={}). " +
                        "Verify API-project model access, billing, and the configured OpenAI base URL.",
                    notFound.statusCode(),
                    properties.model,
                    notFound.code().orElse("unspecified"),
                    notFound.type().orElse("unspecified"),
                    notFound.param().orElse("unspecified"),
                )
            }
            throw AgentUpstreamException("Weather agent upstream request failed", exception)
        }
    }

    private fun Throwable.findNotFound(): NotFoundException? {
        var current: Throwable? = this
        while (current != null) {
            if (current is NotFoundException) {
                return current
            }
            current = current.cause
        }
        return null
    }
}

class AgentUpstreamException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
