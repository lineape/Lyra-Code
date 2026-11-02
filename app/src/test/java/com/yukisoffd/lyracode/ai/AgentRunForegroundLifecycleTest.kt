package com.yukisoffd.lyracode.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunForegroundLifecycleTest {
    @Test
    fun `a stop after startForeground stops now`() {
        val lifecycle = AgentRunForegroundLifecycle()

        lifecycle.onStartRequested()
        assertFalse(lifecycle.onForegroundStarted())

        assertEquals(StopDecision.STOP_NOW, lifecycle.onStopRequested())
    }

    @Test
    fun `a stop before startForeground is deferred and served once onCreate reaches it`() {
        val lifecycle = AgentRunForegroundLifecycle()

        lifecycle.onStartRequested()
        // The run ended before onCreate called startForeground: stopping the service now would
        // bring it down while it is still waiting for startForeground.
        assertEquals(StopDecision.DEFER, lifecycle.onStopRequested())

        assertTrue(lifecycle.onForegroundStarted())
        // The deferred stop is served exactly once.
        assertFalse(lifecycle.onForegroundStarted())
        assertEquals(StopDecision.IGNORE, lifecycle.onStopRequested())
    }

    @Test
    fun `a stop with no start request is ignored`() {
        val lifecycle = AgentRunForegroundLifecycle()

        assertEquals(StopDecision.IGNORE, lifecycle.onStopRequested())
    }

    @Test
    fun `a second start then a stop stops now`() {
        val lifecycle = AgentRunForegroundLifecycle()

        lifecycle.onStartRequested()
        lifecycle.onForegroundStarted()
        lifecycle.onStartRequested()

        assertEquals(StopDecision.STOP_NOW, lifecycle.onStopRequested())
    }

    @Test
    fun `a destroyed service serves no stale stop`() {
        val lifecycle = AgentRunForegroundLifecycle()

        lifecycle.onStartRequested()
        assertEquals(StopDecision.DEFER, lifecycle.onStopRequested())
        lifecycle.onDestroyed()

        assertEquals(StopDecision.IGNORE, lifecycle.onStopRequested())
    }

    @Test
    fun `a start after a deferred stop serves the newer run`() {
        val lifecycle = AgentRunForegroundLifecycle()

        lifecycle.onStartRequested()
        assertEquals(StopDecision.DEFER, lifecycle.onStopRequested())
        // The previous start never reached onCreate (a blocked start); a new run starts.
        lifecycle.onStartRequested()
        assertFalse(lifecycle.onForegroundStarted())
    }
}
