package com.example.mcpweather

import com.example.mcpweather.config.SchedulerProperties
import com.example.mcpweather.model.ScheduleStatus
import com.example.mcpweather.model.WeatherAggregate
import com.example.mcpweather.model.WeatherObservation
import com.example.mcpweather.model.WeatherSchedule
import com.example.mcpweather.repository.WeatherObservationRepository
import com.example.mcpweather.repository.WeatherScheduleRepository
import com.example.mcpweather.repository.WeatherSummaryRepository
import com.example.mcpweather.service.SummaryDeliveryService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID

class WeatherRepositoryTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `schema repositories leases aggregates delivery and reopen are durable`() {
        val dbPath = tempDir.resolve("repository.db")
        TestDatabase.create(tempDir, "repository.db").use { database ->
            val schedules = WeatherScheduleRepository(database.jdbcClient)
            val observations = WeatherObservationRepository(database.jdbcClient)
            val summaries = WeatherSummaryRepository(database.jdbcClient)
            val now = Instant.parse("2026-09-28T12:00:00Z")
            val clock = MutableClock(now)
            val schedule = schedule(now)

            assertEquals(
                setOf("weather_schedule", "weather_observation", "weather_summary"),
                database.jdbcClient.sql(
                    "SELECT name FROM sqlite_master WHERE type = 'table' AND name LIKE 'weather_%' ORDER BY name",
                ).query(String::class.java).list().toSet(),
            )
            assertEquals(1, schedules.insert(schedule))
            assertEquals(schedule, schedules.findById(schedule.id))
            assertEquals(listOf(schedule.id), schedules.findDue(now, 10).map { it.id })
            assertTrue(schedules.acquireLease(schedule.id, "worker-a", now, now.plusSeconds(120)))
            assertFalse(schedules.acquireLease(schedule.id, "worker-b", now, now.plusSeconds(120)))

            val observation = WeatherObservation(
                id = UUID.randomUUID().toString(),
                scheduleId = schedule.id,
                observedAt = now.minusSeconds(30),
                temperatureCelsius = 7.0,
                apparentTemperatureCelsius = 5.0,
                windSpeedKmh = 12.0,
                weatherCode = 3,
                createdAt = now,
            )
            assertTrue(observations.insertIfAbsent(observation))
            assertFalse(observations.insertIfAbsent(observation.copy(id = UUID.randomUUID().toString())))
            val aggregate = observations.aggregate(schedule.id, now.minusSeconds(300), now)
            assertNotNull(aggregate)
            assertEquals(1, aggregate!!.sampleCount)
            assertEquals(7.0, aggregate.avgTemperatureCelsius)
            val legacyFutureObservation = observation.copy(
                id = UUID.randomUUID().toString(),
                observedAt = now.plus(Duration.ofHours(7)),
            )
            assertTrue(observations.insertIfAbsent(legacyFutureObservation))
            assertNull(
                observations.aggregate(
                    schedule.id,
                    now.plus(Duration.ofHours(6)),
                    now.plus(Duration.ofHours(8)),
                ),
                "legacy local-time observations must never enter a UTC summary window",
            )

            val deliveredId = UUID.randomUUID().toString()
            assertTrue(
                summaries.insertIfAbsent(
                    deliveredId,
                    schedule.id,
                    now.minusSeconds(300),
                    now,
                    aggregate,
                    now,
                ),
            )
            assertFalse(
                summaries.insertIfAbsent(
                    UUID.randomUUID().toString(),
                    schedule.id,
                    now.minusSeconds(300),
                    now,
                    aggregate,
                    now,
                ),
            )

            val delivery = SummaryDeliveryService(
                summaries,
                SchedulerProperties(maxDeliveryAttempts = 3),
                clock,
            )
            val claimed = delivery.claimPending("publisher-a", 5)
            assertEquals(listOf(deliveredId), claimed.map { it.id })
            assertTrue(delivery.claimPending("publisher-b", 5).isEmpty())
            assertEquals("DELIVERED", delivery.complete(deliveredId, "publisher-a", "Готовая сводка").status)
            assertEquals(listOf(deliveredId), summaries.listDelivered(null, 20).map { it.id })

            val retryId = UUID.randomUUID().toString()
            val retryWindowEnd = now.plusSeconds(1)
            summaries.insertIfAbsent(
                retryId,
                schedule.id,
                now.minusSeconds(299),
                retryWindowEnd,
                WeatherAggregate(1, 7.0, 7.0, 7.0, 12.0, 3),
                now.plusSeconds(1),
            )
            repeat(2) {
                assertEquals(retryId, delivery.claimPending("publisher-a", 1).single().id)
                assertEquals("PENDING", delivery.fail(retryId, "publisher-a", "rate limited").status)
            }
            assertEquals(retryId, delivery.claimPending("publisher-a", 1).single().id)
            assertEquals("FAILED", delivery.fail(retryId, "publisher-a", "rate limited").status)

            assertEquals(1, schedules.cancel(schedule.id, now.plusSeconds(2)))
            assertEquals(0, schedules.cancel(schedule.id, now.plusSeconds(3)))
            assertEquals(ScheduleStatus.CANCELLED, schedules.findById(schedule.id)?.status)

            TestDatabase.open(dbPath).use { reopened ->
                assertEquals(ScheduleStatus.CANCELLED, WeatherScheduleRepository(reopened.jdbcClient).findById(schedule.id)?.status)
                assertEquals("Готовая сводка", WeatherSummaryRepository(reopened.jdbcClient).findById(deliveredId)?.renderedText)
            }
        }
        assertFalse(Files.exists(dbPath))
        assertFalse(Files.exists(Path.of("$dbPath-wal")))
        assertFalse(Files.exists(Path.of("$dbPath-shm")))
    }

    private fun schedule(now: Instant): WeatherSchedule = WeatherSchedule(
        id = UUID.randomUUID().toString(),
        city = "Новосибирск",
        normalizedCity = "новосибирск",
        collectionIntervalMinutes = 1,
        summaryIntervalMinutes = 5,
        status = ScheduleStatus.ACTIVE,
        nextCollectionAt = now,
        nextSummaryAt = now,
        lastCollectionAt = null,
        leaseOwner = null,
        leaseUntil = null,
        consecutiveFailures = 0,
        lastError = null,
        createdAt = now.minus(Duration.ofMinutes(1)),
        updatedAt = now.minus(Duration.ofMinutes(1)),
    )
}
