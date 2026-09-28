package dev.aichallenge.day20.agent

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.client.transport.ServerParameters
import io.modelcontextprotocol.client.transport.StdioClientTransport
import io.modelcontextprotocol.json.McpJsonDefaults
import io.modelcontextprotocol.spec.McpSchema
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.nio.file.Path
import java.time.Duration

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class McpContractIntegrationTest {
    private lateinit var stub: HttpServer
    private lateinit var baseUrl: String
    private val mapper = ObjectMapper()
    @TempDir lateinit var reports: Path

    @BeforeAll
    fun startStub() {
        stub = HttpServer.create(InetSocketAddress(0), 0)
        stub.createContext("/v1/search") { it.json("""{"results":[{"name":"Казань","country":"Россия","latitude":55.79,"longitude":49.12,"timezone":"Europe/Moscow"}]}""") }
        stub.createContext("/v1/forecast") { it.json("""{"timezone":"Europe/Moscow","daily":{"time":["2026-09-29","2026-09-30","2026-10-01"],"weather_code":[3,61,0],"temperature_2m_min":[8.0,7.0,9.0],"temperature_2m_max":[15.0,13.0,16.0],"precipitation_sum":[0.0,2.0,0.0],"precipitation_probability_max":[10,70,5],"wind_speed_10m_max":[12.0,20.0,8.0]}}""") }
        stub.createContext("/w/rest.php/v1/search/page") { it.json(searchJson()) }
        listOf("kremlin", "kul-sharif", "bauman").forEach { key -> stub.createContext("/api/rest_v1/page/summary/$key") { it.json("""{"title":"$key","extract":"Краткое описание $key.","content_urls":{"desktop":{"page":"https://example.test/wiki/$key"}}}""") } }
        stub.start()
        baseUrl = "http://127.0.0.1:${stub.address.port}"
    }

    @AfterAll
    fun stopStub() = stub.stop(0)

    @Test
    fun `weather jar initializes lists schemas and executes happy path`() {
        withClient("mcp-weather-server/build/libs/mcp-weather-server.jar", mapOf("GEOCODING_BASE_URL" to baseUrl, "FORECAST_BASE_URL" to baseUrl)) { client ->
            assertEquals("weather-server", client.serverInfo.name())
            val tools = client.listTools().tools()
            assertEquals(setOf("weather_resolve_location", "weather_get_forecast"), tools.map { it.name() }.toSet())
            assertTrue(tools.all { it.inputSchema().isNotEmpty() && it.outputSchema().isNotEmpty() })
            val resolved = call(client, "weather_resolve_location", mapOf("city" to "Казань"))
            val locationRef = resolved["locationRef"].asText()
            val forecast = call(client, "weather_get_forecast", mapOf("locationRef" to locationRef, "days" to 3))
            assertEquals(locationRef, forecast["locationRef"].asText())
            assertEquals(3, forecast["days"].size())
        }
        assertProcessStopped("mcp-weather-server.jar")
    }

    @Test
    fun `guide jar initializes lists schemas and executes happy path`() {
        withClient("mcp-guide-server/build/libs/mcp-guide-server.jar", mapOf("MEDIAWIKI_BASE_URL" to baseUrl, "MEDIAWIKI_PUBLIC_BASE_URL" to "https://ru.wikipedia.org", "MEDIAWIKI_USER_AGENT" to "contract-test/1.0")) { client ->
            assertEquals("guide-server", client.serverInfo.name())
            val tools = client.listTools().tools()
            assertEquals(setOf("guide_search_articles", "guide_get_article_summary"), tools.map { it.name() }.toSet())
            assertTrue(tools.all { it.inputSchema().isNotEmpty() && it.outputSchema().isNotEmpty() })
            val search = call(client, "guide_search_articles", mapOf("city" to "Казань", "query" to "достопримечательности", "limit" to 6))
            val articleRef = search["articles"][0]["articleRef"].asText()
            val summary = call(client, "guide_get_article_summary", mapOf("articleRef" to articleRef))
            assertEquals(articleRef, summary["articleRef"].asText())
        }
        assertProcessStopped("mcp-guide-server.jar")
    }

    @Test
    fun `files jar initializes lists schema and performs safe idempotent save`() {
        withClient("mcp-files-server/build/libs/mcp-files-server.jar", mapOf("REPORTS_DIR" to reports.toString())) { client ->
            assertEquals("files-server", client.serverInfo.name())
            val tools = client.listTools().tools()
            assertEquals(listOf("files_save_markdown_report"), tools.map { it.name() })
            assertTrue(tools.single().inputSchema().isNotEmpty() && tools.single().outputSchema().isNotEmpty())
            val args = mapOf<String, Any>("fileName" to "contract.md", "content" to "# Contract", "weatherLocationRef" to "loc-1", "articleRefs" to listOf("a", "b", "c"), "sourceUrls" to listOf("https://example.test/a"))
            val first = call(client, "files_save_markdown_report", args)
            val second = call(client, "files_save_markdown_report", args)
            assertEquals("SAVED", first["status"].asText())
            assertEquals(first["sha256"].asText(), second["sha256"].asText())
            assertTrue(reports.resolve("contract.md").toFile().isFile)
        }
        assertProcessStopped("mcp-files-server.jar")
    }

    private fun withClient(jar: String, env: Map<String, String>, block: (McpSyncClient) -> Unit) {
        val absoluteJar = Path.of("..").resolve(jar).toAbsolutePath().normalize().toString()
        val parameters = ServerParameters.builder("java").args("-jar", absoluteJar).env(env).build()
        val transport = StdioClientTransport(parameters, McpJsonDefaults.getMapper())
        val client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(15)).build()
        try {
            client.initialize()
            assertTrue(client.isInitialized)
            block(client)
        } finally {
            assertTrue(client.closeGracefully())
        }
    }

    private fun call(client: McpSyncClient, tool: String, args: Map<String, Any>) = run {
        val request = McpSchema.CallToolRequest.builder(tool).arguments(args).meta(mapOf("orchestrationRunId" to "contract-run")).build()
        val result = client.callTool(request)
        assertFalse(result.isError() == true)
        val value = result.structuredContent() ?: (result.content().first() as McpSchema.TextContent).text()
        mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(value).let { node -> if (node.isTextual) mapper.readTree(node.asText()) else node }
    }

    private fun assertProcessStopped(jar: String) {
        repeat(30) {
            val alive = ProcessHandle.current().descendants().anyMatch { it.info().commandLine().orElse("").contains(jar) }
            if (!alive) return
            Thread.sleep(50)
        }
        assertFalse(ProcessHandle.current().descendants().anyMatch { it.info().commandLine().orElse("").contains(jar) }, "$jar child process remained alive")
    }

    private fun searchJson() = """{"pages":[{"key":"kremlin","title":"Казанский кремль","excerpt":"<b>Кремль</b>","description":"Крепость"},{"key":"kul-sharif","title":"Кул-Шариф","excerpt":"Мечеть","description":"Мечеть"},{"key":"bauman","title":"Улица Баумана","excerpt":"Улица","description":"Пешеходная улица"}]}"""
    private fun HttpExchange.json(body: String) {
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(200, body.toByteArray().size.toLong())
        responseBody.use { it.write(body.toByteArray()) }
    }
}
