package com.example.weatheragent.web

import com.example.weatheragent.agent.AgentUpstreamException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice
class GlobalExceptionHandler {
    private val logger = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(InvalidChatRequestException::class, HttpMessageNotReadableException::class)
    fun badRequest(): ResponseEntity<ApiError> =
        ResponseEntity.badRequest().body(ApiError("Некорректное сообщение"))

    @ExceptionHandler(AgentUpstreamException::class)
    fun upstreamFailure(exception: AgentUpstreamException): ResponseEntity<ApiError> {
        logger.warn("Weather agent upstream failure: {}", exception.cause?.javaClass?.simpleName ?: exception.javaClass.simpleName)
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
            .body(ApiError("Не удалось получить ответ погодного агента"))
    }

    @ExceptionHandler(Exception::class)
    fun internalFailure(exception: Exception): ResponseEntity<ApiError> {
        logger.error("Unexpected chat API failure", exception)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiError("Внутренняя ошибка сервера"))
    }
}
