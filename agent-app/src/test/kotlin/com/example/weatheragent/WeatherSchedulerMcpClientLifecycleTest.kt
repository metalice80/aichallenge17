package com.example.weatheragent

import com.example.weatheragent.scheduler.WeatherSchedulerMcpClient
import com.example.weatheragent.scheduler.ScheduleListResult
import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.spec.McpSchema
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import tools.jackson.databind.ObjectMapper

class WeatherSchedulerMcpClientLifecycleTest {
    @Test
    fun `closes selected client exactly once`() {
        val client = mock<McpSyncClient>()
        whenever(client.isInitialized).thenReturn(true)
        whenever(client.serverInfo).thenReturn(McpSchema.Implementation("weather-mcp-server", "2.0.0"))
        whenever(client.closeGracefully()).thenReturn(true)
        val adapter = WeatherSchedulerMcpClient(listOf(client), mock<ObjectMapper>())

        adapter.close()
        adapter.close()

        verify(client, times(1)).closeGracefully()
    }

    @Test
    fun `serializes calls over the STDIO transport`() {
        val client = mock<McpSyncClient>()
        whenever(client.isInitialized).thenReturn(true)
        whenever(client.serverInfo).thenReturn(McpSchema.Implementation("weather-mcp-server", "2.0.0"))
        val result = mock<McpSchema.CallToolResult>()
        whenever(result.isError).thenReturn(false)
        whenever(result.structuredContent).thenReturn(mapOf("schedules" to emptyList<Any>()))
        val mapper = mock<ObjectMapper>()
        whenever(mapper.convertValue(any(), eq(ScheduleListResult::class.java)))
            .thenReturn(ScheduleListResult(emptyList()))

        val firstEntered = CountDownLatch(1)
        val bothEntered = CountDownLatch(2)
        val release = CountDownLatch(1)
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        whenever(client.callTool(any())).thenAnswer {
            val current = active.incrementAndGet()
            maxActive.accumulateAndGet(current, ::maxOf)
            firstEntered.countDown()
            bothEntered.countDown()
            assertTrue(release.await(2, TimeUnit.SECONDS))
            active.decrementAndGet()
            result
        }
        val adapter = WeatherSchedulerMcpClient(listOf(client), mapper)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val first = executor.submit<List<*>> { adapter.listSchedules() }
            assertTrue(firstEntered.await(1, TimeUnit.SECONDS))
            val second = executor.submit<List<*>> { adapter.listSchedules() }
            assertFalse(bothEntered.await(500, TimeUnit.MILLISECONDS))
            release.countDown()
            first.get(1, TimeUnit.SECONDS)
            second.get(1, TimeUnit.SECONDS)
            assertEquals(1, maxActive.get())
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }
}
