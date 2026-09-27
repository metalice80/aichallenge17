package com.example.mcpweather

import com.example.mcpweather.config.OpenMeteoProperties
import com.example.mcpweather.service.OpenMeteoClient
import com.example.mcpweather.service.OpenMeteoException
import com.example.mcpweather.service.WeatherService
import com.example.mcpweather.service.WeatherToolException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.KotlinModule
import java.time.Duration

class WeatherServiceTest {
    @Test
    fun `maps live API shape to normalized result and encodes city`() {
        TestWeatherApiStub().use { stub ->
            val service = service(stub)

            val result = service.getCurrentWeather("  Санкт-Петербург & область  ")

            assertAll(
                { assertEquals("Тестоград", result.city) },
                { assertEquals("Тестландия", result.country) },
                { assertEquals(55.03, result.latitude) },
                { assertEquals(82.92, result.longitude) },
                { assertEquals(8.4, result.temperatureCelsius) },
                { assertEquals(6.1, result.apparentTemperatureCelsius) },
                { assertEquals(14.2, result.windSpeedKmh) },
                { assertEquals(3, result.weatherCode) },
                { assertEquals("2026-09-28T12:00", result.observedAt) },
            )
        }
    }

    @Test
    fun `reports unknown city`() {
        TestWeatherApiStub().use { stub ->
            val error = assertThrows(WeatherToolException::class.java) {
                service(stub).getCurrentWeather("Unknown")
            }
            assertEquals("City not found: Unknown", error.message)
        }
    }

    @Test
    fun `rejects invalid city input before HTTP`() {
        TestWeatherApiStub().use { stub ->
            val service = service(stub)
            val inputs = listOf(
                "   ",
                "x".repeat(121),
                "City\u0000",
                "https://example.com",
                "custom://example.com",
                "www.example.com",
            )

            inputs.forEach { input ->
                assertThrows(WeatherToolException::class.java) {
                    service.getCurrentWeather(input)
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [404, 503])
    fun `reports upstream HTTP error`(status: Int) {
        TestWeatherApiStub().use { stub ->
            stub.geocodingStatus = status
            val error = assertThrows(OpenMeteoException::class.java) {
                service(stub).getCurrentWeather("Тестоград")
            }
            assertEquals("Open-Meteo geocoding request failed with HTTP $status", error.message)
        }
    }

    @Test
    fun `reports malformed upstream JSON`() {
        TestWeatherApiStub().use { stub ->
            stub.geocodingBody = "not-json"
            val error = assertThrows(OpenMeteoException::class.java) {
                service(stub).getCurrentWeather("Тестоград")
            }
            assertEquals("Open-Meteo geocoding returned invalid JSON", error.message)
        }
    }

    @Test
    fun `reports upstream timeout`() {
        TestWeatherApiStub().use { stub ->
            stub.responseDelayMillis = 500
            val error = assertThrows(OpenMeteoException::class.java) {
                service(stub, Duration.ofMillis(100)).getCurrentWeather("Тестоград")
            }
            assertEquals("Open-Meteo geocoding request timed out", error.message)
        }
    }

    private fun service(
        stub: TestWeatherApiStub,
        requestTimeout: Duration = Duration.ofSeconds(2),
    ): WeatherService {
        val properties = OpenMeteoProperties(
            geocodingUrl = stub.geocodingUri,
            forecastUrl = stub.forecastUri,
            connectTimeout = Duration.ofSeconds(1),
            requestTimeout = requestTimeout,
        )
        val objectMapper = JsonMapper.builder()
            .addModule(KotlinModule.Builder().build())
            .build()
        return WeatherService(OpenMeteoClient(properties, objectMapper))
    }
}
