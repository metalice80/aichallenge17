package com.example.mcpweather

import com.example.mcpweather.config.ApplicationTerminator
import com.example.mcpweather.config.ParentProcessWatchdog
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.time.Instant
import java.util.Optional

class ParentProcessWatchdogTest {
    @Test
    fun `terminates orphaned server once when original parent exits`() {
        val parent = mock<ProcessHandle>()
        val info = mock<ProcessHandle.Info>()
        val terminator = mock<ApplicationTerminator>()
        `when`(parent.info()).thenReturn(info)
        `when`(info.startInstant()).thenReturn(Optional.of(Instant.parse("2026-09-28T00:00:00Z")))
        `when`(parent.pid()).thenReturn(1234)
        `when`(parent.isAlive).thenReturn(false)
        val watchdog = ParentProcessWatchdog(parent, terminator)

        watchdog.stopIfParentExited()
        watchdog.stopIfParentExited()

        verify(terminator, times(1)).shutdownBecauseParentExited(1234)
    }
}
