package dev.aichallenge.day20.agent.repository

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Repository
class OrchestrationEventRepository(private val jdbc: JdbcClient, private val clock: Clock) {
    fun append(runId: String, type: String, server: String? = null, tool: String? = null, payloadJson: String = "{}") : OrchestrationEvent {
        val sequence = jdbc.sql("SELECT COALESCE(MAX(sequence_number),0)+1 FROM orchestration_event WHERE run_id=?").param(runId).query(Int::class.java).single()
        val event = OrchestrationEvent(UUID.randomUUID().toString(), runId, sequence, type, server, tool, clock.instant(), payloadJson)
        jdbc.sql("INSERT INTO orchestration_event(id,run_id,sequence_number,event_type,server_name,tool_name,occurred_at,payload_json) VALUES(?,?,?,?,?,?,?,?)")
            .params(listOf(event.id, runId, sequence, type, server, tool, event.occurredAt.toString(), payloadJson)).update()
        return event
    }

    fun list(runId: String, after: Int = 0, limit: Int = 100): List<OrchestrationEvent> = jdbc.sql("SELECT * FROM orchestration_event WHERE run_id=? AND sequence_number>? ORDER BY sequence_number LIMIT ?")
        .params(runId, after, limit.coerceIn(1, 500)).query(::map).list()

    private fun map(rs: ResultSet, row: Int) = OrchestrationEvent(
        rs.getString("id"), rs.getString("run_id"), rs.getInt("sequence_number"), rs.getString("event_type"), rs.getString("server_name"), rs.getString("tool_name"), Instant.parse(rs.getString("occurred_at")), rs.getString("payload_json")
    )
}
