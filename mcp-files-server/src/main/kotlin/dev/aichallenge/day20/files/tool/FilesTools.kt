package dev.aichallenge.day20.files.tool

import dev.aichallenge.day20.files.model.SaveReportResult
import dev.aichallenge.day20.files.service.FileToolException
import dev.aichallenge.day20.files.service.SafeMarkdownWriter
import org.springframework.ai.mcp.annotation.McpMeta
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component

@Component
class FilesTools(private val writer: SafeMarkdownWriter) {
    @McpTool(
        name = "files_save_markdown_report",
        description = "Final side effect: atomically save the complete Markdown report only after forecast and three unique article summaries exist. Supply exact evidence references and source URLs.",
        generateOutputSchema = true,
        annotations = McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true, openWorldHint = false),
    )
    fun saveMarkdownReport(
        @McpToolParam(description = "Safe Markdown basename ending in .md", required = true) fileName: String,
        @McpToolParam(description = "Complete Markdown report content", required = true) content: String,
        @McpToolParam(description = "Exact locationRef used for the successful forecast", required = true) weatherLocationRef: String,
        @McpToolParam(description = "Exactly three unique summarized articleRef values", required = true) articleRefs: List<String>,
        @McpToolParam(description = "HTTPS source URLs returned by guide tools", required = true) sourceUrls: List<String>,
        meta: McpMeta,
    ): SaveReportResult {
        val runId = meta.get("orchestrationRunId") as? String
        if (runId.isNullOrBlank()) throw FileToolException("MISSING_ORCHESTRATION_CONTEXT", "orchestrationRunId metadata is required")
        return writer.save(fileName, content, weatherLocationRef, articleRefs, sourceUrls)
    }
}
