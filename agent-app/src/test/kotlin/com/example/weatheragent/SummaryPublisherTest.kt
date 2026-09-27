package com.example.weatheragent

import com.example.weatheragent.config.SummaryPublisherProperties
import com.example.weatheragent.scheduler.SummaryPublisher
import com.example.weatheragent.scheduler.SummaryTextGenerator
import com.example.weatheragent.scheduler.WeatherSchedulerMcpClient
import com.example.weatheragent.scheduler.WeatherSchedulerMcpException
import com.example.weatheragent.scheduler.WeatherSummary
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.times
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.springframework.ai.chat.client.ChatClient
import java.time.Instant

class SummaryPublisherTest {
    @Test
    fun `empty claim does not invoke summary generator`() {
        val mcp = mock<WeatherSchedulerMcpClient>()
        val generator = mock<SummaryTextGenerator>()
        whenever(mcp.claimPendingSummaries(any(), eq(5))).thenReturn(emptyList())

        val delivered = SummaryPublisher(mcp, generator, SummaryPublisherProperties(batchSize = 5)).publishBatch()

        assertEquals(0, delivered)
        verifyNoInteractions(generator)
        verify(mcp, never()).completeSummary(any(), any(), any())
    }

    @Test
    fun `claim timeout is contained and a later run retries`() {
        val mcp = mock<WeatherSchedulerMcpClient>()
        val generator = mock<SummaryTextGenerator>()
        whenever(mcp.claimPendingSummaries(any(), eq(5)))
            .thenThrow(WeatherSchedulerMcpException("MCP request timed out"))
            .thenReturn(emptyList())
        val publisher = SummaryPublisher(mcp, generator, SummaryPublisherProperties(batchSize = 5))

        assertEquals(0, publisher.publishBatch())
        assertEquals(0, publisher.publishBatch())

        verify(mcp, times(2)).claimPendingSummaries(any(), eq(5))
        verifyNoInteractions(generator)
        verify(mcp, never()).completeSummary(any(), any(), any())
        verify(mcp, never()).failSummary(any(), any(), any())
    }

    @Test
    fun `failure records retry and does not stop remaining summaries`() {
        val mcp = mock<WeatherSchedulerMcpClient>()
        val generator = mock<SummaryTextGenerator>()
        val first = summary("00000000-0000-0000-0000-000000000001")
        val second = summary("00000000-0000-0000-0000-000000000002")
        whenever(mcp.claimPendingSummaries(any(), eq(2))).thenReturn(listOf(first, second))
        whenever(generator.render(first)).thenThrow(IllegalStateException("rate limit"))
        whenever(generator.render(second)).thenReturn("Готовая сводка")

        val delivered = SummaryPublisher(mcp, generator, SummaryPublisherProperties(batchSize = 2)).publishBatch()

        assertEquals(1, delivered)
        verify(mcp).failSummary(eq(first.id), any(), eq("rate limit"))
        verify(mcp).completeSummary(eq(second.id), any(), eq("Готовая сводка"))
        verify(mcp).claimPendingSummaries(any(), eq(2))
    }

    @Test
    fun `summary prompt contains every aggregate without rounding`() {
        val generator = SummaryTextGenerator(mock<ChatClient>())
        val prompt = generator.formatPrompt(summary("00000000-0000-0000-0000-000000000003"))

        assertTrue(prompt.contains("Тестоград"))
        assertTrue(prompt.contains("6.125"))
        assertTrue(prompt.contains("9.875"))
        assertTrue(prompt.contains("7.333333333"))
        assertTrue(prompt.contains("18.25"))
        assertTrue(prompt.contains("Число измерений: 3"))
    }

    private fun summary(id: String): WeatherSummary = WeatherSummary(
        id = id,
        scheduleId = "10000000-0000-0000-0000-000000000000",
        city = "Тестоград",
        periodStartedAt = Instant.parse("2026-09-28T11:00:00Z"),
        periodEndedAt = Instant.parse("2026-09-28T12:00:00Z"),
        sampleCount = 3,
        minTemperatureCelsius = 6.125,
        maxTemperatureCelsius = 9.875,
        avgTemperatureCelsius = 7.333333333,
        maxWindSpeedKmh = 18.25,
        latestWeatherCode = 3,
        deliveryStatus = "PROCESSING",
        deliveryAttempts = 1,
        claimedBy = "worker",
        claimedAt = Instant.parse("2026-09-28T12:00:01Z"),
        renderedText = null,
        lastDeliveryError = null,
        createdAt = Instant.parse("2026-09-28T12:00:00Z"),
        deliveredAt = null,
    )
}
