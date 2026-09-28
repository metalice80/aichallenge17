package dev.aichallenge.day20.agent.orchestration

import com.fasterxml.jackson.databind.ObjectMapper
import dev.aichallenge.day20.agent.repository.InvocationStatus
import dev.aichallenge.day20.agent.repository.ToolInvocationRepository
import org.springframework.stereotype.Component

class RoutingState {
    val resolvedLocationRefs = linkedSetOf<String>()
    val forecastedLocationRefs = linkedSetOf<String>()
    val searchedArticleRefs = linkedMapOf<String, String>()
    val summarizedArticleRefs = linkedMapOf<String, String>()
    var savedReportPath: String? = null
    var totalToolCalls: Int = 0
    val perToolCallCounts = linkedMapOf<String, Int>()

    val savedReport: Boolean get() = savedReportPath != null
}

@Component
class RoutingStateLoader(
    private val invocations: ToolInvocationRepository,
    private val objectMapper: ObjectMapper,
) {
    fun load(runId: String): RoutingState {
        val state = RoutingState()
        invocations.list(runId).forEach { invocation ->
            state.totalToolCalls++
            state.perToolCallCounts.merge(invocation.toolName, 1, Int::plus)
            if (invocation.status != InvocationStatus.SUCCEEDED || invocation.outputRefsJson == null) return@forEach
            val refs = objectMapper.readTree(invocation.outputRefsJson)
            refs.path("locationRefs").forEach { state.resolvedLocationRefs += it.asText() }
            refs.path("forecastLocationRefs").forEach { state.forecastedLocationRefs += it.asText() }
            refs.path("searchedArticles").fields().forEach { state.searchedArticleRefs[it.key] = it.value.asText() }
            refs.path("summarizedArticles").fields().forEach { state.summarizedArticleRefs[it.key] = it.value.asText() }
            refs.path("reportPath").takeUnless { it.isMissingNode || it.isNull }?.asText()?.let { state.savedReportPath = it }
        }
        return state
    }
}
