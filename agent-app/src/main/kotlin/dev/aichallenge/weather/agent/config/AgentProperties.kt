package dev.aichallenge.weather.agent.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("app")
data class AgentProperties(
    var agent: Agent = Agent(),
    var pipeline: Pipeline = Pipeline(),
) {
    data class Agent(
        var model: String = "gpt-4o-mini",
        var reasoningEffort: String = "",
        var timeout: Duration = Duration.ofSeconds(90),
    )
    data class Pipeline(
        var executorThreads: Int = 2,
        var executorQueueCapacity: Int = 20,
        var eventPollInterval: Duration = Duration.ofMillis(500),
        var sseTimeout: Duration = Duration.ofMinutes(2),
    )
}
