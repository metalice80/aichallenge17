package com.example.mcpweather

import com.example.mcpweather.config.OpenMeteoProperties
import com.example.mcpweather.repository.WeatherScheduleRepository
import com.example.mcpweather.repository.WeatherSummaryRepository
import com.example.mcpweather.service.OpenMeteoClient
import com.example.mcpweather.service.WeatherScheduleService
import com.example.mcpweather.service.WeatherService
import com.example.mcpweather.service.WeatherToolException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

class WeatherScheduleServiceTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `validates normalizes deduplicates and calculates first runs from clock`() {
        TestWeatherApiStub().use { stub ->
            TestDatabase.create(tempDir).use { database ->
                val clock = MutableClock(Instant.parse("2026-09-28T11:00:00Z"))
                val service = WeatherScheduleService(
                    WeatherScheduleRepository(database.jdbcClient),
                    WeatherSummaryRepository(database.jdbcClient),
                    weatherService(stub),
                    clock,
                )

                val created = service.create("  Новосибирск  ", 10, 60)
                val duplicate = service.create("новосибирск", 10, 60)

                assertEquals(created.scheduleId, duplicate.scheduleId)
                assertEquals("2026-09-28T11:10:00Z", created.nextCollectionAt)
                assertEquals("2026-09-28T12:00:00Z", created.nextSummaryAt)
                assertEquals(1, service.listSchedules().schedules.size)
                assertThrows(WeatherToolException::class.java) { service.create("City", 0, 60) }
                assertThrows(WeatherToolException::class.java) { service.create("City", 10, 4) }
                assertThrows(WeatherToolException::class.java) { service.create("City", 60, 30) }
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
}
