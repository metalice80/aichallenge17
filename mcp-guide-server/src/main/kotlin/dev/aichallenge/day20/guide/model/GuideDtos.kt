package dev.aichallenge.day20.guide.model

import com.fasterxml.jackson.annotation.JsonProperty

data class SearchResponse(val pages: List<SearchPage>? = null)
data class SearchPage(
    val key: String,
    val title: String,
    val excerpt: String? = null,
    val description: String? = null,
)
data class SummaryResponse(
    val title: String,
    val extract: String,
    @JsonProperty("content_urls") val contentUrls: ContentUrls? = null,
)
data class ContentUrls(val desktop: DesktopUrl? = null)
data class DesktopUrl(val page: String? = null)

data class ArticleSearchItem(
    val articleRef: String,
    val title: String,
    val description: String,
    val sourceUrl: String,
)

data class ArticleSearchResult(
    val query: String,
    val articles: List<ArticleSearchItem>,
    val instruction: String = "Select three unique relevant articleRef values and call guide_get_article_summary for each",
)

data class ArticleSummaryResult(
    val articleRef: String,
    val title: String,
    val summary: String,
    val sourceUrl: String,
)
