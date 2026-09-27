package com.example.weatheragent

import com.example.weatheragent.agent.AgentUpstreamException
import com.example.weatheragent.agent.WeatherAgent
import com.example.weatheragent.config.AgentProperties
import com.example.weatheragent.web.ChatController
import com.example.weatheragent.web.GlobalExceptionHandler
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class ChatControllerTest {
    private lateinit var weatherAgent: WeatherAgent
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        weatherAgent = mock()
        mockMvc = MockMvcBuilders
            .standaloneSetup(ChatController(weatherAgent, AgentProperties("test-model", 20)))
            .setControllerAdvice(GlobalExceptionHandler())
            .build()
    }

    @Test
    fun `returns agent answer`() {
        whenever(weatherAgent.ask("Погода?")).thenReturn("Сейчас +8 °C")

        mockMvc.perform(
            post("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"message":"Погода?"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.answer").value("Сейчас +8 °C"))
    }

    @Test
    fun `rejects blank and oversized messages`() {
        mockMvc.perform(
            post("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"message":"   "}"""),
        ).andExpect(status().isBadRequest)

        mockMvc.perform(
            post("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"message":"${"x".repeat(21)}"}"""),
        ).andExpect(status().isBadRequest)
    }

    @Test
    fun `returns safe upstream error`() {
        whenever(weatherAgent.ask(any())).thenThrow(
            AgentUpstreamException("internal upstream URL", IllegalStateException("sensitive upstream details")),
        )

        mockMvc.perform(
            post("/api/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"message":"Погода?"}"""),
        )
            .andExpect(status().isBadGateway)
            .andExpect(content().string("{\"error\":\"Не удалось получить ответ погодного агента\"}"))
    }
}
