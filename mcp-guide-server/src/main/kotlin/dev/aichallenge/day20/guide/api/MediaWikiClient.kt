package dev.aichallenge.day20.guide.api

import dev.aichallenge.day20.guide.cache.GuideToolException
import dev.aichallenge.day20.guide.config.GuideProperties
import dev.aichallenge.day20.guide.model.SearchPage
import dev.aichallenge.day20.guide.model.SearchResponse
import dev.aichallenge.day20.guide.model.SummaryResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.net.http.HttpClient

@Component
class MediaWikiClient(properties: GuideProperties) {
    private val client: RestClient

    init {
        val http = HttpClient.newBuilder().connectTimeout(properties.connectTimeout).build()
        val requestFactory = JdkClientHttpRequestFactory(http).apply { setReadTimeout(properties.readTimeout) }
        client = RestClient.builder()
            .baseUrl(properties.baseUrl)
            .defaultHeader(HttpHeaders.USER_AGENT, properties.userAgent)
            .requestFactory(requestFactory)
            .build()
    }

    fun search(query: String, limit: Int): List<SearchPage> {
        return try {
            client.get()
                .uri { it.path("/w/rest.php/v1/search/page").queryParam("q", query).queryParam("limit", limit).build() }
                .retrieve()
                .body(SearchResponse::class.java)
                ?.pages.orEmpty()
                .ifEmpty { throw GuideToolException("GUIDE_NOT_FOUND", "No guide articles found") }
        } catch (error: GuideToolException) {
            throw error
        } catch (error: Exception) {
            throw GuideToolException("GUIDE_SEARCH_UNAVAILABLE", "MediaWiki search is unavailable")
        }
    }

    fun summary(pageKey: String): SummaryResponse {
        return try {
            client.get()
                .uri { it.path("/api/rest_v1/page/summary/{pageKey}").build(pageKey) }
                .retrieve()
                .body(SummaryResponse::class.java)
                ?: throw GuideToolException("INVALID_ARTICLE_RESPONSE", "MediaWiki returned no article summary")
        } catch (error: GuideToolException) {
            throw error
        } catch (error: Exception) {
            throw GuideToolException("ARTICLE_UNAVAILABLE", "MediaWiki article is unavailable")
        }
    }
}
