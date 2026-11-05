package com.github.gionnio.platea.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class RebufferPolicyTest {
    @Test
    fun repeatedRebuffersAskForMore() {
        val policy = RebufferPolicy()
        assertEquals(5_000, policy.requiredMs(0))
        policy.resumed(0)
        assertEquals(10_000, policy.requiredMs(10_000))
        policy.resumed(10_000)
        assertEquals(20_000, policy.requiredMs(30_000))
        policy.resumed(30_000)
        // Never more than the last step
        assertEquals(20_000, policy.requiredMs(60_000))
    }

    @Test
    fun aQuietStretchStartsAgainFromTheFirstStep() {
        val policy = RebufferPolicy(windowMs = 120_000)
        policy.resumed(0)
        policy.resumed(10_000)
        assertEquals(20_000, policy.requiredMs(20_000))
        // Two minutes after the last resume, the old rebuffers no longer count
        assertEquals(5_000, policy.requiredMs(130_001))
    }
}
