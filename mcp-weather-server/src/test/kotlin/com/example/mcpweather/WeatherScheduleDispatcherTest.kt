package com.example.mcpweather

import com.example.mcpweather.config.OpenMeteoProperties
import com.example.mcpweather.config.SchedulerProperties
import com.example.mcpweather.model.DeliveryStatus
import com.example.mcpweather.repository.WeatherObservationRepository
import com.example.mcpweather.repository.WeatherScheduleRepository
import com.example.mcpweather.repository.WeatherSummaryRepository
import com.example.mcpweather.service.CollectionPersistenceService
import com.example.mcpweather.service.OpenMeteoClient
import com.example.mcpweather.service.SummaryDeliveryService
import com.example.mcpweather.service.WeatherScheduleDispatcher
import com.example.mcpweather.service.WeatherScheduleService
import com.example.mcpweather.service.WeatherService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

class WeatherScheduleDispatcherTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `collects once after downtime creates pending aggregate and restores from same file`() {
        TestWeatherApiStub().use { stub ->
            TestDatabase.create(tempDir, "scheduler.db").use { database ->
                val clock = MutableClock(Instant.parse("2026-09-28T12:00:00Z"))
                val schedules = WeatherScheduleRepository(database.jdbcClient)
                val observations = WeatherObservationRepository(database.jdbcClient)
                val summaries = WeatherSummaryRepository(database.jdbcClient)
                val properties = SchedulerProperties(
                    batchSize = 10,
                    leaseDuration = Duration.ofMinutes(2),
                    retryDelay = Duration.ofMinutes(1),
                    stuckSummaryTimeout = Duration.ofMinutes(5),
                )
                val weatherService = weatherService(stub)
                val scheduleService = WeatherScheduleService(schedules, summaries, weatherService, clock)
                val schedule = scheduleService.create("Тестоград", 1, 5)
                val dispatcher = WeatherScheduleDispatcher(
                    schedules,
                    weatherService,
                    CollectionPersistenceService(schedules, observations, summaries),
                    SummaryDeliveryService(summaries, properties, clock),
                    properties,
                    clock,
                )

                clock.advance(Duration.ofMinutes(1))
                stub.forecastBody = forecast("2026-09-28T12:00:30", 8.0, 10.0)
                assertEquals(1, dispatcher.tick(clock.instant()))
                assertEquals(1, observations.count(schedule.scheduleId))
                assertTrue(summaries.pendingCandidates(10).isEmpty())

                clock.advance(Duration.ofHours(3))
                stub.forecastBody = forecast("2026-09-28T15:00:30", 10.0, 18.0)
                assertEquals(1, dispatcher.tick(clock.instant()))
                assertEquals(2, observations.count(schedule.scheduleId), "missed collection intervals must not replay")
                val pending = summaries.pendingCandidates(10).single()
                assertEquals(DeliveryStatus.PENDING, pending.deliveryStatus)
                assertEquals(1, pending.sampleCount, "summary uses only real observations in its current window")
                assertEquals("2026-09-28T15:02:00Z", schedules.findById(schedule.scheduleId)?.nextCollectionAt.toString())

                TestDatabase.open(database.path).use { reopened ->
                    val reopenedSchedules = WeatherScheduleRepository(reopened.jdbcClient)
                    val reopenedSummaries = WeatherSummaryRepository(reopened.jdbcClient)
                    assertNotNull(reopenedSchedules.findById(schedule.scheduleId))
                    assertEquals(pending.id, reopenedSummaries.pendingCandidates(10).single().id)
                }
            }
        }
    }

    @Test
    fun `one failed schedule does not stop later due schedules`() {
        TestWeatherApiStub().use { stub ->
            TestDatabase.create(tempDir, "isolation.db").use { database ->
                val clock = MutableClock(Instant.parse("2026-09-28T12:00:00Z"))
                val schedules = WeatherScheduleRepository(database.jdbcClient)
                val observations = WeatherObservationRepository(database.jdbcClient)
                val summaries = WeatherSummaryRepository(database.jdbcClient)
                val properties = SchedulerProperties()
                val weatherService = weatherService(stub)
                val service = WeatherScheduleService(schedules, summaries, weatherService, clock)
                val failed = service.create("Unknown", 1, 5)
                val successful = service.create("Тестоград", 1, 5)
                val dispatcher = WeatherScheduleDispatcher(
                    schedules,
                    weatherService,
                    CollectionPersistenceService(schedules, observations, summaries),
                    SummaryDeliveryService(summaries, properties, clock),
                    properties,
                    clock,
                )

                clock.advance(Duration.ofMinutes(1))
                stub.forecastBody = forecast("2026-09-28T12:00:30", 8.0, 10.0)
                assertEquals(1, dispatcher.tick(clock.instant()))
                assertEquals(0, observations.count(failed.scheduleId))
                assertEquals(1, observations.count(successful.scheduleId))
                assertFalse(schedules.findById(failed.scheduleId)?.lastError.isNullOrBlank())
            }
        }
    }

    @Test
    fun `rejects a non UTC future observation instead of silently losing summary samples`() {
        TestWeatherApiStub().use { stub ->
            TestDatabase.create(tempDir, "future-observation.db").use { database ->
                val clock = MutableClock(Instant.parse("2026-09-28T12:00:00Z"))
                val schedules = WeatherScheduleRepository(database.jdbcClient)
                val observations = WeatherObservationRepository(database.jdbcClient)
                val summaries = WeatherSummaryRepository(database.jdbcClient)
                val properties = SchedulerProperties()
                val weatherService = weatherService(stub)
                val service = WeatherScheduleService(schedules, summaries, weatherService, clock)
                val schedule = service.create("Тестоград", 1, 5)
                val dispatcher = WeatherScheduleDispatcher(
                    schedules,
                    weatherService,
                    CollectionPersistenceService(schedules, observations, summaries),
                    SummaryDeliveryService(summaries, properties, clock),
                    properties,
                    clock,
                )

                clock.advance(Duration.ofMinutes(1))
                stub.forecastBody = forecast("2026-09-28T19:00:00", 8.0, 10.0)

                assertEquals(0, dispatcher.tick(clock.instant()))
                assertEquals(0, observations.count(schedule.scheduleId))
                assertTrue(schedules.findById(schedule.scheduleId)?.lastError?.contains("future") == true)
            }
        }
    }

    private fun weatherService(stub: TestWeatherApiStub): WeatherService {
        val properties = OpenMeteoProperties(
            stub.geocodingUri,
            stub.forecastUri,
            Duration.ofSeconds(1),
            Duration.ofSeconds(2),
        )
        val mapper = JsonMapper.builder().addModule(KotlinModule.Builder().build()).build()
        return WeatherService(OpenMeteoClient(properties, mapper))
    }

    private fun forecast(time: String, temperature: Double, wind: Double): String =
        """{"current":{"temperature_2m":$temperature,"apparent_temperature":${temperature - 1},"wind_speed_10m":$wind,"weather_code":3,"time":"$time"}}"""
}
