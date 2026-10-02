package com.example.chat

import android.app.Application
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Centralizes local secret and plaintext cleanup for logout, revocation and account deletion. */
internal class SecurityLifecycleManager(
    private val application: Application,
    private val preferences: ChatPreferences,
    private val local: LocalChatRepository,
) {
    suspend fun wipeForLogout(ownerId: String, clearDeviceId: Boolean = false) = withContext(Dispatchers.IO) {
        val deviceId = preferences.deviceId
        runCatching { local.clearOwner(ownerId) }
        deleteDirectoryContents(File(application.filesDir, "outbox"))
        deleteDirectoryContents(File(application.cacheDir, "shared"))
        application.cacheDir.listFiles()
            ?.filter { it.name.startsWith("pending_upload_") || it.name.startsWith("upload_") || it.name.startsWith("download_") }
            ?.forEach(File::delete)
        runCatching { local.destroyLocalEncryptionKey() }
        DeviceIdentityManager.destroyLocalIdentity(deviceId)
        preferences.destroySecurityState(clearDeviceId)
    }

    private fun deleteDirectoryContents(directory: File) {
        if (!directory.exists()) return
        directory.listFiles()?.forEach { child ->
            if (child.isDirectory) deleteDirectoryContents(child)
            child.delete()
        }
    }
}
