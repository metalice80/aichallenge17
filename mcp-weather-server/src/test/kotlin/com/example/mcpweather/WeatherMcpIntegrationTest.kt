package com.example.mcpweather

import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.transport.ServerParameters
import io.modelcontextprotocol.client.transport.StdioClientTransport
import io.modelcontextprotocol.json.McpJsonDefaults
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest
import io.modelcontextprotocol.spec.McpError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertTimeoutPreemptively
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

@Tag("integration")
class WeatherMcpIntegrationTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `real stdio server supports durable scheduler tools and closes child process`() {
        val jar = Path.of(requireNotNull(System.getProperty("mcp.server.jar")))
        assertTrue(Files.isRegularFile(jar), "Server jar must exist: $jar")
        val database = tempDir.resolve("stdio-integration.db")
        Files.deleteIfExists(database)

        TestWeatherApiStub().use { stub ->
            val parameters = ServerParameters.builder(javaCommand())
                .args("-jar", jar.toString())
                .addEnvVar("WEATHER_DB_PATH", database.toAbsolutePath().toString())
                .addEnvVar("WEATHER_SCHEDULER_ENABLED", "false")
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
                val names = tools.map { it.name() }.toSet()
                assertEquals(
                    setOf(
                        "get_current_weather",
                        "schedule_weather_summary",
                        "get_weather_schedule_status",
                        "get_latest_weather_summary",
                        "cancel_weather_schedule",
                        "claim_pending_weather_summaries",
                        "complete_weather_summary_delivery",
                        "fail_weather_summary_delivery",
                        "list_weather_schedules",
                        "list_delivered_weather_summaries",
                    ),
                    names,
                )
                val scheduleTool = tools.single { it.name() == "schedule_weather_summary" }
                assertEquals(
                    setOf("city", "collectionIntervalMinutes", "summaryIntervalMinutes"),
                    (scheduleTool.inputSchema()["required"] as List<*>).toSet(),
                )
                val properties = scheduleTool.inputSchema()["properties"] as Map<*, *>
                val collectionSchema = properties["collectionIntervalMinutes"] as Map<*, *>
                val summarySchema = properties["summaryIntervalMinutes"] as Map<*, *>
                assertEquals(1, collectionSchema["minimum"])
                assertEquals(1440, collectionSchema["maximum"])
                assertEquals(5, summarySchema["minimum"])
                assertEquals(10080, summarySchema["maximum"])
                assertEquals(false, scheduleTool.inputSchema()["additionalProperties"])
                assertNotNull(scheduleTool.outputSchema())

                val weather = client.callTool(CallToolRequest("get_current_weather", mapOf("city" to "Тестоград")))
                assertFalse(weather.isError() == true)
                assertEquals(8.4, (weather.structuredContent() as Map<*, *>)["temperatureCelsius"])

                val scheduleArguments = mapOf(
                    "city" to "Тестоград",
                    "collectionIntervalMinutes" to 1,
                    "summaryIntervalMinutes" to 5,
                )
                val created = client.callTool(CallToolRequest("schedule_weather_summary", scheduleArguments))
                assertFalse(created.isError() == true)
                val scheduleId = (created.structuredContent() as Map<*, *>)["scheduleId"] as String
                val duplicate = client.callTool(CallToolRequest("schedule_weather_summary", scheduleArguments))
                assertEquals(scheduleId, (duplicate.structuredContent() as Map<*, *>)["scheduleId"])

                assertThrows<McpError> {
                    client.callTool(
                        CallToolRequest(
                            "schedule_weather_summary",
                            mapOf("city" to "Тестоград", "collectionIntervalMinutes" to 10, "summaryIntervalMinutes" to 5),
                        ),
                    )
                }

                val status = client.callTool(
                    CallToolRequest("get_weather_schedule_status", mapOf("scheduleId" to scheduleId)),
                )
                assertEquals("ACTIVE", (status.structuredContent() as Map<*, *>)["status"])

                val summaryId = seedPendingSummary(database, scheduleId)
                val claimed = client.callTool(
                    CallToolRequest(
                        "claim_pending_weather_summaries",
                        mapOf("workerId" to "integration-worker", "limit" to 5),
                    ),
                )
                val claimedSummaries = (claimed.structuredContent() as Map<*, *>)["summaries"] as List<*>
                assertEquals(summaryId, (claimedSummaries.single() as Map<*, *>)["id"])

                val completed = client.callTool(
                    CallToolRequest(
                        "complete_weather_summary_delivery",
                        mapOf(
                            "summaryId" to summaryId,
                            "workerId" to "integration-worker",
                            "renderedText" to "Интеграционная погодная сводка",
                        ),
                    ),
                )
                assertEquals("DELIVERED", (completed.structuredContent() as Map<*, *>)["status"])
                val latest = client.callTool(
                    CallToolRequest("get_latest_weather_summary", mapOf("scheduleId" to scheduleId)),
                )
                assertEquals("DELIVERED", (latest.structuredContent() as Map<*, *>)["status"])

                val cancelled = client.callTool(
                    CallToolRequest("cancel_weather_schedule", mapOf("scheduleId" to scheduleId)),
                )
                assertEquals(false, (cancelled.structuredContent() as Map<*, *>)["alreadyCancelled"])
                val cancelledAgain = client.callTool(
                    CallToolRequest("cancel_weather_schedule", mapOf("scheduleId" to scheduleId)),
                )
                assertEquals(true, (cancelledAgain.structuredContent() as Map<*, *>)["alreadyCancelled"])
                assertTrue(stderr.any { it.contains("SQLite path") })
            } finally {
                client.closeGracefully()
                assertTimeoutPreemptively(Duration.ofSeconds(5)) {
                    transport.awaitForExit()
                }
            }
        }

        Files.deleteIfExists(database)
        Files.deleteIfExists(Path.of("$database-wal"))
        Files.deleteIfExists(Path.of("$database-shm"))
        assertFalse(Files.exists(database))
        assertFalse(Files.exists(Path.of("$database-wal")))
        assertFalse(Files.exists(Path.of("$database-shm")))
    }

    private fun seedPendingSummary(database: Path, scheduleId: String): String {
        val summaryId = UUID.randomUUID().toString()
        DriverManager.getConnection("jdbc:sqlite:${database.toAbsolutePath()}").use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO weather_summary (
                    id, schedule_id, period_started_at, period_ended_at, sample_count,
                    min_temperature_celsius, max_temperature_celsius, avg_temperature_celsius,
                    max_wind_speed_kmh, latest_weather_code, delivery_status, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, summaryId)
                statement.setString(2, scheduleId)
                statement.setString(3, "2026-09-28T11:00:00Z")
                statement.setString(4, "2026-09-28T12:00:00Z")
                statement.setInt(5, 2)
                statement.setDouble(6, 6.0)
                statement.setDouble(7, 9.0)
                statement.setDouble(8, 7.5)
                statement.setDouble(9, 14.0)
                statement.setInt(10, 3)
                statement.setString(11, "2026-09-28T12:00:00Z")
                assertEquals(1, statement.executeUpdate())
            }
        }
        return summaryId
    }

    private fun javaCommand(): String =
        Path.of(System.getProperty("java.home"), "bin", if (isWindows()) "java.exe" else "java").toString()

    private fun isWindows(): Boolean = System.getProperty("os.name").lowercase().contains("win")
}
