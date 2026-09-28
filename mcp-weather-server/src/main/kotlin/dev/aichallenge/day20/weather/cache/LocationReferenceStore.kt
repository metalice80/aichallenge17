package dev.aichallenge.day20.weather.cache

import dev.aichallenge.day20.weather.config.WeatherProperties
import dev.aichallenge.day20.weather.model.GeocodingResult
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Component
class LocationReferenceStore(
    private val properties: WeatherProperties,
    private val clock: Clock,
) {
    data class Entry(
        val reference: String,
        val runId: String,
        val name: String,
        val country: String,
        val latitude: Double,
        val longitude: Double,
        val timezone: String,
        val expiresAt: Instant,
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    internal fun create(runId: String, location: GeocodingResult): Entry {
        purgeExpired()
        if (entries.size >= properties.referenceCacheSize) {
            entries.entries.minByOrNull { it.value.expiresAt }?.let { entries.remove(it.key, it.value) }
        }
        val reference = "loc-${UUID.randomUUID()}"
        return Entry(
            reference = reference,
            runId = runId,
            name = location.name,
            country = location.country.orEmpty(),
            latitude = location.latitude,
            longitude = location.longitude,
            timezone = location.timezone,
            expiresAt = clock.instant().plus(properties.referenceTtl),
        ).also { entries[reference] = it }
    }

    fun require(reference: String, runId: String): Entry {
        val entry = entries[reference] ?: throw WeatherToolException("LOCATION_REF_NOT_FOUND", "Unknown locationRef")
        if (!clock.instant().isBefore(entry.expiresAt)) {
            entries.remove(reference, entry)
            throw WeatherToolException("LOCATION_REF_EXPIRED", "locationRef has expired")
        }
        if (entry.runId != runId) {
            throw WeatherToolException("LOCATION_REF_FROM_ANOTHER_RUN", "locationRef belongs to another orchestration run")
        }
        return entry
    }

    fun size(): Int {
        purgeExpired()
        return entries.size
    }

    private fun purgeExpired() {
        val now = clock.instant()
        entries.entries.removeIf { !now.isBefore(it.value.expiresAt) }
    }
}

class WeatherToolException(val code: String, message: String) : RuntimeException("$code: $message")
