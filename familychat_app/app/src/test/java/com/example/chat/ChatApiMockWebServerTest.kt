package com.example.chat

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class ChatApiMockWebServerTest {
    private lateinit var server: MockWebServer
    private lateinit var api: ChatApi

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        api = ChatApi()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun betaReleaseUsesAuthenticatedChannelRequestAndParsesHash() {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"item":{"version":"v3.0.0(beta)","file_name":"familychat_v3.0.0(beta).apk","file_size":42,"download_url":"/api/app_release/download?channel=beta","sha256":"abc123","channel":"beta"}}"""
            )
        )

        val release = api.appRelease(server.url("/").toString(), "access-token", AppReleaseChannel.PRERELEASE)
        val request = server.takeRequest()

        assertNotNull(release)
        assertEquals("v3.0.0(beta)", release!!.version)
        assertEquals("abc123", release.sha256)
        assertEquals("/api/app_release?channel=beta", request.path)
        assertEquals("Bearer access-token", request.getHeader("Authorization"))
        assertEquals(APP_CLIENT_HEADER_VALUE, request.getHeader(APP_CLIENT_HEADER_NAME))
    }

    @Test
    fun resumableUploadSendsOffsetAndUsesServerCheckpoint() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"offset\":8192}"))

        val offset = api.uploadAttachmentChunk(
            serverUrl = server.url("/").toString(),
            token = "access-token",
            uploadId = "upload-1",
            offset = 4096L,
            bytes = ByteArray(4096) { 7 },
        )
        val request = server.takeRequest()

        assertEquals(8192L, offset)
        assertEquals("4096", request.getHeader("X-Upload-Offset"))
        assertEquals(4096L, request.bodySize)
        assertEquals("/api/attachment_uploads/upload-1/chunk", request.path)
    }

    @Test
    fun refreshFailurePreservesHttpStatusAndServerErrorCode() {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"error":"session_expired"}""")
        )

        try {
            api.refreshSession(server.url("/").toString(), "r".repeat(48), "device-12345678")
            fail("Expected ChatApiException")
        } catch (error: ChatApiException) {
            assertEquals(401, error.statusCode)
            assertEquals("session_expired", error.errorCode)
        }
    }
}
