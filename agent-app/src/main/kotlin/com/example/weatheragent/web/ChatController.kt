package com.example.weatheragent.web

import com.example.weatheragent.agent.WeatherAgent
import com.example.weatheragent.config.AgentProperties
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/chat")
class ChatController(
    private val weatherAgent: WeatherAgent,
    private val properties: AgentProperties,
) {
    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun chat(@RequestBody request: ChatRequest): ChatResponse {
        val message = validateMessage(request.message)
        return ChatResponse(weatherAgent.ask(message))
    }

    private fun validateMessage(rawMessage: String?): String {
        val message = rawMessage?.trim().orEmpty()
        if (message.isEmpty()) {
            throw InvalidChatRequestException("Message must not be blank")
        }
        if (message.length > properties.maxMessageLength) {
            throw InvalidChatRequestException("Message is too long")
        }
        if (message.any { Character.isISOControl(it) && it !in ALLOWED_CONTROL_CHARACTERS }) {
            throw InvalidChatRequestException("Message contains invalid control characters")
        }
        return message
    }

    private companion object {
        val ALLOWED_CONTROL_CHARACTERS = setOf('\n', '\r', '\t')
    }
}

class InvalidChatRequestException(message: String) : RuntimeException(message)
