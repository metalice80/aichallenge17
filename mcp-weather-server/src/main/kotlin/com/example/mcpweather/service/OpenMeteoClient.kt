package com.example.mcpweather.service

import com.example.mcpweather.config.OpenMeteoProperties
import com.example.mcpweather.model.ForecastResponse
import com.example.mcpweather.model.GeocodingResponse
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.util.UriComponentsBuilder
import tools.jackson.databind.ObjectMapper
import java.io.IOException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.system.measureTimeMillis

@Component
class OpenMeteoClient(
    private val properties: OpenMeteoProperties,
    private val objectMapper: ObjectMapper,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(properties.connectTimeout)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    fun geocode(city: String): GeocodingResponse {
        val uri = UriComponentsBuilder.fromUri(properties.geocodingUrl)
            .queryParam("name", city)
            .queryParam("count", 1)
            .queryParam("language", "ru")
            .queryParam("format", "json")
            .build()
            .encode()
            .toUri()
        return get(uri, GeocodingResponse::class.java, "geocoding")
    }

    fun currentWeather(latitude: Double, longitude: Double): ForecastResponse {
        val uri = UriComponentsBuilder.fromUri(properties.forecastUrl)
            .queryParam("latitude", latitude)
            .queryParam("longitude", longitude)
            .queryParam(
                "current",
                "temperature_2m,apparent_temperature,weather_code,wind_speed_10m",
            )
            .queryParam("timezone", "auto")
            .build()
            .encode()
            .toUri()
        return get(uri, ForecastResponse::class.java, "forecast")
    }

    private fun <T> get(uri: java.net.URI, responseType: Class<T>, operation: String): T {
        val request = HttpRequest.newBuilder(uri)
            .timeout(properties.requestTimeout)
            .header("Accept", "application/json")
            .GET()
            .build()

        lateinit var response: HttpResponse<String>
        val elapsed = try {
            measureTimeMillis {
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            }
        } catch (exception: java.net.http.HttpTimeoutException) {
            logger.warn("Open-Meteo {} timed out", operation)
            throw OpenMeteoException("Open-Meteo $operation request timed out", exception)
        } catch (exception: IOException) {
            logger.warn("Open-Meteo {} request failed: {}", operation, exception.javaClass.simpleName)
            throw OpenMeteoException("Open-Meteo $operation request failed", exception)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw OpenMeteoException("Open-Meteo $operation request was interrupted", exception)
        }

        logger.info("Open-Meteo {} returned HTTP {} in {} ms", operation, response.statusCode(), elapsed)
        if (response.statusCode() !in 200..299) {
            throw OpenMeteoException("Open-Meteo $operation request failed with HTTP ${response.statusCode()}")
        }

        return try {
            objectMapper.readValue(response.body(), responseType)
        } catch (exception: RuntimeException) {
            throw OpenMeteoException("Open-Meteo $operation returned invalid JSON", exception)
        }
    }
}

class OpenMeteoException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
