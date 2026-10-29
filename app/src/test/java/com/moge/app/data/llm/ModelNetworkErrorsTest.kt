package com.moge.app.data.llm

import java.io.FileNotFoundException
import java.net.SocketException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ModelNetworkErrorsTest {
    @Test fun transportTimeoutKeepsItsPhaseAndClassification() = runBlocking {
        val transport = ModelTransportFailure(ModelTransportTrace.Phase.UPLOAD, null, 12345, false,
            "http/1.1", SocketTimeoutException("private.example/token"))
        val error = runCatching { withModelNetworkErrors { throw transport } }.exceptionOrNull() as ModelException
        assertEquals(ModelException.Kind.NETWORK, error.kind)
        assertTrue(error.message!!.contains("超时"))
        assertTrue(error.message!!.contains("上传请求内容"))
        assertFalse(error.message!!.contains("private"))
        assertSame(transport, error.cause)
        assertFalse(error.isSearchFallbackAllowed)
    }

    @Test fun readFailureKeepsTheReadStageWithoutBeingReportedAsATimeout() = runBlocking {
        val transport = ModelTransportFailure(ModelTransportTrace.Phase.READ_RESPONSE, 123L, 123, true,
            "h2", SocketException("ECONNRESET private.example/token"))
        val error = runCatching { withModelNetworkErrors { throw transport } }.exceptionOrNull() as ModelException
        assertEquals(ModelException.Kind.NETWORK, error.kind)
        assertTrue(error.message!!.contains("接收响应"))
        assertFalse(error.message!!.contains("超时"))
        assertFalse(error.message!!.contains("private"))
        assertSame(transport, error.cause)
    }

    @Test fun localAttachmentFailuresAndCancellationKeepTheirOriginalIdentity() = runBlocking {
        for (error in listOf(FileNotFoundException("private attachment"), CancellationException("stopped"))) {
            assertSame(error, runCatching { withModelNetworkErrors { throw error } }.exceptionOrNull())
        }
    }
}
