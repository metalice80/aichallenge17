package dev.aichallenge.day20.guide

import dev.aichallenge.day20.guide.config.GuideProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import java.time.Clock

@SpringBootApplication
@EnableConfigurationProperties(GuideProperties::class)
class GuideServerApplication {
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}

fun main(args: Array<String>) {
    runApplication<GuideServerApplication>(*args)
}
