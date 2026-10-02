package com.example.chat

enum class AppReleaseChannel(val wireValue: String) {
    STABLE("release"),
    PRERELEASE("beta");

    companion object {
        fun fromWire(value: String?): AppReleaseChannel {
            val normalized = value.orEmpty().trim().lowercase()
            return when (normalized) {
                "stable", "official", "release" -> STABLE
                "pre", "preview", "prerelease", "test", "beta" -> PRERELEASE
                else -> STABLE
            }
        }
    }
}

data class AppReleaseInfo(
    val version: String,
    val fileName: String,
    val originalName: String,
    val fileSize: Long,
    val uploadedAt: Long,
    val downloadUrl: String,
    val sha256: String = "",
    val channel: AppReleaseChannel = AppReleaseChannel.STABLE,
    val versionLabel: String = version,
)
