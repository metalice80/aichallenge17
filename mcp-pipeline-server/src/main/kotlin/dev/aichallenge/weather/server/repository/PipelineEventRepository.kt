package dev.aichallenge.weather.server.repository

import dev.aichallenge.weather.server.pipeline.EventType
import dev.aichallenge.weather.server.pipeline.PipelineEvent
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
class PipelineEventRepository(private val jdbc: JdbcTemplate) {
    private val mapper = RowMapper<PipelineEvent> { rs, _ ->
        PipelineEvent(
            rs.getString("id"), rs.getString("run_id"), rs.getString("step_id"), rs.getLong("sequence_number"),
            EventType.valueOf(rs.getString("event_type")), Instant.parse(rs.getString("occurred_at")), rs.getString("payload_json"),
        )
    }

    fun append(runId: String, stepId: String?, type: EventType, now: Instant, payloadJson: String): PipelineEvent {
        val next = jdbc.queryForObject(
            "SELECT COALESCE(MAX(sequence_number),0)+1 FROM pipeline_event WHERE run_id=?",
            Long::class.java, runId,
        ) ?: 1L
        val event = PipelineEvent("event-${UUID.randomUUID()}", runId, stepId, next, type, now, payloadJson)
        jdbc.update(
            "INSERT INTO pipeline_event(id,run_id,step_id,sequence_number,event_type,occurred_at,payload_json) VALUES (?,?,?,?,?,?,?)",
            event.id, event.runId, event.stepId, event.sequenceNumber, event.eventType.name, event.occurredAt.toString(), event.payloadJson,
        )
        return event
    }

    fun list(runId: String, after: Long, limit: Int): List<PipelineEvent> = jdbc.query(
        """SELECT * FROM pipeline_event WHERE run_id=? AND sequence_number>? ORDER BY sequence_number LIMIT ?""",
        mapper, runId, after, limit,
    )
}
