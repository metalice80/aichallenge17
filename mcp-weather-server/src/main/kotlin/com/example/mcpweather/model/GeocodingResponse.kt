package com.example.mcpweather.model

data class GeocodingResponse(
    val results: List<GeocodingResult>? = null,
)

data class GeocodingResult(
    val name: String? = null,
    val country: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
)
