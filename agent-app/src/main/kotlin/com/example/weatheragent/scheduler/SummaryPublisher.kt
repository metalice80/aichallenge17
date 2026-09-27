package com.example.weatheragent.scheduler

import com.example.weatheragent.config.SummaryPublisherProperties
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

@Component
class SummaryPublisher(
    private val mcpClient: WeatherSchedulerMcpClient,
    private val textGenerator: SummaryTextGenerator,
    private val properties: SummaryPublisherProperties,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val running = AtomicBoolean(false)
    private val workerId = "summary-publisher-${UUID.randomUUID()}"

    @Scheduled(fixedDelayString = "\${app.summary-publisher.scan-delay:15s}")
    fun publishPendingSummaries() {
        if (properties.enabled) publishBatch()
    }

    fun publishBatch(): Int {
        if (!running.compareAndSet(false, true)) {
            logger.debug("Skipping overlapping summary publisher run")
            return 0
        }
        try {
            val summaries = try {
                mcpClient.claimPendingSummaries(workerId, properties.batchSize)
            } catch (exception: RuntimeException) {
                logger.warn(
                    "Unable to claim pending weather summaries; retrying on next scan: {}",
                    safeError(exception),
                )
                return 0
            }
            logger.debug("Claimed {} pending weather summaries", summaries.size)
            if (summaries.isEmpty()) return 0

            var delivered = 0
            summaries.forEach { summary ->
                try {
                    logger.info("Calling OpenAI for pending weather summary {}", summary.id)
                    val renderedText = textGenerator.render(summary)
                    mcpClient.completeSummary(summary.id, workerId, renderedText)
                    delivered++
                    logger.info("Delivered weather summary {}", summary.id)
                } catch (exception: RuntimeException) {
                    logger.warn(
                        "Weather summary {} delivery failed: {}",
                        summary.id,
                        exception.message?.take(300) ?: exception.javaClass.simpleName,
                    )
                    runCatching {
                        mcpClient.failSummary(summary.id, workerId, safeError(exception))
                    }.onFailure { failure ->
                        logger.error("Unable to record delivery failure for summary {}: {}", summary.id, failure.javaClass.simpleName)
                    }
                }
            }
            return delivered
        } finally {
            running.set(false)
        }
    }


    private fun safeError(exception: RuntimeException): String =
        (exception.message ?: exception.javaClass.simpleName)
            .replace(Regex("[\\p{Cc}\\p{Cf}]"), " ")
            .take(500)
}
