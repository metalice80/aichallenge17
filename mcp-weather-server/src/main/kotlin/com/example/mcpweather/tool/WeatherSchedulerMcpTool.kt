package com.example.mcpweather.tool

import com.example.mcpweather.model.LatestSummaryResult
import com.example.mcpweather.model.ScheduleCancellation
import com.example.mcpweather.model.ScheduleListResult
import com.example.mcpweather.model.ScheduleView
import com.example.mcpweather.model.SummaryListResult
import com.example.mcpweather.model.SummaryOperationResult
import com.example.mcpweather.service.SummaryDeliveryService
import com.example.mcpweather.service.WeatherScheduleService
import org.slf4j.LoggerFactory
import org.springframework.ai.mcp.annotation.McpTool
import org.springframework.ai.mcp.annotation.McpToolParam
import org.springframework.stereotype.Component

@Component
class WeatherSchedulerMcpTool(
    private val scheduleService: WeatherScheduleService,
    private val deliveryService: SummaryDeliveryService,
) {
    private val logger = LoggerFactory.getLogger(javaClass)


    @McpTool(
        name = "get_weather_schedule_status",
        description = "Returns status, intervals, next runs, observation count, and the last safe error for a weather schedule.",
        title = "Get weather schedule status",
        generateOutputSchema = true,
        annotations = McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true),
    )
    fun getWeatherScheduleStatus(
        @McpToolParam(description = "Weather schedule UUID", required = true)
        scheduleId: String,
    ): ScheduleView = scheduleService.status(scheduleId)

    @McpTool(
        name = "get_latest_weather_summary",
        description = "Returns the latest delivered summary for a schedule, or NOT_READY when none exists.",
        title = "Get latest weather summary",
        generateOutputSchema = true,
        annotations = McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true),
    )
    fun getLatestWeatherSummary(
        @McpToolParam(description = "Weather schedule UUID", required = true)
        scheduleId: String,
    ): LatestSummaryResult = scheduleService.latestSummary(scheduleId)

    @McpTool(
        name = "cancel_weather_schedule",
        description = "Idempotently cancels a weather schedule without deleting observations or summaries.",
        title = "Cancel weather schedule",
        generateOutputSchema = true,
        annotations = McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = true, idempotentHint = true),
    )
    fun cancelWeatherSchedule(
        @McpToolParam(description = "Weather schedule UUID", required = true)
        scheduleId: String,
    ): ScheduleCancellation {
        val result = scheduleService.cancel(scheduleId)
        logger.info("Cancelled weather schedule {}", result.scheduleId)
        return result
    }

    @McpTool(
        name = "claim_pending_weather_summaries",
        description = "Internal: atomically claims pending aggregate summaries for one publisher worker.",
        title = "Claim pending weather summaries",
        generateOutputSchema = true,
        annotations = McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = false),
    )
    fun claimPendingWeatherSummaries(
        @McpToolParam(description = "Unique publisher worker identifier", required = true)
        workerId: String,
        @McpToolParam(description = "Maximum summaries to claim, from 1 through 100", required = true)
        limit: Int,
    ): SummaryListResult = SummaryListResult(deliveryService.claimPending(workerId, limit))

    @McpTool(
        name = "complete_weather_summary_delivery",
        description = "Internal: completes a claimed summary and stores rendered text.",
        title = "Complete weather summary delivery",
        generateOutputSchema = true,
        annotations = McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true),
    )
    fun completeWeatherSummaryDelivery(
        @McpToolParam(description = "Weather summary UUID", required = true)
        summaryId: String,
        @McpToolParam(description = "Publisher worker identifier owning the claim", required = true)
        workerId: String,
        @McpToolParam(description = "Rendered non-blank summary text", required = true)
        renderedText: String,
    ): SummaryOperationResult = deliveryService.complete(summaryId, workerId, renderedText)

    @McpTool(
        name = "fail_weather_summary_delivery",
        description = "Internal: records a safe delivery error and retries or terminally fails the summary.",
        title = "Fail weather summary delivery",
        generateOutputSchema = true,
        annotations = McpTool.McpAnnotations(readOnlyHint = false, destructiveHint = false, idempotentHint = true),
    )
    fun failWeatherSummaryDelivery(
        @McpToolParam(description = "Weather summary UUID", required = true)
        summaryId: String,
        @McpToolParam(description = "Publisher worker identifier owning the claim", required = true)
        workerId: String,
        @McpToolParam(description = "Short safe error without stack trace", required = true)
        error: String,
    ): SummaryOperationResult = deliveryService.fail(summaryId, workerId, error)

    @McpTool(
        name = "list_weather_schedules",
        description = "Internal: lists durable weather schedules for the web UI.",
        title = "List weather schedules",
        generateOutputSchema = true,
        annotations = McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true),
    )
    fun listWeatherSchedules(): ScheduleListResult = scheduleService.listSchedules()

    @McpTool(
        name = "list_delivered_weather_summaries",
        description = "Internal: lists delivered summaries after an optional UTC timestamp for the web UI.",
        title = "List delivered weather summaries",
        generateOutputSchema = true,
        annotations = McpTool.McpAnnotations(readOnlyHint = true, destructiveHint = false, idempotentHint = true),
    )
    fun listDeliveredWeatherSummaries(
        @McpToolParam(description = "Optional exclusive ISO-8601 UTC delivered-at cursor", required = false)
        after: String?,
        @McpToolParam(description = "Maximum results, from 1 through 100", required = true)
        limit: Int,
    ): SummaryListResult = scheduleService.listDeliveredSummaries(after, limit)

}
