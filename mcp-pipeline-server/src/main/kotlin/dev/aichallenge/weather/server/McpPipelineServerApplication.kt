package dev.aichallenge.weather.server

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import java.nio.file.Files
import java.nio.file.Path

@SpringBootApplication
@ConfigurationPropertiesScan
class McpPipelineServerApplication

fun main(args: Array<String>) {
    val db = Path.of(System.getenv("PIPELINE_DB_PATH") ?: "./data/pipeline.db").toAbsolutePath().normalize()
    val reports = Path.of(System.getenv("REPORTS_DIR") ?: "./reports").toAbsolutePath().normalize()
    db.parent?.let(Files::createDirectories)
    Files.createDirectories(reports)
    System.setProperty("pipeline.db.absolute", db.toString())
    System.setProperty("reports.absolute", reports.toString())
    runApplication<McpPipelineServerApplication>(*args)
}
