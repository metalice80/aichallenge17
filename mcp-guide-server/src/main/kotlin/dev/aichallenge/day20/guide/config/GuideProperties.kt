package dev.aichallenge.day20.guide.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("app.guide")
data class GuideProperties(
    val baseUrl: String,
    val userAgent: String,
    val sourceBaseUrl: String = baseUrl,
    val referenceTtl: Duration = Duration.ofMinutes(10),
    val referenceCacheSize: Int = 1000,
    val maxSummaryCharacters: Int = 1500,
    val connectTimeout: Duration = Duration.ofSeconds(3),
    val readTimeout: Duration = Duration.ofSeconds(10),
)
