package dev.aichallenge.day20.agent.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic.AtomicInteger

@Configuration(proxyBeanMethods = false)
class ExecutorConfiguration {
    @Bean(destroyMethod = "shutdown")
    fun orchestrationExecutor(properties: AppProperties): ExecutorService {
        val counter = AtomicInteger()
        val factory = ThreadFactory { runnable -> Thread(runnable, "orchestration-${counter.incrementAndGet()}").apply { isDaemon = false } }
        return ThreadPoolExecutor(
            properties.orchestration.executorThreads,
            properties.orchestration.executorThreads,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(properties.orchestration.queueCapacity),
            factory,
            ThreadPoolExecutor.AbortPolicy(),
        )
    }

    @Bean(destroyMethod = "shutdownNow")
    fun orchestrationTimeoutScheduler(): ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "orchestration-timeout").apply { isDaemon = true }
        }
}
