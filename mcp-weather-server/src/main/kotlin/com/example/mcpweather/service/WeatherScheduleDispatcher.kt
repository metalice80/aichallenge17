package com.example.mcpweather.service

import com.example.mcpweather.config.SchedulerProperties
import com.example.mcpweather.model.WeatherSchedule
import com.example.mcpweather.repository.WeatherScheduleRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

@Component
class WeatherScheduleDispatcher(
    private val scheduleRepository: WeatherScheduleRepository,
    private val weatherService: WeatherService,
    private val collectionPersistenceService: CollectionPersistenceService,
    private val summaryDeliveryService: SummaryDeliveryService,
    private val properties: SchedulerProperties,
    private val clock: Clock,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val running = AtomicBoolean(false)
    private val workerId = "dispatcher-${UUID.randomUUID()}"

    @Scheduled(fixedDelayString = "\${app.scheduler.scan-delay:10s}")
    fun scheduledTick() {
        if (properties.enabled) tick(clock.instant())
    }

    fun tick(now: Instant): Int {
        if (!running.compareAndSet(false, true)) {
            logger.debug("Skipping overlapping scheduler tick")
            return 0
        }
        try {
            val recovered = summaryDeliveryService.recoverStuck()
            if (recovered > 0) logger.info("Recovered {} stuck summaries", recovered)
            val due = scheduleRepository.findDue(now, properties.batchSize)
            logger.debug("Starting scheduler tick with {} due schedules", due.size)
            var completed = 0
            due.forEach { schedule ->
                try {
                    if (process(schedule, now)) completed++
                } catch (exception: RuntimeException) {
                    logger.warn(
                        "Schedule {} ({}) collection failed: {}",
                        schedule.id,
                        schedule.city,
                        exception.message?.take(300) ?: exception.javaClass.simpleName,
                    )
                    runCatching {
                        scheduleRepository.failCollection(
                            id = schedule.id,
                            owner = workerId,
                            nextCollectionAt = now.plus(properties.retryDelay),
                            error = safeError(exception),
                            updatedAt = clock.instant(),
                        )
                    }.onFailure { failure ->
                        logger.error("Unable to persist failure for schedule {}: {}", schedule.id, failure.javaClass.simpleName)
                    }
                }
            }
            logger.debug("Completed scheduler tick; {} schedules collected", completed)
            return completed
        } finally {
            running.set(false)
        }
    }

    private fun process(schedule: WeatherSchedule, now: Instant): Boolean {
        val leased = scheduleRepository.acquireLease(
            id = schedule.id,
            owner = workerId,
            now = now,
            leaseUntil = now.plus(properties.leaseDuration),
        )
        if (!leased) return false

        logger.info("Collecting weather for schedule {} city {}", schedule.id, schedule.city)
        val weather = weatherService.getCurrentWeather(schedule.city)
        val completedAt = clock.instant()
        val observedAt = parseObservedAt(weather.observedAt)
        if (observedAt.isAfter(completedAt.plus(MAX_OBSERVATION_CLOCK_SKEW))) {
            throw WeatherToolException("Open-Meteo observation time is unexpectedly in the future")
        }
        collectionPersistenceService.recordSuccess(
            schedule = schedule,
            leaseOwner = workerId,
            weather = weather,
            observedAt = observedAt,
            now = completedAt,
        )
        return true
    }

    private fun parseObservedAt(value: String): Instant = try {
        if (value.endsWith("Z") || OFFSET_SUFFIX.containsMatchIn(value)) {
            Instant.parse(value)
        } else {
            LocalDateTime.parse(value).toInstant(ZoneOffset.UTC)
        }
    } catch (exception: DateTimeParseException) {
        throw WeatherToolException("Open-Meteo current weather has an invalid observation time", exception)
    }

    private fun safeError(exception: RuntimeException): String =
        (exception.message ?: exception.javaClass.simpleName)
            .replace(Regex("[\\p{Cc}\\p{Cf}]"), " ")
            .take(500)

    private companion object {
        val OFFSET_SUFFIX = Regex("[+-]\\d{2}:\\d{2}$")
        val MAX_OBSERVATION_CLOCK_SKEW: Duration = Duration.ofMinutes(1)
    }
}
