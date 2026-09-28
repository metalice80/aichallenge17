package dev.aichallenge.day20.weather.model

import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant

data class GeocodingResponse(val results: List<GeocodingResult>? = null)
data class GeocodingResult(
    val name: String,
    val country: String? = null,
    val latitude: Double,
    val longitude: Double,
    val timezone: String,
)

data class ForecastResponse(
    val timezone: String,
    val daily: ForecastDaily,
)

data class ForecastDaily(
    val time: List<String>,
    @JsonProperty("weather_code") val weatherCode: List<Int>,
    @JsonProperty("temperature_2m_min") val temperatureMin: List<Double>,
    @JsonProperty("temperature_2m_max") val temperatureMax: List<Double>,
    @JsonProperty("precipitation_sum") val precipitation: List<Double>,
    @JsonProperty("precipitation_probability_max") val precipitationProbability: List<Int>,
    @JsonProperty("wind_speed_10m_max") val maxWind: List<Double>,
)

data class ResolveLocationResult(
    val locationRef: String,
    val name: String,
    val country: String,
    val latitude: Double,
    val longitude: Double,
    val timezone: String,
    val expiresAt: Instant,
    val nextTool: String = "weather_get_forecast",
)

data class ForecastDay(
    val date: String,
    val temperatureMinC: Double,
    val temperatureMaxC: Double,
    val precipitationMm: Double,
    val precipitationProbabilityPercent: Int,
    val maxWindKmh: Double,
    val weatherDescription: String,
)

data class ForecastResult(
    val locationRef: String,
    val location: String,
    val timezone: String,
    val days: List<ForecastDay>,
)
