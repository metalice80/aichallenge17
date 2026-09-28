package dev.aichallenge.day20.agent.config

import io.modelcontextprotocol.client.McpSyncClient
import org.springframework.ai.mcp.McpToolFilter
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider
import org.springframework.ai.mcp.ToolContextToMcpMetaConverter
import org.springframework.ai.tool.ToolCallback
import org.springframework.boot.health.contributor.Health
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.slf4j.LoggerFactory

@Configuration(proxyBeanMethods = false)
class McpConfiguration {
    private val expected = mapOf(
        "weather-server" to setOf("weather_resolve_location", "weather_get_forecast"),
        "guide-server" to setOf("guide_search_articles", "guide_get_article_summary"),
        "files-server" to setOf("files_save_markdown_report"),
    )

    @Bean
    fun mcpToolFilter(): McpToolFilter = McpToolFilter { connection, tool ->
        val serverName = connection.initializeResult()?.serverInfo()?.name()
        expected[serverName]?.contains(tool.name()) == true
    }

    @Bean
    fun toolContextToMcpMetaConverter(): ToolContextToMcpMetaConverter = ToolContextToMcpMetaConverter.defaultConverter()

    @Bean
    fun mcpToolRegistry(provider: SyncMcpToolCallbackProvider, clients: List<McpSyncClient>): McpToolRegistry = McpToolRegistry(provider, clients, expected)

    @Bean
    fun mcpHealthIndicator(registry: McpToolRegistry): HealthIndicator = HealthIndicator {
        if (registry.ready) Health.up().withDetail("servers", registry.servers).withDetail("tools", registry.callbacks.size).build()
        else Health.down().withDetail("error", registry.error ?: "MCP discovery incomplete").build()
    }
}

class McpToolRegistry(
    provider: SyncMcpToolCallbackProvider,
    clients: List<McpSyncClient>,
    expected: Map<String, Set<String>>,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    val callbacks: List<ToolCallback>
    val servers: Map<String, List<String>>
    val ready: Boolean
    val error: String?

    init {
        var discovered = emptyList<ToolCallback>()
        var discoveredServers = emptyMap<String, List<String>>()
        var failure: String? = null
        try {
            discovered = provider.toolCallbacks.toList()
            discoveredServers = clients.associate { client ->
                val server = client.serverInfo.name()
                val tools = client.listTools().tools().map { it.name() }.sorted()
                logger.info("connection server={} tools={}", server, tools.size)
                server to tools
            }
            val actualNames = discovered.map { it.toolDefinition.name() }
            require(actualNames.size == actualNames.toSet().size) { "MCP tool names are not unique" }
            require(actualNames.toSet() == expected.values.flatten().toSet()) { "Expected exactly five allowlisted MCP tools, got $actualNames" }
            require(discoveredServers.keys == expected.keys) { "Expected MCP servers ${expected.keys}, got ${discoveredServers.keys}" }
            expected.forEach { (server, tools) -> require(discoveredServers[server]?.toSet() == tools) { "$server exposed unexpected tools" } }
        } catch (exception: Exception) {
            failure = exception.message ?: exception.javaClass.simpleName
            logger.error("MCP discovery failed: {}", failure)
        }
        callbacks = discovered
        servers = discoveredServers
        error = failure
        ready = failure == null
    }

    fun requireReady() {
        check(ready) { "MCP_SERVER_UNAVAILABLE: ${error ?: "MCP discovery incomplete"}" }
    }
}
