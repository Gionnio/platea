package com.github.gionnio.platea.services

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class KeepSubscribedTest {
    @Test
    fun `A stream that fails is opened again after the pause`() =
        runTest {
            var opened = 0
            val received = mutableListOf<Int>()
            val job =
                backgroundScope.keepSubscribed(
                    "test",
                    retryMillis = 1_000,
                    subscribe = {
                        opened++
                        flow {
                            emit(opened)
                            throw IOException("connection lost")
                        }
                    },
                ) { received.add(it) }

            runCurrent()
            assertEquals(1, opened)
            advanceTimeBy(999)
            assertEquals(1, opened)
            advanceTimeBy(2)
            assertEquals(2, opened)
            assertEquals(listOf(1, 2), received)
            job.cancel()
        }

    @Test
    fun `A stream that ends is opened again and stops with the scope`() =
        runTest {
            var opened = 0
            val job =
                backgroundScope.keepSubscribed("test", retryMillis = 1_000, subscribe = {
                    opened++
                    flow<Int> { }
                }) { }

            runCurrent()
            advanceTimeBy(2_500)
            assertEquals(3, opened)
            job.cancel()
            advanceTimeBy(5_000)
            assertEquals(3, opened)
        }
}
