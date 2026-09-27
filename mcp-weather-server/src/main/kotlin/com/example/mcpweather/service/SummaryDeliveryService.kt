package com.example.mcpweather.service

import com.example.mcpweather.config.SchedulerProperties
import com.example.mcpweather.model.SummaryOperationResult
import com.example.mcpweather.model.WeatherSummary
import com.example.mcpweather.repository.WeatherSummaryRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

@Service
class SummaryDeliveryService(
    private val summaryRepository: WeatherSummaryRepository,
    private val properties: SchedulerProperties,
    private val clock: Clock,
) {
    @Transactional
    fun claimPending(workerId: String, limit: Int): List<WeatherSummary> {
        val worker = validateWorker(workerId)
        require(limit in 1..100) { "limit must be between 1 and 100" }
        val now = clock.instant()
        summaryRepository.recoverStuck(now.minus(properties.stuckSummaryTimeout))
        return summaryRepository.pendingCandidates(limit).mapNotNull { candidate ->
            if (summaryRepository.claim(candidate.id, worker, now)) {
                summaryRepository.findById(candidate.id)
            } else {
                null
            }
        }
    }

    @Transactional
    fun complete(summaryId: String, workerId: String, renderedText: String): SummaryOperationResult {
        val id = WeatherScheduleService.validateUuid(summaryId, "summaryId")
        val worker = validateWorker(workerId)
        val text = renderedText.trim()
        if (text.isEmpty()) throw WeatherToolException("renderedText must not be blank")
        if (text.length > MAX_RENDERED_TEXT_LENGTH) {
            throw WeatherToolException("renderedText must not exceed $MAX_RENDERED_TEXT_LENGTH characters")
        }
        if (!summaryRepository.complete(id, worker, text, clock.instant())) {
            throw WeatherToolException("Summary is not claimed by this worker: $id")
        }
        return SummaryOperationResult(id, "DELIVERED")
    }

    @Transactional
    fun fail(summaryId: String, workerId: String, error: String): SummaryOperationResult {
        val id = WeatherScheduleService.validateUuid(summaryId, "summaryId")
        val worker = validateWorker(workerId)
        val safeError = sanitizeError(error)
        val summary = summaryRepository.findById(id)
            ?: throw WeatherToolException("Weather summary not found: $id")
        val terminal = summary.deliveryAttempts >= properties.maxDeliveryAttempts
        if (!summaryRepository.fail(id, worker, safeError, terminal)) {
            throw WeatherToolException("Summary is not claimed by this worker: $id")
        }
        return SummaryOperationResult(id, if (terminal) "FAILED" else "PENDING")
    }

    fun recoverStuck(): Int = summaryRepository.recoverStuck(
        clock.instant().minus(properties.stuckSummaryTimeout),
    )

    private fun validateWorker(raw: String): String {
        val worker = raw.trim()
        if (worker.isEmpty() || worker.length > 120 || worker.any(Character::isISOControl)) {
            throw WeatherToolException("workerId must be a non-blank safe string up to 120 characters")
        }
        return worker
    }

    private fun sanitizeError(raw: String): String {
        val error = raw.replace(Regex("[\\p{Cc}\\p{Cf}]"), " ").trim()
        return error.ifEmpty { "Summary delivery failed" }.take(MAX_ERROR_LENGTH)
    }

    private companion object {
        const val MAX_RENDERED_TEXT_LENGTH = 10_000
        const val MAX_ERROR_LENGTH = 500
    }
}
