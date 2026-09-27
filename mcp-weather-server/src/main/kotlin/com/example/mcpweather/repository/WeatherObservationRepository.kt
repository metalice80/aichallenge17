package com.example.mcpweather.repository

import com.example.mcpweather.model.WeatherAggregate
import com.example.mcpweather.model.WeatherObservation
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
class WeatherObservationRepository(
    private val jdbcClient: JdbcClient,
) {
    fun insertIfAbsent(observation: WeatherObservation): Boolean = jdbcClient.sql(
        """
        INSERT OR IGNORE INTO weather_observation (
            id, schedule_id, observed_at, temperature_celsius,
            apparent_temperature_celsius, wind_speed_kmh, weather_code, created_at
        ) VALUES (
            :id, :scheduleId, :observedAt, :temperatureCelsius,
            :apparentTemperatureCelsius, :windSpeedKmh, :weatherCode, :createdAt
        )
        """.trimIndent(),
    ).params(
        mapOf(
            "id" to observation.id,
            "scheduleId" to observation.scheduleId,
            "observedAt" to observation.observedAt.toString(),
            "temperatureCelsius" to observation.temperatureCelsius,
            "apparentTemperatureCelsius" to observation.apparentTemperatureCelsius,
            "windSpeedKmh" to observation.windSpeedKmh,
            "weatherCode" to observation.weatherCode,
            "createdAt" to observation.createdAt.toString(),
        ),
    ).update() == 1

    fun aggregate(scheduleId: String, periodStartedAt: Instant, periodEndedAt: Instant): WeatherAggregate? {
        val values = jdbcClient.sql(
            """
            SELECT COUNT(*) AS sample_count,
                   MIN(temperature_celsius) AS min_temperature,
                   MAX(temperature_celsius) AS max_temperature,
                   AVG(temperature_celsius) AS avg_temperature,
                   MAX(wind_speed_kmh) AS max_wind_speed
            FROM weather_observation
            WHERE schedule_id = :scheduleId
              AND observed_at >= :periodStartedAt
              AND observed_at < :periodEndedAt
              AND julianday(observed_at) <= julianday(created_at) + (1.0 / 1440.0)
            """.trimIndent(),
        ).param("scheduleId", scheduleId)
            .param("periodStartedAt", periodStartedAt.toString())
            .param("periodEndedAt", periodEndedAt.toString())
            .query { rs, _ ->
                doubleArrayOf(
                    rs.getDouble("sample_count"),
                    rs.getDouble("min_temperature"),
                    rs.getDouble("max_temperature"),
                    rs.getDouble("avg_temperature"),
                    rs.getDouble("max_wind_speed"),
                )
            }.single()
        if (values[0].toInt() == 0) return null

        val latestCode = jdbcClient.sql(
            """
            SELECT weather_code FROM weather_observation
            WHERE schedule_id = :scheduleId
              AND observed_at >= :periodStartedAt
              AND observed_at < :periodEndedAt
              AND julianday(observed_at) <= julianday(created_at) + (1.0 / 1440.0)
            ORDER BY observed_at DESC
            LIMIT 1
            """.trimIndent(),
        ).param("scheduleId", scheduleId)
            .param("periodStartedAt", periodStartedAt.toString())
            .param("periodEndedAt", periodEndedAt.toString())
            .query(Int::class.java)
            .single()

        return WeatherAggregate(
            sampleCount = values[0].toInt(),
            minTemperatureCelsius = values[1],
            maxTemperatureCelsius = values[2],
            avgTemperatureCelsius = values[3],
            maxWindSpeedKmh = values[4],
            latestWeatherCode = latestCode,
        )
    }

    fun count(scheduleId: String): Long = jdbcClient.sql(
        "SELECT COUNT(*) FROM weather_observation WHERE schedule_id = :scheduleId",
    ).param("scheduleId", scheduleId).query(Long::class.java).single()
}
