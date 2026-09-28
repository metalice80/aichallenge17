package dev.aichallenge.weather.server.repository

import dev.aichallenge.weather.server.pipeline.PipelineRun
import dev.aichallenge.weather.server.pipeline.RunStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
class PipelineRunRepository(private val jdbc: JdbcTemplate) {
    private val mapper = RowMapper<PipelineRun> { rs, _ ->
        PipelineRun(
            rs.getString("id"), rs.getString("request_text"), RunStatus.valueOf(rs.getString("status")),
            rs.getString("current_step"), rs.getString("model_id"), Instant.parse(rs.getString("created_at")),
            rs.getString("started_at")?.let(Instant::parse), Instant.parse(rs.getString("updated_at")),
            rs.getString("finished_at")?.let(Instant::parse), rs.getString("result_file"),
            rs.getString("error_code"), rs.getString("error_message"), rs.getLong("version"),
        )
    }

    fun insert(run: PipelineRun) {
        jdbc.update(
            """INSERT INTO pipeline_run(id,request_text,status,current_step,model_id,created_at,started_at,updated_at,finished_at,result_file,error_code,error_message,version)
               VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            run.id, run.requestText, run.status.name, run.currentStep, run.modelId, run.createdAt.toString(),
            run.startedAt?.toString(), run.updatedAt.toString(), run.finishedAt?.toString(), run.resultFile,
            run.errorCode, run.errorMessage, run.version,
        )
    }

    fun find(id: String): PipelineRun? = jdbc.query(
        "SELECT * FROM pipeline_run WHERE id=?", mapper, id,
    ).firstOrNull()

    fun markRunning(id: String, expectedVersion: Long, now: Instant): Boolean = jdbc.update(
        """UPDATE pipeline_run SET status='RUNNING',started_at=?,updated_at=?,version=version+1
           WHERE id=? AND status='CREATED' AND version=?""",
        now.toString(), now.toString(), id, expectedVersion,
    ) == 1

    fun markStepStarted(id: String, expectedVersion: Long, step: String, now: Instant): Boolean = jdbc.update(
        """UPDATE pipeline_run SET current_step=?,updated_at=?,version=version+1
           WHERE id=? AND status='RUNNING' AND version=?""",
        step, now.toString(), id, expectedVersion,
    ) == 1

    fun markCompleted(id: String, expectedVersion: Long, resultFile: String, now: Instant): Boolean = jdbc.update(
        """UPDATE pipeline_run SET status='COMPLETED',current_step=NULL,result_file=?,updated_at=?,finished_at=?,version=version+1
           WHERE id=? AND status='RUNNING' AND version=?""",
        resultFile, now.toString(), now.toString(), id, expectedVersion,
    ) == 1

    fun markFailed(id: String, code: String, message: String, now: Instant): Int = jdbc.update(
        """UPDATE pipeline_run SET status='FAILED',error_code=?,error_message=?,updated_at=?,finished_at=?,version=version+1
           WHERE id=? AND status IN ('CREATED','RUNNING')""",
        code, message, now.toString(), now.toString(), id,
    )

    fun findStale(cutoff: Instant): List<PipelineRun> = jdbc.query(
        "SELECT * FROM pipeline_run WHERE status IN ('CREATED','RUNNING') AND updated_at < ? ORDER BY updated_at",
        mapper, cutoff.toString(),
    )
}
