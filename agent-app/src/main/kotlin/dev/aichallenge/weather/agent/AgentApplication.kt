package dev.aichallenge.weather.agent

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import java.nio.file.Files
import java.nio.file.Path

@SpringBootApplication
@ConfigurationPropertiesScan
class AgentApplication

fun main(args: Array<String>) {
    val serverJar = Path.of(System.getenv("MCP_SERVER_JAR") ?: "./mcp-pipeline-server/build/libs/mcp-pipeline-server.jar")
        .toAbsolutePath().normalize()
    if (!Files.isRegularFile(serverJar)) {
        error("MCP server jar not found at $serverJar. Build it with ./gradlew :mcp-pipeline-server:bootJar")
    }
    System.setProperty("mcp.server.jar.absolute", serverJar.toString())
    runApplication<AgentApplication>(*args)
}

private val logger = LoggerFactory.getLogger(AgentApplication::class.java)
