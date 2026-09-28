package dev.aichallenge.weather.server.repository

import dev.aichallenge.weather.server.pipeline.PipelineStep
import dev.aichallenge.weather.server.pipeline.StepStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
class PipelineStepRepository(private val jdbc: JdbcTemplate) {
    private val mapper = RowMapper<PipelineStep> { rs, _ ->
        PipelineStep(
            rs.getString("id"), rs.getString("run_id"), rs.getInt("sequence_number"), rs.getString("tool_name"),
            StepStatus.valueOf(rs.getString("status")), rs.getString("input_artifact_id"), rs.getString("output_artifact_id"),
            rs.getString("request_fingerprint"), rs.getString("started_at")?.let(Instant::parse),
            rs.getString("finished_at")?.let(Instant::parse), rs.getObject("duration_ms")?.let { rs.getLong("duration_ms") },
            rs.getInt("attempt"), rs.getString("error_code"), rs.getString("error_message"),
        )
    }

    fun insert(step: PipelineStep) {
        jdbc.update(
            """INSERT INTO pipeline_step(id,run_id,sequence_number,tool_name,status,input_artifact_id,output_artifact_id,request_fingerprint,started_at,finished_at,duration_ms,attempt,error_code,error_message)
               VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            step.id, step.runId, step.sequenceNumber, step.toolName, step.status.name, step.inputArtifactId,
            step.outputArtifactId, step.requestFingerprint, step.startedAt?.toString(), step.finishedAt?.toString(),
            step.durationMs, step.attempt, step.errorCode, step.errorMessage,
        )
    }

    fun find(runId: String, sequence: Int): PipelineStep? = jdbc.query(
        "SELECT * FROM pipeline_step WHERE run_id=? AND sequence_number=?", mapper, runId, sequence,
    ).firstOrNull()

    fun findByRun(runId: String): List<PipelineStep> = jdbc.query(
        "SELECT * FROM pipeline_step WHERE run_id=? ORDER BY sequence_number", mapper, runId,
    )

    fun markRunning(id: String, fingerprint: String, inputArtifactId: String?, now: Instant): Boolean = jdbc.update(
        """UPDATE pipeline_step SET status='RUNNING',request_fingerprint=?,input_artifact_id=?,started_at=?,finished_at=NULL,duration_ms=NULL,error_code=NULL,error_message=NULL
           WHERE id=? AND status='PENDING'""",
        fingerprint, inputArtifactId, now.toString(), id,
    ) == 1

    fun markSucceeded(id: String, outputArtifactId: String, now: Instant, durationMs: Long) = jdbc.update(
        "UPDATE pipeline_step SET status='SUCCEEDED',output_artifact_id=?,finished_at=?,duration_ms=? WHERE id=? AND status='RUNNING'",
        outputArtifactId, now.toString(), durationMs, id,
    )

    fun markFailed(id: String, code: String, message: String, now: Instant, durationMs: Long?) = jdbc.update(
        """UPDATE pipeline_step SET status='FAILED',finished_at=?,duration_ms=?,error_code=?,error_message=?
           WHERE id=? AND status IN ('PENDING','RUNNING')""",
        now.toString(), durationMs, code, message, id,
    )

    fun markActiveFailed(runId: String, code: String, message: String, now: Instant) = jdbc.update(
        """UPDATE pipeline_step SET status='FAILED',finished_at=?,error_code=?,error_message=?
           WHERE run_id=? AND status='RUNNING'""",
        now.toString(), code, message, runId,
    )
}
