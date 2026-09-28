package dev.aichallenge.day20.agent

import dev.aichallenge.day20.agent.config.AppProperties
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock

@SpringBootApplication
@EnableConfigurationProperties(AppProperties::class)
class AgentApplication {
    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    fun objectMapper(): ObjectMapper = ObjectMapper().registerKotlinModule()
}

fun main(args: Array<String>) {
    val dbPath = System.getenv("ORCHESTRATION_DB_PATH") ?: "./data/orchestration.db"
    Path.of(dbPath).toAbsolutePath().parent?.let { Files.createDirectories(it) }
    runApplication<AgentApplication>(*args)
}
