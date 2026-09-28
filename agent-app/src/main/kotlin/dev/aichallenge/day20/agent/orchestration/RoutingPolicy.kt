package dev.aichallenge.day20.agent.orchestration

import com.fasterxml.jackson.databind.JsonNode
import dev.aichallenge.day20.agent.config.AppProperties
import dev.aichallenge.day20.agent.repository.OrchestrationRunRepository
import org.springframework.stereotype.Component
import java.net.URI

@Component
class RoutingPolicy(
    private val runs: OrchestrationRunRepository,
    private val states: RoutingStateLoader,
    properties: AppProperties,
) {
    private val maxTotal = properties.orchestration.maxTotalToolCalls
    private val perToolLimits = mapOf(
        "weather_resolve_location" to 2,
        "weather_get_forecast" to 2,
        "guide_search_articles" to 2,
        "guide_get_article_summary" to 5,
        "files_save_markdown_report" to 1,
    )

    data class Decision(val allowed: Boolean, val code: String? = null, val message: String? = null, val retryable: Boolean = true) {
        companion object {
            fun allow() = Decision(true)
            fun reject(code: String, message: String, retryable: Boolean = true) = Decision(false, code, message, retryable)
        }
    }

    fun validateBeforeCall(runId: String, tool: String, input: JsonNode): Decision {
        val run = runs.find(runId) ?: return Decision.reject("ORCHESTRATION_NOT_FOUND", "Unknown orchestration run", false)
        if (!runs.isActive(runId)) return Decision.reject("PRECONDITION_FAILED", "Orchestration run is not active", false)
        val state = states.load(runId)
        if (state.savedReport) return Decision.reject("PRECONDITION_FAILED", "No tool calls are allowed after the report is saved", false)
        if (state.totalToolCalls >= maxTotal) return Decision.reject("TOOL_CALL_LIMIT_EXCEEDED", "Maximum total tool calls exceeded", false)
        val limit = perToolLimits[tool] ?: return Decision.reject("MCP_TOOL_MISSING", "Tool is not allowlisted", false)
        if ((state.perToolCallCounts[tool] ?: 0) >= limit) return Decision.reject("TOOL_CALL_LIMIT_EXCEEDED", "Maximum calls for $tool exceeded", false)

        return when (tool) {
            "weather_resolve_location" -> {
                val city = input.path("city").asText().trim()
                if (city.length !in 2..120 || !run.requestText.contains(city, ignoreCase = true)) Decision.reject("PRECONDITION_FAILED", "Resolved city must match the user request") else Decision.allow()
            }
            "weather_get_forecast" -> {
                val reference = input.path("locationRef").asText()
                val days = input.path("days").asInt(0)
                when {
                    reference !in state.resolvedLocationRefs -> Decision.reject("PRECONDITION_FAILED", "Forecast requires a locationRef returned by weather_resolve_location")
                    reference in state.forecastedLocationRefs -> Decision.reject("PRECONDITION_FAILED", "Forecast already exists for this locationRef")
                    days !in 1..7 -> Decision.reject("VALIDATION_ERROR", "days must be from 1 to 7")
                    else -> Decision.allow()
                }
            }
            "guide_search_articles" -> Decision.allow()
            "guide_get_article_summary" -> {
                val reference = input.path("articleRef").asText()
                when {
                    reference !in state.searchedArticleRefs -> Decision.reject("PRECONDITION_FAILED", "Summary requires an articleRef returned by guide_search_articles")
                    reference in state.summarizedArticleRefs -> Decision.reject("PRECONDITION_FAILED", "This articleRef was already summarized")
                    else -> Decision.allow()
                }
            }
            "files_save_markdown_report" -> validateSave(state, input)
            else -> Decision.reject("MCP_TOOL_MISSING", "Tool is not allowlisted", false)
        }
    }

    private fun validateSave(state: RoutingState, input: JsonNode): Decision {
        if (state.forecastedLocationRefs.isEmpty()) return Decision.reject("PRECONDITION_FAILED", "files_save_markdown_report requires a forecast and three unique article summaries")
        if (state.summarizedArticleRefs.size < 3) return Decision.reject("PRECONDITION_FAILED", "files_save_markdown_report requires a forecast and three unique article summaries")
        val weatherRef = input.path("weatherLocationRef").asText()
        if (weatherRef !in state.forecastedLocationRefs) return Decision.reject("PRECONDITION_FAILED", "weatherLocationRef must match the successful forecast")
        val articleRefs = input.path("articleRefs").map { it.asText() }
        if (articleRefs.size != 3 || articleRefs.toSet().size != 3 || articleRefs.any { it !in state.summarizedArticleRefs }) {
            return Decision.reject("PRECONDITION_FAILED", "articleRefs must be three unique successfully summarized references")
        }
        val allowedUrls = state.searchedArticleRefs.values.toSet() + state.summarizedArticleRefs.values.toSet()
        val urls = input.path("sourceUrls").map { it.asText() }
        if (urls.size > 10 || urls.any { it !in allowedUrls || runCatching { URI.create(it).scheme == "https" }.getOrDefault(false).not() }) {
            return Decision.reject("PRECONDITION_FAILED", "sourceUrls must be a subset of URLs returned by guide tools")
        }
        val fileName = input.path("fileName").asText()
        if (fileName.isBlank() || !fileName.endsWith(".md") || fileName.contains("..") || fileName.any { it == '/' || it == '\\' || it.isISOControl() }) {
            return Decision.reject("UNSAFE_FILE_NAME", "fileName must be a safe Markdown basename", false)
        }
        if (input.path("content").asText().isBlank()) return Decision.reject("VALIDATION_ERROR", "Report content must not be empty")
        return Decision.allow()
    }
}
