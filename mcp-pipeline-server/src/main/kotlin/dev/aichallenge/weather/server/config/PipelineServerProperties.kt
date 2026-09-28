package dev.aichallenge.weather.server.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("app")
data class PipelineServerProperties(
    var reports: Reports = Reports(),
    var weather: Weather = Weather(),
    var pipeline: Pipeline = Pipeline(),
) {
    data class Reports(var directory: String = "./reports")
    data class Weather(
        var geocodingBaseUrl: String = "https://geocoding-api.open-meteo.com",
        var forecastBaseUrl: String = "https://api.open-meteo.com",
        var connectTimeout: Duration = Duration.ofSeconds(3),
        var readTimeout: Duration = Duration.ofSeconds(10),
    )
    data class Pipeline(var staleRunTimeout: Duration = Duration.ofMinutes(5))
}
