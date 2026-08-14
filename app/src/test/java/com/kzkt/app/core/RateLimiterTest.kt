package com.kzkt.app.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RateLimiterTest {

    @Test
    fun `success on first attempt returns the result`() = runBlocking {
        val rl = RateLimiter(0)
        assertEquals(42, rl.executeWithRetry(apiCall = { 42 }, providerName = "Test"))
    }

    @Test
    fun `api key errors are not retried and surface immediately`() = runBlocking {
        val rl = RateLimiter(0)
        var calls = 0
        val thrown = try {
            rl.executeWithRetry(
                apiCall = { calls++; throw RuntimeException("API_KEY_ERROR: invalid key") },
                providerName = "Test",
            )
            null
        } catch (e: RuntimeException) {
            e
        }
        assertNotNull(thrown)
        assertEquals(1, calls)
    }

    @Test
    fun `cancellation propagates without retrying`() = runBlocking {
        val rl = RateLimiter(0)
        var calls = 0
        val thrown = try {
            rl.executeWithRetry(
                apiCall = { calls++; throw CancellationException("stop") },
                providerName = "Test",
            )
            null
        } catch (e: CancellationException) {
            e
        }
        assertNotNull(thrown)
        assertEquals(1, calls)
    }

    @Test
    fun `maxRetries 1 rethrows on first failure without delay`() = runBlocking {
        val rl = RateLimiter(0)
        var calls = 0
        val thrown = try {
            rl.executeWithRetry(
                apiCall = { calls++; throw RuntimeException("boom") },
                providerName = "Test",
                maxRetries = 1,
            )
            null
        } catch (e: RuntimeException) {
            e
        }
        assertNotNull(thrown)
        assertEquals(1, calls)
    }

    @Test
    fun `cancelled before call returns null without invoking api`() = runBlocking {
        val rl = RateLimiter(0)
        var calls = 0
        val result = rl.executeWithRetry(
            apiCall = { calls++; "x" },
            providerName = "Test",
            isCancelled = { true },
        )
        assertNull(result)
        assertEquals(0, calls)
    }

    @Test
    fun `waitForSlot enforces minimum delay between slots`() = runBlocking {
        val rl = RateLimiter(60)
        val start = System.currentTimeMillis()
        rl.waitForSlot() // first call: no sleep
        rl.waitForSlot() // second call: must sleep ~60ms
        val elapsed = System.currentTimeMillis() - start
        assertTrue("expected >= 55ms delay, got $elapsed", elapsed >= 55)
    }
}
