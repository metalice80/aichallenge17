package dev.aichallenge.weather.server.repository

import dev.aichallenge.weather.server.McpPipelineServerApplication
import dev.aichallenge.weather.server.pipeline.PipelineStore
import dev.aichallenge.weather.server.pipeline.EventType
import dev.aichallenge.weather.server.pipeline.PipelineRecovery
import dev.aichallenge.weather.server.pipeline.RunStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.nio.file.Files

@SpringBootTest(
    classes = [McpPipelineServerApplication::class],
    properties = ["spring.ai.mcp.server.enabled=false", "spring.main.web-application-type=none"],
)
class PipelineRepositoryTest @Autowired constructor(
    private val store: PipelineStore,
    private val events: PipelineEventRepository,
    private val recovery: PipelineRecovery,
    private val jdbc: JdbcTemplate,
) {
    @Test
    fun `creates run steps and append-only ordered events transactionally`() {
        val created = store.createRun("weather request", "test-model")
        assertEquals(RunStatus.CREATED, created.run.status)
        assertEquals(3, created.steps.size)
        store.markAgentStarted(created.run.id)
        val listed = events.list(created.run.id, 0, 100)
        assertEquals(listOf(1L, 2L, 3L), listed.map { it.sequenceNumber })
        assertTrue(listed.zipWithNext().all { (a, b) -> a.sequenceNumber < b.sequenceNumber })
    }

    @Test
    fun `foreign keys are enforced and stale running run is recovered`() {
        val created = store.createRun("interrupted request", "test-model")
        store.markAgentStarted(created.run.id)
        jdbc.update("UPDATE pipeline_run SET updated_at='2000-01-01T00:00:00Z' WHERE id=?", created.run.id)
        recovery.recover()
        val recovered = store.getRun(created.run.id)
        assertEquals(RunStatus.FAILED, recovered.run.status)
        assertEquals("PROCESS_INTERRUPTED", recovered.run.errorCode)
        assertTrue(events.list(created.run.id, 0, 100).any { it.eventType == EventType.RUN_RECOVERED_AS_FAILED })
        org.junit.jupiter.api.assertThrows<DataAccessException> {
            jdbc.update(
                "INSERT INTO pipeline_step(id,run_id,sequence_number,tool_name,status,attempt) VALUES ('bad','missing',1,'x','PENDING',1)",
            )
        }
    }

    companion object {
        private val directory = Files.createTempDirectory("pipeline-repository-test")
        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { "jdbc:sqlite:${directory.resolve("pipeline.db")}" }
            registry.add("app.reports.directory") { directory.resolve("reports").toString() }
        }
    }
}
