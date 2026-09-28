package dev.aichallenge.day20.guide

import dev.aichallenge.day20.guide.api.MediaWikiClient
import dev.aichallenge.day20.guide.cache.ArticleReferenceStore
import dev.aichallenge.day20.guide.config.GuideProperties
import dev.aichallenge.day20.guide.tool.GuideTools
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.time.Clock

class GuideServerUnitTest {
    private val properties = GuideProperties("https://ru.wikipedia.org", "test-agent", maxSummaryCharacters = 40)
    private val tools = GuideTools(mock(MediaWikiClient::class.java), ArticleReferenceStore(properties, Clock.systemUTC()), properties)

    @Test
    fun `html is stripped and whitespace normalized`() {
        assertEquals("Казанский кремль & музей", tools.stripHtml("<b>Казанский</b>   кремль &amp; музей"))
    }

    @Test
    fun `summary truncation prefers sentence boundary`() {
        val value = "Первое предложение достаточно длинное. Второе предложение не должно войти полностью."
        val truncated = tools.truncateAtSentence(value, 45)
        assertEquals("Первое предложение достаточно длинное.", truncated)
        assertFalse(truncated.contains("Второе"))
    }
}
