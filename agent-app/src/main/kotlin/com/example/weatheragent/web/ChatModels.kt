package com.example.weatheragent.web

data class ChatRequest(
    val message: String? = null,
)

data class ChatResponse(
    val answer: String,
)

data class ApiError(
    val error: String,
)
