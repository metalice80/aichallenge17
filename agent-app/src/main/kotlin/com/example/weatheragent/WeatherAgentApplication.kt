package com.example.weatheragent

import com.example.weatheragent.config.AgentProperties
import com.example.weatheragent.config.SummaryPublisherProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication

@SpringBootApplication
@EnableConfigurationProperties(AgentProperties::class, SummaryPublisherProperties::class)
class WeatherAgentApplication

fun main(args: Array<String>) {
    runApplication<WeatherAgentApplication>(*args)
}
