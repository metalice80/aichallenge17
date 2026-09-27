package com.example.mcpweather.service

import com.example.mcpweather.model.CurrentWeatherResult
import com.example.mcpweather.model.WeatherObservation
import com.example.mcpweather.model.WeatherSchedule
import com.example.mcpweather.repository.WeatherObservationRepository
import com.example.mcpweather.repository.WeatherScheduleRepository
import com.example.mcpweather.repository.WeatherSummaryRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@Service
class CollectionPersistenceService(
    private val scheduleRepository: WeatherScheduleRepository,
    private val observationRepository: WeatherObservationRepository,
    private val summaryRepository: WeatherSummaryRepository,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun recordSuccess(
        schedule: WeatherSchedule,
        leaseOwner: String,
        weather: CurrentWeatherResult,
        observedAt: Instant,
        now: Instant,
    ) {
        observationRepository.insertIfAbsent(
            WeatherObservation(
                id = UUID.randomUUID().toString(),
                scheduleId = schedule.id,
                observedAt = observedAt,
                temperatureCelsius = weather.temperatureCelsius,
                apparentTemperatureCelsius = weather.apparentTemperatureCelsius,
                windSpeedKmh = weather.windSpeedKmh,
                weatherCode = weather.weatherCode,
                createdAt = now,
            ),
        )

        var nextSummaryAt = schedule.nextSummaryAt
        if (!schedule.nextSummaryAt.isAfter(now)) {
            val periodEndedAt = now
            val periodStartedAt = now.minus(schedule.summaryIntervalMinutes.toLong(), ChronoUnit.MINUTES)
            val aggregate = observationRepository.aggregate(schedule.id, periodStartedAt, periodEndedAt)
            if (aggregate == null) {
                logger.warn("No observations in summary window for schedule {}", schedule.id)
            } else {
                val created = summaryRepository.insertIfAbsent(
                    id = UUID.randomUUID().toString(),
                    scheduleId = schedule.id,
                    periodStartedAt = periodStartedAt,
                    periodEndedAt = periodEndedAt,
                    aggregate = aggregate,
                    createdAt = now,
                )
                if (created) logger.info("Created PENDING weather summary for schedule {}", schedule.id)
            }
            nextSummaryAt = now.plus(schedule.summaryIntervalMinutes.toLong(), ChronoUnit.MINUTES)
        }

        val updated = scheduleRepository.completeCollection(
            id = schedule.id,
            owner = leaseOwner,
            observedAt = observedAt,
            nextCollectionAt = now.plus(schedule.collectionIntervalMinutes.toLong(), ChronoUnit.MINUTES),
            nextSummaryAt = nextSummaryAt,
            updatedAt = now,
        )
        check(updated) { "Schedule lease was lost before collection commit: ${schedule.id}" }
    }
}
