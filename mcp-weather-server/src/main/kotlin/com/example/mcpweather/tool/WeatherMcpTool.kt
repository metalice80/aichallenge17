package com.example.mcpweather.tool

import com.example.mcpweather.model.CurrentWeatherResult
import com.example.mcpweather.service.WeatherService
import org.slf4j.LoggerFactory
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component

@Component
class WeatherMcpTool(
    private val weatherService: WeatherService,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @McpTool(
        name = "get_current_weather",
        description = "Returns normalized current weather for a city using live Open-Meteo data.",
        title = "Get current weather",
        generateOutputSchema = true,
        annotations = McpTool.McpAnnotations(
            readOnlyHint = true,
            destructiveHint = false,
            idempotentHint = true,
            openWorldHint = true,
        ),
    )
    fun getCurrentWeather(
        @McpToolParam(
            description = "City name, for example Novosibirsk or Санкт-Петербург",
            required = true,
        )
        city: String,
    ): CurrentWeatherResult {
        logger.info("Calling get_current_weather")
        return weatherService.getCurrentWeather(city)
    }
}
