package com.example.chat

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class FamilyChatApplication : Application() {
    internal val container: AppContainer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        AppContainer(this)
    }

    override fun onCreate() {
        super.onCreate()
        FamilyChatDiagnostics.initialize(this)
        NotificationCenter.ensureChannel(this)
    }
}

object FamilyChatDiagnostics {
    private const val MAX_LOG_BYTES = 1_048_576L
    private const val LOG_FILE_COUNT = 3
    private const val MAX_VALUE_LENGTH = 16_000
    private const val LOG_RETENTION_MS = 24L * 60L * 60L * 1000L
    private const val ANR_THRESHOLD_MS = 5_000L
    private const val ANR_REPORT_COOLDOWN_MS = 15_000L
    private val sensitiveField = Regex("authorization|cookie|credential|password|private|refresh|secret|token", RegexOption.IGNORE_CASE)
    private val payloadField = Regex(
        "(^|_)(candidate|cipher|ciphertext|description|message_body|payload|sdp|stack|trace)($|_)",
        RegexOption.IGNORE_CASE,
    )

    private val writer = Executors.newSingleThreadExecutor { task ->
        Thread(task, "familychat-diagnostics").apply { isDaemon = true }
    }
    private val lastSampledAt = ConcurrentHashMap<String, Long>()
    private val appInForeground = AtomicBoolean(true)
    private val lastMainHeartbeat = AtomicLong(System.currentTimeMillis())
    private val lastAnrReport = AtomicLong(0L)
    private val dateFormat = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US)
    }

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var initialized = false

    fun initialize(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            appContext = context.applicationContext
            initialized = true
            installCrashHandler()
            startAnrWatchdog()
            writer.execute {
                trimStoredLogs(context.applicationContext)
                capturePreviousExitReason()
            }
            val version = appVersion(context)
            event(
                "app_start",
                "version" to version.first,
                "version_code" to version.second,
                "manufacturer" to Build.MANUFACTURER,
                "model" to Build.MODEL,
                "android_sdk" to Build.VERSION.SDK_INT
            )
        }
    }

    fun event(name: String, vararg fields: Pair<String, Any?>) {
        val context = appContext ?: return
        writer.execute {
            writeLine(context, formatLine("INFO", name, fields))
        }
    }

    fun sampled(name: String, intervalMs: Long, vararg fields: Pair<String, Any?>) {
        val now = System.currentTimeMillis()
        val previous = lastSampledAt.put(name, now)
        if (previous != null && now - previous < intervalMs) return
        event(name, *fields)
    }

    fun setAppForeground(foreground: Boolean) {
        appInForeground.set(foreground)
        if (foreground) {
            lastMainHeartbeat.set(System.currentTimeMillis())
        }
    }

    fun exportFile(context: Context): File {
        initialize(context)
        runCatching { writer.submit { trimStoredLogs(context.applicationContext) }.get(2, TimeUnit.SECONDS) }
        val exportDir = File(context.cacheDir, "shared").apply { mkdirs() }
        val exportFile = File(exportDir, "familychat-diagnostics-${System.currentTimeMillis()}.txt")
        val version = appVersion(context)
        val cutoff = System.currentTimeMillis() - LOG_RETENTION_MS
        exportFile.bufferedWriter().use { output ->
            output.appendLine("Family Chat diagnostics")
            output.appendLine("Version: ${version.first} (${version.second})")
            output.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            output.appendLine("Android SDK: ${Build.VERSION.SDK_INT}")
            output.appendLine("Exported: ${formatTimestamp(System.currentTimeMillis())}")
            output.appendLine("Included logs: last 24 hours")
            output.appendLine()
            var exportedLines = 0
            logFiles(context)
                .asReversed()
                .filter(File::exists)
                .forEach { log ->
                    val recentLines = recentLogLines(log, cutoff)
                    if (recentLines.isEmpty()) return@forEach
                    output.appendLine("===== ${log.name} =====")
                    recentLines.forEach {
                        output.appendLine(it)
                        exportedLines += 1
                    }
                }
            if (exportedLines == 0) {
                output.appendLine("No recent diagnostic entries.")
            }
        }
        return exportFile
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            appContext?.let { context ->
                writeLine(
                    context,
                    formatLine(
                        "FATAL",
                        "uncaught_exception",
                        arrayOf(
                            "thread" to thread.name,
                            "error" to throwable.javaClass.name,
                            "detail" to throwable.message.orEmpty(),
                            "stack" to stackTrace(throwable)
                        )
                    )
                )
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun startAnrWatchdog() {
        val mainHandler = Handler(Looper.getMainLooper())
        val heartbeat = object : Runnable {
            override fun run() {
                lastMainHeartbeat.set(System.currentTimeMillis())
                mainHandler.postDelayed(this, 1_000L)
            }
        }
        mainHandler.post(heartbeat)
        Thread({
            while (true) {
                runCatching { Thread.sleep(2_000L) }
                val now = System.currentTimeMillis()
                if (
                    appInForeground.get() &&
                    now - lastMainHeartbeat.get() >= ANR_THRESHOLD_MS &&
                    now - lastAnrReport.get() >= ANR_REPORT_COOLDOWN_MS
                ) {
                    lastAnrReport.set(now)
                    val mainFrames = Looper.getMainLooper().thread.stackTrace
                        .take(8)
                        .joinToString(" <- ") { frame ->
                            "${frame.className}.${frame.methodName}:${frame.lineNumber}"
                        }
                    event(
                        "main_thread_stall",
                        "blocked_ms" to (now - lastMainHeartbeat.get()),
                        "main_frame" to mainFrames,
                        "main_stack" to Looper.getMainLooper().thread.stackTrace.joinToString("\n")
                    )
                }
            }
        }, "familychat-anr-watchdog").apply {
            isDaemon = true
            start()
        }
    }

    private fun capturePreviousExitReason() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val context = appContext ?: return
        val preferences = context.getSharedPreferences("familychat_diagnostics", Context.MODE_PRIVATE)
        val lastCapturedAt = preferences.getLong("last_exit_captured_at", 0L)
        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return
        val exit = activityManager
            .getHistoricalProcessExitReasons(context.packageName, 0, 5)
            .filter { it.timestamp > lastCapturedAt }
            .maxByOrNull { it.timestamp }
            ?: return
        preferences.edit().putLong("last_exit_captured_at", exit.timestamp).apply()
        val trace = runCatching {
            exit.traceInputStream?.bufferedReader()?.use { it.readText().take(MAX_VALUE_LENGTH) }
        }.getOrNull().orEmpty()
        event(
            "previous_process_exit",
            "reason" to exit.reason,
            "status" to exit.status,
            "importance" to exit.importance,
            "pss_kb" to exit.pss,
            "rss_kb" to exit.rss,
            "timestamp" to exit.timestamp,
            "description" to exit.description.orEmpty(),
            "trace" to trace
        )
    }

    @Synchronized
    private fun writeLine(context: Context, line: String) {
        runCatching {
            val files = logFiles(context)
            val current = files.first()
            current.parentFile?.mkdirs()
            if (current.exists() && current.length() >= MAX_LOG_BYTES) {
                for (index in LOG_FILE_COUNT - 1 downTo 1) {
                    val target = files[index]
                    val source = files[index - 1]
                    if (target.exists()) target.delete()
                    if (source.exists()) source.renameTo(target)
                }
            }
            current.appendText("$line\n")
        }
    }

    @Synchronized
    private fun trimStoredLogs(context: Context) {
        val cutoff = System.currentTimeMillis() - LOG_RETENTION_MS
        logFiles(context)
            .filter(File::exists)
            .forEach { log ->
                val recentLines = recentLogLines(log, cutoff)
                if (recentLines.isEmpty()) {
                    log.delete()
                } else {
                    log.bufferedWriter().use { output ->
                        recentLines.forEach(output::appendLine)
                    }
                }
            }
    }

    private fun recentLogLines(log: File, cutoff: Long): List<String> {
        val lines = mutableListOf<String>()
        log.forEachLine { line ->
            if (shouldKeepLogLine(line, cutoff)) {
                lines += line
            }
        }
        return lines
    }

    private fun shouldKeepLogLine(line: String, cutoff: Long): Boolean {
        val timestamp = parseLogTimestampMillis(line)
        return timestamp == null || timestamp >= cutoff
    }

    private fun parseLogTimestampMillis(line: String): Long? {
        val end = line.indexOf(' ')
        if (end <= 0) return null
        return runCatching {
            dateFormat.get()?.parse(line.substring(0, end))?.time
        }.getOrNull()
    }

    private fun logFiles(context: Context): List<File> {
        val directory = File(context.filesDir, "diagnostics")
        return List(LOG_FILE_COUNT) { index -> File(directory, "familychat-$index.log") }
    }

    private fun formatLine(level: String, name: String, fields: Array<out Pair<String, Any?>>): String {
        val metadata = fields.joinToString(" ") { (key, value) ->
            "$key=${sanitizeField(key, value)}"
        }
        return buildString {
            append(formatTimestamp(System.currentTimeMillis()))
            append(" level=").append(level)
            append(" thread=").append(sanitize(Thread.currentThread().name))
            append(" event=").append(sanitize(name))
            if (metadata.isNotBlank()) append(' ').append(metadata)
        }
    }

    private fun sanitizeField(key: String, value: Any?): String = when {
        sensitiveField.containsMatchIn(key) -> "[redacted]"
        payloadField.containsMatchIn(key) -> "[omitted:${value?.toString()?.length ?: 0}]"
        else -> sanitize(value)
    }

    private fun sanitize(value: Any?): String =
        value
            ?.toString()
            .orEmpty()
            .replace(Regex("Bearer\\s+[A-Za-z0-9._~-]+", RegexOption.IGNORE_CASE), "Bearer [redacted]")
            .replace(Regex("-----BEGIN [^-]+-----.*?-----END [^-]+-----", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "[redacted-pem]")
            .replace("\r", "\\r")
            .replace("\n", "\\n")
            .take(MAX_VALUE_LENGTH)

    private fun formatTimestamp(timestamp: Long): String =
        dateFormat.get()?.format(Date(timestamp)) ?: timestamp.toString()

    private fun stackTrace(throwable: Throwable): String {
        val output = StringWriter()
        throwable.printStackTrace(PrintWriter(output))
        return output.toString().take(16_000)
    }

    private fun appVersion(context: Context): Pair<String, Long> =
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
            (info.versionName ?: "unknown") to versionCode
        }.getOrDefault("unknown" to 0L)
}
