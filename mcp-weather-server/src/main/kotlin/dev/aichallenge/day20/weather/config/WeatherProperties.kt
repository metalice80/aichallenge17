package dev.aichallenge.day20.weather.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("app.weather")
data class WeatherProperties(
    val geocodingBaseUrl: String,
    val forecastBaseUrl: String,
    val referenceTtl: Duration = Duration.ofMinutes(10),
    val referenceCacheSize: Int = 1000,
    val connectTimeout: Duration = Duration.ofSeconds(3),
    val readTimeout: Duration = Duration.ofSeconds(10),
)
