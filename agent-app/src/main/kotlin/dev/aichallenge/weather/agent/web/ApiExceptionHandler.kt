package dev.aichallenge.weather.agent.web

import dev.aichallenge.weather.agent.pipeline.AgentConfigurationException
import dev.aichallenge.weather.agent.pipeline.ApiError
import dev.aichallenge.weather.agent.pipeline.McpCallException
import dev.aichallenge.weather.agent.pipeline.PipelineNotFoundException
import dev.aichallenge.weather.agent.pipeline.PipelineSubmissionException
import jakarta.validation.ConstraintViolationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(MethodArgumentNotValidException::class, ConstraintViolationException::class, IllegalArgumentException::class)
    fun validation(ex: Exception) = ResponseEntity.badRequest().body(ApiError("VALIDATION_ERROR", "Request validation failed"))

    @ExceptionHandler(PipelineNotFoundException::class)
    fun notFound(ex: PipelineNotFoundException) = ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(ApiError("PIPELINE_NOT_FOUND", ex.message ?: "Pipeline run was not found", ex.runId))

    @ExceptionHandler(AgentConfigurationException::class)
    fun configuration(ex: AgentConfigurationException) = ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .body(ApiError("OPENAI_UNAVAILABLE", ex.message ?: "OpenAI is not configured"))

    @ExceptionHandler(PipelineSubmissionException::class)
    fun rejected(ex: PipelineSubmissionException) = ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .body(ApiError("EXECUTOR_REJECTED", ex.message ?: "Pipeline executor rejected the request", ex.runId))

    @ExceptionHandler(McpCallException::class)
    fun mcp(ex: McpCallException) = ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(ApiError("INTERNAL_ERROR", ex.safeMessage))
}
