package dev.aichallenge.weather.server.summary

import dev.aichallenge.weather.server.weather.WeatherForecast
import org.springframework.stereotype.Service
import java.util.Locale

@Service
class WeatherSummaryService {
    data class Summary(val title: String, val preview: String, val markdown: String)

    fun summarize(forecast: WeatherForecast): Summary {
        require(forecast.days.isNotEmpty()) { "Forecast must contain at least one day" }
        val days = forecast.days
        val min = days.minOf { it.temperatureMin }
        val max = days.maxOf { it.temperatureMax }
        val average = days.flatMap { listOf(it.temperatureMin, it.temperatureMax) }.average()
        val precipitation = days.sumOf { it.precipitationSum }
        val wetDays = days.count { it.precipitationSum > 0.0 }
        val maxWind = days.maxOf { it.windSpeedMax }
        val coldest = days.minBy { (it.temperatureMin + it.temperatureMax) / 2.0 }
        val warmest = days.maxBy { (it.temperatureMin + it.temperatureMax) / 2.0 }
        val wettestChance = days.maxBy { it.precipitationProbabilityMax }
        val title = "Прогноз погоды: ${forecast.city}"
        val preview = "Температура от ${fmt(min)} до ${fmt(max)} °C, осадки ${fmt(precipitation)} мм"
        val markdown = buildString {
            appendLine("# $title")
            appendLine()
            appendLine("Период: ${days.first().date} — ${days.last().date}")
            appendLine()
            appendLine("## Краткая сводка")
            appendLine()
            appendLine("- Температура: от ${fmt(min)} до ${fmt(max)} °C")
            appendLine("- Средняя температура: ${fmt(average)} °C")
            appendLine("- Осадки: ${fmt(precipitation)} мм")
            appendLine("- Дней с осадками: $wetDays")
            appendLine("- Наибольшая вероятность осадков: ${wettestChance.date} (${wettestChance.precipitationProbabilityMax}%)")
            appendLine("- Максимальный ветер: ${fmt(maxWind)} км/ч")
            appendLine("- Самый холодный день: ${coldest.date}")
            appendLine("- Самый тёплый день: ${warmest.date}")
            appendLine()
            appendLine("## По дням")
            appendLine()
            appendLine("| Дата | Мин. | Макс. | Осадки | Вероятность | Ветер |")
            appendLine("|---|---:|---:|---:|---:|---:|")
            days.forEach { day ->
                appendLine("| ${day.date} | ${fmt(day.temperatureMin)} °C | ${fmt(day.temperatureMax)} °C | ${fmt(day.precipitationSum)} мм | ${day.precipitationProbabilityMax}% | ${fmt(day.windSpeedMax)} км/ч |")
            }
        }
        return Summary(title, preview, markdown)
    }

    private fun fmt(value: Double): String = String.format(Locale.ROOT, "%.1f", value)
}
