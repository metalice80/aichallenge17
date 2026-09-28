package dev.aichallenge.day20.agent

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.aichallenge.day20.agent.config.McpToolRegistry
import dev.aichallenge.day20.agent.repository.OrchestrationEventRepository
import dev.aichallenge.day20.agent.repository.OrchestrationRunRepository
import dev.aichallenge.day20.agent.repository.RunStatus
import dev.aichallenge.day20.agent.repository.ToolInvocationRepository
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.model.tool.ToolCallingChatOptions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpStatus
import org.springframework.web.client.RestClient
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.annotation.DirtiesContext
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, classes = [AgentApplication::class, FullOrchestrationIntegrationTest.StubModelConfiguration::class])
@DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
class FullOrchestrationIntegrationTest {
    @LocalServerPort var port: Int = 0
    @Autowired lateinit var runs: OrchestrationRunRepository
    @Autowired lateinit var invocations: ToolInvocationRepository
    @Autowired lateinit var events: OrchestrationEventRepository
    @Autowired lateinit var registry: McpToolRegistry

    @Test
    fun `one request completes seven model-directed calls across three real MCP processes`() {
        assertTrue(registry.ready)
        assertEquals(3, registry.servers.size)
        assertEquals(5, registry.callbacks.size)

        val response = RestClient.create("http://127.0.0.1:$port").post()
            .uri("/api/orchestrations")
            .body(mapOf("message" to "Подготовь план поездки в Казань на ближайшие 3 дня. Учти погоду, выбери три достопримечательности, добавь краткие описания и сохрани отчёт в файл kazan-trip.md."))
            .retrieve()
            .toEntity(Map::class.java)
        assertEquals(HttpStatus.ACCEPTED, response.statusCode)
        val runId = response.body!!["runId"] as String
        val run = awaitTerminal(runId)
        assertEquals(RunStatus.COMPLETED, run.status, "${run.errorCode}: ${run.errorMessage}")
        assertEquals("reports/kazan-trip.md", run.reportPath)
        assertTrue(run.finalAnswer!!.contains("reports/kazan-trip.md"))

        val calls = invocations.list(runId)
        assertEquals(listOf(
            "weather_resolve_location", "weather_get_forecast", "guide_search_articles",
            "guide_get_article_summary", "guide_get_article_summary", "guide_get_article_summary",
            "files_save_markdown_report",
        ), calls.map { it.toolName })
        assertEquals((1..7).toList(), calls.map { it.sequenceNumber })
        assertEquals(setOf("weather-server", "guide-server", "files-server"), calls.map { it.serverName }.toSet())
        val resolvedRef = mapper.readTree(calls[0].outputRefsJson).path("locationRefs")[0].asText()
        val forecastRef = mapper.readTree(calls[1].outputRefsJson).path("forecastLocationRefs")[0].asText()
        assertEquals(resolvedRef, forecastRef)
        val searched = mapper.readTree(calls[2].outputRefsJson).path("searchedArticles").fieldNames().asSequence().toSet()
        val summarized = calls.slice(3..5).map { mapper.readTree(it.outputRefsJson).path("summarizedArticles").fieldNames().next() }
        assertEquals(3, summarized.toSet().size)
        assertTrue(searched.containsAll(summarized))
        val saveInput = mapper.readTree(calls.last().inputRefsJson)
        assertEquals(resolvedRef, saveInput.path("weatherLocationRef").asText())
        assertEquals(summarized.toSet(), saveInput.path("articleRefs").map { it.asText() }.toSet())
        assertEquals("REPORT_SAVED", events.list(runId, 0, 100).first { it.eventType == "REPORT_SAVED" }.eventType)
        assertTrue(Files.readString(reports.resolve("kazan-trip.md")).contains("Казанский кремль"))

        val client = RestClient.create("http://127.0.0.1:$port")
        val status = client.get().uri("/api/orchestrations/$runId").retrieve().toEntity(Map::class.java)
        assertEquals(HttpStatus.OK, status.statusCode)
        val report = client.get().uri("/api/orchestrations/$runId/report").retrieve().toEntity(String::class.java)
        assertEquals(HttpStatus.OK, report.statusCode)
        assertTrue(report.body!!.contains("# План поездки"))

        val http = HttpClient.newHttpClient()
        val invalid = http.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/orchestrations"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"message\":\"\"}"))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(400, invalid.statusCode())
        val missing = http.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/orchestrations/missing")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(404, missing.statusCode())
        val stream = http.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/orchestrations/$runId/events/stream")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        ).body()
        val ids = Regex("(?m)^id:(\\d+)$").findAll(stream).map { it.groupValues[1].toInt() }.toList()
        assertTrue(ids.isNotEmpty())
        assertEquals(ids.toSet().size, ids.size)
        assertTrue(stream.contains(runId))
        val reconnect = http.send(
            HttpRequest.newBuilder(URI("http://127.0.0.1:$port/api/orchestrations/$runId/events/stream?after=${ids.first()}")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        ).body()
        assertTrue(!reconnect.contains("id:${ids.first()}\n"))

    }

    private fun awaitTerminal(runId: String): dev.aichallenge.day20.agent.repository.OrchestrationRun {
        repeat(300) {
            val run = runs.find(runId)!!
            if (run.status in setOf(RunStatus.COMPLETED, RunStatus.FAILED, RunStatus.CANCELLED)) return run
            Thread.sleep(50)
        }
        error("Run did not finish")
    }


    @TestConfiguration(proxyBeanMethods = false)
    class StubModelConfiguration {
        @Bean
        @Primary
        fun deterministicChatModel(): ChatModel = DeterministicTravelChatModel(ObjectMapper())
    }

    class DeterministicTravelChatModel(private val mapper: ObjectMapper) : ChatModel {
        override fun getOptions(): ChatOptions = ToolCallingChatOptions.builder().build()

        override fun call(prompt: Prompt): ChatResponse {
            val responses = prompt.instructions.filterIsInstance<ToolResponseMessage>().flatMap { it.responses }
            val parsed = responses.map { parse(it.responseData()) }
            val message = when (responses.size) {
                0 -> tool("call-1", "weather_resolve_location", mapOf("city" to "Казань"))
                1 -> tool("call-2", "weather_get_forecast", mapOf("locationRef" to parsed[0].path("locationRef").asText(), "days" to 3))
                2 -> tool("call-3", "guide_search_articles", mapOf("city" to "Казань", "query" to "достопримечательности", "limit" to 6))
                3, 4, 5 -> {
                    val refs = parsed[2].path("articles").map { it.path("articleRef").asText() }
                    tool("call-${responses.size + 1}", "guide_get_article_summary", mapOf("articleRef" to refs[responses.size - 3]))
                }
                6 -> {
                    val locationRef = parsed[0].path("locationRef").asText()
                    val summaries = parsed.slice(3..5)
                    val refs = summaries.map { it.path("articleRef").asText() }
                    val urls = summaries.map { it.path("sourceUrl").asText() }
                    val content = buildString {
                        appendLine("# План поездки в Казань")
                        appendLine("## Погода")
                        parsed[1].path("days").forEach { appendLine("- ${it.path("date").asText()}: ${it.path("weatherDescription").asText()}, ${it.path("temperatureMinC").asText()}…${it.path("temperatureMaxC").asText()} °C") }
                        appendLine("## Достопримечательности")
                        summaries.forEach { appendLine("### ${it.path("title").asText()}\n${it.path("summary").asText()}\nИсточник: ${it.path("sourceUrl").asText()}") }
                        appendLine("## Совет")
                        appendLine("Проверьте прогноз перед прогулкой и возьмите защиту от дождя.")
                    }
                    tool("call-7", "files_save_markdown_report", mapOf("fileName" to "kazan-trip.md", "content" to content, "weatherLocationRef" to locationRef, "articleRefs" to refs, "sourceUrls" to urls))
                }
                else -> AssistantMessage("Готово. Отчёт сохранён: reports/kazan-trip.md")
            }
            return ChatResponse(listOf(Generation(message)))
        }

        private fun tool(id: String, name: String, args: Map<String, Any>): AssistantMessage = AssistantMessage.builder()
            .content("")
            .toolCalls(listOf(AssistantMessage.ToolCall(id, "function", name, mapper.writeValueAsString(args))))
            .build()

        private fun parse(value: String): JsonNode {
            val root = mapper.readTree(value)
            if (root.has("structuredContent")) return root.path("structuredContent")
            val text = when {
                root.isArray -> root.firstOrNull()?.path("text")?.asText()
                root.path("content").isArray -> root.path("content").firstOrNull()?.path("text")?.asText()
                else -> null
            }
            return if (!text.isNullOrBlank() && text.trimStart().startsWith("{")) mapper.readTree(text) else root
        }
    }

    companion object {
        private val mapper = ObjectMapper()
        private val temp = Files.createTempDirectory("day20-full-")
        val reports: Path = Files.createDirectories(temp.resolve("reports"))
        private val database = temp.resolve("orchestration.db")
        private val stub: HttpServer = HttpServer.create(InetSocketAddress(0), 0).apply {
            createContext("/v1/search") { it.json("""{"results":[{"name":"Казань","country":"Россия","latitude":55.79,"longitude":49.12,"timezone":"Europe/Moscow"}]}""") }
            createContext("/v1/forecast") { it.json("""{"timezone":"Europe/Moscow","daily":{"time":["2026-09-29","2026-09-30","2026-10-01"],"weather_code":[3,61,0],"temperature_2m_min":[8.0,7.0,9.0],"temperature_2m_max":[15.0,13.0,16.0],"precipitation_sum":[0.0,2.0,0.0],"precipitation_probability_max":[10,70,5],"wind_speed_10m_max":[12.0,20.0,8.0]}}""") }
            createContext("/w/rest.php/v1/search/page") { it.json("""{"pages":[{"key":"kremlin","title":"Казанский кремль","excerpt":"Кремль","description":"Крепость"},{"key":"kul-sharif","title":"Кул-Шариф","excerpt":"Мечеть","description":"Мечеть"},{"key":"bauman","title":"Улица Баумана","excerpt":"Улица","description":"Пешеходная улица"}]}""") }
            listOf("kremlin" to "Казанский кремль", "kul-sharif" to "Кул-Шариф", "bauman" to "Улица Баумана").forEach { (key, title) ->
                createContext("/api/rest_v1/page/summary/$key") { it.json("""{"title":"$title","extract":"Проверенное описание: $title.","content_urls":{"desktop":{"page":"https://example.test/wiki/$key"}}}""") }
            }
            start()
        }
        private val baseUrl = "http://127.0.0.1:${stub.address.port}"

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { "jdbc:sqlite:$database" }
            registry.add("spring.ai.openai.api-key") { "test" }
            registry.add("app.agent.api-key-configured") { "test" }
            registry.add("WEATHER_SERVER_JAR") { Path.of("../mcp-weather-server/build/libs/mcp-weather-server.jar").toAbsolutePath().normalize().toString() }
            registry.add("GUIDE_SERVER_JAR") { Path.of("../mcp-guide-server/build/libs/mcp-guide-server.jar").toAbsolutePath().normalize().toString() }
            registry.add("FILES_SERVER_JAR") { Path.of("../mcp-files-server/build/libs/mcp-files-server.jar").toAbsolutePath().normalize().toString() }
            registry.add("GEOCODING_BASE_URL") { baseUrl }
            registry.add("FORECAST_BASE_URL") { baseUrl }
            registry.add("MEDIAWIKI_BASE_URL") { baseUrl }
            registry.add("MEDIAWIKI_PUBLIC_BASE_URL") { "https://ru.wikipedia.org" }
            registry.add("REPORTS_DIR") { reports.toString() }
        }

        @JvmStatic
        @AfterAll
        fun stopStub() = stub.stop(0)

        private fun HttpExchange.json(body: String) {
            responseHeaders.add("Content-Type", "application/json")
            sendResponseHeaders(200, body.toByteArray().size.toLong())
            responseBody.use { it.write(body.toByteArray()) }
        }
    }
}
