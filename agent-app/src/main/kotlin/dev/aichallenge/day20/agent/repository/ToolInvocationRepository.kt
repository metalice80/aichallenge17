package dev.aichallenge.day20.agent.repository

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Repository
class ToolInvocationRepository(private val jdbc: JdbcClient, private val clock: Clock) {
    fun start(runId: String, server: String, tool: String, toolCallId: String?, inputHash: String, inputRefsJson: String): ToolInvocation {
        val sequence = jdbc.sql("SELECT COALESCE(MAX(sequence_number),0)+1 FROM tool_invocation WHERE run_id=?").param(runId).query(Int::class.java).single()
        val id = UUID.randomUUID().toString()
        val now = clock.instant()
        jdbc.sql("INSERT INTO tool_invocation(id,run_id,sequence_number,server_name,tool_name,tool_call_id,status,started_at,input_hash,input_refs_json) VALUES(?,?,?,?,?,?,'RUNNING',?,?,?)")
            .params(listOf(id, runId, sequence, server, tool, toolCallId, now.toString(), inputHash, inputRefsJson)).update()
        return ToolInvocation(id, runId, sequence, server, tool, toolCallId, InvocationStatus.RUNNING, now, null, null, inputHash, null, inputRefsJson, null, null, null)
    }

    fun finish(id: String, status: InvocationStatus, outputHash: String?, outputRefsJson: String?, errorCode: String? = null, errorMessage: String? = null) {
        val now = clock.instant()
        jdbc.sql("UPDATE tool_invocation SET status=?, finished_at=?, duration_ms=CAST((julianday(?) - julianday(started_at))*86400000 AS INTEGER), output_hash=?, output_refs_json=?, error_code=?, error_message=? WHERE id=?")
            .params(listOf(status.name, now.toString(), now.toString(), outputHash, outputRefsJson, errorCode, errorMessage?.take(1000), id)).update()
    }

    fun list(runId: String): List<ToolInvocation> = jdbc.sql("SELECT * FROM tool_invocation WHERE run_id=? ORDER BY sequence_number").param(runId).query(::map).list()
    fun count(runId: String): Int = jdbc.sql("SELECT COUNT(*) FROM tool_invocation WHERE run_id=?").param(runId).query(Int::class.java).single()

    private fun map(rs: ResultSet, row: Int) = ToolInvocation(
        id=rs.getString("id"), runId=rs.getString("run_id"), sequenceNumber=rs.getInt("sequence_number"), serverName=rs.getString("server_name"),
        toolName=rs.getString("tool_name"), toolCallId=rs.getString("tool_call_id"), status=InvocationStatus.valueOf(rs.getString("status")),
        startedAt=Instant.parse(rs.getString("started_at")), finishedAt=rs.getString("finished_at")?.let(Instant::parse), durationMs=rs.getObject("duration_ms")?.let { rs.getLong("duration_ms") },
        inputHash=rs.getString("input_hash"), outputHash=rs.getString("output_hash"), inputRefsJson=rs.getString("input_refs_json"), outputRefsJson=rs.getString("output_refs_json"),
        errorCode=rs.getString("error_code"), errorMessage=rs.getString("error_message"),
    )
}
