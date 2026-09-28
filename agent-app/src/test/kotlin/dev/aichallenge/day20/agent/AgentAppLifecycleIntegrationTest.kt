package dev.aichallenge.day20.agent

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.aichallenge.day20.agent.config.McpToolRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path

class AgentAppLifecycleIntegrationTest {
    @Test
    fun `closing agent context terminates all three child processes`() {
        val stub = HttpServer.create(InetSocketAddress(0), 0).apply {
            createContext("/") { it.json("{}") }
            start()
        }
        val baseUrl = "http://127.0.0.1:${stub.address.port}"
        val temp = Files.createTempDirectory("agent-lifecycle-")
        val context = SpringApplicationBuilder(AgentApplication::class.java)
            .web(WebApplicationType.NONE)
            .run(
                "--spring.datasource.url=jdbc:sqlite:${temp.resolve("journal.db")}",
                "--spring.ai.openai.api-key=test",
                "--app.agent.api-key-configured=test",
                "--WEATHER_SERVER_JAR=${jar("mcp-weather-server")}",
                "--GUIDE_SERVER_JAR=${jar("mcp-guide-server")}",
                "--FILES_SERVER_JAR=${jar("mcp-files-server")}",
                "--GEOCODING_BASE_URL=$baseUrl",
                "--FORECAST_BASE_URL=$baseUrl",
                "--MEDIAWIKI_BASE_URL=$baseUrl",
                "--MEDIAWIKI_PUBLIC_BASE_URL=https://ru.wikipedia.org",
                "--REPORTS_DIR=${temp.resolve("reports")}",
            )
        try {
            val registry = context.getBean(McpToolRegistry::class.java)
            assertTrue(registry.ready)
            assertEquals(3, registry.servers.size)
        } finally {
            context.close()
            stub.stop(0)
        }
        repeat(100) {
            if (childProcesses().isEmpty()) return
            Thread.sleep(50)
        }
        assertTrue(childProcesses().isEmpty(), "MCP child processes remained alive after agent context close: ${childProcesses()}")
    }

    private fun childProcesses(): List<String> = ProcessHandle.current().descendants()
        .map { it.info().commandLine().orElse("") }
        .filter { it.contains("mcp-") && it.contains("-server.jar") }
        .toList()

    private fun jar(module: String): String = Path.of("../$module/build/libs/$module.jar").toAbsolutePath().normalize().toString()

    private fun HttpExchange.json(body: String) {
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(200, body.toByteArray().size.toLong())
        responseBody.use { it.write(body.toByteArray()) }
    }
}
