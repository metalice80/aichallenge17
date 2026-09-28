package dev.aichallenge.weather.server.summary

import dev.aichallenge.weather.server.config.PipelineServerProperties
import dev.aichallenge.weather.server.file.SafeReportWriter
import dev.aichallenge.weather.server.pipeline.ErrorCode
import dev.aichallenge.weather.server.pipeline.Hashing
import dev.aichallenge.weather.server.pipeline.PipelineException
import dev.aichallenge.weather.server.weather.DailyWeather
import dev.aichallenge.weather.server.weather.WeatherForecast
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

class SummaryAndFileTest {
    @TempDir lateinit var temp: Path

    private val forecast = WeatherForecast(
        "Новосибирск", "Россия", "Asia/Novosibirsk",
        listOf(
            DailyWeather(LocalDate.parse("2026-09-28"), 3, -2.0, 6.0, 1.2, 70, 18.0),
            DailyWeather(LocalDate.parse("2026-09-29"), 1, 0.0, 9.0, 0.0, 10, 12.5),
        ),
    )

    @Test
    fun `summary is deterministic and computes aggregates`() {
        val service = WeatherSummaryService()
        val first = service.summarize(forecast)
        val second = service.summarize(forecast)
        assertEquals(first, second)
        assertTrue(first.markdown.contains("Температура: от -2.0 до 9.0 °C"))
        assertTrue(first.markdown.contains("Средняя температура: 3.3 °C"))
        assertTrue(first.markdown.contains("Дней с осадками: 1"))
        assertEquals(Hashing.sha256(first.markdown), Hashing.sha256(second.markdown))
    }

    @Test
    fun `writer is atomic idempotent and blocks traversal`() {
        val properties = PipelineServerProperties(reports = PipelineServerProperties.Reports(temp.toString()))
        val writer = SafeReportWriter(properties)
        val first = writer.save("weather.md", "# report\n")
        val second = writer.save("weather.md", "# report\n")
        assertEquals(first, second)
        assertEquals("# report\n", Files.readString(temp.resolve("weather.md")))
        assertEquals(ErrorCode.FILE_ALREADY_EXISTS, assertThrows<PipelineException> { writer.save("weather.md", "different") }.code)
        listOf("../escape.md", "sub/report.md", "bad.txt", "bad\\name.md", "x..md").forEach { name ->
            assertEquals(ErrorCode.UNSAFE_FILE_NAME, assertThrows<PipelineException> { writer.save(name, "x") }.code)
        }
    }
}
