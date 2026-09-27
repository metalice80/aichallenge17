package com.example.mcpweather.service

import com.example.mcpweather.model.LatestSummaryResult
import com.example.mcpweather.model.ScheduleCancellation
import com.example.mcpweather.model.ScheduleCreated
import com.example.mcpweather.model.ScheduleListResult
import com.example.mcpweather.model.ScheduleStatus
import com.example.mcpweather.model.ScheduleView
import com.example.mcpweather.model.SummaryListResult
import com.example.mcpweather.model.SummaryOperationResult
import com.example.mcpweather.model.WeatherSchedule
import com.example.mcpweather.model.WeatherSummary
import com.example.mcpweather.repository.WeatherScheduleRepository
import com.example.mcpweather.repository.WeatherSummaryRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID

@Service
class WeatherScheduleService(
    private val scheduleRepository: WeatherScheduleRepository,
    private val summaryRepository: WeatherSummaryRepository,
    private val weatherService: WeatherService,
    private val clock: Clock,
) {
    @Transactional
    fun create(rawCity: String, collectionIntervalMinutes: Int, summaryIntervalMinutes: Int): ScheduleCreated {
        val city = weatherService.validateCity(rawCity)
        validateIntervals(collectionIntervalMinutes, summaryIntervalMinutes)
        val normalizedCity = normalizeCity(city)
        scheduleRepository.findActiveDuplicate(
            normalizedCity,
            collectionIntervalMinutes,
            summaryIntervalMinutes,
        )?.let { return it.toCreated() }

        val now = clock.instant().truncatedTo(ChronoUnit.SECONDS)
        val schedule = WeatherSchedule(
            id = UUID.randomUUID().toString(),
            city = city,
            normalizedCity = normalizedCity,
            collectionIntervalMinutes = collectionIntervalMinutes,
            summaryIntervalMinutes = summaryIntervalMinutes,
            status = ScheduleStatus.ACTIVE,
            nextCollectionAt = now.plus(collectionIntervalMinutes.toLong(), ChronoUnit.MINUTES),
            nextSummaryAt = now.plus(summaryIntervalMinutes.toLong(), ChronoUnit.MINUTES),
            lastCollectionAt = null,
            leaseOwner = null,
            leaseUntil = null,
            consecutiveFailures = 0,
            lastError = null,
            createdAt = now,
            updatedAt = now,
        )
        try {
            scheduleRepository.insert(schedule)
        } catch (exception: DataIntegrityViolationException) {
            scheduleRepository.findActiveDuplicate(
                normalizedCity,
                collectionIntervalMinutes,
                summaryIntervalMinutes,
            )?.let { return it.toCreated() }
            throw WeatherToolException("Unable to create weather schedule", exception)
        }
        return schedule.toCreated()
    }

    fun status(rawScheduleId: String): ScheduleView = requireSchedule(rawScheduleId).toView()

    fun latestSummary(rawScheduleId: String): LatestSummaryResult {
        val schedule = requireSchedule(rawScheduleId)
        val summary = summaryRepository.findLatestDelivered(schedule.id)
        return if (summary == null) LatestSummaryResult(status = "NOT_READY")
        else LatestSummaryResult(status = "DELIVERED", summary = summary)
    }

    @Transactional
    fun cancel(rawScheduleId: String): ScheduleCancellation {
        val id = validateUuid(rawScheduleId, "scheduleId")
        val schedule = scheduleRepository.findById(id)
            ?: throw WeatherToolException("Weather schedule not found: $id")
        val alreadyCancelled = schedule.status == ScheduleStatus.CANCELLED
        if (!alreadyCancelled) {
            scheduleRepository.cancel(id, clock.instant())
        }
        return ScheduleCancellation(id, ScheduleStatus.CANCELLED.name, alreadyCancelled)
    }

    fun listSchedules(): ScheduleListResult = ScheduleListResult(
        scheduleRepository.list().map { it.toView() },
    )

    fun listDeliveredSummaries(after: String?, limit: Int): SummaryListResult {
        require(limit in 1..100) { "limit must be between 1 and 100" }
        val parsedAfter = after?.takeIf(String::isNotBlank)?.let {
            try {
                Instant.parse(it)
            } catch (exception: RuntimeException) {
                throw WeatherToolException("after must be an ISO-8601 UTC timestamp", exception)
            }
        }
        return SummaryListResult(summaryRepository.listDelivered(parsedAfter, limit))
    }

    internal fun validateIntervals(collectionIntervalMinutes: Int, summaryIntervalMinutes: Int) {
        if (collectionIntervalMinutes !in 1..1440) {
            throw WeatherToolException("collectionIntervalMinutes must be between 1 and 1440")
        }
        if (summaryIntervalMinutes !in 5..10080) {
            throw WeatherToolException("summaryIntervalMinutes must be between 5 and 10080")
        }
        if (summaryIntervalMinutes < collectionIntervalMinutes) {
            throw WeatherToolException("summaryIntervalMinutes must be greater than or equal to collectionIntervalMinutes")
        }
    }

    private fun requireSchedule(rawScheduleId: String): WeatherSchedule {
        val id = validateUuid(rawScheduleId, "scheduleId")
        return scheduleRepository.findById(id)
            ?: throw WeatherToolException("Weather schedule not found: $id")
    }

    private fun WeatherSchedule.toCreated(): ScheduleCreated = ScheduleCreated(
        scheduleId = id,
        city = city,
        status = status.name,
        collectionIntervalMinutes = collectionIntervalMinutes,
        summaryIntervalMinutes = summaryIntervalMinutes,
        nextCollectionAt = nextCollectionAt.toString(),
        nextSummaryAt = nextSummaryAt.toString(),
    )

    private fun WeatherSchedule.toView(): ScheduleView = ScheduleView(
        id = id,
        city = city,
        status = status.name,
        collectionIntervalMinutes = collectionIntervalMinutes,
        summaryIntervalMinutes = summaryIntervalMinutes,
        nextCollectionAt = nextCollectionAt.toString(),
        nextSummaryAt = nextSummaryAt.toString(),
        lastCollectionAt = lastCollectionAt?.toString(),
        observationCount = scheduleRepository.observationCount(id),
        lastError = lastError,
    )

    companion object {
        fun normalizeCity(city: String): String = city.trim()
            .lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), " ")

        fun validateUuid(raw: String, field: String): String = try {
            UUID.fromString(raw.trim()).toString()
        } catch (exception: RuntimeException) {
            throw WeatherToolException("$field must be a valid UUID", exception)
        }
    }
}
