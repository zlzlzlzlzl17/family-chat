package com.example.chat

import android.Manifest
import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.media.ExifInterface
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Base64
import android.util.LruCache
import android.view.Gravity
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.Surface
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.GetContent
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview as CameraPreview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.ContentInfoCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.chat.ui.theme.FullShape
import com.example.chat.ui.theme.ChatBubbleTokens
import com.example.chat.ui.theme.arrivalSpring
import com.example.chat.ui.theme.placementSpring
import com.example.chat.ui.theme.ChatTheme
import com.example.chat.ui.theme.MotionMedium
import com.example.chat.ui.theme.MotionShort
import com.example.chat.ui.theme.Spacing
import com.example.chat.ui.theme.MdDarkReadReceipt
import com.example.chat.ui.theme.MdLightReadReceipt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.compose.ui.tooling.preview.Preview
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

internal data class ChatScrollPosition(
    val firstVisibleItemIndex: Int,
    val firstVisibleItemScrollOffset: Int,
)

@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun ChatScreen(
    viewModel: ChatViewModel,
    state: ChatScreenUiState,
    localPrefs: ChatPreferences,
    onBack: () -> Unit,
    onStartVoiceCall: (Long) -> Unit,
    onOpenPeerManage: (String?) -> Unit,
    onOpenGroupManage: () -> Unit,
    restoreScrollPosition: ChatScrollPosition?,
    onScrollPositionRestored: () -> Unit,
    onPreviewImage: (
        conversationId: Long,
        messageId: Long,
        scrollPosition: ChatScrollPosition,
    ) -> Unit,
) {
    val strings = stringsFor(state.language)
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val rootView = LocalView.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val shouldAutoScroll = rememberSaveable(state.currentConversationId) { mutableStateOf(true) }
    val conversation = state.conversations.find { it.id == state.currentConversationId } ?: return
    val firstCompositionStartedAt = remember(conversation.id) {
        System.currentTimeMillis().also { startedAt ->
            FamilyChatDiagnostics.event(
                "chat_composition_started",
                "conversation_id" to conversation.id,
                "started_at" to startedAt,
            )
        }
    }
    var firstCommitReported by remember(conversation.id) { mutableStateOf(false) }
    SideEffect {
        if (!firstCommitReported) {
            firstCommitReported = true
            FamilyChatDiagnostics.event(
                "chat_first_commit",
                "conversation_id" to conversation.id,
                "duration_ms" to (System.currentTimeMillis() - firstCompositionStartedAt),
            )
        }
    }
    var bottomInputHeightPx by remember(conversation.id) { mutableIntStateOf(0) }
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    val bottomBounceOffset = remember(conversation.id) { Animatable(0f) }
    val decryptSecret = ""
    val decryptedTextCache = remember(decryptSecret, conversation.id) { mutableStateMapOf<Long, String>() }
    val attachmentMetaCache = remember(decryptSecret, conversation.id) { mutableStateMapOf<Long, JSONObject?>() }
    val messagesById = remember(state.messages) { state.messages.associateBy { it.id } }
    val usersByIdentity = remember(state.users) {
        buildMap {
            state.users.forEach { user ->
                if (user.userCode.isNotBlank()) put(user.userCode, user)
                put(user.username, user)
            }
        }
    }
    val directPeer = remember(conversation.directUserCode, conversation.directUsername, state.users) {
        state.users.firstOrNull { user ->
            (conversation.directUserCode.isNotBlank() && user.userCode == conversation.directUserCode) ||
                user.username == conversation.directUsername
        }
    }
    val bubbleMaxWidth = remember(configuration.screenWidthDp) {
        (configuration.screenWidthDp.dp * 0.78f).coerceAtMost(520.dp)
    }
    LaunchedEffect(conversation.id, conversation.kind) {
        if (conversation.kind == "group") viewModel.refreshCurrentConversationManage()
    }
    val groupMembers = remember(conversation.id, state.currentConversationManage) {
        state.currentConversationManage
            ?.takeIf { it.id == conversation.id && it.kind == "group" }
            ?.members
            .orEmpty()
    }
    val mentionCandidates = remember(conversation.id, conversation.kind, conversation.directUserCode, conversation.directUsername, state.users, groupMembers, state.me?.username, state.me?.userCode) {
        if (conversation.kind == "direct") {
            state.users
                .filter { (conversation.directUserCode.isNotBlank() && it.userCode == conversation.directUserCode) || it.username == conversation.directUsername }
                .filter { it.userCode != state.me?.userCode && it.username != state.me?.username }
        } else {
            groupMembers
                .map { it.user }
                .filter { it.userCode != state.me?.userCode && it.username != state.me?.username }
        }
    }
    val participantUsernames = remember(
        conversation.kind,
        conversation.directUserCode,
        conversation.directUsername,
        groupMembers,
        state.me?.userCode,
        state.me?.username
    ) {
        if (conversation.kind == "direct") {
            listOf(conversation.directUserCode.ifBlank { conversation.directUsername })
                .filter { it.isNotBlank() }
        } else {
            groupMembers
                .map { it.user.userCode.ifBlank { it.user.username } }
                .filter { it.isNotBlank() && it != state.me?.userCode && it != state.me?.username }
        }
            .distinct()
    }
    var replyTarget by remember(conversation.id) { mutableStateOf<ChatMessage?>(null) }
    val activeReplyTarget = remember(replyTarget, messagesById) { replyTarget?.let { messagesById[it.id] ?: it } }
    val activeReplyPreview = remember(
        activeReplyTarget?.id,
        activeReplyTarget?.kind,
        activeReplyTarget?.payload,
        activeReplyTarget?.replyTo?.preview,
        decryptSecret
    ) {
        activeReplyTarget?.let(viewModel::buildReplyPreview)
    }
    var composer by rememberSaveable(conversation.id, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(""))
    }
    var draftLoaded by remember(conversation.id) { mutableStateOf(false) }
    LaunchedEffect(conversation.id) {
        draftLoaded = false
        FamilyChatDiagnostics.event(
            "chat_open",
            "conversation_id" to conversation.id,
            "kind" to conversation.kind,
            "message_count" to state.messages.size
        )
        val draftStartedAt = System.currentTimeMillis()
        val draft = viewModel.loadDraft(conversation.id)
        composer = TextFieldValue(draft, selection = TextRange(draft.length))
        draftLoaded = true
        FamilyChatDiagnostics.event(
            "chat_draft_ready",
            "conversation_id" to conversation.id,
            "duration_ms" to (System.currentTimeMillis() - draftStartedAt),
        )
    }
    val latestComposerText by rememberUpdatedState(composer.text)
    val latestDraftLoaded by rememberUpdatedState(draftLoaded)
    LaunchedEffect(conversation.id, composer.text, draftLoaded) {
        if (!draftLoaded) return@LaunchedEffect
        delay(350L)
        viewModel.saveDraft(conversation.id, composer.text)
    }
    DisposableEffect(conversation.id) {
        onDispose {
            if (latestDraftLoaded) {
                viewModel.saveDraft(conversation.id, latestComposerText)
            }
        }
    }
    var attachmentMenuExpanded by rememberSaveable(conversation.id) { mutableStateOf(false) }
    var voiceInputMode by rememberSaveable(conversation.id) { mutableStateOf(false) }
    var cameraVisible by rememberSaveable(conversation.id) { mutableStateOf(false) }
    var retryTarget by remember(conversation.id) { mutableStateOf<ChatMessage?>(null) }
    var audioPlayer by remember(conversation.id) { mutableStateOf<MediaPlayer?>(null) }
    var playingAudioMessageId by remember(conversation.id) { mutableStateOf<Long?>(null) }
    var audioPlaybackPositionMs by remember(conversation.id) { mutableLongStateOf(0L) }
    var audioPlaybackDurationMs by remember(conversation.id) { mutableLongStateOf(0L) }
    val activeAudioPlaybackProgress by remember {
        derivedStateOf {
            if (playingAudioMessageId != null && audioPlaybackDurationMs > 0L) {
                (audioPlaybackPositionMs.toFloat() / audioPlaybackDurationMs.toFloat()).coerceIn(0f, 1f)
            } else {
                null
            }
        }
    }
    var voiceGestureCancelled by remember(conversation.id) { mutableStateOf(false) }
    var showClearHistoryDialog by remember(conversation.id) { mutableStateOf(false) }
    var headerMenuExpanded by remember(conversation.id) { mutableStateOf(false) }
    // Keyed on conversation.id so reopening a different chat reads that chat's
    // own mute state rather than carrying the previous one over.
    var conversationMuted by remember(conversation.id) {
        mutableStateOf(localPrefs.isConversationMuted(conversation.id))
    }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var recordingFile by remember { mutableStateOf<File?>(null) }
    var recordingStartedAt by remember { mutableLongStateOf(0L) }
    val currentUserIdentity = remember(state.me?.userCode, state.me?.username) {
        state.me?.userCode?.ifBlank { state.me?.username.orEmpty() }
            ?.ifBlank { state.me?.username.orEmpty() }
            .orEmpty()
    }
    val heardAudioMessages = remember(conversation.id, currentUserIdentity) { mutableStateMapOf<Long, Boolean>() }
    val chatBounceConnection = rememberBottomBounceConnection(listState, bottomBounceOffset)
    val connectionStatus = effectiveConnectionStatus(state)
    val connectionStatusTint = connectionStatusColor(connectionStatus)

    val notificationPermission = rememberLauncherForActivityResult(RequestPermission()) {}
    val recordPermission = rememberLauncherForActivityResult(RequestPermission()) { }
    val cameraPermission = rememberLauncherForActivityResult(RequestPermission()) { granted ->
        if (granted) {
            cameraVisible = true
        } else {
            viewModel.reportError(strings.cameraPermissionDenied)
        }
    }
    val imagePicker = rememberLauncherForActivityResult(GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = queryName(context, uri) ?: "image.jpg"
        val reply = activeReplyTarget?.let { ReplyPreview(it.id, it.username, it.color, activeReplyPreview.orEmpty()) }
        shouldAutoScroll.value = true
        viewModel.uploadAttachmentFromUri("image", name, context.contentResolver.getType(uri) ?: "image/jpeg", uri, reply)
        replyTarget = null
    }
    val filePicker = rememberLauncherForActivityResult(GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val name = queryName(context, uri) ?: "file.bin"
        val reply = activeReplyTarget?.let { ReplyPreview(it.id, it.username, it.color, activeReplyPreview.orEmpty()) }
        shouldAutoScroll.value = true
        viewModel.uploadAttachmentFromUri("file", name, context.contentResolver.getType(uri) ?: "application/octet-stream", uri, reply)
        replyTarget = null
    }

    fun uploadRichImageContent(uri: Uri, mime: String) {
        val safeMime = mime.takeIf { it.startsWith("image/") } ?: context.contentResolver.getType(uri) ?: "image/png"
        val name = queryName(context, uri) ?: pastedImageName(safeMime)
        val reply = activeReplyTarget?.let { ReplyPreview(it.id, it.username, it.color, activeReplyPreview.orEmpty()) }
        shouldAutoScroll.value = true
        attachmentMenuExpanded = false
        viewModel.uploadAttachmentFromUri("image", name, safeMime, uri, reply, richContent = true)
        replyTarget = null
    }

    if (Build.VERSION.SDK_INT >= 33) {
        LaunchedEffect(Unit) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    DisposableEffect(lifecycleOwner, conversation.id) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                viewModel.refreshNow(reconnectIfNeeded = true, showIndicator = false)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(conversation.id) {
        viewModel.setConversationVisible(conversation.id, true)
        onDispose {
            viewModel.setConversationVisible(conversation.id, false)
        }
    }

    DisposableEffect(conversation.id) {
        onDispose {
            runCatching { recorder?.stop() }
            recorder?.release()
            recorder = null
            recordingFile?.delete()
            recordingFile = null
            recordingStartedAt = 0L
            voiceGestureCancelled = false
            audioPlayer?.release()
            audioPlayer = null
            playingAudioMessageId = null
        }
    }

    fun startVoiceRecordingIfPossible() {
        if (recorder != null) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            recordPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        startRecording(context) { media, file, started ->
            recorder = media
            recordingFile = file
            recordingStartedAt = started
            voiceGestureCancelled = false
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    fun finishVoiceRecording(send: Boolean) {
        val media = recorder ?: return
        val file = recordingFile
        runCatching { media.stop() }
        media.release()
        recorder = null
        recordingFile = null
        val durationMs = (System.currentTimeMillis() - recordingStartedAt).coerceAtLeast(0L)
        recordingStartedAt = 0L
        val shouldSend = send && !voiceGestureCancelled
        voiceGestureCancelled = false
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        if (shouldSend && file != null && file.exists() && durationMs >= 250L) {
            val reply = activeReplyTarget?.let { ReplyPreview(it.id, it.username, it.color, activeReplyPreview.orEmpty()) }
            shouldAutoScroll.value = true
            viewModel.uploadAttachment("audio", file.name, "audio/mp4", file.readBytes(), reply, durationMs)
            replyTarget = null
        }
        file?.delete()
    }

    fun stopAudioPlayback() {
        audioPlayer?.runCatching {
            if (isPlaying) stop()
        }
        audioPlayer?.release()
        audioPlayer = null
        playingAudioMessageId = null
        audioPlaybackPositionMs = 0L
        audioPlaybackDurationMs = 0L
    }

    fun belongsToCurrentUser(message: ChatMessage): Boolean = when {
        message.userCode.isNotBlank() && state.me?.userCode?.isNotBlank() == true -> message.userCode == state.me?.userCode
        else -> message.username == state.me?.username
    }

    suspend fun toggleAudioPlayback(message: ChatMessage) {
        if (playingAudioMessageId == message.id) {
            stopAudioPlayback()
            return
        }
        stopAudioPlayback()
        runCatching {
            val attachment = viewModel.materializeAttachment(context, message, exportToDownloads = false)
            val file = attachment.file ?: throw IllegalStateException("Unable to access voice note")
            val player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    it.release()
                    if (playingAudioMessageId == message.id) {
                        audioPlayer = null
                        playingAudioMessageId = null
                        audioPlaybackPositionMs = 0L
                        audioPlaybackDurationMs = 0L
                    }
                }
                prepare()
                audioPlaybackDurationMs = duration.toLong().coerceAtLeast(0L)
                audioPlaybackPositionMs = 0L
                start()
            }
            if (!belongsToCurrentUser(message) && currentUserIdentity.isNotBlank()) {
                localPrefs.markAudioMessageHeard(currentUserIdentity, message.id)
                heardAudioMessages[message.id] = true
            }
            audioPlayer = player
            playingAudioMessageId = message.id
        }.onFailure {
            viewModel.reportError(it.message ?: "Unable to play voice note")
            viewModel.clearDownloadProgress()
        }
    }

    LaunchedEffect(audioPlayer, playingAudioMessageId) {
        val player = audioPlayer ?: return@LaunchedEffect
        while (playingAudioMessageId != null) {
            audioPlaybackPositionMs = runCatching { player.currentPosition.toLong().coerceAtLeast(0L) }.getOrDefault(0L)
            audioPlaybackDurationMs = runCatching { player.duration.toLong().coerceAtLeast(0L) }.getOrDefault(audioPlaybackDurationMs)
            delay(250)
        }
    }

    fun openInAppCamera() {
        attachmentMenuExpanded = false
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            cameraVisible = true
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    var initialScrollConversationId by rememberSaveable(conversation.id) { mutableLongStateOf(-1L) }
    var initialScrollReadyConversationId by rememberSaveable(conversation.id) { mutableLongStateOf(-1L) }
    val messageCount = state.messages.size
    val latestMessageKey = state.messages.lastOrNull()?.let { "${it.id}:${it.localSendState}:${it.ts}" }.orEmpty()
    val isInitialChatPositionReady = messageCount == 0 || initialScrollReadyConversationId == conversation.id

    suspend fun scrollToLatestMessage(force: Boolean = false): Boolean {
        val targetIndex = state.messages.size
        if (targetIndex <= 0 || (!force && !shouldAutoScroll.value)) return false
        repeat(4) {
            if (listState.layoutInfo.totalItemsCount > targetIndex) {
                listState.scrollToItem(targetIndex)
                return true
            }
            withFrameNanos { }
        }
        return false
    }

    LaunchedEffect(conversation.id, restoreScrollPosition, messageCount) {
        val savedPosition = restoreScrollPosition ?: return@LaunchedEffect
        repeat(4) {
            val itemCount = listState.layoutInfo.totalItemsCount
            if (itemCount > 0) {
                listState.scrollToItem(
                    index = savedPosition.firstVisibleItemIndex.coerceIn(0, itemCount - 1),
                    scrollOffset = savedPosition.firstVisibleItemScrollOffset.coerceAtLeast(0),
                )
                shouldAutoScroll.value = false
                initialScrollConversationId = conversation.id
                initialScrollReadyConversationId = conversation.id
                onScrollPositionRestored()
                return@LaunchedEffect
            }
            withFrameNanos { }
        }
    }

    LaunchedEffect(conversation.id) {
        if (restoreScrollPosition != null) return@LaunchedEffect
        shouldAutoScroll.value = true
        initialScrollConversationId = -1L
        initialScrollReadyConversationId = if (state.messages.isEmpty()) conversation.id else -1L
    }
    LaunchedEffect(conversation.id, messageCount) {
        if (restoreScrollPosition != null) return@LaunchedEffect
        val forceInitialScroll = messageCount > 0 && initialScrollConversationId != conversation.id
        if (forceInitialScroll) {
            scrollToLatestMessage(force = true)
            initialScrollConversationId = conversation.id
            initialScrollReadyConversationId = conversation.id
            return@LaunchedEffect
        }
        if (messageCount == 0 && initialScrollReadyConversationId != conversation.id) {
            initialScrollReadyConversationId = conversation.id
        }
    }
    LaunchedEffect(conversation.id, latestMessageKey) {
        if (restoreScrollPosition != null) return@LaunchedEffect
        if (initialScrollReadyConversationId == conversation.id) scrollToLatestMessage()
    }
    LaunchedEffect(conversation.id, imeBottomPx, bottomInputHeightPx) {
        if (restoreScrollPosition != null) return@LaunchedEffect
        if (initialScrollReadyConversationId == conversation.id && shouldAutoScroll.value) {
            withFrameNanos { }
            scrollToLatestMessage()
        }
    }

    LaunchedEffect(conversation.id, state.hasMoreBefore, state.isLoadingOlder, isInitialChatPositionReady) {
        if (!isInitialChatPositionReady) return@LaunchedEffect
        snapshotFlow {
            Triple(
                listState.firstVisibleItemIndex,
                listState.isScrollInProgress,
                shouldAutoScroll.value,
            )
        }
            .distinctUntilChanged()
            .collect { (firstVisible, scrolling, followingLatest) ->
                if (
                    scrolling && !followingLatest && firstVisible <= 1 &&
                    state.hasMoreBefore && !state.isLoadingOlder
                ) {
                    viewModel.loadOlder()
                }
            }
    }

    LaunchedEffect(state.messages.map { it.id to it.kind }, currentUserIdentity) {
        if (currentUserIdentity.isBlank()) {
            heardAudioMessages.clear()
        } else {
            val activeAudioIds = state.messages.asSequence()
                .filter { it.kind == "audio" }
                .map { it.id }
                .toSet()
            heardAudioMessages.keys.toList().forEach { id ->
                if (id !in activeAudioIds) heardAudioMessages.remove(id)
            }
            state.messages.asSequence()
                .filter { it.kind == "audio" }
                .forEach { message ->
                    if (message.id !in heardAudioMessages) {
                        heardAudioMessages[message.id] = localPrefs.hasHeardAudioMessage(currentUserIdentity, message.id)
                    }
                }
        }
    }

    LaunchedEffect(activeReplyTarget?.id, activeReplyTarget?.kind) {
        if (activeReplyTarget?.kind == "recalled" || activeReplyTarget?.kind == "attachment_cleared") {
            replyTarget = null
        }
    }

    val mentionQuery = remember(composer) { currentMentionQuery(composer) }

    if (showClearHistoryDialog) {
        AnimatedActionDialog(
            title = strings.clearChatHistory,
            destructive = true,
            body = { Text(strings.deleteHistoryConfirm) },
            confirmText = strings.delete,
            onConfirm = {
                showClearHistoryDialog = false
                viewModel.clearCurrentConversationHistory()
            },
            dismissText = strings.cancel,
            onDismissRequest = { showClearHistoryDialog = false }
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        // The thread gets its own material so bubbles read as sitting on
        // something, rather than floating on the same colour as the page.
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        topBar = {
            // Same concept as the conversation list's group avatar, which used
            // 14dp here and 10dp there. Both now use the small token.
            val avatarShape = if (conversation.kind == "group") MaterialTheme.shapes.small else CircleShape
            AppTopBar(
                title = conversation.title,
                subtitle = connectionStatusText(connectionStatus, strings),
                subtitleColor = connectionStatusTint,
                onBack = onBack,
                navigationAvatar = {
                    Box(
                        modifier = Modifier
                            .clip(avatarShape)
                            .clickable {
                                if (conversation.kind == "group") {
                                    onOpenGroupManage()
                                } else {
                                    onOpenPeerManage(directPeer?.userCode)
                                }
                            }
                    ) {
                        AvatarBubble(
                            conversation.avatarUrl.ifBlank { directPeer?.avatarUrl.orEmpty() },
                            conversation.title,
                            directPeer?.color ?: "#128c7e",
                            36.dp,
                            serverUrl = state.serverUrl,
                            shape = avatarShape
                        )
                    }
                },
                actions = {
                    if (conversation.kind == "direct") {
                        IconButton(
                            onClick = { onStartVoiceCall(conversation.id) },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(Icons.Default.Call, contentDescription = strings.voiceCall)
                        }
                    }
                    Box {
                        IconButton(
                            onClick = { headerMenuExpanded = true },
                            modifier = Modifier.size(48.dp),
                        ) {
                            Icon(Icons.Default.MoreVert, contentDescription = strings.settings)
                        }
                        DropdownMenu(
                            expanded = headerMenuExpanded,
                            onDismissRequest = { headerMenuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(strings.scrollToTop) },
                                leadingIcon = { Icon(Icons.Default.KeyboardArrowUp, contentDescription = null) },
                                onClick = {
                                    headerMenuExpanded = false
                                    scope.launch { listState.animateScrollToItem(0) }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(strings.scrollToBottom) },
                                leadingIcon = { Icon(Icons.Default.KeyboardArrowDown, contentDescription = null) },
                                onClick = {
                                    headerMenuExpanded = false
                                    scope.launch { scrollToLatestMessage(force = true) }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(strings.refresh) },
                                leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                                onClick = {
                                    headerMenuExpanded = false
                                    viewModel.refreshNow(reconnectIfNeeded = true, showIndicator = true)
                                },
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (conversationMuted) strings.unmuteConversation
                                        else strings.muteConversation
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        if (conversationMuted) Icons.Default.VolumeOff
                                        else Icons.Default.VolumeUp,
                                        contentDescription = null
                                    )
                                },
                                onClick = {
                                    headerMenuExpanded = false
                                    val next = !conversationMuted
                                    localPrefs.setConversationMuted(conversation.id, next)
                                    conversationMuted = next
                                    Toast.makeText(
                                        context,
                                        if (next) strings.conversationMuted else strings.conversationUnmuted,
                                        Toast.LENGTH_SHORT
                                    ).show()
                                },
                            )
                            if (conversation.kind == "direct") {
                                DropdownMenuItem(
                                    text = { Text(strings.clearChatHistory) },
                                    leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                                    onClick = {
                                        headerMenuExpanded = false
                                        showClearHistoryDialog = true
                                    },
                                )
                            }
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(padding)
                // Scaffold already applied the navigation-bar inset through
                // `padding`, but a plain padding modifier does not *consume* it,
                // so imePadding() below went on to apply the full IME inset -
                // which spans the navigation bar too. The two summed instead of
                // taking the larger, leaving the composer a navigation bar's
                // height above the keyboard. Consuming first makes imePadding
                // add only the remainder.
                .consumeWindowInsets(padding)
                .imePadding()
        ) {
            ConnectionActivityIndicator(connectionStatus)
            state.uploadProgress
                ?.takeIf { it.messageId == 0L || it.conversationId != conversation.id }
                ?.let { TransferProgressCard(strings.uploading, it) }
            state.downloadProgress
                ?.takeIf { it.messageId == 0L || it.conversationId != conversation.id }
                ?.let { TransferProgressCard(strings.downloading, it) }

            PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = { viewModel.refreshNow(reconnectIfNeeded = true, showIndicator = true) },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = if (configuration.screenWidthDp < 360) 8.dp else 12.dp)
                        .focusProperties { canFocus = false }
                        .nestedScroll(chatBounceConnection)
                        .offset { IntOffset(0, bottomBounceOffset.value.roundToInt()) }
                        .graphicsLayer {
                            alpha = if (isInitialChatPositionReady) 1f else 0f
                        },
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(top = 6.dp, bottom = 10.dp)
                ) {
                    itemsIndexed(
                        state.messages,
                        key = { _, item -> stableMessageListKey(item, currentUserIdentity) }
                    ) { index, message ->
                        Column(
                            // A message arriving is the most repeated moment in
                            // the app and previously had no motion at all - new
                            // bubbles simply appeared. The spring settles with a
                            // slight overshoot so the message reads as landing
                            // rather than being painted into place.
                            //
                            // This animates items added to or reordered within the
                            // list data, not items scrolled into view, so it does
                            // not fire while paging through history.
                            modifier = Modifier.animateItem(
                                fadeInSpec = arrivalSpring(),
                                placementSpec = placementSpring(),
                            ),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            val previousMessage = state.messages.getOrNull(index - 1)
                            if (previousMessage != null && shouldShowTimelineSeparator(previousMessage.ts, message.ts)) {
                                TimelineSeparator(label = formatTimelineLabel(message.ts, state.language, strings))
                            }
                            MessageBubble(
                                message = message,
                                state = state,
                                conversationKind = conversation.kind,
                                sender = usersByIdentity[message.userCode.ifBlank { message.username }],
                                strings = strings,
                                participantUsernames = participantUsernames,
                                bubbleMaxWidth = bubbleMaxWidth,
                                messagesById = messagesById,
                                decryptedTextCache = decryptedTextCache,
                                attachmentMetaCache = attachmentMetaCache,
                                localPrefs = localPrefs,
                                resolveMessageText = { viewModel.resolveMessageText(it) },
                                prepareMessageDecryption = { viewModel.prepareMessageDecryption(it) },
                                uploadProgress = state.uploadProgress,
                                downloadProgress = state.downloadProgress,
                                onReply = { replyTarget = it },
                                onRecall = { viewModel.recallMessage(it) },
                                onRetryTap = { retryTarget = it },
                                onOpenAttachment = { opened -> scope.launch { openAttachment(context, viewModel, opened) } },
                                onPreviewImage = {
                                    onPreviewImage(
                                        it.conversationId,
                                        it.id,
                                        ChatScrollPosition(
                                            firstVisibleItemIndex = listState.firstVisibleItemIndex,
                                            firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset,
                                        ),
                                    )
                                },
                                onPlayAudio = { opened -> scope.launch { toggleAudioPlayback(opened) } },
                                onSenderAvatarClick = { userCode -> onOpenPeerManage(userCode) },
                                isAudioPlaying = playingAudioMessageId == message.id,
                                audioPlaybackProgress = activeAudioPlaybackProgress.takeIf {
                                    playingAudioMessageId == message.id
                                },
                                hasUnreadAudioIndicator = message.kind == "audio" &&
                                    !belongsToCurrentUser(message) &&
                                    heardAudioMessages[message.id] != true
                            )
                        }
                    }
                    item(key = "bottom-anchor-${conversation.id}") {
                        Spacer(modifier = Modifier.height(1.dp))
                    }
                }
            }

            LaunchedEffect(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, isInitialChatPositionReady) {
                if (!isInitialChatPositionReady) return@LaunchedEffect
                val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                shouldAutoScroll.value = lastVisible >= (state.messages.lastIndex - 2).coerceAtLeast(0)
                if (shouldAutoScroll.value) {
                    viewModel.markReadLatest()
                }
            }


            retryTarget?.let { failedMessage ->
                RetrySendSheet(
                    strings = strings,
                    onRetry = {
                        shouldAutoScroll.value = true
                        viewModel.retryMessage(failedMessage.id)
                        scope.launch { scrollToLatestMessage() }
                        retryTarget = null
                    },
                    onDeleteLocal = {
                        viewModel.deleteLocalPendingMessage(failedMessage.id)
                        retryTarget = null
                    },
                    onDismiss = { retryTarget = null }
                )
            }

            if (cameraVisible) {
                InAppCameraDialog(
                    strings = strings,
                    onDismiss = { cameraVisible = false },
                    onError = { viewModel.reportError(it) },
                    onSendPhoto = { file ->
                        scope.launch {
                            runCatching {
                                val bytes = withContext(Dispatchers.IO) { file.readBytes() }
                                val reply = activeReplyTarget?.let {
                                    ReplyPreview(it.id, it.username, it.color, activeReplyPreview.orEmpty())
                                }
                                val fileName = "photo_${System.currentTimeMillis()}.jpg"
                                shouldAutoScroll.value = true
                                viewModel.uploadAttachment("image", fileName, "image/jpeg", bytes, reply)
                                scrollToLatestMessage()
                                replyTarget = null
                            }.onFailure {
                                viewModel.reportError(it.message ?: strings.cameraCaptureFailed)
                            }
                            file.delete()
                            cameraVisible = false
                        }
                    }
                )
            }

            AnimatedVisibility(
                visible = activeReplyTarget != null,
                enter = fadeIn(tween(MotionMedium, easing = FastOutSlowInEasing)) +
                    scaleIn(initialScale = 0.98f, animationSpec = tween(MotionMedium, easing = FastOutSlowInEasing)),
                exit = fadeOut(tween(MotionShort, easing = FastOutSlowInEasing)) +
                    scaleOut(targetScale = 0.98f, animationSpec = tween(MotionShort, easing = FastOutSlowInEasing))
            ) {
                val target = activeReplyTarget ?: return@AnimatedVisibility
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, bottom = 6.dp),
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f))
                ) {
                    Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("${strings.replyingTo} ${target.username}", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                            ReplyPreviewContent(
                                fallbackPreview = activeReplyPreview.orEmpty(),
                                originalMessage = target,
                                secret = decryptSecret,
                                strings = strings,
                                decryptedTextCache = decryptedTextCache,
                                attachmentMetaCache = attachmentMetaCache,
                                localPrefs = localPrefs,
                                resolveMessageText = { viewModel.resolveMessageText(it) },
                                prepareMessageDecryption = { viewModel.prepareMessageDecryption(it) },
                                serverUrl = state.serverUrl,
                                thumbnailSize = 44.dp
                            )
                        }
                        TextButton(onClick = { replyTarget = null }) { Text(strings.cancel) }
                    }
                }
            }

            if (mentionQuery != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    mentionCandidates.filter { it.username.contains(mentionQuery, true) }.take(6).forEach { user ->
                        FilledTonalButton(onClick = {
                            val updated = replaceMention(composer, user.username)
                            composer = updated
                        }) {
                            Text("@${user.username}")
                        }
                    }
                }
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .onSizeChanged { bottomInputHeightPx = it.height },
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 8.dp)
                ) {
                    // ChatScreen called reportError() in four places - camera
                    // permission denied, voice playback failure, capture failure
                    // - and then never rendered state.error anywhere. Denying the
                    // camera permission produced no visible response at all.
                    //
                    // It sits above the composer rather than over the thread, so
                    // it appears next to the controls that caused it without
                    // covering messages.
                    state.error?.let { rawError ->
                        FcMessageBanner(
                            text = friendlyErrorMessage(rawError, state.language),
                            tone = FcMessageTone.PROBLEM,
                            onDismiss = viewModel::clearError,
                            modifier = Modifier.padding(bottom = Spacing.sm),
                        )
                    }
                    AnimatedVisibility(
                        visible = attachmentMenuExpanded && !voiceInputMode,
                        enter = fadeIn(tween(MotionMedium, easing = FastOutSlowInEasing)) +
                            scaleIn(initialScale = 0.98f, animationSpec = tween(MotionMedium, easing = FastOutSlowInEasing)),
                        exit = fadeOut(tween(MotionShort, easing = FastOutSlowInEasing)) +
                            scaleOut(targetScale = 0.98f, animationSpec = tween(MotionShort, easing = FastOutSlowInEasing))
                    ) {
                        AttachmentInlinePanel(
                            strings = strings,
                            onCamera = { openInAppCamera() },
                            onImage = {
                                attachmentMenuExpanded = false
                                imagePicker.launch("image/*")
                            },
                            onFile = {
                                attachmentMenuExpanded = false
                                filePicker.launch("*/*")
                            }
                        )
                    }
                    if (recorder != null) {
                        Text(
                            strings.recording,
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(start = 12.dp, bottom = 6.dp)
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        IconButton(
                            onClick = {
                                finishVoiceRecording(send = false)
                                voiceInputMode = false
                                attachmentMenuExpanded = !attachmentMenuExpanded
                            },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = strings.addMedia,
                                tint = if (recorder == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                            )
                        }
                        IconButton(
                            onClick = {
                                attachmentMenuExpanded = false
                                if (voiceInputMode) {
                                    finishVoiceRecording(send = false)
                                    voiceInputMode = false
                                } else {
                                    val focusedView = rootView.findFocus()
                                    val windowToken = focusedView?.windowToken ?: rootView.windowToken
                                    focusedView?.clearFocus()
                                    keyboardController?.hide()
                                    context.getSystemService(InputMethodManager::class.java)
                                        ?.hideSoftInputFromWindow(windowToken, 0)
                                    voiceInputMode = true
                                    FamilyChatDiagnostics.sampled(
                                        "composer_voice_mode_entered",
                                        1_000L,
                                        "conversation_id" to conversation.id,
                                    )
                                }
                            },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                if (voiceInputMode) Icons.Default.Keyboard else Icons.Default.Mic,
                                contentDescription = if (voiceInputMode) strings.keyboardInput else strings.voiceNote,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        if (voiceInputMode) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .clip(FullShape)
                                    .background(
                                        if (recorder != null) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f)
                                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                                    )
                                    .pointerInteropFilter { event ->
                                        when (event.actionMasked) {
                                            MotionEvent.ACTION_DOWN -> {
                                                voiceGestureCancelled = false
                                                startVoiceRecordingIfPossible()
                                                true
                                            }

                                            MotionEvent.ACTION_MOVE -> {
                                                val wasCancelled = voiceGestureCancelled
                                                voiceGestureCancelled = event.y < -32f
                                                if (!wasCancelled && voiceGestureCancelled) {
                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                }
                                                true
                                            }

                                            MotionEvent.ACTION_UP -> {
                                                finishVoiceRecording(send = true)
                                                true
                                            }

                                            MotionEvent.ACTION_CANCEL -> {
                                                voiceGestureCancelled = true
                                                finishVoiceRecording(send = false)
                                                true
                                            }

                                            else -> false
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    when {
                                        recorder == null -> strings.holdToTalk
                                        voiceGestureCancelled -> strings.releaseToCancel
                                        else -> strings.releaseToSend
                                    },
                                    color = if (recorder != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            RichMessageInput(
                                value = composer,
                                onValueChange = {
                                    attachmentMenuExpanded = false
                                    composer = it
                                },
                                placeholder = strings.typeMessage,
                                onRichImageContent = ::uploadRichImageContent,
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp, max = 120.dp)
                            )
                            val sendEnabled = composer.text.isNotBlank()
                            IconButton(
                                onClick = {
                                    val outgoingText = composer.text
                                    if (outgoingText.isBlank()) return@IconButton
                                    // Confirms the send landed without the user
                                    // having to watch for the tick. Deliberately
                                    // the light variant: LongPress is already
                                    // used here for destructive and menu actions.
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    shouldAutoScroll.value = true
                                    val reply = activeReplyTarget?.let { ReplyPreview(it.id, it.username, it.color, activeReplyPreview.orEmpty()) }
                                    viewModel.sendText(outgoingText, reply)
                                    composer = TextFieldValue("")
                                    viewModel.saveDraft(conversation.id, "")
                                    replyTarget = null
                                    attachmentMenuExpanded = false
                                    scope.launch { scrollToLatestMessage() }
                                },
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (sendEnabled) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f)
                                    )
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.Send,
                                    strings.sendMessage,
                                    tint = if (sendEnabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RichMessageInput(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    placeholder: String,
    onRichImageContent: (Uri, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val textColor = MaterialTheme.colorScheme.onSurface
    val hintColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
    // The composer field is a pill; FullShape keeps it one at any height rather
    // than only at the 48dp the 24dp value happened to assume.
    val inputShape = FullShape
    val backgroundColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.52f)
    val outlineColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)
    val density = LocalDensity.current

    Box(
        modifier = modifier
            .clip(inputShape)
            .background(backgroundColor)
            .border(0.8.dp, outlineColor, inputShape),
        contentAlignment = Alignment.CenterStart
    ) {
        AndroidView(
            factory = { context ->
                RichContentEditText(context).apply {
                    val horizontalPadding = with(density) { 16.dp.toPx().roundToInt() }
                    val verticalPadding = with(density) { 11.dp.toPx().roundToInt() }
                    setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
                    setTextColor(textColor.toArgb())
                    setHintTextColor(hintColor.toArgb())
                    hint = placeholder
                    this.onTextFieldValueChange = onValueChange
                    this.onRichImageContent = onRichImageContent
                    applyValue(value)
                }
            },
            update = { editText ->
                editText.hint = placeholder
                editText.setTextColor(textColor.toArgb())
                editText.setHintTextColor(hintColor.toArgb())
                editText.onTextFieldValueChange = onValueChange
                editText.onRichImageContent = onRichImageContent
                editText.applyValue(value)
            },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
        )
    }
}

@Composable
internal fun TransferProgressCard(label: String, progress: TransferProgress) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("$label: ${progress.label}", fontSize = 13.sp)
            LinearProgressIndicator(progress = { progress.progress }, modifier = Modifier.fillMaxWidth())
            Text("${formatSize(progress.bytesDone)} / ${formatSize(progress.totalBytes)}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
    }
}

@Composable
private fun InAppCameraDialog(
    strings: AppStrings,
    onDismiss: () -> Unit,
    onError: (String) -> Unit,
    onSendPhoto: (File) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    val previewUseCase = remember { CameraPreview.Builder().build() }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var capturedFile by remember { mutableStateOf<File?>(null) }
    var captureInProgress by remember { mutableStateOf(false) }
    var cameraReady by remember { mutableStateOf(false) }
    var captureRotation by remember { mutableIntStateOf(Surface.ROTATION_0) }
    val dialogActive = remember { AtomicBoolean(true) }

    fun updateCaptureRotation(requestedRotation: Int, source: String): Boolean {
        val safeRotation = normalizeSurfaceRotation(requestedRotation)
        return runCatching {
            imageCapture.targetRotation = safeRotation
            captureRotation = safeRotation
        }.onFailure {
            FamilyChatDiagnostics.event(
                "camera_rotation_rejected",
                "source" to source,
                "requested" to requestedRotation,
                "normalized" to safeRotation,
                "error" to it::class.java.simpleName,
            )
        }.isSuccess
    }
    val cameraPreviewTargetPx = remember(configuration.screenWidthDp, configuration.screenHeightDp, density) {
        with(density) {
            maxOf(configuration.screenWidthDp.dp, configuration.screenHeightDp.dp).roundToPx()
        }
    }
    val capturedBitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(initialValue = null, capturedFile?.absolutePath, cameraPreviewTargetPx) {
        value = capturedFile
            ?.takeIf { it.exists() }
            ?.let { file ->
                withContext(Dispatchers.IO) {
                    decodeScaledBitmap(file, cameraPreviewTargetPx)?.asImageBitmap()
                }
            }
    }

    fun discardPhotoAndClose() {
        if (!dialogActive.getAndSet(false)) return
        capturedFile?.takeIf { it.exists() }?.delete()
        capturedFile = null
        captureInProgress = false
        cameraReady = false
        runCatching { cameraProvider?.unbindAll() }
        FamilyChatDiagnostics.event("camera_dialog_dismissed")
        onDismiss()
    }

    DisposableEffect(previewView, lifecycleOwner, capturedFile) {
        val currentPreview = previewView
        if (currentPreview == null || capturedFile != null) {
            onDispose { }
        } else {
            var disposed = false
            cameraReady = false
            FamilyChatDiagnostics.event("camera_bind_started")
            val providerFuture = ProcessCameraProvider.getInstance(context)
            val listener = Runnable {
                if (disposed || !dialogActive.get()) return@Runnable
                runCatching {
                    val provider = providerFuture.get()
                    provider.unbindAll()
                    previewUseCase.setSurfaceProvider(currentPreview.surfaceProvider)
                    updateCaptureRotation(
                        requestedRotation = currentPreview.display?.rotation ?: captureRotation,
                        source = "display",
                    )
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        previewUseCase,
                        imageCapture,
                    )
                    cameraProvider = provider
                    cameraReady = true
                    FamilyChatDiagnostics.event("camera_bind_succeeded")
                }.onFailure {
                    FamilyChatDiagnostics.event(
                        "camera_bind_failed",
                        "error" to it::class.java.simpleName,
                    )
                    onError(it.message ?: strings.cameraUnavailable)
                    discardPhotoAndClose()
                }
            }
            providerFuture.addListener(listener, mainExecutor)
            onDispose {
                disposed = true
                cameraReady = false
                runCatching { cameraProvider?.unbindAll() }
            }
        }
    }

    DisposableEffect(context) {
        val orientationListener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                val nextRotation = rotationFromOrientation(orientation) ?: return
                updateCaptureRotation(nextRotation, source = "sensor")
            }
        }
        if (orientationListener.canDetectOrientation()) {
            orientationListener.enable()
        }
        onDispose {
            orientationListener.disable()
        }
    }

    DisposableEffect(Unit) {
        FamilyChatDiagnostics.event("camera_dialog_opened")
        onDispose {
            dialogActive.set(false)
            runCatching { cameraProvider?.unbindAll() }
            capturedFile?.takeIf { it.exists() }?.delete()
        }
    }

    fun capturePhoto() {
        if (!cameraReady || captureInProgress || !dialogActive.get()) return
        val outputFile = runCatching {
            File.createTempFile("camera_", ".jpg", context.cacheDir)
        }.getOrElse {
            FamilyChatDiagnostics.event("camera_capture_failed", "stage" to "create_file", "error" to it::class.java.simpleName)
            onError(it.message ?: strings.cameraCaptureFailed)
            return
        }
        captureInProgress = true
        FamilyChatDiagnostics.event("camera_capture_requested", "rotation" to captureRotation)
        runCatching {
            if (!updateCaptureRotation(captureRotation, source = "capture")) {
                error("Unable to configure camera rotation")
            }
            imageCapture.takePicture(
                ImageCapture.OutputFileOptions.Builder(outputFile).build(),
                mainExecutor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        if (!dialogActive.get()) {
                            outputFile.delete()
                            return
                        }
                        FamilyChatDiagnostics.event("camera_capture_saved", "bytes" to outputFile.length())
                        scope.launch {
                            val normalized = runCatching {
                                withContext(Dispatchers.IO) {
                                    normalizeCapturedPhoto(outputFile, maxDimensionPx = 2048)
                                }
                            }
                            if (!dialogActive.get()) {
                                outputFile.delete()
                                return@launch
                            }
                            normalized.onSuccess {
                                captureInProgress = false
                                cameraReady = false
                                runCatching { cameraProvider?.unbindAll() }
                                capturedFile
                                    ?.takeIf { it.exists() && it.absolutePath != outputFile.absolutePath }
                                    ?.delete()
                                capturedFile = outputFile
                                FamilyChatDiagnostics.event("camera_photo_ready", "bytes" to outputFile.length())
                            }.onFailure {
                                captureInProgress = false
                                outputFile.delete()
                                FamilyChatDiagnostics.event(
                                    "camera_capture_failed",
                                    "stage" to "normalize",
                                    "error" to it::class.java.simpleName,
                                )
                                onError(it.message ?: strings.cameraCaptureFailed)
                            }
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        if (!dialogActive.get()) {
                            outputFile.delete()
                            return
                        }
                        captureInProgress = false
                        outputFile.delete()
                        FamilyChatDiagnostics.event(
                            "camera_capture_failed",
                            "stage" to "camera_x",
                            "code" to exception.imageCaptureError,
                            "error" to exception::class.java.simpleName,
                        )
                        onError(exception.message ?: strings.cameraCaptureFailed)
                    }
                },
            )
        }.onFailure {
            captureInProgress = false
            outputFile.delete()
            FamilyChatDiagnostics.event(
                "camera_capture_failed",
                "stage" to "take_picture",
                "error" to it::class.java.simpleName,
            )
            onError(it.message ?: strings.cameraCaptureFailed)
        }
    }

    Dialog(
        onDismissRequest = { discardPhotoAndClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
    ) {
        ConfigureDialogWindow()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            if (capturedFile == null) {
                AndroidView(
                    factory = { ctx ->
                        PreviewView(ctx).apply {
                            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                            previewView = this
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
                // A full-screen camera does not need a title bar announcing that
                // it is a camera, and a close affordance should be a target
                // rather than the word "Cancel". Gradient over flat, matching
                // the picture viewer.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent)
                            )
                        )
                        .statusBarsPadding()
                        .padding(horizontal = Spacing.sm, vertical = Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { discardPhotoAndClose() },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = strings.cancel, tint = Color.White)
                    }
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 132.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (!cameraReady || captureInProgress) {
                        CircularProgressIndicator(color = Color.White, modifier = Modifier.size(42.dp), strokeWidth = 3.dp)
                    } else {
                        // A shutter, not an icon button. The camera glyph inside
                        // it restated the screen the user was already looking at.
                        IconButton(
                            onClick = ::capturePhoto,
                            modifier = Modifier.size(86.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(74.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.28f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(60.dp)
                                        .clip(CircleShape)
                                        .background(Color.White)
                                        .semantics { contentDescription = strings.takePhoto }
                                )
                            }
                        }
                    }
                }
            } else {
                capturedBitmap?.let { bitmap ->
                    Image(
                        bitmap = bitmap,
                        contentDescription = strings.takePhoto,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .padding(horizontal = 16.dp, vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { discardPhotoAndClose() }) {
                        Text(strings.cancel, color = Color.White)
                    }
                    Spacer(Modifier.weight(1f))
                    Text(strings.takePhoto, color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    Spacer(Modifier.width(64.dp))
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(start = 20.dp, end = 20.dp, bottom = 132.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { discardPhotoAndClose() }) {
                        Text(strings.cancel, color = Color.White)
                    }
                    Button(
                        onClick = {
                            val photo = capturedFile ?: return@Button
                            dialogActive.set(false)
                            onSendPhoto(photo)
                            capturedFile = null
                        },
                        shape = MaterialTheme.shapes.large
                    ) {
                        Text(strings.sendPhoto)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: ChatMessage,
    state: ChatScreenUiState,
    conversationKind: String,
    sender: ChatUser?,
    strings: AppStrings,
    participantUsernames: List<String>,
    bubbleMaxWidth: Dp,
    messagesById: Map<Long, ChatMessage>,
    decryptedTextCache: MutableMap<Long, String>,
    attachmentMetaCache: MutableMap<Long, JSONObject?>,
    localPrefs: ChatPreferences,
    resolveMessageText: suspend (ChatMessage) -> String,
    prepareMessageDecryption: suspend (ChatMessage) -> Unit,
    uploadProgress: TransferProgress?,
    downloadProgress: TransferProgress?,
    onReply: (ChatMessage) -> Unit,
    onRecall: (ChatMessage) -> Unit,
    onRetryTap: (ChatMessage) -> Unit,
    onOpenAttachment: (ChatMessage) -> Unit,
    onPreviewImage: (ChatMessage) -> Unit,
    onPlayAudio: (ChatMessage) -> Unit,
    onSenderAvatarClick: (String) -> Unit,
    isAudioPlaying: Boolean,
    audioPlaybackProgress: Float?,
    hasUnreadAudioIndicator: Boolean,
) {
    if (message.kind == "security_notice") {
        val noticePayload = remember(message.payload) { parseJson(message.payload) }
        val noticeText = buildString {
            val noticeType = noticePayload?.optString("type").orEmpty()
            append(
                when (noticeType) {
                    "device_added" -> strings.deviceAdded
                    "device_removed" -> strings.deviceRemoved
                    else -> strings.safetyCodeChanged
                }
            )
            noticePayload?.optString("username")?.takeIf { it.isNotBlank() }?.let {
                append(" - ")
                append(it)
            }
            noticePayload?.optString("device_name")?.takeIf { it.isNotBlank() }?.let {
                append(" / ")
                append(it)
            }
        }
        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
            Text(
                noticeText,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
        return
    }

    val mine = when {
        message.userCode.isNotBlank() && state.me?.userCode?.isNotBlank() == true -> message.userCode == state.me?.userCode
        else -> message.username == state.me?.username
    }
    val showSenderAvatar = !mine && conversationKind != "direct"
    val replyEnabled = message.kind != "recalled" && message.kind != "attachment_cleared"
    val density = androidx.compose.ui.platform.LocalDensity.current
    val swipeThresholdPx = with(density) { 56.dp.toPx() }
    val maxSwipePx = with(density) { 88.dp.toPx() }
    var swipeTarget by remember(message.id) { mutableStateOf(0f) }
    var showRecallMenu by remember(message.id) { mutableStateOf(false) }
    val animatedOffset by animateFloatAsState(targetValue = swipeTarget, label = "replySwipe")
    val replyIconAlpha = if (replyEnabled) ((animatedOffset.absoluteValue / swipeThresholdPx).coerceIn(0f, 1f) * 0.95f) else 0f
    val decryptSecret = ""
    val bubbleTokens = chatBubbleTokens(mine)
    val bubbleShape = MaterialTheme.shapes.medium
    val messagePayloadForDisplay = remember(message.payload) { parseJson(message.payload) }
    val stickerLikeImage by produceState(
        initialValue = (message.kind == "image" || message.kind == "photo") &&
            messagePayloadForDisplay?.optJSONObject("display")?.optBoolean("sticker") == true,
        key1 = message.id,
        key2 = message.kind,
        key3 = message.payload
    ) {
        value = (message.kind == "image" || message.kind == "photo") &&
            (
                messagePayloadForDisplay?.optJSONObject("display")?.optBoolean("sticker") == true ||
                    resolveAttachmentMeta(
                        message,
                        decryptSecret,
                        attachmentMetaCache,
                        localPrefs,
                        prepareMessageDecryption,
                    )?.optBoolean("sticker") == true
                )
    }
    val deliveryStatus = resolveMessageDeliveryStatus(
        localSendState = message.localSendState,
        conversationKind = conversationKind,
        participantIdentities = participantUsernames,
        deliveryStates = state.currentConversationDeliveryStates,
        readStates = state.currentConversationReadStates,
        messageId = message.id,
        isOutgoing = mine
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom
    ) {
        if (showSenderAvatar) {
            AvatarBubble(
                avatarUrl = sender?.avatarUrl.orEmpty(),
                name = message.username,
                colorHex = sender?.color ?: message.color,
                size = 34.dp,
                serverUrl = state.serverUrl,
                modifier = Modifier
                    .focusProperties { canFocus = false }
                    .clickable(enabled = (sender?.userCode ?: message.userCode).isNotBlank()) {
                        val userCode = sender?.userCode ?: message.userCode
                        if (userCode.isNotBlank()) onSenderAvatarClick(userCode)
                    }
            )
            Spacer(Modifier.width(4.dp))
        }
        Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
            if (showRecallMenu) {
                Card(
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.padding(bottom = 6.dp)
                ) {
                    TextButton(
                        onClick = {
                            showRecallMenu = false
                            onRecall(message)
                        }
                    ) {
                        Text(strings.recallAction)
                    }
                }
            }
            Box(
                modifier = Modifier.padding(
                    start = if (mine) 0.dp else 18.dp,
                    end = if (mine) 18.dp else 0.dp
                ),
                contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Reply,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = replyIconAlpha),
                    modifier = Modifier
                        .align(if (mine) Alignment.CenterEnd else Alignment.CenterStart)
                        .offset(x = if (mine) 6.dp else (-6).dp)
                        .size(18.dp)
                )
                Card(
                    shape = bubbleShape,
                    colors = CardDefaults.cardColors(
                        containerColor = if (stickerLikeImage) Color.Transparent else bubbleTokens.container,
                        contentColor = bubbleTokens.content
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    modifier = Modifier
                        .widthIn(max = bubbleMaxWidth)
                        .offset { IntOffset(animatedOffset.roundToInt(), 0) }
                        .then(
                            if (stickerLikeImage) {
                                Modifier
                            } else {
                                Modifier.border(0.7.dp, bubbleTokens.outline, bubbleShape)
                            }
                        )
                        .then(
                            if (replyEnabled) {
                                Modifier.pointerInput(message.id, mine) {
                                    detectHorizontalDragGestures(
                                        onDragEnd = {
                                            val shouldReply = if (mine) animatedOffset <= -swipeThresholdPx else animatedOffset >= swipeThresholdPx
                                            if (shouldReply) onReply(message)
                                            swipeTarget = 0f
                                        },
                                        onDragCancel = { swipeTarget = 0f }
                                    ) { change, dragAmount ->
                                        change.consume()
                                        val next = swipeTarget + dragAmount
                                        swipeTarget = if (mine) next.coerceIn(-maxSwipePx, 0f) else next.coerceIn(0f, maxSwipePx)
                                    }
                                }
                            } else {
                                Modifier
                            }
                        )
                        .focusProperties { canFocus = false }
                        .combinedClickable(
                            onLongClick = {
                                if (mine && message.kind != "recalled" && message.kind != "attachment_cleared") {
                                    showRecallMenu = !showRecallMenu
                                }
                            },
                            onClick = { showRecallMenu = false }
                        )
                ) {
                    Column(
                        modifier = Modifier.padding(
                            horizontal = if (stickerLikeImage) 0.dp else 9.dp,
                            vertical = if (stickerLikeImage) 0.dp else 6.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (!mine) {
                            Text(
                                message.username,
                                color = safeUserColor(
                                    sender?.color ?: message.color,
                                    MaterialTheme.colorScheme.primary
                                ),
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                        message.replyTo?.let {
                            val originalMessage = messagesById[it.id]
                            Card(
                                shape = MaterialTheme.shapes.small,
                                colors = CardDefaults.cardColors(
                                    containerColor = bubbleTokens.replyContainer
                                )
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
                                    Text(
                                        "${strings.replyingTo} ${it.username}",
                                        color = MaterialTheme.colorScheme.primary,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                    ReplyPreviewContent(
                                        it.preview,
                                        originalMessage,
                                        decryptSecret,
                                        strings,
                                        decryptedTextCache,
                                        attachmentMetaCache,
                                        localPrefs,
                                        resolveMessageText,
                                        prepareMessageDecryption,
                                        state.serverUrl,
                                        36.dp,
                                        bubbleTokens.muted,
                                        11.sp
                                    )
                                }
                            }
                        }
                        when (message.kind) {
                            "text" -> MessageText(message, decryptSecret, strings, decryptedTextCache, resolveMessageText)
                            "recalled" -> Text(strings.recalledMessage, color = bubbleTokens.muted)
                            "attachment_cleared" -> Text(strings.attachmentRemoved, color = bubbleTokens.muted)
                            "image", "photo", "audio", "file" -> AttachmentBubble(
                                message = message,
                                secret = decryptSecret,
                                strings = strings,
                                attachmentMetaCache = attachmentMetaCache,
                                localPrefs = localPrefs,
                                serverUrl = state.serverUrl,
                                prepareMessageDecryption = prepareMessageDecryption,
                                uploadProgress = uploadProgress?.takeIf { it.messageId == message.id && it.conversationId == message.conversationId },
                                downloadProgress = downloadProgress?.takeIf { it.messageId == message.id && it.conversationId == message.conversationId },
                                onOpenAttachment = onOpenAttachment,
                                onPreviewImage = onPreviewImage,
                                onPlayAudio = onPlayAudio,
                                isAudioPlaying = isAudioPlaying,
                                audioPlaybackProgress = audioPlaybackProgress,
                                hasUnreadAudioIndicator = hasUnreadAudioIndicator
                            )
                        }
                        Row(
                            modifier = if (stickerLikeImage) {
                                Modifier
                                    .align(Alignment.End)
                                    .clip(MaterialTheme.shapes.small)
                                    .background(bubbleTokens.container.copy(alpha = 0.96f))
                                    .padding(horizontal = 7.dp, vertical = 3.dp)
                            } else {
                                Modifier
                            },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(formatMessageTime(message.ts), color = bubbleTokens.muted, style = MaterialTheme.typography.labelSmall)
                            if (mine) {
                                Spacer(Modifier.width(4.dp))
                                when (deliveryStatus) {
                                    MessageDeliveryStatus.SENDING -> CircularProgressIndicator(
                                        modifier = Modifier.size(12.dp),
                                        color = bubbleTokens.muted,
                                        strokeWidth = 1.6.dp
                                    )
                                    MessageDeliveryStatus.SENT -> ReadReceiptIcon(
                                        doubleTick = false,
                                        tint = bubbleTokens.muted,
                                        description = strings.messageStatusSent
                                    )
                                    MessageDeliveryStatus.DELIVERED -> ReadReceiptIcon(
                                        doubleTick = true,
                                        tint = bubbleTokens.muted,
                                        description = strings.messageStatusDelivered
                                    )
                                    MessageDeliveryStatus.READ -> ReadReceiptIcon(
                                        doubleTick = true,
                                        tint = bubbleTokens.readReceipt,
                                        description = strings.messageStatusRead
                                    )
                                    MessageDeliveryStatus.FAILED -> Box(
                                        modifier = Modifier
                                            .size(48.dp)
                                            .focusProperties { canFocus = false }
                                            .clickable { onRetryTap(message) },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(18.dp)
                                                .clip(CircleShape)
                                                .background(MaterialTheme.colorScheme.errorContainer),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "!",
                                                color = MaterialTheme.colorScheme.onErrorContainer,
                                                fontSize = 12.sp,
                                                modifier = Modifier.offset(y = (-0.5).dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun chatBubbleTokens(mine: Boolean): ChatBubbleTokens {
    val scheme = MaterialTheme.colorScheme
    val darkSurface = scheme.background.luminance() < 0.5f
    val readReceipt = if (darkSurface) MdDarkReadReceipt else MdLightReadReceipt
    return if (mine) {
        ChatBubbleTokens(
            container = scheme.primaryContainer,
            content = scheme.onPrimaryContainer,
            muted = scheme.onPrimaryContainer.copy(alpha = if (darkSurface) 0.78f else 0.70f),
            outline = Color.Transparent,
            replyContainer = scheme.surfaceContainerLowest.copy(alpha = if (darkSurface) 0.30f else 0.55f),
            readReceipt = readReceipt
        )
    } else {
        // Incoming bubbles sit one step above the thread rather than being
        // faked with alpha over whatever is behind them: white on the tinted
        // thread in light, a lighter container in dark.
        ChatBubbleTokens(
            container = if (darkSurface) scheme.surfaceContainerHigh else scheme.surfaceContainerLowest,
            content = scheme.onSurface,
            muted = scheme.onSurfaceVariant,
            outline = Color.Transparent,
            replyContainer = if (darkSurface) scheme.surfaceContainerHighest else scheme.surfaceContainer,
            readReceipt = readReceipt
        )
    }
}

/**
 * Parses a user's stored avatar colour, falling back to [fallback] when the
 * stored value is missing or malformed. The fallback is passed in rather than
 * hardcoded so it can come from the active colour scheme.
 */
internal fun safeUserColor(colorHex: String, fallback: Color): Color =
    runCatching { Color(android.graphics.Color.parseColor(colorHex)) }.getOrDefault(fallback)

internal data class ImageBubbleSize(val width: Dp, val height: Dp)

internal fun imageBubbleSize(
    imageWidth: Int,
    imageHeight: Int,
    screenWidthDp: Int,
    sticker: Boolean,
): ImageBubbleSize {
    val safeWidth = imageWidth.coerceAtLeast(1)
    val safeHeight = imageHeight.coerceAtLeast(1)
    val aspect = (safeWidth.toFloat() / safeHeight.toFloat()).coerceIn(0.18f, 5.5f)
    val screenBasedMax = (screenWidthDp.dp * if (sticker) 0.36f else 0.68f)
    val maxWidth = minOf(screenBasedMax, if (sticker) 168.dp else 284.dp)
    val maxHeight = if (sticker) 168.dp else 360.dp
    val minWidth = if (sticker) 64.dp else 138.dp
    val minHeight = if (sticker) 64.dp else 96.dp

    var width = maxWidth
    var height = width / aspect
    if (height > maxHeight) {
        height = maxHeight
        width = height * aspect
    }
    if (width < minWidth) {
        width = minWidth
        height = width / aspect
    }
    if (height < minHeight) {
        height = minHeight
        width = height * aspect
    }
    return ImageBubbleSize(
        width = width.coerceIn(minWidth, maxWidth),
        height = height.coerceIn(minHeight, maxHeight)
    )
}

@Composable
private fun ReadReceiptIcon(doubleTick: Boolean, tint: Color, description: String) {
    if (!doubleTick) {
        Icon(
            imageVector = Icons.Default.Done,
            contentDescription = description,
            tint = tint,
            modifier = Modifier.size(14.dp)
        )
        return
    }

    // The two ticks are one status, not two icons: merge them so a screen
    // reader announces "Delivered" once instead of reading a bare tick twice.
    Box(
        modifier = Modifier
            .width(18.dp)
            .height(14.dp)
            .semantics(mergeDescendants = true) { contentDescription = description }
    ) {
        Icon(
            imageVector = Icons.Default.Done,
            contentDescription = null,
            tint = tint,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = 0.dp)
                .size(12.dp)
        )
        Icon(
            imageVector = Icons.Default.Done,
            contentDescription = null,
            tint = tint,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .offset(x = (-1).dp)
                .size(12.dp)
        )
    }
}

@Composable
private fun AttachmentProgressOverlay(label: String, progress: TransferProgress) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.36f))
            .padding(14.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(label, color = Color.White, fontSize = 12.sp)
                Text("${(progress.progress.coerceIn(0f, 1f) * 100).roundToInt()}%", color = Color.White, fontSize = 12.sp)
            }
            LinearProgressIndicator(
                progress = { progress.progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                color = Color.White,
                trackColor = Color.White.copy(alpha = 0.28f)
            )
        }
    }
}

@Composable
private fun RetrySendSheet(
    strings: AppStrings,
    onRetry: () -> Unit,
    onDeleteLocal: () -> Unit,
    onDismiss: () -> Unit,
) {
    AnimatedDialogContainer(
        onDismissRequest = onDismiss,
        contentAlignment = Alignment.BottomCenter,
        scrimAlpha = 0.28f,
        enterScale = 1f,
        exitScale = 0.98f
    ) { dismiss ->
        Surface(
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(strings.retrySend, style = MaterialTheme.typography.titleLarge)
                Text(strings.retrySendConfirm, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(
                    onClick = {
                        dismiss()
                        onRetry()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = strings.retrySend)
                    Spacer(Modifier.width(8.dp))
                    Text(strings.retrySend)
                }
                FilledTonalButton(
                    onClick = {
                        dismiss()
                        onDeleteLocal()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) {
                    Icon(Icons.Default.Delete, contentDescription = strings.deleteLocalMessage)
                    Spacer(Modifier.width(8.dp))
                    Text(strings.deleteLocalMessage)
                }
                TextButton(onClick = dismiss, modifier = Modifier.fillMaxWidth()) {
                    Text(strings.cancel)
                }
            }
        }
    }
}

@Composable
private fun AttachmentInlinePanel(
    strings: AppStrings,
    onCamera: () -> Unit,
    onImage: () -> Unit,
    onFile: () -> Unit,
) {
    val cameraLabel = if (strings.takePhoto.equals("Take photo", ignoreCase = true)) "Camera" else strings.takePhoto
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            strings.chooseAttachment,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AttachmentSheetAction(cameraLabel, strings.takePhoto, Icons.Default.CameraAlt, onCamera)
            AttachmentSheetAction(strings.image, strings.image, Icons.Default.Image, onImage)
            AttachmentSheetAction(strings.file, strings.file, Icons.Default.AttachFile, onFile)
        }
    }
}

@Composable
private fun RowScope.AttachmentSheetAction(
    label: String,
    contentDescription: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier
            .weight(1f)
            .heightIn(min = 64.dp),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(icon, contentDescription = contentDescription)
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun InlineTransferProgress(label: String, progress: TransferProgress) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            Text(
                "${formatSize(progress.bytesDone)} / ${formatSize(progress.totalBytes)}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp
            )
        }
        LinearProgressIndicator(
            progress = { progress.progress.coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
    }
}

@Composable
private fun MessageText(
    message: ChatMessage,
    secret: String,
    strings: AppStrings,
    decryptedTextCache: MutableMap<Long, String>,
    resolveMessageText: suspend (ChatMessage) -> String,
) {
    val text by produceState(
        initialValue = decryptedTextCache[message.id] ?: if (message.e2ee) strings.encryptedMessage else message.payload,
        key1 = message.id,
        key2 = secret,
        key3 = strings
    ) {
        value = decryptedTextCache[message.id] ?: if (!message.e2ee) {
            message.payload
        } else if (
            DirectMessageCrypto.isDirectPayload(message.payload) ||
            GroupSenderKeyCrypto.isGroupPayload(message.payload)
        ) {
            val decrypted = try {
                resolveMessageText(message)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                logDirectDecryptFailure(message, error, "message_text")
                null
            }
            decrypted?.also { decryptedTextCache[message.id] = it } ?: strings.unableToDecrypt
        } else {
            strings.encryptedMessage
        }
    }
    val annotatedText = remember(text) { annotatedMentions(text) }
    Text(annotatedText, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun ReplyPreviewContent(
    fallbackPreview: String,
    originalMessage: ChatMessage?,
    secret: String,
    strings: AppStrings,
    decryptedTextCache: MutableMap<Long, String>,
    attachmentMetaCache: MutableMap<Long, JSONObject?>,
    localPrefs: ChatPreferences,
    resolveMessageText: suspend (ChatMessage) -> String,
    prepareMessageDecryption: suspend (ChatMessage) -> Unit,
    serverUrl: String,
    thumbnailSize: Dp = 40.dp,
    color: Color? = null,
    fontSize: androidx.compose.ui.unit.TextUnit = 14.sp,
) {
    val message = originalMessage
    if (message?.kind == "image" || message?.kind == "photo") {
        val context = LocalContext.current
        val density = LocalDensity.current
        val targetPx = remember(thumbnailSize, density) { with(density) { thumbnailSize.roundToPx() * 2 } }
        val payload = remember(message.id, message.payload) { parseJson(message.payload) }
        val meta by produceState<JSONObject?>(initialValue = attachmentMetaCache[message.id], key1 = message.id, key2 = secret) {
            value = attachmentMetaCache[message.id] ?: resolveAttachmentMeta(
                message,
                secret,
                attachmentMetaCache,
                localPrefs,
                prepareMessageDecryption,
            )
        }
        val sticker = payload?.optJSONObject("display")?.optBoolean("sticker") == true ||
            meta?.optBoolean("sticker") == true
        val bitmap by produceState<Bitmap?>(initialValue = null, key1 = message.id, key2 = serverUrl, key3 = targetPx) {
            value = AttachmentBitmapStore.load(
                context,
                serverUrl,
                message,
                localPrefs,
                targetPx,
                prepareMessageDecryption,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(thumbnailSize)
                    .clip(RoundedCornerShape(if (sticker) 6.dp else 9.dp))
                    .then(
                        if (sticker) Modifier
                        else Modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap!!.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        Icons.Default.Image,
                        contentDescription = null,
                        tint = color ?: MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(thumbnailSize * 0.55f)
                    )
                }
            }
            Text(
                if (sticker) stickerLabel(strings) else strings.image,
                color = color ?: androidx.compose.material3.LocalContentColor.current,
                fontSize = fontSize
            )
        }
        return
    }
    ReplyPreviewText(
        fallbackPreview = fallbackPreview,
        originalMessage = originalMessage,
        secret = secret,
        strings = strings,
        decryptedTextCache = decryptedTextCache,
        attachmentMetaCache = attachmentMetaCache,
        localPrefs = localPrefs,
        resolveMessageText = resolveMessageText,
        prepareMessageDecryption = prepareMessageDecryption,
        color = color,
        fontSize = fontSize
    )
}

@Composable
private fun ReplyPreviewText(
    fallbackPreview: String,
    originalMessage: ChatMessage?,
    secret: String,
    strings: AppStrings,
    decryptedTextCache: MutableMap<Long, String>,
    attachmentMetaCache: MutableMap<Long, JSONObject?>,
    localPrefs: ChatPreferences,
    resolveMessageText: suspend (ChatMessage) -> String,
    prepareMessageDecryption: suspend (ChatMessage) -> Unit,
    color: Color? = null,
    fontSize: androidx.compose.ui.unit.TextUnit = 14.sp,
) {
    val previewColor = color ?: androidx.compose.material3.LocalContentColor.current
    val preview by produceState(initialValue = fallbackPreview.ifBlank { strings.encryptedMessage }, fallbackPreview, originalMessage?.id, secret, strings) {
        value = if (originalMessage == null) {
            fallbackPreview.ifBlank { strings.encryptedMessage }
        } else {
            resolvePreviewText(
                originalMessage,
                fallbackPreview,
                secret,
                strings,
                decryptedTextCache,
                attachmentMetaCache,
                localPrefs,
                resolveMessageText,
                prepareMessageDecryption,
            )
        }
    }
    Text(preview, color = previewColor, fontSize = fontSize)
}

@Composable
private fun AttachmentBubble(
    message: ChatMessage,
    secret: String,
    strings: AppStrings,
    attachmentMetaCache: MutableMap<Long, JSONObject?>,
    localPrefs: ChatPreferences,
    serverUrl: String,
    prepareMessageDecryption: suspend (ChatMessage) -> Unit,
    uploadProgress: TransferProgress?,
    downloadProgress: TransferProgress?,
    onOpenAttachment: (ChatMessage) -> Unit,
    onPreviewImage: (ChatMessage) -> Unit,
    onPlayAudio: (ChatMessage) -> Unit,
    isAudioPlaying: Boolean,
    audioPlaybackProgress: Float?,
    hasUnreadAudioIndicator: Boolean,
) {
    val context = LocalContext.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val configuration = LocalConfiguration.current
    val previewTargetPx = remember(density) { with(density) { 360.dp.roundToPx() } }
    val payload = remember(message.payload) { parseJson(message.payload) }
    val displayJson = payload?.optJSONObject("display")
    val inlineProgress = uploadProgress ?: downloadProgress
    val inlineProgressLabel = if (uploadProgress != null) strings.uploading else strings.downloading
    val metaJson by produceState<JSONObject?>(initialValue = attachmentMetaCache[message.id], key1 = message.id, key2 = secret) {
        value = attachmentMetaCache[message.id] ?: if (!message.e2ee || payload?.has("meta") != true) {
            JSONObject()
                .put("name", payload?.optString("name").orEmpty().ifBlank { payload?.optJSONObject("attachment")?.optString("file").orEmpty() })
                .put(
                    "mime",
                    payload?.optString("mime").orEmpty()
                        .ifBlank { payload?.optJSONObject("attachment")?.optString("mime").orEmpty() }
                )
                .put("size", payload?.optLong("size") ?: 0L)
        } else {
            try {
                prepareMessageDecryption(message)
                withContext(Dispatchers.Default) {
                    val metaCipher = payload?.optString("meta").orEmpty()
                    if (metaCipher.isBlank()) null else decryptAttachmentMeta(message, localPrefs)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                null
            }.also { meta -> if (meta != null) attachmentMetaCache[message.id] = meta }
        }
    }
    val previewBitmap by produceState<Bitmap?>(initialValue = null, key1 = message.id, key2 = secret, key3 = serverUrl) {
        value = if (message.kind == "image" || message.kind == "photo") {
            AttachmentBitmapStore.load(
                context,
                serverUrl,
                message,
                localPrefs,
                previewTargetPx,
                prepareMessageDecryption,
            )
        } else {
            null
        }
    }
    if (message.kind == "image" || message.kind == "photo") {
        val displayWidth = displayJson?.optInt("width")?.takeIf { it > 0 }
            ?: metaJson?.optInt("width")?.takeIf { it > 0 }
            ?: previewBitmap?.width
            ?: 1
        val displayHeight = displayJson?.optInt("height")?.takeIf { it > 0 }
            ?: metaJson?.optInt("height")?.takeIf { it > 0 }
            ?: previewBitmap?.height
            ?: 1
        val sticker = displayJson?.optBoolean("sticker") == true || metaJson?.optBoolean("sticker") == true
        val imageSize = imageBubbleSize(
            imageWidth = displayWidth,
            imageHeight = displayHeight,
            screenWidthDp = configuration.screenWidthDp,
            sticker = sticker
        )
        val imageBoxModifier = Modifier
            .width(imageSize.width)
            .height(imageSize.height)
            .focusProperties { canFocus = false }
            .clickable { onPreviewImage(message) }

        if (sticker) {
            Box(
                modifier = imageBoxModifier,
                contentAlignment = Alignment.Center
            ) {
                previewBitmap?.let { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        // The picture itself cannot be described, but without a
                        // label a screen reader does not announce that a sticker
                        // is here at all.
                        contentDescription = stickerLabel(strings),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                } ?: Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Transparent),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (message.e2ee && (payload?.optInt("v", 0) ?: 0) < 3 && message.localAttachmentPath.isBlank()) {
                            strings.encryptedAttachment
                        } else {
                            strings.image
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                inlineProgress?.let { progress ->
                    AttachmentProgressOverlay(label = inlineProgressLabel, progress = progress)
                }
            }
        } else {
            Card(
                shape = MaterialTheme.shapes.medium,
                modifier = imageBoxModifier,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f))
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    previewBitmap?.let { bitmap ->
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = strings.image,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f))
                        )
                    } ?: Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (message.e2ee && (payload?.optInt("v", 0) ?: 0) < 3 && message.localAttachmentPath.isBlank()) {
                                strings.encryptedAttachment
                            } else {
                                strings.image
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    inlineProgress?.let { progress ->
                        AttachmentProgressOverlay(label = inlineProgressLabel, progress = progress)
                    }
                }
            }
        }
        return
    }
    val attachmentName = when (message.kind) {
        "image" -> metaJson?.optString("name").orEmpty().ifBlank { message.localAttachmentName.ifBlank { strings.image } }
        "audio" -> metaJson?.optString("name").orEmpty().ifBlank { message.localAttachmentName.ifBlank { strings.voiceNote } }
        else -> metaJson?.optString("name").orEmpty().ifBlank { message.localAttachmentName.ifBlank { strings.file } }
    }
    if (message.kind != "audio") {
        Text(attachmentName)
    }
    val sizeLine = buildString {
        val size = (metaJson?.optLong("size") ?: 0L).takeIf { it > 0L } ?: message.localAttachmentSize
        if (message.kind != "audio" && size > 0L) append(formatSize(size))
        val mime = metaJson?.optString("mime").orEmpty().ifBlank { message.localAttachmentMime }
        if (message.kind != "audio" && mime.isNotBlank()) {
            if (isNotEmpty()) append(" | ")
            append(mime)
        }
        val duration = (metaJson?.optLong("durationMs") ?: 0L).takeIf { it > 0L } ?: message.localAttachmentDurationMs
        if (duration > 0L) {
            if (isNotEmpty()) append(" | ")
            append(formatDuration(duration))
        }
    }
    if (sizeLine.isNotBlank()) Text(sizeLine, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
    else if (message.kind != "audio") Text(strings.tapOpen, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
    if (message.kind == "audio") {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(
                    onClick = { onPlayAudio(message) },
                    modifier = Modifier.focusProperties { canFocus = false }
                ) {
                    Icon(
                        if (isAudioPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isAudioPlaying) strings.pause else strings.play
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (isAudioPlaying) strings.pause else strings.play)
                }
                Text(
                    metaJson?.optLong("durationMs")?.takeIf { it > 0L }?.let(::formatDuration) ?: strings.voiceNote,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
                if (hasUnreadAudioIndicator) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.error)
                    )
                }
            }
            if (audioPlaybackProgress != null) {
                LinearProgressIndicator(
                    progress = { audioPlaybackProgress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
        }
        inlineProgress?.let { InlineTransferProgress(inlineProgressLabel, it) }
    } else {
        inlineProgress?.let { InlineTransferProgress(inlineProgressLabel, it) }
        FilledTonalButton(
            onClick = {
                if (message.kind == "image" || message.kind == "photo") onPreviewImage(message)
                else onOpenAttachment(message)
            },
            modifier = Modifier.focusProperties { canFocus = false }
        ) {
            Text(if (message.kind == "file") strings.downloadOpen else strings.open)
        }
    }
}

private object AttachmentBitmapStore {
    private val memoryCache = object : LruCache<String, Bitmap>(((Runtime.getRuntime().maxMemory() / 24L) / 1024L).toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    suspend fun load(
        context: Context,
        serverUrl: String,
        message: ChatMessage,
        localPrefs: ChatPreferences,
        targetSizePx: Int,
        prepareMessageDecryption: suspend (ChatMessage) -> Unit = {},
    ): Bitmap? {
        if (message.kind != "image" && message.kind != "photo") return null
        val key = "${message.id}:${message.localAttachmentPath}:$targetSizePx"
        memoryCache.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            message.localAttachmentPath.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.exists() }?.let { localFile ->
                return@withContext decodeAttachmentBitmap(localFile, targetSizePx)?.also { memoryCache.put(key, it) }
            }
            val attachment = try {
                prepareMessageDecryption(message)
                resolveAttachment(
                    context = context,
                    api = ChatApi(),
                    serverUrl = serverUrl,
                    message = message,
                    prefs = localPrefs,
                    exportToDownloads = false
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                null
            } ?: return@withContext null
            val file = attachment.file ?: return@withContext null
            decodeAttachmentBitmap(file, targetSizePx)?.also { memoryCache.put(key, it) }
        }
    }

    private fun decodeAttachmentBitmap(file: File, targetSizePx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateSampleSize(bounds, targetSizePx)
        }
        return BitmapFactory.decodeFile(file.absolutePath, options)
    }

    private fun calculateSampleSize(options: BitmapFactory.Options, targetSizePx: Int): Int {
        val maxTarget = targetSizePx.coerceAtLeast(240)
        var sampleSize = 1
        var width = options.outWidth
        var height = options.outHeight
        while (width / 2 >= maxTarget && height / 2 >= maxTarget) {
            width /= 2
            height /= 2
            sampleSize *= 2
        }
        return sampleSize.coerceAtLeast(1)
    }
}

@Composable
internal fun ImagePreviewScreen(
    message: ChatMessage,
    localPrefs: ChatPreferences,
    strings: AppStrings,
    serverUrl: String,
    downloadProgress: TransferProgress?,
    prepareMessageDecryption: suspend (ChatMessage) -> Unit,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val previewTargetPx = remember(configuration.screenWidthDp, density) {
        with(density) { (configuration.screenWidthDp.dp * 2.4f).roundToPx() }
    }
    val previewBitmap by produceState<Bitmap?>(initialValue = null, message.id, serverUrl, previewTargetPx) {
        value = AttachmentBitmapStore.load(
            context,
            serverUrl,
            message,
            localPrefs,
            previewTargetPx,
            prepareMessageDecryption,
        )
    }
    val imageAlpha by animateFloatAsState(
        targetValue = if (previewBitmap != null) 1f else 0f,
        animationSpec = tween(MotionMedium, easing = FastOutSlowInEasing),
        label = "imagePreviewAlpha"
    )
    var scale by remember(message.id) { mutableStateOf(1f) }
    var offset by remember(message.id) { mutableStateOf(Offset.Zero) }

    BackHandler(onBack = onDismiss)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
            previewBitmap?.let { bitmap ->
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = strings.image,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            alpha = imageAlpha
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        }
                        .pointerInput(message.id) {
                            detectTapGestures(
                                onTap = {
                                    if (scale > 1.02f) {
                                        scale = 1f
                                        offset = Offset.Zero
                                    } else {
                                        onDismiss()
                                    }
                                }
                            )
                        }
                        .pointerInput(message.id) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                val nextScale = (scale * zoom).coerceIn(1f, 4f)
                                scale = nextScale
                                offset = if (nextScale <= 1f) {
                                    Offset.Zero
                                } else {
                                    offset + pan * 1.75f
                                }
                            }
                        }
                )
            } ?: Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Color.White)
            }

            // Chrome. Previously this screen showed a photo and a download disc
            // and nothing else: no sender, no date, and no way back except the
            // system gesture. In a family chat "who sent this and when" is
            // frequently the reason the photo was opened at all.
            //
            // A gradient rather than a flat scrim, so the controls stay legible
            // over a light photo without stamping a hard-edged panel on it.
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.62f), Color.Transparent)
                        )
                    )
                    .statusBarsPadding()
                    .padding(horizontal = Spacing.sm, vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = strings.cancel,
                        tint = Color.White,
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        message.username.ifBlank { strings.image },
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (message.ts > 0L) {
                        Text(
                            formatMessageTime(message.ts),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.76f),
                            maxLines = 1,
                        )
                    }
                }
                if (message.localAttachmentPath.isBlank()) {
                    if (downloadProgress != null) {
                        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                progress = { downloadProgress.progress.coerceIn(0f, 1f) },
                                modifier = Modifier.size(26.dp),
                                color = Color.White,
                                trackColor = Color.White.copy(alpha = 0.26f),
                                strokeWidth = 3.dp,
                            )
                        }
                    } else {
                        IconButton(onClick = onDownload, modifier = Modifier.size(48.dp)) {
                            Icon(
                                Icons.Default.Download,
                                contentDescription = strings.downloadOpen,
                                tint = Color.White,
                            )
                        }
                    }
                }
            }
    }
}
