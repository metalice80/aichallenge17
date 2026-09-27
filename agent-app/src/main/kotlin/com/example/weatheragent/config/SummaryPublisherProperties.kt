package com.example.weatheragent.config

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated
import java.time.Duration

@ConfigurationProperties(prefix = "app.summary-publisher")
@Validated
data class SummaryPublisherProperties(
    val enabled: Boolean = true,
    val scanDelay: Duration = Duration.ofSeconds(15),
    @field:Min(1)
    @field:Max(100)
    val batchSize: Int = 5,
    @field:Min(1)
    @field:Max(20)
    val maxDeliveryAttempts: Int = 3,
) {
    init {
        require(!scanDelay.isNegative && !scanDelay.isZero) {
            "app.summary-publisher.scan-delay must be positive"
        }
    }
}
