package dev.aichallenge.day20.weather

import dev.aichallenge.day20.weather.config.WeatherProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import java.time.Clock

@SpringBootApplication
@EnableConfigurationProperties(WeatherProperties::class)
class WeatherServerApplication {
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}

fun main(args: Array<String>) {
    runApplication<WeatherServerApplication>(*args)
}
