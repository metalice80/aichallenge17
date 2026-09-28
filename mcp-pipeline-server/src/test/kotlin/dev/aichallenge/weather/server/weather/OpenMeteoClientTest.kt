package dev.aichallenge.weather.server.weather

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.sun.net.httpserver.HttpServer
import dev.aichallenge.weather.server.config.PipelineServerProperties
import dev.aichallenge.weather.server.pipeline.ErrorCode
import dev.aichallenge.weather.server.pipeline.PipelineException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.InetSocketAddress
import java.time.Duration

class OpenMeteoClientTest {
    private var server: HttpServer? = null

    @AfterEach fun stop() { server?.stop(0) }

    @Test
    fun `loads geocoding and complete forecast from HTTP stub`() {
        start(
            geocoding = """{"results":[{"name":"Новосибирск","country":"Россия","latitude":55.0,"longitude":83.0,"timezone":"Asia/Novosibirsk"}]}""",
            forecast = """{"daily":{"time":["2026-09-28","2026-09-29"],"weather_code":[1,2],"temperature_2m_min":[1.0,2.0],"temperature_2m_max":[5.0,6.0],"precipitation_sum":[0.0,1.2],"precipitation_probability_max":[10,80],"wind_speed_10m_max":[12.0,15.0]}}""",
        )
        val result = client().search("Новосибирск", 2)
        assertEquals(2, result.days.size)
        assertEquals("Asia/Novosibirsk", result.timezone)
    }

    @Test
    fun `reports city not found and malformed forecast`() {
        start("{}", "{}")
        assertEquals(ErrorCode.CITY_NOT_FOUND, assertThrows<PipelineException> { client().search("missing", 1) }.code)
        stop()
        start("""{"results":[{"name":"X","country":"Y","latitude":1,"longitude":2,"timezone":"UTC"}]}""", "{broken")
        assertEquals(ErrorCode.FORECAST_UNAVAILABLE, assertThrows<PipelineException> { client().search("X", 1) }.code)
    }

    @Test
    fun `maps 429 and incomplete arrays to forecast errors`() {
        start("""{"results":[{"name":"X","country":"Y","latitude":1,"longitude":2,"timezone":"UTC"}]}""", "{}", forecastStatus = 429)
        val rateLimit = assertThrows<PipelineException> { client().search("X", 1) }
        assertEquals(ErrorCode.FORECAST_UNAVAILABLE, rateLimit.code)
        assertEquals(true, rateLimit.retryable)
        stop()
        start("""{"results":[{"name":"X","country":"Y","latitude":1,"longitude":2,"timezone":"UTC"}]}""", """{"daily":{"time":[]}}""")
        assertEquals(ErrorCode.FORECAST_UNAVAILABLE, assertThrows<PipelineException> { client().search("X", 1) }.code)
    }

    @Test
    fun `maps 5xx and timeout to retryable forecast errors`() {
        val location = """{"results":[{"name":"X","country":"Y","latitude":1,"longitude":2,"timezone":"UTC"}]}"""
        start(location, "{}", forecastStatus = 503)
        val unavailable = assertThrows<PipelineException> { client().search("X", 1) }
        assertEquals(ErrorCode.FORECAST_UNAVAILABLE, unavailable.code)
        assertEquals(true, unavailable.retryable)
        stop()
        start(location, "{}", forecastDelayMs = 300)
        val timeout = assertThrows<PipelineException> { client(Duration.ofMillis(100)).search("X", 1) }
        assertEquals(ErrorCode.FORECAST_UNAVAILABLE, timeout.code)
        assertEquals(true, timeout.retryable)
    }

    private fun client(readTimeout: Duration = Duration.ofSeconds(1)): OpenMeteoClient {
        val base = "http://127.0.0.1:${server!!.address.port}"
        val properties = PipelineServerProperties(weather = PipelineServerProperties.Weather(base, base, Duration.ofSeconds(1), readTimeout))
        return OpenMeteoClient(properties, ObjectMapper().registerKotlinModule())
    }

    private fun start(geocoding: String, forecast: String, forecastStatus: Int = 200, forecastDelayMs: Long = 0) {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/search") { exchange ->
                val bytes = geocoding.toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
            }
            createContext("/v1/forecast") { exchange ->
                if (forecastDelayMs > 0) Thread.sleep(forecastDelayMs)
                val bytes = forecast.toByteArray()
                exchange.sendResponseHeaders(forecastStatus, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
    }
}
