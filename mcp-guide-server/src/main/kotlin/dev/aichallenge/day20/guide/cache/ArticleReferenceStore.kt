package dev.aichallenge.day20.guide.cache

import dev.aichallenge.day20.guide.config.GuideProperties
import dev.aichallenge.day20.guide.model.SearchPage
import org.springframework.stereotype.Component
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Component
class ArticleReferenceStore(
    private val properties: GuideProperties,
    private val clock: Clock,
) {
    data class Entry(
        val reference: String,
        val runId: String,
        val pageKey: String,
        val title: String,
        val description: String,
        val sourceUrl: String,
        val expiresAt: Instant,
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    internal fun create(runId: String, page: SearchPage, description: String): Entry {
        purgeExpired()
        if (entries.size >= properties.referenceCacheSize) {
            entries.entries.minByOrNull { it.value.expiresAt }?.let { entries.remove(it.key, it.value) }
        }
        val reference = "article-${UUID.randomUUID()}"
        val base = properties.sourceBaseUrl.trimEnd('/')
        val encoded = URLEncoder.encode(page.key, StandardCharsets.UTF_8).replace("+", "%20")
        return Entry(reference, runId, page.key, page.title, description, "$base/wiki/$encoded", clock.instant().plus(properties.referenceTtl))
            .also { entries[reference] = it }
    }

    fun require(reference: String, runId: String): Entry {
        val entry = entries[reference] ?: throw GuideToolException("ARTICLE_REF_NOT_FOUND", "Unknown articleRef")
        if (!clock.instant().isBefore(entry.expiresAt)) {
            entries.remove(reference, entry)
            throw GuideToolException("ARTICLE_REF_EXPIRED", "articleRef has expired")
        }
        if (entry.runId != runId) throw GuideToolException("ARTICLE_REF_FROM_ANOTHER_RUN", "articleRef belongs to another orchestration run")
        return entry
    }

    fun size(): Int {
        purgeExpired()
        return entries.size
    }

    private fun purgeExpired() {
        val now = clock.instant()
        entries.entries.removeIf { !now.isBefore(it.value.expiresAt) }
    }
}

class GuideToolException(val code: String, message: String) : RuntimeException("$code: $message")
