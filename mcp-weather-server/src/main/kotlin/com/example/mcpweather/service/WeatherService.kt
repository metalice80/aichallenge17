package com.example.mcpweather.service

import com.example.mcpweather.model.CurrentWeatherResult
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class WeatherService(
    private val openMeteoClient: OpenMeteoClient,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun getCurrentWeather(rawCity: String): CurrentWeatherResult {
        val city = validateCity(rawCity)
        val location = openMeteoClient.geocode(city).results?.firstOrNull()
            ?: throw WeatherToolException("City not found: $city")
        val locationName = location.name?.takeIf(String::isNotBlank)
            ?: throw WeatherToolException("Open-Meteo geocoding response has no city name")
        val latitude = location.latitude
            ?: throw WeatherToolException("Open-Meteo geocoding response has no latitude")
        val longitude = location.longitude
            ?: throw WeatherToolException("Open-Meteo geocoding response has no longitude")

        logger.info("Resolved city {}", locationName)
        val current = openMeteoClient.currentWeather(latitude, longitude).current
            ?: throw WeatherToolException("Open-Meteo forecast response has no current weather")

        return CurrentWeatherResult(
            city = locationName,
            country = location.country,
            latitude = latitude,
            longitude = longitude,
            temperatureCelsius = current.temperatureCelsius
                ?: throw WeatherToolException("Open-Meteo current weather has no temperature"),
            apparentTemperatureCelsius = current.apparentTemperatureCelsius
                ?: throw WeatherToolException("Open-Meteo current weather has no apparent temperature"),
            windSpeedKmh = current.windSpeedKmh
                ?: throw WeatherToolException("Open-Meteo current weather has no wind speed"),
            weatherCode = current.weatherCode
                ?: throw WeatherToolException("Open-Meteo current weather has no weather code"),
            observedAt = current.time?.takeIf(String::isNotBlank)
                ?: throw WeatherToolException("Open-Meteo current weather has no observation time"),
        )
    }

    internal fun validateCity(rawCity: String): String {
        val city = rawCity.trim()
        if (city.isEmpty()) {
            throw WeatherToolException("City must not be blank")
        }
        if (city.length > MAX_CITY_LENGTH) {
            throw WeatherToolException("City must not exceed $MAX_CITY_LENGTH characters")
        }
        if (city.any(Character::isISOControl)) {
            throw WeatherToolException("City must not contain control characters")
        }
        if (URL_LIKE_PREFIX.containsMatchIn(city)) {
            throw WeatherToolException("City must be a city name, not a URL")
        }
        return city
    }

    private companion object {
        const val MAX_CITY_LENGTH = 120
        val URL_LIKE_PREFIX = Regex("^(?i:[a-z][a-z0-9+.-]*://|www\\.)")
    }
}

class WeatherToolException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
