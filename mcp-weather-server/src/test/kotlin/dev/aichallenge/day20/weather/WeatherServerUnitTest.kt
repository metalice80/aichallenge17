package dev.aichallenge.day20.weather

import dev.aichallenge.day20.weather.cache.LocationReferenceStore
import dev.aichallenge.day20.weather.cache.WeatherToolException
import dev.aichallenge.day20.weather.config.WeatherProperties
import dev.aichallenge.day20.weather.model.GeocodingResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

class WeatherServerUnitTest {
    @Test
    fun `references are run scoped bounded and expire`() {
        val clock = MutableClock(Instant.parse("2026-09-28T10:00:00Z"))
        val properties = WeatherProperties("http://unused", "http://unused", Duration.ofMinutes(1), 2)
        val store = LocationReferenceStore(properties, clock)
        val location = GeocodingResult("Казань", "Россия", 55.79, 49.12, "Europe/Moscow")
        val first = store.create("run-a", location)
        store.create("run-a", location)
        store.create("run-a", location)
        assertEquals(2, store.size())
        assertThrows(WeatherToolException::class.java) { store.require(first.reference, "run-b") }
        val current = store.create("run-a", location)
        clock.advance(Duration.ofMinutes(2))
        assertThrows(WeatherToolException::class.java) { store.require(current.reference, "run-a") }
        assertEquals(0, store.size())
    }

    private class MutableClock(private var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneId.of("UTC")
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = now
        fun advance(duration: Duration) { now = now.plus(duration) }
    }
}
