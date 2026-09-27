package com.example.mcpweather

import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator
import java.nio.file.Files
import java.nio.file.Path
import javax.sql.DataSource

class TestDatabase private constructor(
    val path: Path,
) : AutoCloseable {
    val dataSource: DataSource = DriverManagerDataSource("jdbc:sqlite:${path.toAbsolutePath()}")
    val jdbcClient: JdbcClient = JdbcClient.create(dataSource)

    init {
        ResourceDatabasePopulator(ClassPathResource("schema.sql")).execute(dataSource)
    }

    override fun close() {
        Files.deleteIfExists(path)
        Files.deleteIfExists(Path.of("$path-wal"))
        Files.deleteIfExists(Path.of("$path-shm"))
    }

    companion object {
        fun create(directory: Path, name: String = "weather-test.db"): TestDatabase {
            val path = directory.resolve(name)
            Files.deleteIfExists(path)
            return TestDatabase(path)
        }

        fun open(path: Path): TestDatabase = TestDatabase(path)
    }
}
