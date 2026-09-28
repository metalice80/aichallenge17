package dev.aichallenge.weather.server.config

import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteDataSource
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import javax.sql.DataSource

@Configuration(proxyBeanMethods = false)
class DatabaseConfiguration {
    @Bean
    fun dataSource(@Value("\${spring.datasource.url}") url: String): DataSource {
        if (url.startsWith("jdbc:sqlite:") && url != "jdbc:sqlite::memory:") {
            val path = Path.of(url.removePrefix("jdbc:sqlite:")).toAbsolutePath().normalize()
            path.parent?.let(Files::createDirectories)
        }
        val config = SQLiteConfig().apply {
            enforceForeignKeys(true)
            setJournalMode(SQLiteConfig.JournalMode.WAL)
            setBusyTimeout(5_000)
        }
        val dataSource = SQLiteDataSource(config).apply { this.url = url }
        ResourceDatabasePopulator(ClassPathResource("schema.sql")).execute(dataSource)
        return dataSource
    }

    @Bean
    fun jdbcTemplate(dataSource: DataSource) = JdbcTemplate(dataSource)

    @Bean
    fun objectMapper(): ObjectMapper = ObjectMapper()
        .registerKotlinModule()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)

    @Bean
    fun clock(): Clock = Clock.systemUTC()
}
