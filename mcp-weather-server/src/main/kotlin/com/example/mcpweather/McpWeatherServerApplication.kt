package com.example.mcpweather

import com.example.mcpweather.config.OpenMeteoProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.ContextClosedEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@SpringBootApplication
@EnableConfigurationProperties(OpenMeteoProperties::class)
class McpWeatherServerApplication

fun main(args: Array<String>) {
    runApplication<McpWeatherServerApplication>(*args)
}

@Component
class ServerLifecycleLogger {
    private val logger = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    fun onReady() {
        logger.info("Weather MCP server started; registered get_current_weather")
    }

    @EventListener(ContextClosedEvent::class)
    fun onClosed() {
        logger.info("Weather MCP server stopped")
    }
}
