package com.example.weatheragent

import com.example.weatheragent.scheduler.ScheduleView
import com.example.weatheragent.scheduler.WeatherSchedulerMcpClient
import com.example.weatheragent.scheduler.WeatherSchedulerMcpException
import com.example.weatheragent.scheduler.WeatherSummary
import com.example.weatheragent.web.GlobalExceptionHandler
import com.example.weatheragent.web.SchedulerController
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Instant

class SchedulerControllerTest {
    private lateinit var mcpClient: WeatherSchedulerMcpClient
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        mcpClient = mock()
        mockMvc = MockMvcBuilders.standaloneSetup(SchedulerController(mcpClient))
            .setControllerAdvice(GlobalExceptionHandler())
            .build()
    }

    @Test
    fun `returns schedules and delivered summaries`() {
        whenever(mcpClient.listSchedules()).thenReturn(
            listOf(
                ScheduleView(
                    id = "schedule-id",
                    city = "Новосибирск",
                    status = "ACTIVE",
                    collectionIntervalMinutes = 10,
                    summaryIntervalMinutes = 60,
                    nextCollectionAt = "2026-09-28T11:10:00Z",
                    nextSummaryAt = "2026-09-28T12:00:00Z",
                    lastCollectionAt = null,
                    observationCount = 0,
                    lastError = null,
                ),
            ),
        )
        whenever(mcpClient.listDeliveredSummaries(any(), eq(20))).thenReturn(listOf(summary()))

        mockMvc.perform(get("/api/schedules"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.schedules[0].city").value("Новосибирск"))

        mockMvc.perform(get("/api/summaries").param("after", "2026-09-28T11:00:00Z"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.summaries[0].renderedText").value("Готовая сводка"))
    }

    @Test
    fun `validates cursor and limit`() {
        mockMvc.perform(get("/api/summaries").param("after", "not-a-time"))
            .andExpect(status().isBadRequest)
        mockMvc.perform(get("/api/summaries").param("limit", "101"))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `returns safe error for MCP failure`() {
        whenever(mcpClient.listSchedules()).thenThrow(WeatherSchedulerMcpException("internal detail"))

        mockMvc.perform(get("/api/schedules"))
            .andExpect(status().isBadGateway)
            .andExpect(jsonPath("$.error").value("Не удалось получить данные планировщика"))
    }

    private fun summary(): WeatherSummary = WeatherSummary(
        id = "summary-id",
        scheduleId = "schedule-id",
        city = "Новосибирск",
        periodStartedAt = Instant.parse("2026-09-28T11:00:00Z"),
        periodEndedAt = Instant.parse("2026-09-28T12:00:00Z"),
        sampleCount = 2,
        minTemperatureCelsius = 6.0,
        maxTemperatureCelsius = 9.0,
        avgTemperatureCelsius = 7.5,
        maxWindSpeedKmh = 14.0,
        latestWeatherCode = 3,
        deliveryStatus = "DELIVERED",
        deliveryAttempts = 1,
        claimedBy = null,
        claimedAt = null,
        renderedText = "Готовая сводка",
        lastDeliveryError = null,
        createdAt = Instant.parse("2026-09-28T12:00:00Z"),
        deliveredAt = Instant.parse("2026-09-28T12:00:02Z"),
    )
}
