package com.example.weatheragent

import com.example.weatheragent.config.AgentProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication

@SpringBootApplication
@EnableConfigurationProperties(AgentProperties::class)
class WeatherAgentApplication

fun main(args: Array<String>) {
    runApplication<WeatherAgentApplication>(*args)
}
