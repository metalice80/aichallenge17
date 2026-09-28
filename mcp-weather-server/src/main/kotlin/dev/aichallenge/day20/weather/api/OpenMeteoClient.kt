package dev.aichallenge.day20.weather.api

import dev.aichallenge.day20.weather.cache.WeatherToolException
import dev.aichallenge.day20.weather.config.WeatherProperties
import dev.aichallenge.day20.weather.model.ForecastResponse
import dev.aichallenge.day20.weather.model.GeocodingResult
import dev.aichallenge.day20.weather.model.GeocodingResponse
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.net.http.HttpClient

@Component
class OpenMeteoClient(properties: WeatherProperties) {
    private val geocoding = restClient(properties.geocodingBaseUrl, properties)
    private val forecast = restClient(properties.forecastBaseUrl, properties)

    fun resolve(city: String): GeocodingResult {
        return try {
            geocoding.get()
                .uri { builder -> builder.path("/v1/search").queryParam("name", city).queryParam("count", 1).queryParam("language", "ru").queryParam("format", "json").build() }
                .retrieve()
                .body(GeocodingResponse::class.java)
                ?.results?.firstOrNull()
                ?: throw WeatherToolException("CITY_NOT_FOUND", "City was not found")
        } catch (error: WeatherToolException) {
            throw error
        } catch (error: Exception) {
            throw WeatherToolException("GEOCODING_UNAVAILABLE", "Open-Meteo geocoding is unavailable")
        }
    }

    fun forecast(latitude: Double, longitude: Double, timezone: String, days: Int): ForecastResponse {
        return try {
            forecast.get()
                .uri { builder ->
                    builder.path("/v1/forecast")
                        .queryParam("latitude", latitude)
                        .queryParam("longitude", longitude)
                        .queryParam("timezone", timezone)
                        .queryParam("forecast_days", days)
                        .queryParam("daily", "weather_code,temperature_2m_min,temperature_2m_max,precipitation_sum,precipitation_probability_max,wind_speed_10m_max")
                        .build()
                }
                .retrieve()
                .body(ForecastResponse::class.java)
                ?: throw WeatherToolException("INVALID_FORECAST_RESPONSE", "Open-Meteo returned no forecast")
        } catch (error: WeatherToolException) {
            throw error
        } catch (error: Exception) {
            throw WeatherToolException("FORECAST_UNAVAILABLE", "Open-Meteo forecast is unavailable")
        }
    }

    private fun restClient(baseUrl: String, properties: WeatherProperties): RestClient {
        val httpClient = HttpClient.newBuilder().connectTimeout(properties.connectTimeout).build()
        val requestFactory = JdkClientHttpRequestFactory(httpClient).apply { setReadTimeout(properties.readTimeout) }
        return RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build()
    }
}
