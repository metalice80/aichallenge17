package dev.aichallenge.day20.agent.config

import dev.aichallenge.day20.agent.orchestration.OrchestrationService
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration(proxyBeanMethods = false)
class MetricsConfiguration {
    @Bean
    fun orchestrationActiveGauge(registry: MeterRegistry, service: OrchestrationService): Gauge =
        Gauge.builder("orchestration.active", service.active) { it.get().toDouble() }.register(registry)
}
