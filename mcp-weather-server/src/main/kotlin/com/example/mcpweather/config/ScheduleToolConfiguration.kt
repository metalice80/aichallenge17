package com.example.mcpweather.config

import com.example.mcpweather.service.WeatherScheduleService
import com.example.mcpweather.service.WeatherToolException
import io.modelcontextprotocol.server.McpServerFeatures
import io.modelcontextprotocol.spec.McpSchema
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class ScheduleToolConfiguration {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Bean
    fun scheduleWeatherSummaryTool(
        scheduleService: WeatherScheduleService,
    ): List<McpServerFeatures.SyncToolSpecification> {
        val tool = McpSchema.Tool.builder("schedule_weather_summary", inputSchema())
            .title("Schedule weather summary")
            .description(
                "Creates a durable periodic weather collection and summary schedule. " +
                    "Collection interval: 1-1440 minutes; summary interval: 5-10080 minutes and not shorter than collection.",
            )
            .outputSchema(outputSchema())
            .annotations(
                McpSchema.ToolAnnotations.builder()
                    .readOnlyHint(false)
                    .destructiveHint(false)
                    .idempotentHint(true)
                    .openWorldHint(true)
                    .build(),
            )
            .build()

        return listOf(
            McpServerFeatures.SyncToolSpecification(tool) { _, request ->
                val arguments = request.arguments()
                val city = arguments["city"] as? String
                    ?: throw WeatherToolException("city must be a string")
                val collection = (arguments["collectionIntervalMinutes"] as? Number)?.toInt()
                    ?: throw WeatherToolException("collectionIntervalMinutes must be an integer")
                val summary = (arguments["summaryIntervalMinutes"] as? Number)?.toInt()
                    ?: throw WeatherToolException("summaryIntervalMinutes must be an integer")
                val result = scheduleService.create(city, collection, summary)
                logger.info("Created or reused weather schedule {} for {}", result.scheduleId, result.city)
                val structured = linkedMapOf<String, Any>(
                    "scheduleId" to result.scheduleId,
                    "city" to result.city,
                    "status" to result.status,
                    "collectionIntervalMinutes" to result.collectionIntervalMinutes,
                    "summaryIntervalMinutes" to result.summaryIntervalMinutes,
                    "nextCollectionAt" to result.nextCollectionAt,
                    "nextSummaryAt" to result.nextSummaryAt,
                )
                McpSchema.CallToolResult.builder()
                    .addTextContent("Weather schedule ${result.scheduleId} is ${result.status}")
                    .structuredContent(structured)
                    .isError(false)
                    .build()
            },
        )
    }

    private fun inputSchema(): Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "city" to mapOf(
                "type" to "string",
                "description" to "Город для мониторинга",
            ),
            "collectionIntervalMinutes" to mapOf(
                "type" to "integer",
                "description" to "Интервал сбора погодных данных в минутах",
                "minimum" to 1,
                "maximum" to 1440,
            ),
            "summaryIntervalMinutes" to mapOf(
                "type" to "integer",
                "description" to "Интервал формирования сводки в минутах",
                "minimum" to 5,
                "maximum" to 10080,
            ),
        ),
        "required" to listOf("city", "collectionIntervalMinutes", "summaryIntervalMinutes"),
        "additionalProperties" to false,
    )

    private fun outputSchema(): Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "scheduleId" to mapOf("type" to "string"),
            "city" to mapOf("type" to "string"),
            "status" to mapOf("type" to "string"),
            "collectionIntervalMinutes" to mapOf("type" to "integer"),
            "summaryIntervalMinutes" to mapOf("type" to "integer"),
            "nextCollectionAt" to mapOf("type" to "string"),
            "nextSummaryAt" to mapOf("type" to "string"),
        ),
        "required" to listOf(
            "scheduleId",
            "city",
            "status",
            "collectionIntervalMinutes",
            "summaryIntervalMinutes",
            "nextCollectionAt",
            "nextSummaryAt",
        ),
        "additionalProperties" to false,
    )
}
