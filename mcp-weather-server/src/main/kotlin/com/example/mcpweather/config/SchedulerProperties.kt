package com.example.mcpweather.config

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

@ConfigurationProperties(prefix = "app.scheduler")
@Validated
data class SchedulerProperties(
    val enabled: Boolean = true,
    val scanDelay: Duration = Duration.ofSeconds(10),
    @field:Min(1)
    @field:Max(100)
    val batchSize: Int = 10,
    val leaseDuration: Duration = Duration.ofMinutes(2),
    val retryDelay: Duration = Duration.ofMinutes(1),
    val stuckSummaryTimeout: Duration = Duration.ofMinutes(5),
    @field:Min(1)
    @field:Max(20)
    val maxDeliveryAttempts: Int = 3,
) {
    init {
        require(!scanDelay.isNegative && !scanDelay.isZero) { "app.scheduler.scan-delay must be positive" }
        require(!leaseDuration.isNegative && !leaseDuration.isZero) { "app.scheduler.lease-duration must be positive" }
        require(!retryDelay.isNegative && !retryDelay.isZero) { "app.scheduler.retry-delay must be positive" }
        require(!stuckSummaryTimeout.isNegative && !stuckSummaryTimeout.isZero) {
            "app.scheduler.stuck-summary-timeout must be positive"
        }
    }
}
