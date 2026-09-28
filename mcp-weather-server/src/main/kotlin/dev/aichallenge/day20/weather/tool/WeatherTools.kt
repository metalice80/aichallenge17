package dev.aichallenge.day20.weather.tool

import dev.aichallenge.day20.weather.api.OpenMeteoClient
import dev.aichallenge.day20.weather.cache.LocationReferenceStore
import dev.aichallenge.day20.weather.cache.WeatherToolException
import dev.aichallenge.day20.weather.model.ForecastDay
import dev.aichallenge.day20.weather.model.ForecastResult
import dev.aichallenge.day20.weather.model.ResolveLocationResult
import org.springframework.ai.mcp.annotation.McpMeta
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component

@Component
class WeatherTools(
    private val client: OpenMeteoClient,
    private val references: LocationReferenceStore,
) {
    @McpTool(
        name = "weather_resolve_location",
        description = "Resolve a city using Open-Meteo and return an opaque locationRef. Pass that locationRef unchanged to weather_get_forecast.",
        generateOutputSchema = true,
        annotations = McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = false, openWorldHint = true),
    )
    fun resolveLocation(
        @McpToolParam(description = "Destination city name", required = true) city: String,
        meta: McpMeta,
    ): ResolveLocationResult {
        val runId = requireRunId(meta)
        val normalized = city.trim()
        if (normalized.length !in 2..120 || normalized.any { it.isISOControl() } || normalized.startsWith("http://") || normalized.startsWith("https://")) {
            throw WeatherToolException("INVALID_CITY", "City must contain 2 to 120 safe characters")
        }
        val entry = references.create(runId, client.resolve(normalized))
        return ResolveLocationResult(entry.reference, entry.name, entry.country, entry.latitude, entry.longitude, entry.timezone, entry.expiresAt)
    }

    @McpTool(
        name = "weather_get_forecast",
        description = "Get a 1-7 day Open-Meteo forecast. locationRef must be passed unchanged from weather_resolve_location in the same run.",
        generateOutputSchema = true,
        annotations = McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true, openWorldHint = true),
    )
    fun getForecast(
        @McpToolParam(description = "Exact opaque reference returned by weather_resolve_location", required = true) locationRef: String,
        @McpToolParam(description = "Forecast length from 1 to 7 days", required = true) days: Int,
        meta: McpMeta,
    ): ForecastResult {
        if (days !in 1..7) throw WeatherToolException("INVALID_FORECAST_RESPONSE", "days must be between 1 and 7")
        val entry = references.require(locationRef, requireRunId(meta))
        val response = client.forecast(entry.latitude, entry.longitude, entry.timezone, days)
        val daily = response.daily
        val sizes = listOf(daily.time.size, daily.weatherCode.size, daily.temperatureMin.size, daily.temperatureMax.size, daily.precipitation.size, daily.precipitationProbability.size, daily.maxWind.size)
        if (sizes.any { it < days }) throw WeatherToolException("INVALID_FORECAST_RESPONSE", "Forecast arrays are incomplete")
        return ForecastResult(
            locationRef = locationRef,
            location = listOf(entry.name, entry.country).filter { it.isNotBlank() }.joinToString(", "),
            timezone = response.timezone,
            days = (0 until days).map { index ->
                ForecastDay(
                    date = daily.time[index],
                    temperatureMinC = daily.temperatureMin[index],
                    temperatureMaxC = daily.temperatureMax[index],
                    precipitationMm = daily.precipitation[index],
                    precipitationProbabilityPercent = daily.precipitationProbability[index],
                    maxWindKmh = daily.maxWind[index],
                    weatherDescription = describeWeather(daily.weatherCode[index]),
                )
            },
        )
    }

    private fun requireRunId(meta: McpMeta): String = (meta.get("orchestrationRunId") as? String)?.takeIf { it.isNotBlank() }
        ?: throw WeatherToolException("MISSING_ORCHESTRATION_CONTEXT", "orchestrationRunId metadata is required")

    internal fun describeWeather(code: Int): String = when (code) {
        0 -> "Ясно"
        1, 2, 3 -> "Переменная облачность"
        45, 48 -> "Туман"
        51, 53, 55, 56, 57 -> "Морось"
        61, 63, 65, 66, 67, 80, 81, 82 -> "Дождь"
        71, 73, 75, 77, 85, 86 -> "Снег"
        95, 96, 99 -> "Гроза"
        else -> "Неизвестные условия"
    }
}
