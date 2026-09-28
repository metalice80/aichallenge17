package dev.aichallenge.day20.agent.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.nio.file.Path
import java.time.Duration

@ConfigurationProperties("app")
data class AppProperties(
    val agent: Agent = Agent(),
    val orchestration: Orchestration = Orchestration(),
    val files: Files = Files(),
    val mcp: Mcp = Mcp(),
) {
    data class Agent(
        val model: String = "gpt-5.6-terra",
        val reasoningEffort: String = "none",
        val apiKeyConfigured: String = "",
        val executionTimeout: Duration = Duration.ofSeconds(90),
    )

    data class Orchestration(
        val executorThreads: Int = 2,
        val queueCapacity: Int = 20,
        val staleRunTimeout: Duration = Duration.ofMinutes(5),
        val eventPollInterval: Duration = Duration.ofMillis(500),
        val sseTimeout: Duration = Duration.ofMinutes(2),
        val maxTotalToolCalls: Int = 12,
    )

    data class Files(val reportsDirectory: Path = Path.of("./reports"))

    data class Mcp(
        val weatherJar: Path = Path.of("mcp-weather-server/build/libs/mcp-weather-server.jar"),
        val guideJar: Path = Path.of("mcp-guide-server/build/libs/mcp-guide-server.jar"),
        val filesJar: Path = Path.of("mcp-files-server/build/libs/mcp-files-server.jar"),
    )
}
