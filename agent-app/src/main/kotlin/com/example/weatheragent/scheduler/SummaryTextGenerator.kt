package com.example.weatheragent.scheduler

import org.springframework.ai.chat.client.ChatClient
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component

@Component
class SummaryTextGenerator(
    @Qualifier("summaryChatClient")
    private val summaryChatClient: ChatClient,
) {
    fun render(summary: WeatherSummary): String = summaryChatClient.prompt()
        .user(formatPrompt(summary))
        .call()
        .content()
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?: throw IllegalStateException("OpenAI returned an empty summary")

    internal fun formatPrompt(summary: WeatherSummary): String = """
        Город: ${summary.city}
        Период: ${summary.periodStartedAt} — ${summary.periodEndedAt}
        Число измерений: ${summary.sampleCount}
        Минимальная температура, °C: ${summary.minTemperatureCelsius}
        Максимальная температура, °C: ${summary.maxTemperatureCelsius}
        Средняя температура, °C: ${summary.avgTemperatureCelsius}
        Максимальная скорость ветра, км/ч: ${summary.maxWindSpeedKmh}
        Последний weather code: ${summary.latestWeatherCode}
    """.trimIndent()
}
