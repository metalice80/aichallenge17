package dev.aichallenge.day20.agent.web

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

class ApiException(val status: HttpStatus, val code: String, override val message: String) : RuntimeException(message)

@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(ApiException::class)
    fun api(error: ApiException) = ResponseEntity.status(error.status).body(mapOf("success" to false, "code" to error.code, "message" to error.message))

    @ExceptionHandler(HttpMessageNotReadableException::class, IllegalArgumentException::class)
    fun validation(error: Exception) = ResponseEntity.badRequest().body(mapOf("success" to false, "code" to "VALIDATION_ERROR", "message" to "Invalid request"))

    @ExceptionHandler(Exception::class)
    fun unexpected(error: Exception) = ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(mapOf("success" to false, "code" to "INTERNAL_ERROR", "message" to "Request failed"))
}
