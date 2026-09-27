package com.example.weatheragent.scheduler

import java.time.Instant

data class ScheduleView(
    val id: String,
    val city: String,
    val status: String,
    val collectionIntervalMinutes: Int,
    val summaryIntervalMinutes: Int,
    val nextCollectionAt: String,
    val nextSummaryAt: String,
    val lastCollectionAt: String?,
    val observationCount: Long,
    val lastError: String?,
)

data class WeatherSummary(
    val id: String,
    val scheduleId: String,
    val city: String,
    val periodStartedAt: Instant,
    val periodEndedAt: Instant,
    val sampleCount: Int,
    val minTemperatureCelsius: Double,
    val maxTemperatureCelsius: Double,
    val avgTemperatureCelsius: Double,
    val maxWindSpeedKmh: Double,
    val latestWeatherCode: Int,
    val deliveryStatus: String,
    val deliveryAttempts: Int,
    val claimedBy: String?,
    val claimedAt: Instant?,
    val renderedText: String?,
    val lastDeliveryError: String?,
    val createdAt: Instant,
    val deliveredAt: Instant?,
)

data class ScheduleListResult(val schedules: List<ScheduleView>)
data class SummaryListResult(val summaries: List<WeatherSummary>)
data class SummaryOperationResult(val summaryId: String, val status: String)
