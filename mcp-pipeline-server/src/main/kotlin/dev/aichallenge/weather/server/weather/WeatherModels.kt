package dev.aichallenge.weather.server.weather

import java.time.LocalDate

data class ResolvedLocation(
    val name: String,
    val country: String,
    val latitude: Double,
    val longitude: Double,
    val timezone: String,
)

data class DailyWeather(
    val date: LocalDate,
    val weatherCode: Int,
    val temperatureMin: Double,
    val temperatureMax: Double,
    val precipitationSum: Double,
    val precipitationProbabilityMax: Int,
    val windSpeedMax: Double,
)

data class WeatherForecast(
    val city: String,
    val country: String,
    val timezone: String,
    val days: List<DailyWeather>,
)
