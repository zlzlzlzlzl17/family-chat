package com.example.chat

import android.app.Application
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

internal data class ReleaseCheckResult(
    val release: AppReleaseInfo?,
    val downloaded: DecryptedAttachment?,
    val status: String,
)

/** Owns update discovery, package caching, and APK integrity checks. */
internal class UpdateCoordinator(
    private val app: Application,
    private val repository: UpdateRepository,
) {
    suspend fun check(
        serverUrl: String,
        token: String,
        channel: AppReleaseChannel,
        strings: AppStrings,
    ): ReleaseCheckResult = withContext(Dispatchers.IO) {
        val release = repository.release(serverUrl, token, channel)
        val downloaded = release
            ?.takeIf { it.version.isNotBlank() }
            ?.let { resolveDownloadedRelease(it, comparableReleaseVersion(it), releaseFileNameFor(it)) }
        val comparableVersion = release?.let(::comparableReleaseVersion).orEmpty()
        val status = when (channel) {
            AppReleaseChannel.STABLE -> when {
                release == null || release.version.isBlank() || release.downloadUrl.isBlank() -> strings.noReleaseUploaded
                compareVersionNames(comparableVersion, currentAppVersion()) > 0 ->
                    "${strings.updateAvailable}: ${release.versionLabel.ifBlank { release.version }}"
                else -> strings.latestVersionInstalled
            }
            AppReleaseChannel.PRERELEASE -> when {
                release == null || release.version.isBlank() || release.downloadUrl.isBlank() -> strings.noPrereleaseUploaded
                compareVersionNames(comparableVersion, currentAppVersion()) > 0 ->
                    "${strings.prereleaseAvailable}: ${release.versionLabel.ifBlank { release.version }}"
                else -> strings.latestVersionInstalled
            }
        }
        ReleaseCheckResult(release, downloaded, status)
    }

    suspend fun download(
        context: Context,
        serverUrl: String,
        release: AppReleaseInfo,
        channel: AppReleaseChannel,
        strings: AppStrings,
        onProgress: (Long, Long) -> Unit,
    ): DecryptedAttachment = withContext(Dispatchers.IO) {
        if (release.downloadUrl.isBlank()) {
            throw IllegalStateException(
                if (channel == AppReleaseChannel.STABLE) strings.noReleaseUploaded else strings.noPrereleaseUploaded
            )
        }
        val comparableVersion = comparableReleaseVersion(release)
        if (compareVersionNames(comparableVersion, currentAppVersion()) <= 0) {
            throw IllegalStateException(strings.latestVersionInstalled)
        }
        val fileName = releaseFileNameFor(release)
        resolveDownloadedRelease(release, comparableVersion, fileName)?.let { return@withContext it }

        val bytes = repository.download(serverUrl, release.downloadUrl, onProgress)
        val privateFile = repository.packageFile(context, fileName).apply { writeBytes(bytes) }
        validateDownloadedRelease(privateFile, release, comparableVersion)
        DecryptedAttachment(
            name = fileName,
            mime = "application/vnd.android.package-archive",
            size = bytes.size.toLong(),
            file = privateFile,
        )
    }

    fun cleanupInstalledPackages() {
        val currentVersion = currentAppVersion()
        cleanupVersionedFiles(repository.packageFile(app, "placeholder.apk").parentFile, currentVersion)
        cleanupVersionedFiles(app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), currentVersion)
        cleanupVersionedFiles(File(app.cacheDir, "shared"), currentVersion)
        cleanupLegacyDownloadedApks(currentVersion)
    }

    private fun currentAppVersion(): String =
        runCatching {
            app.packageManager.getPackageInfo(app.packageName, 0)
                .versionName
                ?.ifBlank { "0.0.0" }
                ?: "0.0.0"
        }.getOrDefault("0.0.0")

    private fun releaseFileNameFor(release: AppReleaseInfo): String =
        release.fileName.ifBlank {
            when (release.channel) {
                AppReleaseChannel.STABLE -> "familychat_${release.version}.apk"
                AppReleaseChannel.PRERELEASE -> "familychat_${release.version}(beta).apk"
            }
        }

    private fun cleanupVersionedFiles(directory: File?, currentVersion: String) {
        directory?.listFiles()?.forEach { file ->
            val version = if (file.extension.equals("apk", true)) {
                inspectApkVersion(file)
                    ?: Regex("v\\d+(?:\\.\\d+)+(?:\\((?:pre|beta)\\))?", RegexOption.IGNORE_CASE).find(file.name)?.value
            } else {
                Regex("v\\d+(?:\\.\\d+)+(?:\\((?:pre|beta)\\))?", RegexOption.IGNORE_CASE).find(file.name)?.value
            }
            if (version != null && compareVersionNames(version, currentVersion) <= 0) {
                runCatching { file.delete() }
            }
        }
    }

    private fun cleanupLegacyDownloadedApks(currentVersion: String) {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Downloads.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Files.getContentUri("external")
        }
        runCatching {
            app.contentResolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? OR ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? OR ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ? OR ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
                arrayOf(
                    "familychat_v%.apk",
                    "familychat_v%(beta).apk",
                    "familychat-release-v%.apk",
                    "familychat-prerelease-v%.apk",
                ),
                null,
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIndex) ?: continue
                    val version = Regex(
                        "v\\d+(?:\\.\\d+)+(?:\\((?:pre|beta)\\))?",
                        RegexOption.IGNORE_CASE,
                    ).find(name)?.value ?: continue
                    if (compareVersionNames(version, currentVersion) <= 0) {
                        val uri = ContentUris.withAppendedId(collection, cursor.getLong(idIndex))
                        runCatching { app.contentResolver.delete(uri, null, null) }
                    }
                }
            }
        }
    }

    private fun resolveDownloadedRelease(
        release: AppReleaseInfo,
        expectedVersion: String,
        releaseFileName: String,
    ): DecryptedAttachment? {
        val fileName = releaseFileName.ifBlank {
            when (release.channel) {
                AppReleaseChannel.STABLE -> "familychat_${expectedVersion}.apk"
                AppReleaseChannel.PRERELEASE -> "familychat_${expectedVersion}(beta).apk"
            }
        }
        val file = repository.packageFile(app, fileName)
        if (!file.exists()) return null
        if (runCatching { validateDownloadedRelease(file, release, expectedVersion) }.isFailure) {
            runCatching { file.delete() }
            return null
        }
        return DecryptedAttachment(
            name = fileName,
            mime = "application/vnd.android.package-archive",
            size = file.length(),
            file = file,
        )
    }

    private fun inspectApkVersion(file: File): String? =
        runCatching {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                app.packageManager.getPackageArchiveInfo(file.absolutePath, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                app.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
            }
            info?.versionName?.ifBlank { null }
        }.getOrNull()

    private fun validateDownloadedRelease(file: File, release: AppReleaseInfo, expectedVersion: String) {
        require(file.exists() && file.isFile) { "Downloaded update is missing" }
        if (release.fileSize > 0L) {
            require(file.length() == release.fileSize) { "Downloaded update size does not match the server" }
        }
        if (release.sha256.matches(Regex("[a-fA-F0-9]{64}"))) {
            require(fileSha256(file).equals(release.sha256, ignoreCase = true)) {
                "Downloaded update checksum verification failed"
            }
        }

        val archiveInfo = packageArchiveInfo(file) ?: throw IllegalStateException("Downloaded file is not a valid APK")
        require(archiveInfo.packageName == app.packageName) { "Downloaded APK belongs to a different app" }
        val actualVersion = archiveInfo.versionName?.ifBlank { null }
        require(actualVersion != null && compareVersionNames(actualVersion, expectedVersion) == 0) {
            "Downloaded APK version does not match the server"
        }

        val installedSigners = packageInfo(app.packageName).signerDigests()
        val archiveSigners = archiveInfo.signerDigests()
        require(installedSigners.isNotEmpty() && archiveSigners.any(installedSigners::contains)) {
            "Downloaded APK signature does not match this app"
        }
    }

    private fun packageArchiveInfo(file: File) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        app.packageManager.getPackageArchiveInfo(
            file.absolutePath,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
        )
    } else {
        @Suppress("DEPRECATION")
        app.packageManager.getPackageArchiveInfo(
            file.absolutePath,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                PackageManager.GET_SIGNATURES
            },
        )
    }

    private fun packageInfo(packageName: String) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        app.packageManager.getPackageInfo(
            packageName,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
        )
    } else {
        @Suppress("DEPRECATION")
        app.packageManager.getPackageInfo(
            packageName,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageManager.GET_SIGNING_CERTIFICATES
            } else {
                PackageManager.GET_SIGNATURES
            },
        )
    }

    private fun android.content.pm.PackageInfo.signerDigests(): Set<String> {
        val certificateSignatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            signingInfo?.apkContentsSigners.orEmpty()
        } else {
            @Suppress("DEPRECATION")
            signatures.orEmpty()
        }
        return certificateSignatures.mapTo(mutableSetOf()) { signature ->
            MessageDigest.getInstance("SHA-256")
                .digest(signature.toByteArray())
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        }
    }

    private fun fileSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}
