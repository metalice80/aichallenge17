package dev.aichallenge.weather.agent

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.sun.net.httpserver.HttpServer
import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.client.transport.ServerParameters
import io.modelcontextprotocol.client.transport.StdioClientTransport
import io.modelcontextprotocol.json.McpJsonDefaults
import io.modelcontextprotocol.spec.McpSchema
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.mcp.McpToolFilter
import org.springframework.ai.mcp.McpToolNamePrefixGenerator
import org.springframework.ai.model.tool.ToolCallingChatOptions
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider
import org.springframework.ai.mcp.ToolContextToMcpMetaConverter
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID

class RealStdioPipelineIntegrationTest {
    @TempDir lateinit var temp: Path
    private var http: HttpServer? = null
    private var client: McpSyncClient? = null
    private val mapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())

    @AfterEach
    fun close() {
        client?.closeGracefully()
        http?.stop(0)
    }

    @Test
    fun `real child process initializes lists tools and executes artifact pipeline`() {
        val mcp = startMcp()
        assertTrue(mcp.isInitialized)
        val names = mcp.listTools().tools().map { it.name() }.toSet()
        assertEquals(REQUIRED_TOOLS, names)

        val runId = createAndStart(mcp)
        val search = call(mcp, "search_weather_forecast", mapOf("city" to "Новосибирск", "days" to 5), runId)
        val searchId = search.path("artifactId").asText()
        val summary = call(mcp, "summarize_weather_forecast", mapOf("searchArtifactId" to searchId), runId)
        val summaryId = summary.path("artifactId").asText()
        val saved = call(mcp, "save_weather_report", mapOf("summaryArtifactId" to summaryId, "fileName" to "novosibirsk.md"), runId)

        assertEquals(searchId, summary.path("sourceArtifactId").asText())
        assertEquals(summaryId, saved.path("sourceArtifactId").asText())
        assertEquals("SAVED", saved.path("status").asText())
        val state = internal(mcp, "get_pipeline_run", mapOf("runId" to runId))
        assertEquals("COMPLETED", state.path("run").path("status").asText())
        assertEquals(listOf("SUCCEEDED", "SUCCEEDED", "SUCCEEDED"), state.path("steps").map { it.path("status").asText() })
        assertTrue(state.path("steps").all { !it.path("durationMs").isNull && it.path("durationMs").asLong() >= 0 })
        val events = internal(mcp, "list_pipeline_events", mapOf("runId" to runId, "afterSequence" to 0, "limit" to 100)).path("events")
        assertTrue(events.map { it.path("sequenceNumber").asLong() }.zipWithNext().all { (a, b) -> a < b })
        assertTrue(events.any { it.path("eventType").asText() == "RUN_COMPLETED" })
        val report = temp.resolve("reports/novosibirsk.md")
        assertTrue(Files.isRegularFile(report))
        assertTrue(Files.readString(report).contains("Новосибирск"))

        val repeated = call(mcp, "save_weather_report", mapOf("summaryArtifactId" to summaryId, "fileName" to "novosibirsk.md"), runId)
        assertEquals(saved.path("reportArtifactId").asText(), repeated.path("reportArtifactId").asText())

        val crossRunId = createAndStart(mcp)
        call(mcp, "search_weather_forecast", mapOf("city" to "Омск", "days" to 5), crossRunId)
        val crossRun = mcp.callTool(
            McpSchema.CallToolRequest(
                "summarize_weather_forecast", mapOf("searchArtifactId" to searchId),
                mapOf("pipelineRunId" to crossRunId),
            ),
        )
        assertTrue(crossRun.isError() == true)
        assertTrue(crossRun.content().toString().contains("ARTIFACT_FROM_ANOTHER_RUN"))
        assertEquals("FAILED", internal(mcp, "get_pipeline_run", mapOf("runId" to crossRunId)).path("run").path("status").asText())

        val wrongOrderRunId = createAndStart(mcp)
        val wrongOrder = mcp.callTool(
            McpSchema.CallToolRequest(
                "summarize_weather_forecast", mapOf("searchArtifactId" to searchId),
                mapOf("pipelineRunId" to wrongOrderRunId),
            ),
        )
        assertTrue(wrongOrder.isError() == true)
        assertTrue(wrongOrder.content().toString().contains("INVALID_PIPELINE_STATE"))
        assertEquals("FAILED", internal(mcp, "get_pipeline_run", mapOf("runId" to wrongOrderRunId)).path("run").path("status").asText())
        assertTrue(mcp.closeGracefully())
        client = null
    }

    @Test
    fun `stub ChatModel drives real MCP callbacks through search summarize save`() {
        val mcp = startMcp()
        val runId = createAndStart(mcp)
        val callbacks = SyncMcpToolCallbackProvider.builder()
            .mcpClients(mcp)
            .toolFilter(McpToolFilter { _, tool -> tool.name() in AGENT_TOOLS })
            .toolNamePrefixGenerator(McpToolNamePrefixGenerator.noPrefix())
            .toolContextToMcpMetaConverter(ToolContextToMcpMetaConverter.defaultConverter())
            .build()
        assertEquals(AGENT_TOOLS, callbacks.toolCallbacks.map { it.toolDefinition.name() }.toSet())
        val model = PipelineChatModel(mapper)
        val chat = ChatClient.builder(model)
            .defaultAdvisors(ToolCallingAdvisor.builder().build())
            .defaultTools(*callbacks.toolCallbacks)
            .build()

        val answer = chat.prompt("weather request")
            .options(ToolCallingChatOptions.builder())
            .toolContext(mapOf("pipelineRunId" to runId, "conversationId" to "test-conversation"))
            .call().content()

        assertEquals("Pipeline saved successfully", answer)
        assertEquals(listOf("search_weather_forecast", "summarize_weather_forecast", "save_weather_report"), model.calledTools)
        val state = internal(mcp, "get_pipeline_run", mapOf("runId" to runId))
        assertEquals("COMPLETED", state.path("run").path("status").asText())
        assertFalse(model.artifactIds.any { it.isBlank() })
        assertEquals(model.artifactIds[0], model.summaryInput)
        assertEquals(model.artifactIds[1], model.saveInput)
    }

    private fun startMcp(): McpSyncClient {
        startHttpStub()
        val jar = Path.of("mcp-pipeline-server/build/libs/mcp-pipeline-server.jar").toAbsolutePath()
        assertTrue(Files.isRegularFile(jar), "MCP server jar must be built before integration test")
        val parameters = ServerParameters.builder("java")
            .args("-jar", jar.toString())
            .env(mapOf(
                "PIPELINE_DB_PATH" to temp.resolve("pipeline.db").toString(),
                "REPORTS_DIR" to temp.resolve("reports").toString(),
                "GEOCODING_BASE_URL" to "http://127.0.0.1:${http!!.address.port}",
                "FORECAST_BASE_URL" to "http://127.0.0.1:${http!!.address.port}",
            )).build()
        val transport = StdioClientTransport(parameters, McpJsonDefaults.getMapper())
        val mcp = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(30)).build()
        mcp.initialize()
        client = mcp
        return mcp
    }

    private fun createAndStart(mcp: McpSyncClient): String {
        val created = internal(mcp, "create_pipeline_run", mapOf("requestText" to "weather request", "modelId" to "stub-model"))
        val runId = created.path("run").path("id").asText()
        internal(mcp, "mark_pipeline_agent_started", mapOf("runId" to runId))
        return runId
    }

    private fun call(mcp: McpSyncClient, name: String, arguments: Map<String, Any>, runId: String) =
        result(mcp.callTool(McpSchema.CallToolRequest(name, arguments, mapOf("pipelineRunId" to runId, "conversationId" to "integration"))))

    private fun internal(mcp: McpSyncClient, name: String, arguments: Map<String, Any>) =
        result(mcp.callTool(McpSchema.CallToolRequest(name, arguments)))

    private fun result(result: McpSchema.CallToolResult): com.fasterxml.jackson.databind.JsonNode {
        assertFalse(result.isError() == true, result.content().toString())
        return if (result.structuredContent() != null) mapper.valueToTree(result.structuredContent())
        else mapper.readTree(result.content().filterIsInstance<McpSchema.TextContent>().joinToString("") { it.text() })
    }

    private fun startHttpStub() {
        val dates = listOf("2026-09-28", "2026-09-29", "2026-09-30", "2026-10-01", "2026-10-02")
            .joinToString(",") { "\"$it\"" }
        http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/search") { exchange -> respond(exchange, """{"results":[{"name":"Новосибирск","country":"Россия","latitude":55.03,"longitude":82.92,"timezone":"Asia/Novosibirsk"}]}""") }
            createContext("/v1/forecast") { exchange -> respond(exchange, """{"daily":{"time":[$dates],"weather_code":[1,2,3,61,2],"temperature_2m_min":[1,2,0,-1,3],"temperature_2m_max":[8,9,7,5,10],"precipitation_sum":[0,0,1.2,4.0,0],"precipitation_probability_max":[10,20,60,90,15],"wind_speed_10m_max":[10,12,15,20,11]}}""") }
            start()
        }
    }

    private fun respond(exchange: com.sun.net.httpserver.HttpExchange, body: String) {
        val bytes = body.toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private class PipelineChatModel(private val mapper: ObjectMapper) : ChatModel {
        val calledTools = mutableListOf<String>()
        val artifactIds = mutableListOf<String>()
        var summaryInput: String? = null
        var saveInput: String? = null

        override fun getDefaultOptions() = ToolCallingChatOptions.builder().build()
        override fun getOptions() = ToolCallingChatOptions.builder().build()

        override fun call(prompt: Prompt): ChatResponse {
            check(prompt.options is ToolCallingChatOptions) { "Prompt options are not tool-calling options: ${prompt.options?.javaClass}" }
            val response = prompt.instructions.filterIsInstance<ToolResponseMessage>().lastOrNull()?.responses?.lastOrNull()
            val call = when (calledTools.size) {
                0 -> tool("search_weather_forecast", """{"city":"Новосибирск","days":5}""")
                1 -> {
                    val id = toolResult(response!!.responseData()).path("artifactId").asText()
                    check(id.isNotBlank()) { "Search tool response did not contain artifactId: ${response.responseData()}" }
                    artifactIds += id
                    summaryInput = id
                    tool("summarize_weather_forecast", mapper.writeValueAsString(mapOf("searchArtifactId" to id)))
                }
                2 -> {
                    val id = toolResult(response!!.responseData()).path("artifactId").asText()
                    check(id.isNotBlank()) { "Summary tool response did not contain artifactId: ${response.responseData()}" }
                    artifactIds += id
                    saveInput = id
                    tool("save_weather_report", mapper.writeValueAsString(mapOf("summaryArtifactId" to id, "fileName" to "agent.md")))
                }
                else -> {
                    val reportId = toolResult(response!!.responseData()).path("reportArtifactId").asText()
                    artifactIds += reportId
                    return ChatResponse(listOf(Generation(AssistantMessage("Pipeline saved successfully"))))
                }
            }
            calledTools += call.name()
            val result = ChatResponse(listOf(Generation(AssistantMessage.builder().content("").toolCalls(listOf(call)).build())))
            check(result.hasToolCalls()) { "Stub tool-call response was not recognized" }
            return result
        }

        private fun toolResult(data: String): com.fasterxml.jackson.databind.JsonNode {
            val root = mapper.readTree(data)
            return if (root.isArray) mapper.readTree(root.first().path("text").asText()) else root
        }

        private fun tool(name: String, arguments: String) = AssistantMessage.ToolCall(UUID.randomUUID().toString(), "function", name, arguments)
    }

    companion object {
        private val AGENT_TOOLS = setOf("search_weather_forecast", "summarize_weather_forecast", "save_weather_report")
        private val REQUIRED_TOOLS = AGENT_TOOLS + setOf(
            "create_pipeline_run", "mark_pipeline_agent_started", "fail_pipeline_run",
            "get_pipeline_run", "list_pipeline_events", "get_pipeline_report",
        )
    }
}
