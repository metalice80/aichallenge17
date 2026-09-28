package dev.aichallenge.weather.server.repository

import dev.aichallenge.weather.server.pipeline.ArtifactType
import dev.aichallenge.weather.server.pipeline.PipelineArtifact
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
class PipelineArtifactRepository(private val jdbc: JdbcTemplate) {
    private val mapper = RowMapper<PipelineArtifact> { rs, _ ->
        PipelineArtifact(
            rs.getString("id"), rs.getString("run_id"), ArtifactType.valueOf(rs.getString("type")),
            rs.getString("source_artifact_id"), rs.getString("media_type"), rs.getString("content"),
            rs.getString("sha256"), Instant.parse(rs.getString("created_at")),
        )
    }

    fun insert(artifact: PipelineArtifact) {
        jdbc.update(
            """INSERT INTO pipeline_artifact(id,run_id,type,source_artifact_id,media_type,content,sha256,created_at)
               VALUES (?,?,?,?,?,?,?,?)""",
            artifact.id, artifact.runId, artifact.type.name, artifact.sourceArtifactId, artifact.mediaType,
            artifact.content, artifact.sha256, artifact.createdAt.toString(),
        )
    }

    fun find(id: String): PipelineArtifact? = jdbc.query(
        "SELECT * FROM pipeline_artifact WHERE id=?", mapper, id,
    ).firstOrNull()

    fun findByRun(runId: String): List<PipelineArtifact> = jdbc.query(
        "SELECT * FROM pipeline_artifact WHERE run_id=? ORDER BY created_at,id", mapper, runId,
    )
}
