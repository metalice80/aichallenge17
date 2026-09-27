package com.example.mcpweather.model

import com.fasterxml.jackson.annotation.JsonProperty

data class ForecastResponse(
    val current: CurrentConditions? = null,
)

data class CurrentConditions(
    @param:JsonProperty("temperature_2m")
    val temperatureCelsius: Double? = null,
    @param:JsonProperty("apparent_temperature")
    val apparentTemperatureCelsius: Double? = null,
    @param:JsonProperty("wind_speed_10m")
    val windSpeedKmh: Double? = null,
    @param:JsonProperty("weather_code")
    val weatherCode: Int? = null,
    val time: String? = null,
)
