package com.example.mcpweather.model

import java.time.Instant

enum class ScheduleStatus {
    ACTIVE,
    PAUSED,
    CANCELLED,
}

enum class DeliveryStatus {
    PENDING,
    PROCESSING,
    DELIVERED,
    FAILED,
}

data class WeatherSchedule(
    val id: String,
    val city: String,
    val normalizedCity: String,
    val collectionIntervalMinutes: Int,
    val summaryIntervalMinutes: Int,
    val status: ScheduleStatus,
    val nextCollectionAt: Instant,
    val nextSummaryAt: Instant,
    val lastCollectionAt: Instant?,
    val leaseOwner: String?,
    val leaseUntil: Instant?,
    val consecutiveFailures: Int,
    val lastError: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class WeatherObservation(
    val id: String,
    val scheduleId: String,
    val observedAt: Instant,
    val temperatureCelsius: Double,
    val apparentTemperatureCelsius: Double,
    val windSpeedKmh: Double,
    val weatherCode: Int,
    val createdAt: Instant,
)

data class WeatherAggregate(
    val sampleCount: Int,
    val minTemperatureCelsius: Double,
    val maxTemperatureCelsius: Double,
    val avgTemperatureCelsius: Double,
    val maxWindSpeedKmh: Double,
    val latestWeatherCode: Int,
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
    val deliveryStatus: DeliveryStatus,
    val deliveryAttempts: Int,
    val claimedBy: String?,
    val claimedAt: Instant?,
    val renderedText: String?,
    val lastDeliveryError: String?,
    val createdAt: Instant,
    val deliveredAt: Instant?,
)

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

data class ScheduleCreated(
    val scheduleId: String,
    val city: String,
    val status: String,
    val collectionIntervalMinutes: Int,
    val summaryIntervalMinutes: Int,
    val nextCollectionAt: String,
    val nextSummaryAt: String,
)

data class ScheduleCancellation(
    val scheduleId: String,
    val status: String,
    val alreadyCancelled: Boolean,
)

data class LatestSummaryResult(
    val status: String,
    val summary: WeatherSummary? = null,
)

data class SummaryListResult(
    val summaries: List<WeatherSummary>,
)

data class ScheduleListResult(
    val schedules: List<ScheduleView>,
)

data class SummaryOperationResult(
    val summaryId: String,
    val status: String,
)
