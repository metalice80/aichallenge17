package com.example.mcpweather.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI
import java.time.Duration

@ConfigurationProperties(prefix = "app.open-meteo")
data class OpenMeteoProperties(
    val geocodingUrl: URI,
    val forecastUrl: URI,
    val connectTimeout: Duration,
    val requestTimeout: Duration,
) {
    init {
        validateHttpUrl("geocoding-url", geocodingUrl)
        validateHttpUrl("forecast-url", forecastUrl)
        require(!connectTimeout.isZero && !connectTimeout.isNegative) {
            "app.open-meteo.connect-timeout must be positive"
        }
        require(!requestTimeout.isZero && !requestTimeout.isNegative) {
            "app.open-meteo.request-timeout must be positive"
        }
    }

    private fun validateHttpUrl(name: String, uri: URI) {
        require(uri.isAbsolute && uri.host != null && uri.scheme.lowercase() in setOf("http", "https")) {
            "app.open-meteo.$name must be an absolute HTTP/HTTPS URL"
        }
    }
}
