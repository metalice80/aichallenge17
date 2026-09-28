package dev.aichallenge.weather.server.tool

import com.fasterxml.jackson.databind.ObjectMapper
import dev.aichallenge.weather.server.file.SafeReportWriter
import dev.aichallenge.weather.server.pipeline.ArtifactType
import dev.aichallenge.weather.server.pipeline.ErrorCode
import dev.aichallenge.weather.server.pipeline.Hashing
import dev.aichallenge.weather.server.pipeline.PipelineArtifact
import dev.aichallenge.weather.server.pipeline.PipelineException
import dev.aichallenge.weather.server.pipeline.PipelineObserver
import dev.aichallenge.weather.server.pipeline.PipelineStore
import dev.aichallenge.weather.server.pipeline.StepName
import dev.aichallenge.weather.server.summary.WeatherSummaryService
import dev.aichallenge.weather.server.weather.OpenMeteoClient
import dev.aichallenge.weather.server.weather.WeatherForecast
import org.springframework.ai.mcp.annotation.McpMeta
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.UUID

@Component
class WeatherPipelineTools(
    private val weatherClient: OpenMeteoClient,
    private val summaryService: WeatherSummaryService,
    private val reportWriter: SafeReportWriter,
    private val observer: PipelineObserver,
    private val store: PipelineStore,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) {
    @McpTool(
        name = "search_weather_forecast",
        description = "Find a city and obtain its normalized daily forecast. After success, pass artifactId unchanged to summarize_weather_forecast. Never copy or rewrite the forecast payload.",
        generateOutputSchema = true,
    )
    fun search(
        @McpToolParam(description = "City name, 1 to 120 characters", required = true) city: String,
        @McpToolParam(description = "Forecast length in days, integer from 1 to 7", required = true) days: Int,
        meta: McpMeta,
    ): SearchResult {
        val runId = runId(meta)
        val normalized = "city=${city.trim()}&days=$days"
        val fingerprint = Hashing.fingerprint(runId, normalized)
        return observer.execute(
            runId, StepName.SEARCH, fingerprint,
            replay = { replaySearch(runId, it.id, it.outputArtifactId) },
        ) {
            val forecast = weatherClient.search(city, days)
            val content = objectMapper.writeValueAsString(forecast)
            val artifact = artifact(runId, ArtifactType.SEARCH_RESULT, null, "application/json", content)
            PipelineObserver.Outcome(
                SearchResult(runId, requireStepId(runId, StepName.SEARCH), artifact.id, city = forecast.city, country = forecast.country,
                    timezone = forecast.timezone, days = forecast.days.size, recordCount = forecast.days.size, sha256 = artifact.sha256),
                artifact,
            )
        }
    }

    @McpTool(
        name = "summarize_weather_forecast",
        description = "Create deterministic Markdown from a stored search artifact. Pass the exact artifactId from search_weather_forecast as searchArtifactId. After success, pass the returned artifactId unchanged to save_weather_report.",
        generateOutputSchema = true,
    )
    fun summarize(
        @McpToolParam(description = "Exact artifactId returned by search_weather_forecast", required = true) searchArtifactId: String,
        meta: McpMeta,
    ): SummaryResult {
        val runId = runId(meta)
        val fingerprint = Hashing.fingerprint(runId, "searchArtifactId=$searchArtifactId")
        return observer.execute(
            runId, StepName.SUMMARY, fingerprint, searchArtifactId,
            replay = { replaySummary(runId, it.id, it.outputArtifactId) },
        ) {
            val source = store.requireArtifact(runId, searchArtifactId, ArtifactType.SEARCH_RESULT)
            val forecast = objectMapper.readValue(source.content, WeatherForecast::class.java)
            val summary = summaryService.summarize(forecast)
            val artifact = artifact(runId, ArtifactType.WEATHER_SUMMARY, source.id, "text/markdown", summary.markdown)
            PipelineObserver.Outcome(
                SummaryResult(runId, requireStepId(runId, StepName.SUMMARY), artifact.id, source.id,
                    title = summary.title, preview = summary.preview, sha256 = artifact.sha256),
                artifact,
            )
        }
    }

    @McpTool(
        name = "save_weather_report",
        description = "Safely save a stored weather summary as a Markdown file. Pass the exact artifactId from summarize_weather_forecast. Do not claim success until this tool returns status SAVED.",
        generateOutputSchema = true,
    )
    fun save(
        @McpToolParam(description = "Exact artifactId returned by summarize_weather_forecast", required = true) summaryArtifactId: String,
        @McpToolParam(description = "Simple file name ending in .md, without directories", required = true) fileName: String,
        meta: McpMeta,
    ): SaveResult {
        val runId = runId(meta)
        val fingerprint = Hashing.fingerprint(runId, "summaryArtifactId=$summaryArtifactId&fileName=$fileName")
        return observer.execute(
            runId, StepName.SAVE, fingerprint, summaryArtifactId,
            replay = { replaySave(runId, it.id, it.outputArtifactId) },
        ) {
            val source = store.requireArtifact(runId, summaryArtifactId, ArtifactType.WEATHER_SUMMARY)
            val saved = reportWriter.save(fileName, source.content)
            val artifact = artifact(runId, ArtifactType.SAVED_REPORT, source.id, "text/markdown", source.content)
            PipelineObserver.Outcome(
                SaveResult(runId, requireStepId(runId, StepName.SAVE), source.id, artifact.id, saved.fileName,
                    saved.relativePath, saved.sizeBytes, saved.sha256),
                artifact,
                saved.relativePath,
            )
        }
    }

    private fun replaySearch(runId: String, stepId: String, artifactId: String?): SearchResult {
        val artifact = requiredOutput(artifactId)
        val forecast = objectMapper.readValue(artifact.content, WeatherForecast::class.java)
        return SearchResult(runId, stepId, artifact.id, city = forecast.city, country = forecast.country,
            timezone = forecast.timezone, days = forecast.days.size, recordCount = forecast.days.size, sha256 = artifact.sha256)
    }

    private fun replaySummary(runId: String, stepId: String, artifactId: String?): SummaryResult {
        val artifact = requiredOutput(artifactId)
        val source = requiredOutput(artifact.sourceArtifactId)
        val forecast = objectMapper.readValue(source.content, WeatherForecast::class.java)
        val summary = summaryService.summarize(forecast)
        return SummaryResult(runId, stepId, artifact.id, source.id, title = summary.title, preview = summary.preview, sha256 = artifact.sha256)
    }

    private fun replaySave(runId: String, stepId: String, artifactId: String?): SaveResult {
        val artifact = requiredOutput(artifactId)
        val run = store.getRun(runId).run
        val relativePath = run.resultFile ?: throw PipelineException(ErrorCode.INTERNAL_ERROR, "Saved report path is missing")
        val fileName = relativePath.substringAfterLast('/')
        return SaveResult(runId, stepId, artifact.sourceArtifactId!!, artifact.id, fileName, relativePath,
            artifact.content.toByteArray(Charsets.UTF_8).size.toLong(), artifact.sha256)
    }

    private fun requiredOutput(id: String?) = id?.let(store::findArtifact)
        ?: throw PipelineException(ErrorCode.INVALID_ARTIFACT, "Completed step output artifact was not found")

    private fun requireStepId(runId: String, step: StepName) = store.getRun(runId).steps.single { it.sequenceNumber == step.sequence }.id

    private fun artifact(runId: String, type: ArtifactType, sourceId: String?, mediaType: String, content: String) = PipelineArtifact(
        "${type.name.lowercase()}-${UUID.randomUUID()}", runId, type, sourceId, mediaType, content, Hashing.sha256(content), clock.instant(),
    )

    private fun runId(meta: McpMeta): String = (meta.get("pipelineRunId") as? String)?.takeIf { it.isNotBlank() }
        ?: throw PipelineException(ErrorCode.MISSING_PIPELINE_CONTEXT, "pipelineRunId is required in MCP metadata")
}
