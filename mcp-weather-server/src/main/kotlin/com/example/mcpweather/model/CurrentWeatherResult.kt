package com.example.mcpweather.model

data class CurrentWeatherResult(
    val city: String,
    val country: String?,
    val latitude: Double,
    val longitude: Double,
    val temperatureCelsius: Double,
    val apparentTemperatureCelsius: Double,
    val windSpeedKmh: Double,
    val weatherCode: Int,
    val observedAt: String,
)
