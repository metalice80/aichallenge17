package dev.aichallenge.weather.agent.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import java.util.concurrent.ThreadPoolExecutor

@Configuration(proxyBeanMethods = false)
class ExecutorConfiguration {
    @Bean("pipelineExecutor")
    fun pipelineExecutor(properties: AgentProperties): ThreadPoolTaskExecutor = ThreadPoolTaskExecutor().apply {
        corePoolSize = properties.pipeline.executorThreads
        maxPoolSize = properties.pipeline.executorThreads
        queueCapacity = properties.pipeline.executorQueueCapacity
        setThreadNamePrefix("pipeline-agent-")
        setRejectedExecutionHandler(ThreadPoolExecutor.AbortPolicy())
        setWaitForTasksToCompleteOnShutdown(true)
        setAwaitTerminationSeconds(30)
        initialize()
    }

    @Bean("pipelineEventScheduler")
    fun pipelineEventScheduler(): ThreadPoolTaskScheduler = ThreadPoolTaskScheduler().apply {
        poolSize = 2
        setThreadNamePrefix("pipeline-events-")
        setWaitForTasksToCompleteOnShutdown(false)
        initialize()
    }
}
