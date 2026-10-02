package com.example.chat

private data class ParsedReleaseVersion(
    val numericParts: List<Int>,
    val prerelease: Boolean,
)

fun comparableReleaseVersion(release: AppReleaseInfo): String {
    val version = release.version.ifBlank { release.versionLabel }.trim()
    if (release.channel != AppReleaseChannel.PRERELEASE || version.isPrereleaseLabel()) {
        return version
    }
    return "$version(beta)"
}

fun compareVersionNames(left: String, right: String): Int {
    val parsedLeft = parseReleaseVersion(left)
    val parsedRight = parseReleaseVersion(right)
    val size = maxOf(parsedLeft.numericParts.size, parsedRight.numericParts.size)
    for (index in 0 until size) {
        val leftPart = parsedLeft.numericParts.getOrElse(index) { 0 }
        val rightPart = parsedRight.numericParts.getOrElse(index) { 0 }
        if (leftPart != rightPart) return leftPart.compareTo(rightPart)
    }
    if (parsedLeft.prerelease != parsedRight.prerelease) {
        return if (parsedLeft.prerelease) -1 else 1
    }
    return 0
}

fun isReleaseUpdateAvailable(release: AppReleaseInfo?, currentVersion: String): Boolean =
    release != null && compareVersionNames(comparableReleaseVersion(release), currentVersion) > 0

private fun parseReleaseVersion(value: String): ParsedReleaseVersion {
    val normalized = value.trim()
    return ParsedReleaseVersion(
        numericParts = Regex("\\d+")
            .findAll(normalized)
            .map { it.value.toIntOrNull() ?: 0 }
            .toList(),
        prerelease = normalized.isPrereleaseLabel(),
    )
}

private fun String.isPrereleaseLabel(): Boolean =
    contains("(beta)", ignoreCase = true) ||
        contains("(pre)", ignoreCase = true) ||
        contains("prerelease", ignoreCase = true)
