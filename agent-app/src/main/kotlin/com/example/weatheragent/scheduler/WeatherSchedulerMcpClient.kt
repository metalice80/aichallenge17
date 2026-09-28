package com.example.weatheragent.scheduler

import jakarta.annotation.PreDestroy
import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

@Component
class WeatherSchedulerMcpClient(
    clients: List<McpSyncClient>,
    private val objectMapper: ObjectMapper,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val client: McpSyncClient
    private val closed = AtomicBoolean(false)
    private val callLock = ReentrantLock()

    init {
        val matching = clients.filter { candidate ->
            candidate.isInitialized && runCatching {
                candidate.serverInfo.name() == EXPECTED_SERVER_NAME
            }.getOrDefault(false)
        }
        check(matching.size == 1) {
            "Expected exactly one initialized MCP client for $EXPECTED_SERVER_NAME, found ${matching.size}"
        }
        client = matching.single()
        logger.info("Selected initialized MCP server {}", client.serverInfo.name())
    }

    fun claimPendingSummaries(workerId: String, limit: Int): List<WeatherSummary> =
        call(
            "claim_pending_weather_summaries",
            mapOf("workerId" to workerId, "limit" to limit),
            SummaryListResult::class.java,
        ).summaries

    fun completeSummary(summaryId: String, workerId: String, renderedText: String): SummaryOperationResult =
        call(
            "complete_weather_summary_delivery",
            mapOf("summaryId" to summaryId, "workerId" to workerId, "renderedText" to renderedText),
            SummaryOperationResult::class.java,
        )

    fun failSummary(summaryId: String, workerId: String, error: String): SummaryOperationResult =
        call(
            "fail_weather_summary_delivery",
            mapOf("summaryId" to summaryId, "workerId" to workerId, "error" to error.take(500)),
            SummaryOperationResult::class.java,
        )

    fun listSchedules(): List<ScheduleView> =
        call("list_weather_schedules", emptyMap(), ScheduleListResult::class.java).schedules

    fun listDeliveredSummaries(after: InstantCursor?, limit: Int): List<WeatherSummary> {
        val arguments = linkedMapOf<String, Any>("limit" to limit)
        after?.value?.let { arguments["after"] = it }
        return call("list_delivered_weather_summaries", arguments, SummaryListResult::class.java).summaries
    }

    private fun <T> call(name: String, arguments: Map<String, Any>, responseType: Class<T>): T =
        callLock.withLock {
            val result = try {
                client.callTool(CallToolRequest(name, arguments))
            } catch (exception: RuntimeException) {
                throw WeatherSchedulerMcpException("MCP request failed for $name", exception)
            }
            if (result.isError == true) {
                throw WeatherSchedulerMcpException("MCP tool $name returned an error")
            }
            val structured = result.structuredContent
                ?: throw WeatherSchedulerMcpException("MCP tool $name returned no structured content")
            try {
                objectMapper.convertValue(structured, responseType)
            } catch (exception: RuntimeException) {
                throw WeatherSchedulerMcpException("MCP tool $name returned an invalid response", exception)
            }
        }

    @PreDestroy
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        logger.info("Closing weather MCP client and child process")
        if (!client.closeGracefully()) {
            logger.warn("Weather MCP client did not complete graceful shutdown")
        }
    }

    companion object {
        const val EXPECTED_SERVER_NAME = "weather-mcp-server"
    }
}

@JvmInline
value class InstantCursor(val value: String)

class WeatherSchedulerMcpException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
