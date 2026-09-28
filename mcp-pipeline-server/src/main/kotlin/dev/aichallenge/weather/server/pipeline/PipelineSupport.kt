package dev.aichallenge.weather.server.pipeline

import java.security.MessageDigest

enum class ErrorCode {
    VALIDATION_ERROR, MISSING_PIPELINE_CONTEXT, PIPELINE_NOT_FOUND, INVALID_PIPELINE_STATE,
    CITY_NOT_FOUND, GEOCODING_UNAVAILABLE, FORECAST_UNAVAILABLE, INVALID_ARTIFACT,
    ARTIFACT_FROM_ANOTHER_RUN, STEP_ALREADY_COMPLETED_WITH_DIFFERENT_INPUT, UNSAFE_FILE_NAME,
    FILE_ALREADY_EXISTS, FILE_WRITE_FAILED, OPENAI_AUTHENTICATION_FAILED, OPENAI_UNAVAILABLE, OPENAI_TIMEOUT,
    PIPELINE_INCOMPLETE, EXECUTOR_REJECTED, PROCESS_INTERRUPTED, INTERNAL_ERROR
}

class PipelineException(
    val code: ErrorCode,
    val safeMessage: String,
    val retryable: Boolean = false,
    val step: StepName? = null,
) : RuntimeException("${code.name}: $safeMessage")

object Hashing {
    fun sha256(content: String): String = MessageDigest.getInstance("SHA-256")
        .digest(content.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    fun fingerprint(runId: String, normalizedInput: String): String = sha256("$runId\n$normalizedInput")
}
