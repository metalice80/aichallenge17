package dev.aichallenge.weather.server.tool

import dev.aichallenge.weather.server.pipeline.PipelineEvent
import dev.aichallenge.weather.server.pipeline.PipelineRun
import dev.aichallenge.weather.server.pipeline.PipelineStep

data class SearchResult(
    val runId: String,
    val stepId: String,
    val artifactId: String,
    val artifactType: String = "SEARCH_RESULT",
    val city: String,
    val country: String,
    val timezone: String,
    val days: Int,
    val recordCount: Int,
    val sha256: String,
    val status: String = "SEARCHED",
    val nextTool: String = "summarize_weather_forecast",
)

data class SummaryResult(
    val runId: String,
    val stepId: String,
    val artifactId: String,
    val sourceArtifactId: String,
    val artifactType: String = "WEATHER_SUMMARY",
    val title: String,
    val preview: String,
    val sha256: String,
    val status: String = "SUMMARIZED",
    val nextTool: String = "save_weather_report",
)

data class SaveResult(
    val runId: String,
    val stepId: String,
    val sourceArtifactId: String,
    val reportArtifactId: String,
    val fileName: String,
    val relativePath: String,
    val sizeBytes: Long,
    val sha256: String,
    val status: String = "SAVED",
)

data class RunResult(val run: PipelineRun, val steps: List<PipelineStep>)
data class EventListResult(val runId: String, val events: List<PipelineEvent>)
data class ReportResult(val runId: String, val fileName: String, val markdown: String)
