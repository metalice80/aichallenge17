package com.example.mcpweather.repository

import com.example.mcpweather.model.DeliveryStatus
import com.example.mcpweather.model.WeatherAggregate
import com.example.mcpweather.model.WeatherSummary
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant

@Repository
class WeatherSummaryRepository(
    private val jdbcClient: JdbcClient,
) {
    fun insertIfAbsent(
        id: String,
        scheduleId: String,
        periodStartedAt: Instant,
        periodEndedAt: Instant,
        aggregate: WeatherAggregate,
        createdAt: Instant,
    ): Boolean = jdbcClient.sql(
        """
        INSERT OR IGNORE INTO weather_summary (
            id, schedule_id, period_started_at, period_ended_at, sample_count,
            min_temperature_celsius, max_temperature_celsius, avg_temperature_celsius,
            max_wind_speed_kmh, latest_weather_code, delivery_status, delivery_attempts,
            claimed_by, claimed_at, rendered_text, last_delivery_error, created_at, delivered_at
        ) VALUES (
            :id, :scheduleId, :periodStartedAt, :periodEndedAt, :sampleCount,
            :minTemperature, :maxTemperature, :avgTemperature,
            :maxWindSpeed, :latestWeatherCode, 'PENDING', 0,
            NULL, NULL, NULL, NULL, :createdAt, NULL
        )
        """.trimIndent(),
    ).params(
        mapOf(
            "id" to id,
            "scheduleId" to scheduleId,
            "periodStartedAt" to periodStartedAt.toString(),
            "periodEndedAt" to periodEndedAt.toString(),
            "sampleCount" to aggregate.sampleCount,
            "minTemperature" to aggregate.minTemperatureCelsius,
            "maxTemperature" to aggregate.maxTemperatureCelsius,
            "avgTemperature" to aggregate.avgTemperatureCelsius,
            "maxWindSpeed" to aggregate.maxWindSpeedKmh,
            "latestWeatherCode" to aggregate.latestWeatherCode,
            "createdAt" to createdAt.toString(),
        ),
    ).update() == 1

    fun findById(id: String): WeatherSummary? = joinedQuery(
        "WHERE ws.id = :id",
    ).param("id", id).query(::mapSummary).optional().orElse(null)

    fun findLatestDelivered(scheduleId: String): WeatherSummary? = joinedQuery(
        """
        WHERE ws.schedule_id = :scheduleId AND ws.delivery_status = 'DELIVERED'
        ORDER BY ws.delivered_at DESC
        LIMIT 1
        """.trimIndent(),
    ).param("scheduleId", scheduleId).query(::mapSummary).optional().orElse(null)

    fun pendingCandidates(limit: Int): List<WeatherSummary> = joinedQuery(
        """
        WHERE ws.delivery_status = 'PENDING'
        ORDER BY ws.created_at, ws.id
        LIMIT :limit
        """.trimIndent(),
    ).param("limit", limit).query(::mapSummary).list()

    fun claim(id: String, workerId: String, claimedAt: Instant): Boolean = jdbcClient.sql(
        """
        UPDATE weather_summary
        SET delivery_status = 'PROCESSING',
            claimed_by = :workerId,
            claimed_at = :claimedAt,
            delivery_attempts = delivery_attempts + 1,
            last_delivery_error = NULL
        WHERE id = :id AND delivery_status = 'PENDING'
        """.trimIndent(),
    ).param("id", id).param("workerId", workerId).param("claimedAt", claimedAt.toString()).update() == 1

    fun complete(id: String, workerId: String, renderedText: String, deliveredAt: Instant): Boolean = jdbcClient.sql(
        """
        UPDATE weather_summary
        SET delivery_status = 'DELIVERED', rendered_text = :renderedText,
            delivered_at = :deliveredAt, claimed_by = NULL, claimed_at = NULL,
            last_delivery_error = NULL
        WHERE id = :id AND delivery_status = 'PROCESSING' AND claimed_by = :workerId
        """.trimIndent(),
    ).param("id", id)
        .param("workerId", workerId)
        .param("renderedText", renderedText)
        .param("deliveredAt", deliveredAt.toString())
        .update() == 1

    fun fail(id: String, workerId: String, error: String, terminal: Boolean): Boolean = jdbcClient.sql(
        """
        UPDATE weather_summary
        SET delivery_status = :status,
            claimed_by = NULL,
            claimed_at = NULL,
            last_delivery_error = :error
        WHERE id = :id AND delivery_status = 'PROCESSING' AND claimed_by = :workerId
        """.trimIndent(),
    ).param("id", id)
        .param("workerId", workerId)
        .param("status", if (terminal) DeliveryStatus.FAILED.name else DeliveryStatus.PENDING.name)
        .param("error", error)
        .update() == 1

    fun recoverStuck(olderThan: Instant): Int = jdbcClient.sql(
        """
        UPDATE weather_summary
        SET delivery_status = 'PENDING', claimed_by = NULL, claimed_at = NULL,
            last_delivery_error = 'Delivery claim expired and was recovered'
        WHERE delivery_status = 'PROCESSING' AND claimed_at < :olderThan
        """.trimIndent(),
    ).param("olderThan", olderThan.toString()).update()

    fun listDelivered(after: Instant?, limit: Int): List<WeatherSummary> {
        val condition = if (after == null) {
            "WHERE ws.delivery_status = 'DELIVERED'"
        } else {
            "WHERE ws.delivery_status = 'DELIVERED' AND ws.delivered_at > :after"
        }
        var query = joinedQuery(
            "$condition ORDER BY ws.delivered_at ASC, ws.id ASC LIMIT :limit",
        ).param("limit", limit)
        if (after != null) {
            query = query.param("after", after.toString())
        }
        return query.query(::mapSummary).list()
    }

    private fun joinedQuery(suffix: String): JdbcClient.StatementSpec = jdbcClient.sql(
        """
        SELECT ws.*, s.city AS schedule_city
        FROM weather_summary ws
        JOIN weather_schedule s ON s.id = ws.schedule_id
        $suffix
        """.trimIndent(),
    )

    private fun mapSummary(rs: ResultSet, rowNumber: Int): WeatherSummary = WeatherSummary(
        id = rs.getString("id"),
        scheduleId = rs.getString("schedule_id"),
        city = rs.getString("schedule_city"),
        periodStartedAt = Instant.parse(rs.getString("period_started_at")),
        periodEndedAt = Instant.parse(rs.getString("period_ended_at")),
        sampleCount = rs.getInt("sample_count"),
        minTemperatureCelsius = rs.getDouble("min_temperature_celsius"),
        maxTemperatureCelsius = rs.getDouble("max_temperature_celsius"),
        avgTemperatureCelsius = rs.getDouble("avg_temperature_celsius"),
        maxWindSpeedKmh = rs.getDouble("max_wind_speed_kmh"),
        latestWeatherCode = rs.getInt("latest_weather_code"),
        deliveryStatus = DeliveryStatus.valueOf(rs.getString("delivery_status")),
        deliveryAttempts = rs.getInt("delivery_attempts"),
        claimedBy = rs.getString("claimed_by"),
        claimedAt = rs.getString("claimed_at")?.let(Instant::parse),
        renderedText = rs.getString("rendered_text"),
        lastDeliveryError = rs.getString("last_delivery_error"),
        createdAt = Instant.parse(rs.getString("created_at")),
        deliveredAt = rs.getString("delivered_at")?.let(Instant::parse),
    )
}
