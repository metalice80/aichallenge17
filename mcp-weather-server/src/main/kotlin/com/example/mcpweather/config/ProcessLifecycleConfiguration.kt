package com.example.mcpweather.config

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

@Configuration
class ProcessLifecycleConfiguration {
    @Bean("mcpParentProcess")
    fun parentProcess(): ProcessHandle = ProcessHandle.current().parent()
        .orElseThrow { IllegalStateException("MCP server must have a parent process") }
}

@Component
class ParentProcessWatchdog(
    @Qualifier("mcpParentProcess") private val parent: ProcessHandle,
    private val terminator: ApplicationTerminator,
) {
    private val parentStartedAt: Instant? = parent.info().startInstant().orElse(null)
    private val stopping = AtomicBoolean(false)

    @Scheduled(fixedDelayString = "\${app.process-watchdog.scan-delay:5s}")
    fun stopIfParentExited() {
        val sameProcess = parent.isAlive && parent.info().startInstant().orElse(null) == parentStartedAt
        if (!sameProcess && stopping.compareAndSet(false, true)) {
            terminator.shutdownBecauseParentExited(parent.pid())
        }
    }
}

@Component
class ApplicationTerminator(
    private val context: ConfigurableApplicationContext,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun shutdownBecauseParentExited(parentPid: Long) {
        logger.warn("Parent process {} exited; stopping orphaned MCP server", parentPid)
        Thread.ofPlatform().name("mcp-parent-exit-shutdown").start(context::close)
    }
}
