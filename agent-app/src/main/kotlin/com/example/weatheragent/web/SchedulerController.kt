package com.example.weatheragent.web

import com.example.weatheragent.scheduler.InstantCursor
import com.example.weatheragent.scheduler.ScheduleListResult
import com.example.weatheragent.scheduler.SummaryListResult
import com.example.weatheragent.scheduler.WeatherSchedulerMcpClient
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
@RequestMapping("/api")
class SchedulerController(
    private val mcpClient: WeatherSchedulerMcpClient,
) {
    @GetMapping("/schedules", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun schedules(): ScheduleListResult = ScheduleListResult(mcpClient.listSchedules())

    @GetMapping("/summaries", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun summaries(
        @RequestParam(required = false) after: String?,
        @RequestParam(defaultValue = "20") limit: Int,
    ): SummaryListResult {
        if (limit !in 1..100) throw InvalidSchedulerRequestException("limit must be between 1 and 100")
        val cursor = after?.takeIf(String::isNotBlank)?.let {
            try {
                InstantCursor(Instant.parse(it).toString())
            } catch (exception: RuntimeException) {
                throw InvalidSchedulerRequestException("after must be an ISO-8601 UTC timestamp")
            }
        }
        return SummaryListResult(mcpClient.listDeliveredSummaries(cursor, limit))
    }
}

class InvalidSchedulerRequestException(message: String) : RuntimeException(message)
