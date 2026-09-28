package dev.aichallenge.weather.agent.web

import com.fasterxml.jackson.databind.ObjectMapper
import dev.aichallenge.weather.agent.pipeline.CreatePipelineResponse
import dev.aichallenge.weather.agent.pipeline.McpEvent
import dev.aichallenge.weather.agent.pipeline.McpEventList
import dev.aichallenge.weather.agent.pipeline.PipelineEventStreamService
import dev.aichallenge.weather.agent.pipeline.PipelineExecutionService
import dev.aichallenge.weather.agent.pipeline.PipelineNotFoundException
import dev.aichallenge.weather.agent.pipeline.PipelineQueryService
import dev.aichallenge.weather.agent.pipeline.PipelineView
import org.junit.jupiter.api.BeforeEach
import dev.aichallenge.weather.agent.pipeline.PipelineSubmissionException
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Instant

class PipelineControllerTest {
    private lateinit var execution: PipelineExecutionService
    private lateinit var queries: PipelineQueryService
    private lateinit var streams: PipelineEventStreamService
    private lateinit var mvc: MockMvc

    @BeforeEach
    fun setup() {
        execution = mock(PipelineExecutionService::class.java)
        queries = mock(PipelineQueryService::class.java)
        streams = mock(PipelineEventStreamService::class.java)
        mvc = MockMvcBuilders.standaloneSetup(PipelineController(execution, queries, streams))
            .setControllerAdvice(ApiExceptionHandler()).build()
    }

    @Test
    fun `post returns 202 and generated links`() {
        `when`(execution.submit(anyString())).thenReturn(CreatePipelineResponse("run-1", "CREATED", "/api/pipelines/run-1", "/api/pipelines/run-1/events/stream"))
        mvc.perform(post("/api/pipelines").contentType(MediaType.APPLICATION_JSON).content("""{"message":"weather"}"""))
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.runId").value("run-1"))
            .andExpect(jsonPath("$.eventsUrl").value("/api/pipelines/run-1/events/stream"))
    }

    @Test
    fun `blank and oversized messages return 400`() {
        mvc.perform(post("/api/pipelines").contentType(MediaType.APPLICATION_JSON).content("""{"message":""}"""))
            .andExpect(status().isBadRequest)
        val body = ObjectMapper().writeValueAsString(mapOf("message" to "x".repeat(2001)))
        mvc.perform(post("/api/pipelines").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `executor rejection returns safe 503 with run id`() {
        `when`(execution.submit(anyString())).thenThrow(PipelineSubmissionException("run-rejected", "Pipeline executor queue is full"))
        mvc.perform(post("/api/pipelines").contentType(MediaType.APPLICATION_JSON).content("""{"message":"weather"}"""))
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.code").value("EXECUTOR_REJECTED"))
            .andExpect(jsonPath("$.runId").value("run-rejected"))
    }

    @Test
    fun `unknown run returns 404 and events stay ordered`() {
        `when`(queries.get("missing")).thenThrow(PipelineNotFoundException("missing"))
        mvc.perform(get("/api/pipelines/missing")).andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("PIPELINE_NOT_FOUND"))
        val now = Instant.parse("2026-09-28T00:00:00Z")
        `when`(queries.events("run-1", 0, 100)).thenReturn(McpEventList("run-1", listOf(
            McpEvent("e1", "run-1", null, 1, "RUN_CREATED", now, "{}"),
            McpEvent("e2", "run-1", null, 2, "RUN_STARTED", now, "{}"),
        )))
        mvc.perform(get("/api/pipelines/run-1/events"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.events[0].sequenceNumber").value(1))
            .andExpect(jsonPath("$.events[1].sequenceNumber").value(2))
    }
}
