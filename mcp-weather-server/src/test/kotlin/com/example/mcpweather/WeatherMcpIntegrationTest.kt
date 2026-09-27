package com.example.mcpweather

import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.transport.ServerParameters
import io.modelcontextprotocol.client.transport.StdioClientTransport
import io.modelcontextprotocol.json.McpJsonDefaults
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.junit.jupiter.api.assertTimeoutPreemptively
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

@Tag("integration")
class WeatherMcpIntegrationTest {
    @Test
    fun `real stdio server initializes lists tool and returns structured weather`() {
        val jar = Path.of(requireNotNull(System.getProperty("mcp.server.jar")))
        assertTrue(Files.isRegularFile(jar), "Server jar must exist: $jar")

        TestWeatherApiStub().use { stub ->
            val parameters = ServerParameters.builder(javaCommand())
                .args("-jar", jar.toString())
                .addEnvVar("OPEN_METEO_GEOCODING_URL", stub.geocodingUri.toString())
                .addEnvVar("OPEN_METEO_FORECAST_URL", stub.forecastUri.toString())
                .build()
            val transport = StdioClientTransport(parameters, McpJsonDefaults.getMapper())
            val stderr = CopyOnWriteArrayList<String>()
            transport.setStdErrorHandler {
                stderr.add(it)
                System.err.println(it)
            }
            val client = McpClient.sync(transport)
                .initializationTimeout(Duration.ofSeconds(20))
                .requestTimeout(Duration.ofSeconds(20))
                .build()

            try {
                val initialized = client.initialize()
                assertEquals("weather-mcp-server", initialized.serverInfo().name())

                val tools = client.listTools().tools()
                assertEquals(1, tools.size)
                val tool = tools.single()
                assertAll(
                    { assertEquals("get_current_weather", tool.name()) },
                    { assertTrue(tool.description().contains("Open-Meteo")) },
                    { assertTrue((tool.inputSchema()["required"] as List<*>).contains("city")) },
                    { assertTrue((tool.inputSchema()["properties"] as Map<*, *>).containsKey("city")) },
                    { assertNotNull(tool.outputSchema()) },
                )

                val success = client.callTool(
                    CallToolRequest("get_current_weather", mapOf("city" to "Тестоград")),
                )
                assertFalse(success.isError() == true)
                val weather = success.structuredContent() as Map<*, *>
                assertAll(
                    { assertEquals("Тестоград", weather["city"]) },
                    { assertEquals(8.4, weather["temperatureCelsius"]) },
                    { assertEquals(6.1, weather["apparentTemperatureCelsius"]) },
                    { assertEquals(14.2, weather["windSpeedKmh"]) },
                    { assertEquals(3, weather["weatherCode"]) },
                )

                val missing = client.callTool(
                    CallToolRequest("get_current_weather", mapOf("city" to "Unknown")),
                )
                assertTrue(missing.isError() == true)
                assertTrue(missing.content().joinToString().contains("City not found: Unknown"))
                assertTrue(stderr.any { it.contains("get_current_weather") })
            } finally {
                client.closeGracefully()
                assertTimeoutPreemptively(Duration.ofSeconds(5)) {
                    transport.awaitForExit()
                }
            }
        }
    }

    private fun javaCommand(): String =
        Path.of(System.getProperty("java.home"), "bin", if (isWindows()) "java.exe" else "java").toString()

    private fun isWindows(): Boolean = System.getProperty("os.name").lowercase().contains("win")
}
