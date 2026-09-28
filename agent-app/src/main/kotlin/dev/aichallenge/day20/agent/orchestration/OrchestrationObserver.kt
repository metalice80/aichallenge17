package dev.aichallenge.day20.agent.orchestration

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Component
import java.time.Duration

@Component
class OrchestrationObserver(private val meters: MeterRegistry) {
    fun toolCompleted(server: String, tool: String, status: String, duration: Duration) {
        meters.counter("orchestration.tool.calls", "server", server, "tool", tool, "status", status).increment()
        Timer.builder("orchestration.tool.duration").tags("server", server, "tool", tool, "status", status).register(meters).record(duration)
    }

    fun routingRejected(tool: String, reason: String) {
        meters.counter("orchestration.routing.rejections", "tool", tool, "reason", reason).increment()
    }

    fun externalFailure(server: String, errorCode: String) {
        meters.counter("orchestration.external.failures", "server", server, "error_code", errorCode).increment()
    }

    fun runFinished(status: String) {
        meters.counter("orchestration.runs", "status", status).increment()
    }
}
