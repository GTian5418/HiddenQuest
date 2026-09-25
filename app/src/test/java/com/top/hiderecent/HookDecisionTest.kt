package com.top.hiderecent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class HookDecisionTest {
    @Test
    fun fallsBackToProceedWhenEvaluateThrows() {
        val proceedCount = AtomicInteger(0)
        val result = HookDecision.evaluateOrProceed(
            proceed = { proceedCount.incrementAndGet(); "proceed" },
            evaluate = { error("boom") }
        )
        assertEquals("proceed", result)
        assertEquals(1, proceedCount.get())
    }

    @Test
    fun doesNotProceedWhenEvaluateReturnsDecision() {
        val proceedCount = AtomicInteger(0)
        val result = HookDecision.evaluateOrProceed(
            proceed = { proceedCount.incrementAndGet(); "proceed" },
            evaluate = { "hide" }
        )
        assertEquals("hide", result)
        assertEquals(0, proceedCount.get())
    }

    @Test
    fun reportsErrorAndProceedsOnce() {
        val proceedCount = AtomicInteger(0)
        var errorCalled = false
        HookDecision.evaluateOrProceed(
            proceed = { proceedCount.incrementAndGet(); Unit },
            onError = { errorCalled = true },
            evaluate = { throw IllegalStateException("x") }
        )
        assertEquals(1, proceedCount.get())
        assertTrue(errorCalled)
    }
}
