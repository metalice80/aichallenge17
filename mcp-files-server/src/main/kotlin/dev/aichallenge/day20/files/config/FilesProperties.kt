package dev.aichallenge.day20.files.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.nio.file.Path

@ConfigurationProperties("app.files")
data class FilesProperties(
    val reportsDirectory: Path,
    val maxContentBytes: Int = 51_200,
)
