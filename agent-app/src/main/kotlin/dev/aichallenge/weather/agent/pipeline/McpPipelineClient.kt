package dev.aichallenge.weather.agent.pipeline

import com.fasterxml.jackson.databind.ObjectMapper
import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.spec.McpSchema
import org.springframework.stereotype.Component
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.TimeoutException
import kotlin.concurrent.withLock

@Component
class McpPipelineClient(
    clients: List<McpSyncClient>,
    private val objectMapper: ObjectMapper,
) {
    private val client = clients.singleOrNull()
        ?: error("Exactly one MCP pipeline server connection is required; found ${clients.size}")
    private val callLock = ReentrantLock()

    fun <T : Any> call(name: String, arguments: Map<String, Any?>, type: Class<T>, meta: Map<String, Any> = emptyMap()): T =
        callLock.withLock {
            try {
                val request = McpSchema.CallToolRequest(name, arguments, meta)
                val result = client.callTool(request)
                if (result.isError() == true) throw McpCallException(name, text(result).take(1000))
                val structured = result.structuredContent()
                if (structured != null) return@withLock objectMapper.convertValue(structured, type)
                objectMapper.readValue(text(result), type)
            } catch (ex: McpCallException) {
                throw ex
            } catch (ex: RuntimeException) {
                if (ex.hasCause<TimeoutException>()) {
                    throw McpCallException(name, "MCP server did not respond within the configured timeout")
                }
                throw ex
            }
        }

    fun toolNames(): Set<String> = callLock.withLock { client.listTools().tools().map { it.name() }.toSet() }

    private fun text(result: McpSchema.CallToolResult): String = result.content()
        .filterIsInstance<McpSchema.TextContent>()
        .joinToString("\n") { it.text() }
}

class McpCallException(val toolName: String, val safeMessage: String) :
    RuntimeException("MCP tool $toolName failed: $safeMessage")

private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean =
    generateSequence(this) { it.cause }.any { it is T }
