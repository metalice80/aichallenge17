package com.example.mcpweather.repository

import com.example.mcpweather.model.ScheduleStatus
import com.example.mcpweather.model.WeatherSchedule
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant

@Repository
class WeatherScheduleRepository(
    private val jdbcClient: JdbcClient,
) {
    fun insert(schedule: WeatherSchedule): Int = jdbcClient.sql(
        """
        INSERT INTO weather_schedule (
            id, city, normalized_city, collection_interval_minutes, summary_interval_minutes,
            status, next_collection_at, next_summary_at, last_collection_at, lease_owner,
            lease_until, consecutive_failures, last_error, created_at, updated_at
        ) VALUES (
            :id, :city, :normalizedCity, :collectionIntervalMinutes, :summaryIntervalMinutes,
            :status, :nextCollectionAt, :nextSummaryAt, :lastCollectionAt, :leaseOwner,
            :leaseUntil, :consecutiveFailures, :lastError, :createdAt, :updatedAt
        )
        """.trimIndent(),
    ).params(scheduleParameters(schedule)).update()

    fun findById(id: String): WeatherSchedule? = jdbcClient.sql(
        "SELECT * FROM weather_schedule WHERE id = :id",
    ).param("id", id).query(::mapSchedule).optional().orElse(null)

    fun findActiveDuplicate(
        normalizedCity: String,
        collectionIntervalMinutes: Int,
        summaryIntervalMinutes: Int,
    ): WeatherSchedule? = jdbcClient.sql(
        """
        SELECT * FROM weather_schedule
        WHERE normalized_city = :normalizedCity
          AND collection_interval_minutes = :collectionIntervalMinutes
          AND summary_interval_minutes = :summaryIntervalMinutes
          AND status = 'ACTIVE'
        LIMIT 1
        """.trimIndent(),
    ).params(
        mapOf(
            "normalizedCity" to normalizedCity,
            "collectionIntervalMinutes" to collectionIntervalMinutes,
            "summaryIntervalMinutes" to summaryIntervalMinutes,
        ),
    ).query(::mapSchedule).optional().orElse(null)

    fun findDue(now: Instant, limit: Int): List<WeatherSchedule> = jdbcClient.sql(
        """
        SELECT * FROM weather_schedule
        WHERE status = 'ACTIVE'
          AND next_collection_at <= :now
          AND (lease_until IS NULL OR lease_until <= :now)
        ORDER BY next_collection_at, created_at
        LIMIT :limit
        """.trimIndent(),
    ).param("now", now.toString()).param("limit", limit).query(::mapSchedule).list()

    fun acquireLease(id: String, owner: String, now: Instant, leaseUntil: Instant): Boolean =
        jdbcClient.sql(
            """
            UPDATE weather_schedule
            SET lease_owner = :owner, lease_until = :leaseUntil, updated_at = :now
            WHERE id = :id
              AND status = 'ACTIVE'
              AND next_collection_at <= :now
              AND (lease_until IS NULL OR lease_until <= :now)
            """.trimIndent(),
        ).params(
            mapOf(
                "id" to id,
                "owner" to owner,
                "leaseUntil" to leaseUntil.toString(),
                "now" to now.toString(),
            ),
        ).update() == 1

    fun completeCollection(
        id: String,
        owner: String,
        observedAt: Instant,
        nextCollectionAt: Instant,
        nextSummaryAt: Instant,
        updatedAt: Instant,
    ): Boolean = jdbcClient.sql(
        """
        UPDATE weather_schedule
        SET last_collection_at = :observedAt,
            next_collection_at = :nextCollectionAt,
            next_summary_at = :nextSummaryAt,
            lease_owner = NULL,
            lease_until = NULL,
            consecutive_failures = 0,
            last_error = NULL,
            updated_at = :updatedAt
        WHERE id = :id AND lease_owner = :owner AND status = 'ACTIVE'
        """.trimIndent(),
    ).params(
        mapOf(
            "id" to id,
            "owner" to owner,
            "observedAt" to observedAt.toString(),
            "nextCollectionAt" to nextCollectionAt.toString(),
            "nextSummaryAt" to nextSummaryAt.toString(),
            "updatedAt" to updatedAt.toString(),
        ),
    ).update() == 1

    fun failCollection(
        id: String,
        owner: String,
        nextCollectionAt: Instant,
        error: String,
        updatedAt: Instant,
    ): Boolean = jdbcClient.sql(
        """
        UPDATE weather_schedule
        SET next_collection_at = :nextCollectionAt,
            lease_owner = NULL,
            lease_until = NULL,
            consecutive_failures = consecutive_failures + 1,
            last_error = :error,
            updated_at = :updatedAt
        WHERE id = :id AND lease_owner = :owner AND status = 'ACTIVE'
        """.trimIndent(),
    ).params(
        mapOf(
            "id" to id,
            "owner" to owner,
            "nextCollectionAt" to nextCollectionAt.toString(),
            "error" to error,
            "updatedAt" to updatedAt.toString(),
        ),
    ).update() == 1

    fun cancel(id: String, updatedAt: Instant): Int = jdbcClient.sql(
        """
        UPDATE weather_schedule
        SET status = 'CANCELLED', lease_owner = NULL, lease_until = NULL, updated_at = :updatedAt
        WHERE id = :id AND status <> 'CANCELLED'
        """.trimIndent(),
    ).param("id", id).param("updatedAt", updatedAt.toString()).update()

    fun list(limit: Int = 100): List<WeatherSchedule> = jdbcClient.sql(
        "SELECT * FROM weather_schedule ORDER BY updated_at DESC LIMIT :limit",
    ).param("limit", limit).query(::mapSchedule).list()

    fun observationCount(scheduleId: String): Long = jdbcClient.sql(
        "SELECT COUNT(*) FROM weather_observation WHERE schedule_id = :scheduleId",
    ).param("scheduleId", scheduleId).query(Long::class.java).single()

    private fun scheduleParameters(schedule: WeatherSchedule): Map<String, Any?> = mapOf(
        "id" to schedule.id,
        "city" to schedule.city,
        "normalizedCity" to schedule.normalizedCity,
        "collectionIntervalMinutes" to schedule.collectionIntervalMinutes,
        "summaryIntervalMinutes" to schedule.summaryIntervalMinutes,
        "status" to schedule.status.name,
        "nextCollectionAt" to schedule.nextCollectionAt.toString(),
        "nextSummaryAt" to schedule.nextSummaryAt.toString(),
        "lastCollectionAt" to schedule.lastCollectionAt?.toString(),
        "leaseOwner" to schedule.leaseOwner,
        "leaseUntil" to schedule.leaseUntil?.toString(),
        "consecutiveFailures" to schedule.consecutiveFailures,
        "lastError" to schedule.lastError,
        "createdAt" to schedule.createdAt.toString(),
        "updatedAt" to schedule.updatedAt.toString(),
    )

    private fun mapSchedule(rs: ResultSet, rowNumber: Int): WeatherSchedule = WeatherSchedule(
        id = rs.getString("id"),
        city = rs.getString("city"),
        normalizedCity = rs.getString("normalized_city"),
        collectionIntervalMinutes = rs.getInt("collection_interval_minutes"),
        summaryIntervalMinutes = rs.getInt("summary_interval_minutes"),
        status = ScheduleStatus.valueOf(rs.getString("status")),
        nextCollectionAt = Instant.parse(rs.getString("next_collection_at")),
        nextSummaryAt = Instant.parse(rs.getString("next_summary_at")),
        lastCollectionAt = rs.getString("last_collection_at")?.let(Instant::parse),
        leaseOwner = rs.getString("lease_owner"),
        leaseUntil = rs.getString("lease_until")?.let(Instant::parse),
        consecutiveFailures = rs.getInt("consecutive_failures"),
        lastError = rs.getString("last_error"),
        createdAt = Instant.parse(rs.getString("created_at")),
        updatedAt = Instant.parse(rs.getString("updated_at")),
    )
}
