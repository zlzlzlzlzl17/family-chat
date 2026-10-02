package com.example.chat

import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.media.ExifInterface
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.LruCache
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import com.example.chat.ui.theme.FullShape
import com.example.chat.ui.theme.appBarColors
import com.example.chat.ui.theme.MotionMedium
import com.example.chat.ui.theme.Spacing
import com.example.chat.ui.theme.MotionShort
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppTopBar(
    title: String,
    subtitle: String? = null,
    subtitleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onBack: (() -> Unit)? = null,
    navigationAvatar: (@Composable () -> Unit)? = null,
    // Filled draws the bar in the accent. Used on the app's front door so the
    // product has a face, rather than the accent only appearing as a tick and a
    // badge somewhere in the middle of an otherwise colourless screen.
    filled: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                subtitle?.takeIf(String::isNotBlank)?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = subtitleColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        navigationIcon = {
            if (onBack != null || navigationAvatar != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    onBack?.let { callback ->
                        IconButton(onClick = callback) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                    navigationAvatar?.invoke()
                }
            }
        },
        actions = actions,
        colors = if (filled) {
            val bar = appBarColors()
            TopAppBarDefaults.topAppBarColors(
                containerColor = bar.container,
                titleContentColor = bar.content,
                navigationIconContentColor = bar.content,
                actionIconContentColor = bar.content,
            )
        } else {
            TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CenteredAppTopBar(title: String, onBack: () -> Unit) {
    CenterAlignedTopAppBar(
        title = {
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        },
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
        ),
    )
}

@Composable
internal fun ConfigureDialogWindow(dimAmount: Float = 0f) {
    val view = LocalView.current
    LaunchedEffect(view, dimAmount) {
        (view.parent as? DialogWindowProvider)?.window?.apply {
            setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            if (dimAmount > 0f) {
                addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                setDimAmount(dimAmount.coerceIn(0f, 1f))
            } else {
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                setDimAmount(0f)
            }
        }
    }
}

@Composable
internal fun AnimatedDialogContainer(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(usePlatformDefaultWidth = false),
    contentAlignment: Alignment = Alignment.Center,
    scrimAlpha: Float = 0.32f,
    enterScale: Float = 0.92f,
    exitScale: Float = 0.96f,
    content: @Composable (dismiss: () -> Unit) -> Unit,
) {
    var visible by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun dismiss() {
        if (!visible) return
        visible = false
        scope.launch {
            delay(MotionShort.toLong())
            onDismissRequest()
        }
    }
    Dialog(onDismissRequest = ::dismiss, properties = properties) {
        ConfigureDialogWindow(scrimAlpha)
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = contentAlignment) {
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(tween(MotionMedium)) + scaleIn(initialScale = enterScale, animationSpec = tween(MotionMedium, easing = FastOutSlowInEasing)),
                exit = fadeOut(tween(MotionShort)) + scaleOut(targetScale = exitScale, animationSpec = tween(MotionShort)),
            ) {
                content(::dismiss)
            }
        }
        LaunchedEffect(Unit) { visible = true }
    }
}

@Composable
/**
 * Delegates to [FcDialog] so all 32 dialogs share one chassis. Keeps its own
 * parameter shape so existing call sites did not have to be rewritten to gain
 * the unified padding, radius and button placement.
 *
 * Set [destructive] on anything irreversible: the confirming action is then
 * styled as danger instead of looking identical to a save.
 */
internal fun AnimatedActionDialog(
    title: String,
    body: @Composable () -> Unit,
    confirmText: String,
    onConfirm: () -> Unit,
    dismissText: String,
    onDismissRequest: () -> Unit,
    destructive: Boolean = false,
) {
    FcDialog(
        title = title,
        confirmText = confirmText,
        onConfirm = onConfirm,
        dismissText = dismissText,
        onDismissRequest = onDismissRequest,
        destructive = destructive,
        body = { body() },
    )
}

@Composable
internal fun rememberBottomBounceConnection(
    listState: LazyListState,
    offset: Animatable<Float, AnimationVector1D>,
): NestedScrollConnection {
    val scope = rememberCoroutineScope()
    return remember(listState, offset) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || available.y >= 0f || listState.canScrollForward) return Offset.Zero
                scope.launch { offset.snapTo((offset.value + available.y * 0.22f).coerceAtLeast(-54f)) }
                return Offset(0f, available.y * 0.22f)
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                if (offset.value != 0f) offset.animateTo(0f, animationSpec = tween(MotionMedium, easing = FastOutSlowInEasing))
                return Velocity.Zero
            }
        }
    }
}

private object AvatarBitmapStore {
    private val memory = LruCache<String, Bitmap>(96)
    private const val DISK_CACHE_DIRECTORY = "avatar_thumbnails"
    private const val MAX_DISK_ENTRIES = 160

    suspend fun load(context: Context, serverUrl: String, avatarUrl: String, targetPx: Int): Bitmap? {
        if (avatarUrl.isBlank()) return null
        val absolute = when {
            avatarUrl.startsWith("http://") || avatarUrl.startsWith("https://") -> avatarUrl
            else -> serverUrl.trimEnd('/') + "/" + avatarUrl.trimStart('/')
        }
        val key = "$absolute|$targetPx"
        memory.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            val cacheDirectory = File(context.cacheDir, DISK_CACHE_DIRECTORY).apply { mkdirs() }
            val cacheFile = File(cacheDirectory, key.sha256FileName())
            decodeCachedBitmap(cacheFile)?.let { cached ->
                cacheFile.setLastModified(System.currentTimeMillis())
                memory.put(key, cached)
                return@withContext cached
            }
            val downloaded = runCatching {
                val connection = URL(absolute).openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 8_000
                    connection.readTimeout = 10_000
                    connection.setRequestProperty(APP_CLIENT_HEADER_NAME, APP_CLIENT_HEADER_VALUE)
                    connection.connect()
                    if (connection.responseCode !in 200..299) return@runCatching null
                    connection.inputStream.use(BitmapFactory::decodeStream)?.let { source ->
                        scaleAvatarBitmap(source, targetPx)
                    }
                } finally {
                    connection.disconnect()
                }
            }.getOrNull() ?: return@withContext null
            persist(cacheFile, downloaded)
            prune(cacheDirectory)
            memory.put(key, downloaded)
            downloaded
        }
    }

    private fun scaleAvatarBitmap(source: Bitmap, targetPx: Int): Bitmap {
        val maxSide = maxOf(source.width, source.height).coerceAtLeast(1)
        val factor = (targetPx.toFloat() / maxSide).coerceAtMost(1f)
        if (factor >= 1f) return source
        val scaled = Bitmap.createScaledBitmap(
            source,
            (source.width * factor).roundToInt().coerceAtLeast(1),
            (source.height * factor).roundToInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== source) source.recycle()
        return scaled
    }

    private fun decodeCachedBitmap(file: File): Bitmap? =
        file.takeIf(File::isFile)?.let { runCatching { BitmapFactory.decodeFile(it.absolutePath) }.getOrNull() }

    private fun persist(file: File, bitmap: Bitmap) {
        runCatching {
            val temporary = File(file.parentFile, "${file.name}.tmp")
            FileOutputStream(temporary).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (!temporary.renameTo(file)) {
                temporary.copyTo(file, overwrite = true)
                temporary.delete()
            }
        }
    }

    private fun prune(directory: File) {
        val files = directory.listFiles()?.filter(File::isFile)?.sortedByDescending(File::lastModified).orEmpty()
        files.drop(MAX_DISK_ENTRIES).forEach { runCatching { it.delete() } }
    }

    private fun String.sha256FileName(): String =
        MessageDigest.getInstance("SHA-256")
            .digest(toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) } + ".png"
}

@Composable
internal fun AvatarBubble(
    avatarUrl: String,
    name: String,
    colorHex: String,
    size: Dp,
    serverUrl: String,
    shape: Shape = CircleShape,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val targetPx = remember(size, density) { with(density) { (size * 2f).roundToPx() } }
    val bitmap by produceState<Bitmap?>(null, avatarUrl, serverUrl, targetPx) {
        value = AvatarBitmapStore.load(context.applicationContext, serverUrl, avatarUrl, targetPx)
    }
    val fallbackColor = runCatching { Color(android.graphics.Color.parseColor(colorHex)) }
        .getOrDefault(MaterialTheme.colorScheme.primaryContainer)
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(fallbackColor, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = name,
                modifier = Modifier.fillMaxSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            )
        } else {
            Text(name.trim().firstOrNull()?.uppercase() ?: "?", color = Color.White, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
internal fun LanguageSelector(language: AppLanguage, onSelect: (AppLanguage) -> Unit, label: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = language == AppLanguage.ZH, onClick = { onSelect(AppLanguage.ZH) }, label = { Text("中文") })
            FilterChip(selected = language == AppLanguage.EN, onClick = { onSelect(AppLanguage.EN) }, label = { Text("English") })
        }
    }
}

@Composable
internal fun DisplayModeSelector(mode: AppDisplayMode, onSelect: (AppDisplayMode) -> Unit, strings: AppStrings) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(strings.displayMode, style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = mode == AppDisplayMode.SYSTEM, onClick = { onSelect(AppDisplayMode.SYSTEM) }, label = { Text(strings.followSystem) })
            FilterChip(selected = mode == AppDisplayMode.LIGHT, onClick = { onSelect(AppDisplayMode.LIGHT) }, label = { Text(strings.lightMode) })
            FilterChip(selected = mode == AppDisplayMode.DARK, onClick = { onSelect(AppDisplayMode.DARK) }, label = { Text(strings.darkMode) })
        }
    }
}

internal fun currentMentionQuery(value: TextFieldValue): String? {
    val cursor = value.selection.start.coerceIn(0, value.text.length)
    val prefix = value.text.substring(0, cursor)
    val match = Regex("(?:^|\\s|[^A-Za-z0-9_.-])@([A-Za-z0-9_.-]*)$").find(prefix) ?: return null
    return match.groupValues[1]
}

internal fun replaceMention(value: TextFieldValue, username: String): TextFieldValue {
    val cursor = value.selection.start.coerceIn(0, value.text.length)
    val prefix = value.text.substring(0, cursor)
    val at = prefix.lastIndexOf('@')
    if (at < 0) return value
    val replacement = "@$username "
    val updated = value.text.replaceRange(at, cursor, replacement)
    return TextFieldValue(updated, TextRange(at + replacement.length))
}

internal fun stickerLabel(strings: AppStrings): String =
    if (strings.image == stringsFor(AppLanguage.ZH).image) "贴图" else "Sticker"

internal suspend fun resolvePreviewText(
    message: ChatMessage,
    fallbackPreview: String,
    secret: String,
    strings: AppStrings,
    decryptedTextCache: MutableMap<Long, String>,
    attachmentMetaCache: MutableMap<Long, JSONObject?>,
    localPrefs: ChatPreferences,
    resolveMessageText: suspend (ChatMessage) -> String,
    prepareMessageDecryption: suspend (ChatMessage) -> Unit = {},
): String {
    if (message.kind == "recalled") return strings.recalledMessage
    if (message.kind == "audio") return strings.voiceNote
    if (message.kind == "image" || message.kind == "photo") {
        val meta = resolveAttachmentMeta(
            message,
            secret,
            attachmentMetaCache,
            localPrefs,
            prepareMessageDecryption,
        )
        return if (meta?.optBoolean("sticker") == true || parseJson(message.payload)?.optJSONObject("display")?.optBoolean("sticker") == true) {
            stickerLabel(strings)
        } else strings.image
    }
    if (message.kind == "file") return strings.file
    decryptedTextCache[message.id]?.let { return it }
    return try {
        resolveMessageText(message).also { decryptedTextCache[message.id] = it }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        fallbackPreview.ifBlank { strings.encryptedMessage }
    }
}

internal suspend fun resolveAttachmentMeta(
    message: ChatMessage,
    secret: String,
    cache: MutableMap<Long, JSONObject?>,
    preferences: ChatPreferences,
    prepareMessageDecryption: suspend (ChatMessage) -> Unit = {},
): JSONObject? {
    if (cache.containsKey(message.id)) return cache[message.id]
    val payload = parseJson(message.payload)
    val result = payload?.optJSONObject("display") ?: if (message.e2ee) {
        try {
            prepareMessageDecryption(message)
            withContext(Dispatchers.Default) { decryptAttachmentMeta(message, preferences) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
    } else payload
    if (result != null) cache[message.id] = result
    return result
}

internal fun parseJson(text: String): JSONObject? = runCatching { JSONObject(text) }.getOrNull()

internal fun logDirectDecryptFailure(message: ChatMessage, error: Throwable, source: String) {
    if (error is CancellationException) return
    FamilyChatDiagnostics.sampled(
        "message_decrypt_failed",
        3_000L,
        "conversation_id" to message.conversationId,
        "message_id" to message.id,
        "source" to source,
        "scheme" to if (GroupSenderKeyCrypto.isGroupPayload(message.payload)) "group" else "direct",
        "error" to (error.message ?: error.javaClass.simpleName).take(80),
    )
}

internal fun annotatedMentions(text: String): AnnotatedString = buildAnnotatedString {
    append(text)
    Regex("@[A-Za-z0-9_.-]{1,32}").findAll(text).forEach { match ->
        addStyle(SpanStyle(color = Color(0xFF087F5B)), match.range.first, match.range.last + 1)
    }
}

internal fun buildStatusLine(state: ConnectionAwareUiState, strings: AppStrings): String =
    connectionStatusText(effectiveConnectionStatus(state), strings)

internal fun effectiveConnectionStatus(state: ConnectionAwareUiState): ConnectionStatus = when {
    state.isRefreshing && state.isConnected -> ConnectionStatus.SYNCING
    state.isConnected -> ConnectionStatus.CONNECTED
    state.connectionStatus == ConnectionStatus.RECONNECTING -> ConnectionStatus.RECONNECTING
    else -> state.connectionStatus
}

internal fun connectionStatusText(status: ConnectionStatus, strings: AppStrings): String = when (status) {
    ConnectionStatus.CONNECTED -> strings.connected
    ConnectionStatus.SYNCING -> strings.syncing
    ConnectionStatus.RECONNECTING -> strings.reconnecting
    ConnectionStatus.OFFLINE -> strings.disconnected
}

@Composable
internal fun connectionStatusColor(status: ConnectionStatus): Color = when (status) {
    ConnectionStatus.CONNECTED -> Color(0xFF16855B)
    ConnectionStatus.SYNCING -> MaterialTheme.colorScheme.primary
    ConnectionStatus.RECONNECTING -> Color(0xFFC06B00)
    ConnectionStatus.OFFLINE -> MaterialTheme.colorScheme.error
}

/**
 * Status colours for use on the accent-filled top bar.
 *
 * The palette above is built for a light background and does not survive being
 * placed on the accent: CONNECTED is a green on green, and SYNCING returns the
 * primary itself, which would be literally invisible. These are chosen to read
 * on the accent instead.
 */
@Composable
internal fun connectionStatusColorOnAccent(status: ConnectionStatus): Color = when (status) {
    // Derived from the bar's own content colour, not onPrimary: the bar no
    // longer uses primary, and in dark mode onPrimary is a near-black that would
    // vanish against the deep green.
    ConnectionStatus.CONNECTED -> appBarColors().content.copy(alpha = 0.82f)
    ConnectionStatus.SYNCING -> appBarColors().content.copy(alpha = 0.82f)
    ConnectionStatus.RECONNECTING -> Color(0xFFFFD79A)
    ConnectionStatus.OFFLINE -> Color(0xFFFFB4AB)
}

@Composable
internal fun ConnectionActivityIndicator(status: ConnectionStatus) {
    if (status == ConnectionStatus.SYNCING || status == ConnectionStatus.RECONNECTING) {
        androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
    }
}

internal fun clampAvatarCropOffset(bitmap: Bitmap, viewportPx: Float, scale: Float, proposed: Offset): Offset {
    val baseScale = maxOf(viewportPx / bitmap.width, viewportPx / bitmap.height)
    val width = bitmap.width * baseScale * scale
    val height = bitmap.height * baseScale * scale
    val maxX = ((width - viewportPx) / 2f).coerceAtLeast(0f)
    val maxY = ((height - viewportPx) / 2f).coerceAtLeast(0f)
    return Offset(proposed.x.coerceIn(-maxX, maxX), proposed.y.coerceIn(-maxY, maxY))
}

internal fun cropAvatarBitmap(bitmap: Bitmap, scale: Float, offset: Offset, viewportPx: Float): ByteArray? = runCatching {
    val outputSize = 768
    val output = Bitmap.createBitmap(outputSize, outputSize, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(output)
    val viewportScale = maxOf(viewportPx / bitmap.width, viewportPx / bitmap.height) * scale
    val conversion = outputSize / viewportPx
    val matrix = Matrix().apply {
        postScale(viewportScale * conversion, viewportScale * conversion)
        postTranslate(
            outputSize / 2f - bitmap.width * viewportScale * conversion / 2f + offset.x * conversion,
            outputSize / 2f - bitmap.height * viewportScale * conversion / 2f + offset.y * conversion,
        )
    }
    canvas.drawBitmap(bitmap, matrix, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
    ByteArrayOutputStream().use { stream ->
        output.compress(Bitmap.CompressFormat.JPEG, 90, stream)
        output.recycle()
        stream.toByteArray()
    }
}.getOrNull()

internal fun queryName(context: Context, uri: Uri): String? =
    context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

internal fun resolveRichImageMime(context: Context, uri: Uri, description: ClipDescription? = null): String =
    context.contentResolver.getType(uri)
        ?: description?.let { clip ->
            (0 until clip.mimeTypeCount)
                .mapNotNull { index -> clip.getMimeType(index) }
                .firstOrNull { mime -> mime.startsWith("image/") }
        }
        ?: "image/png"

internal fun isShareIntent(intent: Intent): Boolean =
    intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE

internal fun extractSharedContent(context: Context, intent: Intent): List<PendingSharedContent> {
    if (!isShareIntent(intent)) return emptyList()
    val declaredMime = intent.type
        ?.trim()
        ?.takeUnless { it.isBlank() || it == "*/*" }
    val streams = if (intent.action == Intent.ACTION_SEND_MULTIPLE) intent.sharedStreamUris() else listOfNotNull(intent.sharedStreamUri())
    val sharedAt = System.currentTimeMillis()
    val attachments = streams.mapIndexed { index, uri ->
        val mime = context.contentResolver.getType(uri)
            ?.takeUnless(String::isBlank)
            ?: declaredMime
            ?: "application/octet-stream"
        val name = queryName(context, uri)
            ?.takeUnless(String::isBlank)
            ?: if (mime.startsWith("image/")) pastedImageName(mime) else "shared_${sharedAt}_$index"
        if (mime.startsWith("image/")) {
            PendingSharedContent.Image(uri, mime, name)
        } else {
            PendingSharedContent.File(uri, mime, name)
        }
    }
    val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty().trim()
    return attachments + text.takeIf(String::isNotBlank)?.let(PendingSharedContent::Text).let(::listOfNotNull)
}

private fun Intent.sharedStreamUri(): Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
} else {
    @Suppress("DEPRECATION") getParcelableExtra(Intent.EXTRA_STREAM)
}

private fun Intent.sharedStreamUris(): List<Uri> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) ?: emptyList()
} else {
    @Suppress("DEPRECATION")
    (getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: emptyList())
}

internal fun sendPendingSharedContent(viewModel: ChatViewModel, items: List<PendingSharedContent>) {
    items.forEach { item ->
        when (item) {
            is PendingSharedContent.Image -> viewModel.uploadAttachmentFromUri("image", item.name, item.mime, item.uri, null, richContent = true)
            is PendingSharedContent.File -> viewModel.uploadAttachmentFromUri("file", item.name, item.mime, item.uri, null)
            is PendingSharedContent.Text -> viewModel.sendText(item.text, null)
        }
    }
}

internal fun pastedImageName(mime: String): String {
    val extension = when (mime.lowercase()) {
        "image/jpeg" -> "jpg"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        else -> "png"
    }
    return "pasted_${System.currentTimeMillis()}.$extension"
}

internal fun startRecording(context: Context, onStarted: (MediaRecorder, File, Long) -> Unit) {
    val file = File.createTempFile("voice_", ".m4a", context.cacheDir)
    val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        MediaRecorder(context)
    } else {
        @Suppress("DEPRECATION")
        MediaRecorder()
    }
    recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
    recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
    recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
    recorder.setAudioEncodingBitRate(96_000)
    recorder.setAudioSamplingRate(44_100)
    recorder.setOutputFile(file.absolutePath)
    recorder.prepare()
    recorder.start()
    onStarted(recorder, file, System.currentTimeMillis())
}

internal fun rotationFromOrientation(orientation: Int): Int? = when {
    orientation < 0 -> null
    orientation >= 315 || orientation < 45 -> android.view.Surface.ROTATION_0
    orientation < 135 -> android.view.Surface.ROTATION_270
    orientation < 225 -> android.view.Surface.ROTATION_180
    else -> android.view.Surface.ROTATION_90
}

internal fun normalizeSurfaceRotation(rotation: Int): Int = when (rotation) {
    android.view.Surface.ROTATION_0,
    android.view.Surface.ROTATION_90,
    android.view.Surface.ROTATION_180,
    android.view.Surface.ROTATION_270 -> rotation
    90 -> android.view.Surface.ROTATION_90
    180 -> android.view.Surface.ROTATION_180
    270 -> android.view.Surface.ROTATION_270
    else -> android.view.Surface.ROTATION_0
}

internal fun normalizeCapturedPhoto(file: File, maxDimensionPx: Int = 2560) {
    val targetDimension = maxDimensionPx.coerceIn(720, 2048)
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return
    val options = BitmapFactory.Options().apply {
        inSampleSize = calculateBitmapSampleSize(bounds.outWidth, bounds.outHeight, targetDimension)
    }
    val source = BitmapFactory.decodeFile(file.absolutePath, options) ?: return
    val orientation = runCatching { ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val matrix = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                postScale(-1f, 1f)
                postRotate(270f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                postScale(-1f, 1f)
                postRotate(90f)
            }
        }
    }
    var rotated: Bitmap? = null
    var output: Bitmap? = null
    val tempFile = File(file.parentFile, "${file.name}.normalized.tmp")
    try {
        rotated = if (!matrix.isIdentity) {
            Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        } else {
            source
        }
        val oriented = requireNotNull(rotated)
        val factor = (targetDimension.toFloat() / maxOf(oriented.width, oriented.height)).coerceAtMost(1f)
        output = if (factor < 1f) {
            Bitmap.createScaledBitmap(
                oriented,
                (oriented.width * factor).roundToInt().coerceAtLeast(1),
                (oriented.height * factor).roundToInt().coerceAtLeast(1),
                true,
            )
        } else {
            oriented
        }
        FileOutputStream(tempFile).use { stream ->
            check(requireNotNull(output).compress(Bitmap.CompressFormat.JPEG, 88, stream)) {
                "Unable to encode captured photo"
            }
            stream.fd.sync()
        }
        runCatching {
            java.nio.file.Files.move(
                tempFile.toPath(),
                file.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        }.getOrElse {
            tempFile.copyTo(file, overwrite = true)
            tempFile.delete()
        }
    } finally {
        output?.takeIf { it !== rotated && !it.isRecycled }?.recycle()
        rotated?.takeIf { it !== source && !it.isRecycled }?.recycle()
        if (!source.isRecycled) source.recycle()
        tempFile.takeIf(File::exists)?.delete()
    }
}

internal fun decodeScaledBitmap(file: File, targetSizePx: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    return BitmapFactory.decodeFile(
        file.absolutePath,
        BitmapFactory.Options().apply { inSampleSize = calculateBitmapSampleSize(bounds.outWidth, bounds.outHeight, targetSizePx) },
    )
}

internal fun calculateBitmapSampleSize(width: Int, height: Int, targetSizePx: Int): Int {
    var sample = 1
    val target = targetSizePx.coerceAtLeast(240)
    val longestSide = maxOf(width, height).coerceAtLeast(1)
    while (longestSide / sample > target && sample <= Int.MAX_VALUE / 2) sample *= 2
    return sample
}

internal suspend fun openAttachment(context: Context, viewModel: ChatViewModel, message: ChatMessage) {
    runCatching { viewModel.materializeAttachment(context, message, exportToDownloads = true) }
        .onSuccess { attachment ->
            val uri = attachment.uri ?: FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                requireNotNull(attachment.file) { "Attachment file is missing" },
            )
            context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, attachment.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }
        .onFailure { viewModel.reportError(it.message ?: "Unable to open attachment") }
    viewModel.clearDownloadProgress()
}

internal suspend fun downloadAndOpenAttachment(context: Context, viewModel: ChatViewModel, message: ChatMessage) {
    openAttachment(context, viewModel, message)
}

internal suspend fun downloadLatestUpdate(context: Context, viewModel: SettingsViewModel) {
    runCatching { viewModel.downloadLatestAppRelease(context) }
        .onFailure { viewModel.reportError(it.message ?: "Unable to download update") }
}

internal suspend fun downloadLatestPrerelease(context: Context, viewModel: SettingsViewModel) {
    runCatching { viewModel.downloadLatestPrerelease(context) }
        .onFailure { viewModel.reportError(it.message ?: "Unable to download beta") }
}

internal fun openInstaller(context: Context, viewModel: SettingsViewModel, attachment: DecryptedAttachment) {
    runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
            return
        }
        val uri = attachment.uri ?: FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            requireNotNull(attachment.file) { "Update package is missing" },
        )
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.onFailure { viewModel.reportError(it.message ?: "Unable to install update") }
}

internal fun exportDiagnostics(context: Context, language: AppLanguage) {
    runCatching {
        val file = FamilyChatDiagnostics.exportFile(context)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                if (language == AppLanguage.ZH) "导出诊断日志" else "Export diagnostics",
            )
        )
    }
}

internal fun releaseDisplayVersion(release: AppReleaseInfo?): String =
    release?.versionLabel?.ifBlank { release.version }?.ifBlank { "-" } ?: "-"

internal fun releaseDownloadFileName(release: AppReleaseInfo?): String? = release?.fileName?.ifBlank { release.originalName }

internal fun currentAppVersion(context: Context): String = runCatching {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"
}.getOrDefault("0.0.0")

internal fun stableMessageListKey(message: ChatMessage, currentUserIdentity: String): String =
    message.clientMessageId.takeIf(String::isNotBlank)
        ?: message.id.takeIf { it > 0L }?.let { "server:$it" }
        ?: "local:${message.conversationId}:${message.id}:${message.userCode.ifBlank { currentUserIdentity }}"

@Composable
internal fun TimelineSeparator(label: String) {
    // A pill on its own container colour, so it reads as a divider between days
    // rather than as a floating system message.
    Box(Modifier.fillMaxWidth().padding(vertical = Spacing.sm), contentAlignment = Alignment.Center) {
        Surface(
            shape = FullShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Text(
                label,
                Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal fun shouldShowTimelineSeparator(previousTs: Long, currentTs: Long): Boolean =
    currentTs - previousTs > 3 * 60 * 1_000L

internal fun formatTimelineLabel(ts: Long, language: AppLanguage, strings: AppStrings): String {
    val now = System.currentTimeMillis()
    val day = 24 * 60 * 60 * 1_000L
    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))
    return when {
        now - ts < day && SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(Date(now)) == SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(Date(ts)) ->
            (if (language == AppLanguage.ZH) "今天 " else "Today ") + time
        now - ts < 2 * day -> (if (language == AppLanguage.ZH) "昨天 " else "Yesterday ") + time
        else -> SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
    }
}

internal fun formatMessageTime(ts: Long): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))

/**
 * Height of [FamilySectionBar]'s own content, excluding system insets.
 *
 * The two section roots reserve this much at the bottom themselves. The bar is
 * drawn as an overlay rather than as a layout participant, because a bar that
 * occupies space has to give that space back at some point - and
 * `AnimatedVisibility` gives it back in a single frame when the exit animation
 * ends, not gradually while it plays. Screens entered from a section root were
 * laid out short for the length of that animation and then grew, which made the
 * message and comment composers jump.
 */
internal val FamilySectionBarHeight = 80.dp

/**
 * The bar that switches between the app's two top-level sections.
 *
 * It is owned by the navigation shell, never by a screen. When each screen
 * carried its own copy, switching sections slid one bar off to the left while a
 * second slid in from the right - the control you had just pressed appeared to
 * break in half. Rendered once, over the NavHost, it simply stays put and the
 * content moves underneath it.
 */
@Composable
internal fun FamilySectionBar(
    blogSelected: Boolean,
    strings: AppStrings,
    onChats: () -> Unit,
    onBlog: () -> Unit,
) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
        NavigationBarItem(
            selected = !blogSelected,
            onClick = onChats,
            icon = { Icon(Icons.Default.Chat, contentDescription = null) },
            label = { Text(strings.blog.chatsTab) },
            colors = familySectionBarItemColors(),
        )
        NavigationBarItem(
            selected = blogSelected,
            onClick = onBlog,
            icon = { Icon(Icons.Default.Article, contentDescription = null) },
            label = { Text(strings.blog.blogTab) },
            colors = familySectionBarItemColors(),
        )
    }
}

// Selection is carried by the accent, matching how selection reads everywhere
// else in the app, rather than by M3's default secondaryContainer pill.
@Composable
private fun familySectionBarItemColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
    selectedTextColor = MaterialTheme.colorScheme.primary,
    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
)

internal fun formatSize(size: Long): String = when {
    size >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", size / (1024f * 1024f))
    size >= 1024L -> String.format(Locale.US, "%.1f KB", size / 1024f)
    else -> "$size B"
}

internal fun formatDuration(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
    return "%02d:%02d".format(Locale.US, totalSeconds / 60L, totalSeconds % 60L)
}
