package dev.aichallenge.day20.agent.orchestration

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.aichallenge.day20.agent.repository.InvocationStatus
import dev.aichallenge.day20.agent.repository.OrchestrationEventRepository
import dev.aichallenge.day20.agent.repository.ToolInvocationRepository
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.definition.ToolDefinition
import org.springframework.ai.tool.metadata.ToolMetadata
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

class GuardedToolCallback(
    private val delegate: ToolCallback,
    private val policy: RoutingPolicy,
    private val invocations: ToolInvocationRepository,
    private val events: OrchestrationEventRepository,
    private val objectMapper: ObjectMapper,
    private val observer: OrchestrationObserver,
) : ToolCallback {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val toolName = delegate.toolDefinition.name()
    private val serverName = when {
        toolName.startsWith("weather_") -> "weather-server"
        toolName.startsWith("guide_") -> "guide-server"
        toolName.startsWith("files_") -> "files-server"
        else -> "unknown-server"
    }

    override fun getToolDefinition(): ToolDefinition = delegate.toolDefinition
    override fun getToolMetadata(): ToolMetadata = delegate.toolMetadata
    override fun call(toolInput: String): String = missingContext()

    override fun call(toolInput: String, toolContext: ToolContext?): String {
        val runId = toolContext?.context?.get("orchestrationRunId") as? String ?: return missingContext()
        val toolCallId = toolContext.context["toolCallId"] as? String
        val input = try { objectMapper.readTree(toolInput) } catch (_: Exception) {
            return errorJson("VALIDATION_ERROR", "Tool arguments are not valid JSON", false)
        }
        val decision = policy.validateBeforeCall(runId, toolName, input)
        val invocation = invocations.start(runId, serverName, toolName, toolCallId, hash(toolInput), inputRefs(input))
        events.append(runId, "TOOL_REQUESTED", serverName, toolName, objectMapper.writeValueAsString(mapOf("invocationId" to invocation.id, "sequence" to invocation.sequenceNumber)))
        putMdc(runId, invocation.id, invocation.sequenceNumber, toolCallId)
        val started = Instant.now()
        try {
            if (!decision.allowed) {
                val response = errorJson(decision.code!!, decision.message!!, decision.retryable)
                invocations.finish(invocation.id, InvocationStatus.REJECTED, hash(response), "{}", decision.code, decision.message)
                events.append(runId, "TOOL_REJECTED", serverName, toolName, objectMapper.writeValueAsString(mapOf("code" to decision.code, "message" to decision.message)))
                observer.routingRejected(toolName, decision.code)
                observer.toolCompleted(serverName, toolName, "REJECTED", Duration.between(started, Instant.now()))
                logger.warn("event=orchestration.tool.rejected reason={}", decision.code)
                return response
            }
            events.append(runId, "TOOL_ALLOWED", serverName, toolName)
            val result = delegate.call(toolInput, toolContext)
            val resultNode = normalizeResult(result)
            val outputRefs = outputRefs(toolName, resultNode)
            invocations.finish(invocation.id, InvocationStatus.SUCCEEDED, hash(result), objectMapper.writeValueAsString(outputRefs))
            events.append(runId, "TOOL_SUCCEEDED", serverName, toolName, objectMapper.writeValueAsString(mapOf("invocationId" to invocation.id)))
            events.append(runId, "EVIDENCE_RECORDED", serverName, toolName, objectMapper.writeValueAsString(outputRefs))
            if (toolName == "files_save_markdown_report") events.append(runId, "REPORT_SAVED", serverName, toolName, objectMapper.writeValueAsString(outputRefs))
            observer.toolCompleted(serverName, toolName, "SUCCEEDED", Duration.between(started, Instant.now()))
            logger.info("event=orchestration.tool.completed status=SUCCEEDED durationMs={}", Duration.between(started, Instant.now()).toMillis())
            return result
        } catch (exception: Exception) {
            val code = extractErrorCode(exception.message)
            invocations.finish(invocation.id, InvocationStatus.FAILED, null, null, code, safeMessage(exception))
            events.append(runId, "TOOL_FAILED", serverName, toolName, objectMapper.writeValueAsString(mapOf("code" to code, "message" to safeMessage(exception))))
            observer.toolCompleted(serverName, toolName, "FAILED", Duration.between(started, Instant.now()))
            observer.externalFailure(serverName, code)
            logger.error("event=orchestration.tool.failed code={}", code, exception)
            return errorJson(code, safeMessage(exception), false)
        } finally {
            MDC.clear()
        }
    }

    private fun normalizeResult(result: String): JsonNode {
        val root = objectMapper.readTree(result)
        if (root.has("structuredContent")) return root.path("structuredContent")
        val text = when {
            root.isArray -> root.firstOrNull()?.path("text")?.asText()
            root.path("content").isArray -> root.path("content").firstOrNull()?.path("text")?.asText()
            else -> null
        }
        return if (!text.isNullOrBlank() && text.trimStart().startsWith("{")) objectMapper.readTree(text) else root
    }

    private fun outputRefs(tool: String, node: JsonNode): Map<String, Any> = when (tool) {
        "weather_resolve_location" -> mapOf("locationRefs" to listOf(requiredText(node, "locationRef")))
        "weather_get_forecast" -> mapOf("forecastLocationRefs" to listOf(requiredText(node, "locationRef")))
        "guide_search_articles" -> mapOf("searchedArticles" to node.path("articles").associate { requiredText(it, "articleRef") to requiredText(it, "sourceUrl") })
        "guide_get_article_summary" -> mapOf("summarizedArticles" to mapOf(requiredText(node, "articleRef") to requiredText(node, "sourceUrl")))
        "files_save_markdown_report" -> {
            require(requiredText(node, "status") == "SAVED") { "FILE_WRITE_FAILED: File server did not return SAVED" }
            mapOf("reportPath" to requiredText(node, "relativePath"), "saved" to true)
        }
        else -> error("MCP_TOOL_MISSING: Unknown tool")
    }

    private fun requiredText(node: JsonNode, field: String): String = node.path(field).asText().takeIf { it.isNotBlank() }
        ?: error("INTERNAL_ERROR: Tool result is missing $field")

    private fun inputRefs(input: JsonNode): String {
        val refs = mutableMapOf<String, Any>()
        listOf("locationRef", "weatherLocationRef", "articleRef").forEach { name -> input.path(name).takeIf { !it.isMissingNode }?.asText()?.let { refs[name] = it } }
        if (input.path("articleRefs").isArray) refs["articleRefs"] = input.path("articleRefs").map { it.asText() }
        return objectMapper.writeValueAsString(refs)
    }

    private fun putMdc(runId: String, invocationId: String, sequence: Int, toolCallId: String?) {
        MDC.put("runId", runId); MDC.put("invocationId", invocationId); MDC.put("serverName", serverName); MDC.put("toolName", toolName)
        MDC.put("sequenceNumber", sequence.toString()); if (toolCallId != null) MDC.put("toolCallId", toolCallId)
    }

    private fun missingContext() = errorJson("MISSING_ORCHESTRATION_CONTEXT", "orchestrationRunId ToolContext is required", false)
    private fun errorJson(code: String, message: String, retryable: Boolean) = objectMapper.writeValueAsString(mapOf("success" to false, "code" to code, "message" to message, "retryable" to retryable, "server" to serverName, "tool" to toolName))
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun extractErrorCode(message: String?): String = Regex("([A-Z][A-Z0-9_]{2,})[: ]").find(message.orEmpty())?.groupValues?.get(1) ?: "INTERNAL_ERROR"
    private fun safeMessage(error: Exception): String = (error.message ?: "Tool execution failed").take(500)
}
