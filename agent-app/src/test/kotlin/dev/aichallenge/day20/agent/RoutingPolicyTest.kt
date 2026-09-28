package dev.aichallenge.day20.agent

import com.fasterxml.jackson.databind.ObjectMapper
import dev.aichallenge.day20.agent.config.AppProperties
import dev.aichallenge.day20.agent.orchestration.RoutingPolicy
import dev.aichallenge.day20.agent.orchestration.RoutingState
import dev.aichallenge.day20.agent.orchestration.RoutingStateLoader
import dev.aichallenge.day20.agent.repository.OrchestrationRun
import dev.aichallenge.day20.agent.repository.OrchestrationRunRepository
import dev.aichallenge.day20.agent.repository.RunStatus
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.time.Instant

class RoutingPolicyTest {
    private val mapper = ObjectMapper()
    private val runs = mock(OrchestrationRunRepository::class.java)
    private val loader = mock(RoutingStateLoader::class.java)
    private lateinit var state: RoutingState
    private lateinit var policy: RoutingPolicy

    @BeforeEach
    fun setUp() {
        state = RoutingState()
        val now = Instant.parse("2026-09-28T10:00:00Z")
        val run = OrchestrationRun("run-1", "Подготовь поездку в Казань на 3 дня и сохрани kazan-trip.md", RunStatus.RUNNING, "test", now, now, now, null, null, null, null, null, 0)
        `when`(runs.find("run-1")).thenReturn(run)
        `when`(runs.isActive("run-1")).thenReturn(true)
        `when`(loader.load("run-1")).thenAnswer { state }
        policy = RoutingPolicy(runs, loader, AppProperties())
    }

    @Test
    fun `dependency partial order and side effect are enforced`() {
        assertFalse(policy.validateBeforeCall("run-1", "weather_get_forecast", json("""{"locationRef":"loc-1","days":3}""")).allowed)
        state.resolvedLocationRefs += "loc-1"
        assertTrue(policy.validateBeforeCall("run-1", "weather_get_forecast", json("""{"locationRef":"loc-1","days":3}""")).allowed)
        assertFalse(policy.validateBeforeCall("run-1", "guide_get_article_summary", json("""{"articleRef":"a"}""")).allowed)
        state.searchedArticleRefs["a"] = "https://example.test/a"
        assertTrue(policy.validateBeforeCall("run-1", "guide_get_article_summary", json("""{"articleRef":"a"}""")).allowed)
        state.summarizedArticleRefs["a"] = "https://example.test/a"
        assertFalse(policy.validateBeforeCall("run-1", "guide_get_article_summary", json("""{"articleRef":"a"}""")).allowed)

        val save = """{"fileName":"kazan-trip.md","content":"# report","weatherLocationRef":"loc-1","articleRefs":["a","b","c"],"sourceUrls":["https://example.test/a","https://example.test/b","https://example.test/c"]}"""
        assertFalse(policy.validateBeforeCall("run-1", "files_save_markdown_report", json(save)).allowed)
        state.forecastedLocationRefs += "loc-1"
        state.searchedArticleRefs["b"] = "https://example.test/b"; state.searchedArticleRefs["c"] = "https://example.test/c"
        state.summarizedArticleRefs["b"] = "https://example.test/b"
        assertFalse(policy.validateBeforeCall("run-1", "files_save_markdown_report", json(save)).allowed)
        state.summarizedArticleRefs["c"] = "https://example.test/c"
        assertTrue(policy.validateBeforeCall("run-1", "files_save_markdown_report", json(save)).allowed)
        assertFalse(policy.validateBeforeCall("run-1", "files_save_markdown_report", json(save.replace("\"a\",\"b\",\"c\"", "\"a\",\"a\",\"c\""))).allowed)
        state.savedReportPath = "reports/kazan-trip.md"
        assertFalse(policy.validateBeforeCall("run-1", "guide_search_articles", json("""{"city":"Казань","query":"музеи","limit":6}""")).allowed)
    }

    @Test
    fun `call limits and user destination are enforced`() {
        assertFalse(policy.validateBeforeCall("run-1", "weather_resolve_location", json("""{"city":"Москва"}""")).allowed)
        assertTrue(policy.validateBeforeCall("run-1", "weather_resolve_location", json("""{"city":"Казань"}""")).allowed)
        state.totalToolCalls = 12
        assertFalse(policy.validateBeforeCall("run-1", "guide_search_articles", json("""{"city":"Казань","query":"музеи","limit":6}""")).allowed)
    }

    private fun json(value: String) = mapper.readTree(value)
}
