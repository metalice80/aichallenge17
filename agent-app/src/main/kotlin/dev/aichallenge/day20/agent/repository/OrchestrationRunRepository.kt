package dev.aichallenge.day20.agent.repository

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Clock
import java.time.Instant

@Repository
class OrchestrationRunRepository(private val jdbc: JdbcClient, private val clock: Clock) {
    fun create(id: String, request: String, model: String) {
        val now = clock.instant().toString()
        jdbc.sql("INSERT INTO orchestration_run(id, request_text, status, model_id, created_at, updated_at) VALUES (?, ?, 'CREATED', ?, ?, ?)")
            .params(id, request, model, now, now).update()
    }

    fun find(id: String): OrchestrationRun? = jdbc.sql("SELECT * FROM orchestration_run WHERE id = ?").param(id).query(::map).optional().orElse(null)

    fun markRunning(id: String) {
        val now = clock.instant().toString()
        jdbc.sql("UPDATE orchestration_run SET status='RUNNING', started_at=?, updated_at=?, version=version+1 WHERE id=? AND status='CREATED'").params(now, now, id).update()
    }

    fun complete(id: String, answer: String, reportPath: String) {
        val now = clock.instant().toString()
        jdbc.sql("UPDATE orchestration_run SET status='COMPLETED', final_answer=?, report_path=?, updated_at=?, finished_at=?, version=version+1 WHERE id=? AND status='RUNNING'")
            .params(answer, reportPath, now, now, id).update()
    }

    fun fail(id: String, code: String, message: String) {
        val now = clock.instant().toString()
        jdbc.sql("UPDATE orchestration_run SET status='FAILED', error_code=?, error_message=?, updated_at=?, finished_at=?, version=version+1 WHERE id=? AND status IN ('CREATED','RUNNING')")
            .params(code, message.take(1000), now, now, id).update()
    }

    fun isActive(id: String): Boolean = jdbc.sql("SELECT COUNT(*) FROM orchestration_run WHERE id=? AND status IN ('CREATED','RUNNING')")
        .param(id).query(Int::class.java).single() > 0

    fun stale(cutoff: Instant): List<String> = jdbc.sql("SELECT id FROM orchestration_run WHERE status IN ('CREATED','RUNNING') AND updated_at < ?")
        .param(cutoff.toString()).query(String::class.java).list().filterNotNull()

    private fun map(rs: ResultSet, row: Int) = OrchestrationRun(
        id = rs.getString("id"), requestText = rs.getString("request_text"), status = RunStatus.valueOf(rs.getString("status")),
        modelId = rs.getString("model_id"), createdAt = Instant.parse(rs.getString("created_at")), startedAt = rs.getString("started_at")?.let(Instant::parse),
        updatedAt = Instant.parse(rs.getString("updated_at")), finishedAt = rs.getString("finished_at")?.let(Instant::parse),
        finalAnswer = rs.getString("final_answer"), reportPath = rs.getString("report_path"), errorCode = rs.getString("error_code"),
        errorMessage = rs.getString("error_message"), version = rs.getInt("version"),
    )
}
