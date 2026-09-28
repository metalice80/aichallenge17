package dev.aichallenge.day20.guide.tool

import dev.aichallenge.day20.guide.api.MediaWikiClient
import dev.aichallenge.day20.guide.cache.ArticleReferenceStore
import dev.aichallenge.day20.guide.cache.GuideToolException
import dev.aichallenge.day20.guide.config.GuideProperties
import dev.aichallenge.day20.guide.model.ArticleSearchItem
import dev.aichallenge.day20.guide.model.ArticleSearchResult
import dev.aichallenge.day20.guide.model.ArticleSummaryResult
import org.springframework.ai.mcp.annotation.McpMeta
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component

@Component
class GuideTools(
    private val client: MediaWikiClient,
    private val references: ArticleReferenceStore,
    private val properties: GuideProperties,
) {
    @McpTool(
        name = "guide_search_articles",
        description = "Search destination articles in MediaWiki and return opaque articleRef values. Select three unique relevant refs for guide_get_article_summary.",
        generateOutputSchema = true,
        annotations = McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = false, openWorldHint = true),
    )
    fun searchArticles(
        @McpToolParam(description = "Destination city", required = true) city: String,
        @McpToolParam(description = "Guide topic such as attractions", required = true) query: String,
        @McpToolParam(description = "Number of candidates, from 3 to 8", required = true) limit: Int,
        meta: McpMeta,
    ): ArticleSearchResult {
        val normalizedCity = validateText(city, "city")
        val normalizedQuery = validateText(query, "query")
        if (limit !in 3..8) throw GuideToolException("INVALID_GUIDE_QUERY", "limit must be between 3 and 8")
        val runId = requireRunId(meta)
        val fullQuery = "$normalizedCity $normalizedQuery"
        val articles = client.search(fullQuery, limit).map { page ->
            val description = stripHtml(page.description ?: page.excerpt.orEmpty()).take(400)
            references.create(runId, page, description).let { entry ->
                ArticleSearchItem(entry.reference, entry.title, entry.description, entry.sourceUrl)
            }
        }
        return ArticleSearchResult(fullQuery, articles)
    }

    @McpTool(
        name = "guide_get_article_summary",
        description = "Get a concise MediaWiki summary using an exact articleRef returned by guide_search_articles in this run.",
        generateOutputSchema = true,
        annotations = McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = true),
    )
    fun getArticleSummary(
        @McpToolParam(description = "Exact opaque articleRef returned by guide_search_articles", required = true) articleRef: String,
        meta: McpMeta,
    ): ArticleSummaryResult {
        val entry = references.require(articleRef, requireRunId(meta))
        val response = client.summary(entry.pageKey)
        val summary = truncateAtSentence(stripHtml(response.extract), properties.maxSummaryCharacters)
        if (summary.isBlank()) throw GuideToolException("INVALID_ARTICLE_RESPONSE", "Article summary is empty")
        return ArticleSummaryResult(articleRef, response.title.ifBlank { entry.title }, summary, entry.sourceUrl)
    }

    internal fun stripHtml(value: String): String = value
        .replace(Regex("<[^>]+>"), " ")
        .replace("&quot;", "\"")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&#39;", "'")
        .replace(Regex("\\s+"), " ")
        .trim()

    internal fun truncateAtSentence(value: String, maximum: Int): String {
        if (value.length <= maximum) return value
        val candidate = value.substring(0, maximum)
        val boundary = maxOf(candidate.lastIndexOf('.'), candidate.lastIndexOf('!'), candidate.lastIndexOf('?'))
        return if (boundary >= maximum / 2) candidate.substring(0, boundary + 1) else candidate.trimEnd() + "…"
    }

    private fun validateText(value: String, field: String): String {
        val normalized = value.trim()
        if (normalized.isEmpty() || normalized.length > 120 || normalized.any { it.isISOControl() }) {
            throw GuideToolException("INVALID_GUIDE_QUERY", "$field must contain 1 to 120 safe characters")
        }
        return normalized
    }

    private fun requireRunId(meta: McpMeta): String = (meta.get("orchestrationRunId") as? String)?.takeIf { it.isNotBlank() }
        ?: throw GuideToolException("MISSING_ORCHESTRATION_CONTEXT", "orchestrationRunId metadata is required")
}
