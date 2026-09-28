package dev.aichallenge.weather.server.weather

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.aichallenge.weather.server.config.PipelineServerProperties
import dev.aichallenge.weather.server.pipeline.ErrorCode
import dev.aichallenge.weather.server.pipeline.PipelineException
import org.springframework.http.HttpStatus
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import java.net.http.HttpClient
import java.time.LocalDate

@Component
class OpenMeteoClient(
    properties: PipelineServerProperties,
    private val objectMapper: ObjectMapper,
) {
    private val geocoding = client(properties.weather.geocodingBaseUrl, properties)
    private val forecast = client(properties.weather.forecastBaseUrl, properties)

    fun search(city: String, days: Int): WeatherForecast {
        val normalizedCity = city.trim()
        if (normalizedCity.isEmpty() || normalizedCity.length > 120 || days !in 1..7) {
            throw PipelineException(ErrorCode.VALIDATION_ERROR, "city must contain 1 to 120 characters and days must be between 1 and 7")
        }
        val location = resolve(normalizedCity)
        return fetch(location, days)
    }

    private fun resolve(city: String): ResolvedLocation {
        val body = request(ErrorCode.GEOCODING_UNAVAILABLE) {
            geocoding.get().uri { b ->
                b.path("/v1/search").queryParam("name", city).queryParam("count", 1)
                    .queryParam("language", "ru").queryParam("format", "json").build()
            }.retrieve().body(String::class.java) ?: ""
        }
        val root = parse(body, ErrorCode.GEOCODING_UNAVAILABLE)
        val first = root.path("results").firstOrNull()
            ?: throw PipelineException(ErrorCode.CITY_NOT_FOUND, "City was not found")
        return try {
            ResolvedLocation(
                first.path("name").asText().ifBlank { city },
                first.path("country").asText().ifBlank { first.path("country_code").asText() },
                first.path("latitude").doubleValue(), first.path("longitude").doubleValue(),
                first.path("timezone").asText("UTC"),
            )
        } catch (ex: Exception) {
            throw PipelineException(ErrorCode.GEOCODING_UNAVAILABLE, "Geocoding service returned malformed data")
        }
    }

    private fun fetch(location: ResolvedLocation, requestedDays: Int): WeatherForecast {
        val fields = "weather_code,temperature_2m_min,temperature_2m_max,precipitation_sum,precipitation_probability_max,wind_speed_10m_max"
        val body = request(ErrorCode.FORECAST_UNAVAILABLE) {
            forecast.get().uri { b ->
                b.path("/v1/forecast").queryParam("latitude", location.latitude)
                    .queryParam("longitude", location.longitude).queryParam("timezone", location.timezone)
                    .queryParam("forecast_days", requestedDays).queryParam("daily", fields).build()
            }.retrieve().body(String::class.java) ?: ""
        }
        val daily = parse(body, ErrorCode.FORECAST_UNAVAILABLE).path("daily")
        val names = listOf("time", "weather_code", "temperature_2m_min", "temperature_2m_max", "precipitation_sum", "precipitation_probability_max", "wind_speed_10m_max")
        val arrays = names.associateWith { daily.path(it) }
        if (arrays.values.any { !it.isArray || it.size() != requestedDays }) {
            throw PipelineException(ErrorCode.FORECAST_UNAVAILABLE, "Forecast service returned incomplete daily data")
        }
        val result = try {
            (0 until requestedDays).map { index ->
                DailyWeather(
                    LocalDate.parse(arrays.getValue("time")[index].asText()),
                    arrays.getValue("weather_code")[index].intValue(),
                    arrays.getValue("temperature_2m_min")[index].doubleValue(),
                    arrays.getValue("temperature_2m_max")[index].doubleValue(),
                    arrays.getValue("precipitation_sum")[index].doubleValue(),
                    arrays.getValue("precipitation_probability_max")[index].intValue(),
                    arrays.getValue("wind_speed_10m_max")[index].doubleValue(),
                )
            }
        } catch (ex: Exception) {
            throw PipelineException(ErrorCode.FORECAST_UNAVAILABLE, "Forecast service returned malformed daily data")
        }
        return WeatherForecast(location.name, location.country, location.timezone, result)
    }

    private fun parse(body: String, code: ErrorCode): JsonNode = try {
        objectMapper.readTree(body)
    } catch (ex: Exception) {
        throw PipelineException(code, "Weather service returned malformed JSON")
    }

    private fun <T> request(code: ErrorCode, block: () -> T): T = try {
        block()
    } catch (ex: RestClientResponseException) {
        val retryable = ex.statusCode == HttpStatus.TOO_MANY_REQUESTS || ex.statusCode.is5xxServerError
        throw PipelineException(code, "Weather service request failed with HTTP ${ex.statusCode.value()}", retryable)
    } catch (ex: ResourceAccessException) {
        throw PipelineException(code, "Weather service request timed out or was unavailable", true)
    }

    private fun client(baseUrl: String, properties: PipelineServerProperties): RestClient {
        val http = HttpClient.newBuilder().connectTimeout(properties.weather.connectTimeout).build()
        val factory = JdkClientHttpRequestFactory(http).apply { setReadTimeout(properties.weather.readTimeout) }
        return RestClient.builder().baseUrl(baseUrl.trimEnd('/')).requestFactory(factory).build()
    }
}
