package dev.aichallenge.day20.files

import dev.aichallenge.day20.files.config.FilesProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication

@SpringBootApplication
@EnableConfigurationProperties(FilesProperties::class)
class FilesServerApplication

fun main(args: Array<String>) {
    runApplication<FilesServerApplication>(*args)
}
