package com.example.mcpweather

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

class TestWeatherApiStub : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val executor = Executors.newCachedThreadPool()

    @Volatile
    var geocodingStatus: Int = 200

    @Volatile
    var forecastStatus: Int = 200

    @Volatile
    var geocodingBody: String = SUCCESS_GEOCODING

    @Volatile
    var forecastBody: String = SUCCESS_FORECAST

    @Volatile
    var responseDelayMillis: Long = 0

    @Volatile
    var lastForecastTimezone: String? = null
        private set

    init {
        server.executor = executor
        server.createContext("/geocoding") { exchange ->
            val requestedCity = queryParameters(exchange.requestURI)["name"]
            val body = if (requestedCity == "Unknown") EMPTY_GEOCODING else geocodingBody
            respond(exchange, geocodingStatus, body)
        }
        server.createContext("/forecast") { exchange ->
            lastForecastTimezone = queryParameters(exchange.requestURI)["timezone"]
            respond(exchange, forecastStatus, forecastBody)
        }
        server.start()
    }

    val geocodingUri: URI
        get() = URI("http://127.0.0.1:${server.address.port}/geocoding")

    val forecastUri: URI
        get() = URI("http://127.0.0.1:${server.address.port}/forecast")

    private fun respond(exchange: HttpExchange, status: Int, body: String) {
        exchange.use {
            if (responseDelayMillis > 0) {
                Thread.sleep(responseDelayMillis)
            }
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.write(bytes)
        }
    }

    private fun queryParameters(uri: URI): Map<String, String> =
        uri.rawQuery.orEmpty()
            .split('&')
            .filter(String::isNotEmpty)
            .associate { entry ->
                val parts = entry.split('=', limit = 2)
                URLDecoder.decode(parts[0], StandardCharsets.UTF_8) to
                    URLDecoder.decode(parts.getOrElse(1) { "" }, StandardCharsets.UTF_8)
            }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
    }

    companion object {
        const val SUCCESS_GEOCODING =
            """{"results":[{"name":"Тестоград","country":"Тестландия","latitude":55.03,"longitude":82.92}]}"""
        const val EMPTY_GEOCODING = """{"results":[]}"""
        const val SUCCESS_FORECAST =
            """{"current":{"temperature_2m":8.4,"apparent_temperature":6.1,"wind_speed_10m":14.2,"weather_code":3,"time":"2026-09-28T12:00"}}"""
    }
}
