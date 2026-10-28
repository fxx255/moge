package com.moge.app.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.moge.app.data.llm.GenerationNetworkSession
import com.moge.app.data.llm.NetworkAvailability
import com.moge.app.data.llm.NetworkRecoveryStatus
import com.moge.app.data.llm.OkHttpCancellation
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Controlled network availability on Android; MuMu radio changes also disrupt its ADB transport. */
@RunWith(AndroidJUnit4::class)
class NetworkRecoveryDeviceTest {
    @Test fun disconnectedSessionWaitsThenSendsOnceWhenConnectivityReturns() = runBlocking(Dispatchers.IO) {
        val ready = CompletableDeferred<Boolean>()
        val waiting = CompletableDeferred<Unit>()
        val requests = AtomicInteger(0)
        val network = object : NetworkAvailability {
            override val available = false
            override suspend fun awaitAvailable(timeoutMs: Long) = ready.await()
        }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body("recovered".toResponseBody()).build()
        }.build()
        val pending = async(GenerationNetworkSession(network, {
            if (it == NetworkRecoveryStatus.WAITING) waiting.complete(Unit)
        })) {
            OkHttpCancellation.execute(client, Request.Builder().url("https://local-fixture.invalid/").build())
                .use { it.body!!.string() }
        }
        try {
            withTimeout(5000) { waiting.await() }
            assertEquals(0, requests.get())
            ready.complete(true)
            assertEquals("recovered", withTimeout(5000) { pending.await() })
            assertEquals(1, requests.get())
        } finally {
            pending.cancelAndJoin()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
        }
    }
}
