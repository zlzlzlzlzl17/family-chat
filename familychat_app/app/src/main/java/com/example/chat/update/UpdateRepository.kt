package com.example.chat

import android.content.Context
import java.io.File

internal class UpdateRepository(
    private val api: ChatApi,
) {
    fun release(
        serverUrl: String,
        token: String,
        channel: AppReleaseChannel,
    ): AppReleaseInfo? = api.appRelease(serverUrl, token, channel)

    fun download(
        serverUrl: String,
        path: String,
        onProgress: ((Long, Long) -> Unit)? = null,
    ): ByteArray = api.download(serverUrl, path, onProgress)

    fun packageFile(context: Context, fileName: String): File {
        val updatesDir = File(context.filesDir, "updates").apply { mkdirs() }
        return File(updatesDir, fileName)
    }
}
