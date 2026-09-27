package com.example.weatheragent.config

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

@ConfigurationProperties(prefix = "app.agent")
@Validated
data class AgentProperties(
    @field:NotBlank
    val model: String,
    @field:Min(1)
    @field:Max(20_000)
    val maxMessageLength: Int,
)
