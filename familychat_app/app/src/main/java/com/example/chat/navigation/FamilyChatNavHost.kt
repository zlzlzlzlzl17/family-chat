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
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.consumeWindowInsets
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
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
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
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
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
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import com.example.chat.ui.theme.ChatTheme
import com.example.chat.ui.theme.MotionMedium
import com.example.chat.ui.theme.MotionShort
import kotlinx.coroutines.Dispatchers
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
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.compose.ui.tooling.preview.Preview
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

private const val ImageReturnScrollRequestedKey = "image_return_scroll_requested"
private const val ImageReturnFirstVisibleIndexKey = "image_return_first_visible_index"
private const val ImageReturnFirstVisibleOffsetKey = "image_return_first_visible_offset"

@Composable
internal fun FamilyChatApp(
    shellViewModel: ShellViewModel,
    authViewModel: AuthViewModel,
    container: AppContainer,
    conversationViewModel: ConversationViewModel,
    callViewModel: CallViewModel,
    settingsViewModel: SettingsViewModel,
    shareIntent: Intent?,
    onShareIntentConsumed: () -> Unit,
) {
    val state = shellViewModel.uiState
    val settingsState = settingsViewModel.uiState
    val callState = callViewModel.uiState.call
    val voiceCallMinimized by VoiceCallRuntime.isMinimized.collectAsState()
    val strings = stringsFor(state.language)
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: FamilyChatDestinations.HOME
    val context = LocalContext.current
    val navigationScope = rememberCoroutineScope()
    val activity = context as? ComponentActivity
    val chatViewModelHolder = remember(activity, container) {
        lazy(LazyThreadSafetyMode.NONE) {
            ViewModelProvider(
                requireNotNull(activity),
                featureViewModelFactory { ChatViewModel(container) },
            )[ChatViewModel::class.java]
        }
    }
    val managementViewModelHolder = remember(activity, container) {
        lazy(LazyThreadSafetyMode.NONE) {
            ViewModelProvider(
                requireNotNull(activity),
                featureViewModelFactory { ManagementViewModel(container) },
            )[ManagementViewModel::class.java]
        }
    }
    val blogViewModelHolder = remember(activity, container) {
        lazy(LazyThreadSafetyMode.NONE) {
            ViewModelProvider(
                requireNotNull(activity),
                featureViewModelFactory { BlogViewModel(container) },
            )[BlogViewModel::class.java]
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentVersion = remember(context) { currentAppVersion(context) }
    var lastAutoUpdateCheckAt by rememberSaveable { mutableLongStateOf(0L) }
    var dismissedUpdateVersion by rememberSaveable { mutableStateOf("") }
    var lastBackPressAt by rememberSaveable { mutableLongStateOf(0L) }
    var pendingVoiceConversationId by rememberSaveable { mutableLongStateOf(0L) }
    var pendingVoiceAccept by rememberSaveable { mutableStateOf(false) }
    var pendingSharedContent by remember { mutableStateOf<List<PendingSharedContent>>(emptyList()) }
    val hasOngoingVoiceCall =
        callState.phase != VoiceCallPhase.IDLE &&
            callState.phase != VoiceCallPhase.ENDED &&
            callState.phase != VoiceCallPhase.FAILED
    val latestRelease = settingsState.latestAppRelease
    val shouldShowUpdatePrompt = state.isLoggedIn &&
        latestRelease != null &&
        latestRelease.version.isNotBlank() &&
        compareVersionNames(comparableReleaseVersion(latestRelease), currentVersion) > 0 &&
        dismissedUpdateVersion != latestRelease.version
    val voicePermissionLauncher = rememberLauncherForActivityResult(RequestPermission()) { granted ->
        if (granted) {
            when {
                pendingVoiceAccept -> callViewModel.acceptIncomingCall()
                pendingVoiceConversationId > 0L -> callViewModel.startVoiceCall(pendingVoiceConversationId)
            }
        } else {
            shellViewModel.reportError(strings.microphonePermissionDenied)
        }
        pendingVoiceAccept = false
        pendingVoiceConversationId = 0L
    }

    fun triggerVersionCheck(force: Boolean = false) {
        if (!state.isLoggedIn || settingsState.isCheckingUpdate) return
        val now = System.currentTimeMillis()
        if (!force && now - lastAutoUpdateCheckAt < 5 * 60 * 1000L) return
        lastAutoUpdateCheckAt = now
        shellViewModel.checkForUpdates()
    }

    fun startVoiceCallWithPermission(conversationId: Long) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            callViewModel.startVoiceCall(conversationId)
        } else {
            pendingVoiceAccept = false
            pendingVoiceConversationId = conversationId
            voicePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun acceptVoiceCallWithPermission() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            callViewModel.acceptIncomingCall()
        } else {
            pendingVoiceAccept = true
            pendingVoiceConversationId = 0L
            voicePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LaunchedEffect(shareIntent) {
        val incoming = shareIntent ?: return@LaunchedEffect
        val parsed = extractSharedContent(context, incoming)
        if (parsed.isEmpty()) {
            Toast.makeText(context, strings.sharedContentUnsupported, Toast.LENGTH_SHORT).show()
        } else {
            pendingSharedContent = parsed
            if (!state.isLoggedIn) {
                Toast.makeText(context, strings.shareLoginFirst, Toast.LENGTH_SHORT).show()
            }
        }
        onShareIntentConsumed()
    }

    fun minimizeVoiceCall() {
        VoiceCallRuntime.setMinimized(context, true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            Toast.makeText(context, strings.overlayPermissionHint, Toast.LENGTH_LONG).show()
            runCatching {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    LaunchedEffect(state.isLoggedIn) {
        if (state.isLoggedIn) triggerVersionCheck(force = true)
    }

    LaunchedEffect(state.relationshipMessage) {
        if (!state.relationshipMessage.isNullOrBlank()) {
            delay(5_000)
            shellViewModel.clearRelationshipMessage()
        }
    }

    DisposableEffect(lifecycleOwner, state.isLoggedIn) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START, Lifecycle.Event.ON_RESUME -> {
                    VoiceCallRuntime.setAppForeground(context, true)
                    NotificationCenter.clearAll(context)
                    shellViewModel.onAppForeground()
                    triggerVersionCheck()
                }
                Lifecycle.Event.ON_STOP -> {
                    VoiceCallRuntime.setAppForeground(context, false)
                    shellViewModel.onAppBackground()
                    if (hasOngoingVoiceCall) {
                        VoiceCallRuntime.setMinimized(context, true)
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(callState.phase, callState.conversationId, voiceCallMinimized, currentRoute) {
        val active = callState.phase != VoiceCallPhase.IDLE
        when {
            active && !voiceCallMinimized && currentRoute != FamilyChatDestinations.CALL_PATTERN -> {
                navController.navigate(FamilyChatDestinations.call(callState.conversationId.coerceAtLeast(0L))) {
                    launchSingleTop = true
                }
            }
            (!active || voiceCallMinimized) && currentRoute == FamilyChatDestinations.CALL_PATTERN -> {
                navController.popBackStack()
            }
        }
    }

    if (shouldShowUpdatePrompt) {
        AnimatedActionDialog(
            title = strings.updatePromptTitle,
            body = {
                Text(
                    buildString {
                        append(strings.updatePromptBody)
                        latestRelease?.version?.takeIf { it.isNotBlank() }?.let {
                            append("\n\n")
                            append(strings.latestVersion)
                            append(": ")
                            append(it)
                        }
                    }
                )
            },
            confirmText = strings.updateNow,
            onConfirm = {
                navController.navigate(FamilyChatDestinations.SETTINGS) { launchSingleTop = true }
                dismissedUpdateVersion = latestRelease!!.version
            },
            dismissText = strings.later,
            onDismissRequest = { dismissedUpdateVersion = latestRelease!!.version }
        )
    }

    if (state.isLoggedIn && pendingSharedContent.isNotEmpty()) {
        ShareTargetDialog(
            state = conversationViewModel.uiState,
            strings = strings,
            onSelect = { conversation ->
                val sharedContent = pendingSharedContent
                conversationViewModel.openConversation(conversation.id)
                navController.navigate(FamilyChatDestinations.chat(conversation.id)) { launchSingleTop = true }
                navigationScope.launch {
                    withContext(Dispatchers.IO) { container.prepareChatFeatures() }
                    sendPendingSharedContent(chatViewModelHolder.value, sharedContent)
                }
                pendingSharedContent = emptyList()
            },
            onDismiss = { pendingSharedContent = emptyList() }
        )
    }

    if (!state.isLoggedIn) {
        LoginScreen(authViewModel, authViewModel.uiState)
        return
    }

    BackHandler(enabled = currentRoute == FamilyChatDestinations.HOME) {
        val now = System.currentTimeMillis()
        if (now - lastBackPressAt < 1800L) {
            if (hasOngoingVoiceCall) {
                VoiceCallRuntime.setMinimized(context, true)
                activity?.moveTaskToBack(true)
            } else {
                activity?.finish()
            }
        } else {
            lastBackPressAt = now
            Toast.makeText(context, strings.backAgainToExit, Toast.LENGTH_SHORT).show()
        }
    }
    // Back out of Blog returns to Chats. This is a peer move, not a pop, so it
    // is spelled as one: popping would leave the section bar's selected item
    // and the visible screen briefly disagreeing.
    BackHandler(enabled = currentRoute == FamilyChatDestinations.BLOG) {
        navController.navigate(FamilyChatDestinations.HOME) {
            popUpTo(FamilyChatDestinations.HOME) { inclusive = false }
            launchSingleTop = true
        }
    }
    val onOpenChats: () -> Unit = {
        navController.navigate(FamilyChatDestinations.HOME) {
            popUpTo(FamilyChatDestinations.HOME) { inclusive = false }
            launchSingleTop = true
        }
    }
    val onOpenBlogSection: () -> Unit = {
        navController.navigate(FamilyChatDestinations.BLOG) { launchSingleTop = true }
    }
    val sectionBarVisible = currentRoute == FamilyChatDestinations.HOME ||
        currentRoute == FamilyChatDestinations.BLOG

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // The shell owns the section bar so it never takes part in a screen
        // transition, and draws it *over* the NavHost rather than above it in
        // the layout. Reserving layout space for a bar that animates away means
        // handing that space back mid-transition, which is a jump; the two
        // section roots reserve FamilySectionBarHeight for themselves instead.
        NavHost(
            navController = navController,
            startDestination = FamilyChatDestinations.HOME,
            modifier = Modifier.fillMaxSize(),
            // Motion follows the depth ladder in familyChatNavigationDepth().
            // Chats and Blog are peers at depth 0, so switching between them is
            // a small lateral shift plus a crossfade - the shared-axis move.
            // Going deeper is the larger drill-down slide. Using the drill-down
            // for both was what made the two sections feel wrongly nested.
            enterTransition = {
                when {
                    targetState.destination.route?.isFullScreenMedia() == true ->
                        fadeIn(tween(MotionMedium, easing = FastOutSlowInEasing)) +
                            scaleIn(initialScale = 0.86f, animationSpec = tween(MotionMedium, easing = FastOutSlowInEasing))
                    isPeerMove() ->
                        slideInHorizontally(tween(MotionShort, easing = FastOutSlowInEasing)) { it / 10 * lateralSign() } +
                            fadeIn(tween(MotionShort, easing = FastOutSlowInEasing))
                    else ->
                        slideInHorizontally(tween(MotionMedium, easing = FastOutSlowInEasing)) { it / 4 } +
                            fadeIn(tween(MotionMedium, delayMillis = 40, easing = FastOutSlowInEasing))
                }
            },
            exitTransition = {
                when {
                    targetState.destination.route?.isFullScreenMedia() == true ->
                        fadeOut(tween(MotionShort, easing = FastOutSlowInEasing))
                    isPeerMove() ->
                        slideOutHorizontally(tween(MotionShort, easing = FastOutSlowInEasing)) { -it / 10 * lateralSign() } +
                            fadeOut(tween(MotionShort, easing = FastOutSlowInEasing))
                    else ->
                        slideOutHorizontally(tween(MotionMedium, easing = FastOutSlowInEasing)) { -it / 5 } +
                            fadeOut(tween(MotionShort, easing = FastOutSlowInEasing))
                }
            },
            popEnterTransition = {
                when {
                    initialState.destination.route?.isFullScreenMedia() == true ->
                        fadeIn(tween(MotionMedium, easing = FastOutSlowInEasing))
                    isPeerMove() ->
                        slideInHorizontally(tween(MotionShort, easing = FastOutSlowInEasing)) { it / 10 * lateralSign() } +
                            fadeIn(tween(MotionShort, easing = FastOutSlowInEasing))
                    else ->
                        slideInHorizontally(tween(MotionMedium, easing = FastOutSlowInEasing)) { -it / 5 } +
                            fadeIn(tween(MotionMedium, delayMillis = 40, easing = FastOutSlowInEasing))
                }
            },
            popExitTransition = {
                when {
                    initialState.destination.route?.isFullScreenMedia() == true ->
                        fadeOut(tween(MotionMedium, easing = FastOutSlowInEasing)) +
                            scaleOut(targetScale = 0.86f, animationSpec = tween(MotionMedium, easing = FastOutSlowInEasing))
                    isPeerMove() ->
                        slideOutHorizontally(tween(MotionShort, easing = FastOutSlowInEasing)) { -it / 10 * lateralSign() } +
                            fadeOut(tween(MotionShort, easing = FastOutSlowInEasing))
                    else ->
                        slideOutHorizontally(tween(MotionMedium, easing = FastOutSlowInEasing)) { it / 4 } +
                            fadeOut(tween(MotionShort, easing = FastOutSlowInEasing))
                }
            },
        ) {
            composable(FamilyChatDestinations.HOME) {
                HomeScreen(
                    viewModel = conversationViewModel,
                    state = conversationViewModel.uiState,
                    onOpenChat = {
                        FamilyChatDiagnostics.event("chat_navigation_requested", "conversation_id" to it)
                        conversationViewModel.openConversation(it)
                        navController.navigate(FamilyChatDestinations.chat(it)) { launchSingleTop = true }
                    },
                    onOpenSettings = {
                        navController.navigate(FamilyChatDestinations.SETTINGS) { launchSingleTop = true }
                    },
                    onOpenAccount = {
                        navController.navigate(FamilyChatDestinations.ACCOUNT) { launchSingleTop = true }
                    },
                )
            }
            composable(FamilyChatDestinations.BLOG) {
                val blogViewModel = blogViewModelHolder.value
                BlogFeedScreen(
                    viewModel = blogViewModel,
                    state = blogViewModel.uiState,
                    strings = strings,
                    language = settingsViewModel.uiState.language,
                    onCompose = { navController.navigate(FamilyChatDestinations.BLOG_COMPOSE) },
                    onOpenPost = { navController.navigate(FamilyChatDestinations.blogPost(it)) },
                    onOpenMedia = { postId, index ->
                        navController.navigate(FamilyChatDestinations.blogMedia(postId, index))
                    },
                )
            }
            composable(FamilyChatDestinations.BLOG_COMPOSE) {
                val blogViewModel = blogViewModelHolder.value
                BlogComposeScreen(
                    viewModel = blogViewModel,
                    state = blogViewModel.uiState,
                    strings = strings,
                    language = settingsViewModel.uiState.language,
                    onBack = { navController.popBackStack() },
                    onPublished = { postId ->
                        blogViewModel.selectPost(postId)
                        navController.navigate(FamilyChatDestinations.blogPost(postId)) {
                            popUpTo(FamilyChatDestinations.BLOG_COMPOSE) { inclusive = true }
                        }
                    },
                )
            }
            composable(
                route = FamilyChatDestinations.BLOG_POST_PATTERN,
                arguments = listOf(navArgument("postId") { type = NavType.LongType }),
                deepLinks = listOf(navDeepLink { uriPattern = "familychat://open/blog/post/{postId}" }),
            ) { entry ->
                val blogViewModel = blogViewModelHolder.value
                val postId = entry.arguments?.getLong("postId") ?: 0L
                LaunchedEffect(postId) {
                    if (postId > 0L) blogViewModel.selectPost(postId)
                }
                BlogPostScreen(
                    viewModel = blogViewModel,
                    state = blogViewModel.uiState,
                    strings = strings,
                    language = settingsViewModel.uiState.language,
                    onBack = { navController.popBackStack() },
                    onOpenMedia = { id, index ->
                        navController.navigate(FamilyChatDestinations.blogMedia(id, index))
                    },
                )
            }
            composable(
                route = FamilyChatDestinations.BLOG_MEDIA_PATTERN,
                arguments = listOf(
                    navArgument("postId") { type = NavType.LongType },
                    navArgument("index") { type = NavType.IntType },
                ),
            ) { entry ->
                val blogViewModel = blogViewModelHolder.value
                val postId = entry.arguments?.getLong("postId") ?: 0L
                val index = entry.arguments?.getInt("index") ?: 0
                BlogMediaViewerScreen(
                    viewModel = blogViewModel,
                    post = blogViewModel.uiState.posts.firstOrNull { it.id == postId },
                    startIndex = index,
                    strings = strings,
                    language = settingsViewModel.uiState.language,
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = FamilyChatDestinations.CHAT_PATTERN,
                arguments = listOf(navArgument("conversationId") { type = NavType.LongType }),
                deepLinks = listOf(navDeepLink { uriPattern = "familychat://open/chat/{conversationId}" }),
            ) { entry ->
                val conversationId = entry.arguments?.getLong("conversationId") ?: 0L
                val restoreImageScrollRequested by entry.savedStateHandle
                    .getStateFlow(ImageReturnScrollRequestedKey, false)
                    .collectAsState()
                val restoreImageScrollIndex by entry.savedStateHandle
                    .getStateFlow(ImageReturnFirstVisibleIndexKey, 0)
                    .collectAsState()
                val restoreImageScrollOffset by entry.savedStateHandle
                    .getStateFlow(ImageReturnFirstVisibleOffsetKey, 0)
                    .collectAsState()
                val restoreImageScrollPosition = if (restoreImageScrollRequested) {
                    ChatScrollPosition(
                        firstVisibleItemIndex = restoreImageScrollIndex,
                        firstVisibleItemScrollOffset = restoreImageScrollOffset,
                    )
                } else {
                    null
                }
                remember(conversationId) {
                    FamilyChatDiagnostics.event("chat_route_composed", "conversation_id" to conversationId)
                    true
                }
                var chatFeaturesReady by remember(conversationId) {
                    mutableStateOf(container.areChatFeaturesPrepared)
                }
                LaunchedEffect(conversationId) {
                    if (!chatFeaturesReady) {
                        withContext(Dispatchers.IO) { container.prepareChatFeatures() }
                        chatFeaturesReady = true
                    }
                    if (
                        conversationId > 0L &&
                        conversationViewModel.uiState.currentConversationId != conversationId
                    ) {
                        conversationViewModel.openConversation(conversationId)
                    }
                }
                if (!chatFeaturesReady) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 2.5.dp)
                    }
                } else {
                    val chatViewModel = chatViewModelHolder.value
                    ChatScreen(
                        viewModel = chatViewModel,
                        state = chatViewModel.uiState,
                        localPrefs = container.preferences,
                        onBack = { navController.popBackStack() },
                        onStartVoiceCall = ::startVoiceCallWithPermission,
                        onOpenPeerManage = { userCode ->
                            userCode?.takeIf(String::isNotBlank)?.let {
                                navController.navigate(FamilyChatDestinations.contact(it)) { launchSingleTop = true }
                            }
                        },
                        onOpenGroupManage = {
                            if (conversationId > 0L) {
                                navController.navigate(FamilyChatDestinations.group(conversationId)) { launchSingleTop = true }
                            }
                        },
                        restoreScrollPosition = restoreImageScrollPosition,
                        onScrollPositionRestored = {
                            entry.savedStateHandle[ImageReturnScrollRequestedKey] = false
                        },
                        onPreviewImage = { targetConversationId, messageId, scrollPosition ->
                            entry.savedStateHandle[ImageReturnFirstVisibleIndexKey] =
                                scrollPosition.firstVisibleItemIndex
                            entry.savedStateHandle[ImageReturnFirstVisibleOffsetKey] =
                                scrollPosition.firstVisibleItemScrollOffset
                            entry.savedStateHandle[ImageReturnScrollRequestedKey] = false
                            navController.navigate(FamilyChatDestinations.image(targetConversationId, messageId)) {
                                launchSingleTop = true
                            }
                        },
                    )
                }
            }
            composable(
                route = FamilyChatDestinations.IMAGE_PATTERN,
                arguments = listOf(
                    navArgument("conversationId") { type = NavType.LongType },
                    navArgument("messageId") { type = NavType.LongType },
                ),
                deepLinks = listOf(navDeepLink { uriPattern = "familychat://open/image/{conversationId}/{messageId}" }),
                enterTransition = {
                    fadeIn(tween(MotionMedium, easing = FastOutSlowInEasing)) +
                        scaleIn(initialScale = 0.86f, animationSpec = tween(MotionMedium, easing = FastOutSlowInEasing))
                },
                exitTransition = {
                    fadeOut(tween(MotionShort, easing = FastOutSlowInEasing)) +
                        scaleOut(targetScale = 0.94f, animationSpec = tween(MotionShort, easing = FastOutSlowInEasing))
                },
                popEnterTransition = {
                    fadeIn(tween(MotionShort, easing = FastOutSlowInEasing))
                },
                popExitTransition = {
                    fadeOut(tween(MotionMedium, easing = FastOutSlowInEasing)) +
                        scaleOut(targetScale = 0.86f, animationSpec = tween(MotionMedium, easing = FastOutSlowInEasing))
                },
            ) { entry ->
                val chatViewModel = chatViewModelHolder.value
                val conversationId = entry.arguments?.getLong("conversationId") ?: 0L
                val messageId = entry.arguments?.getLong("messageId") ?: 0L
                LaunchedEffect(conversationId) {
                    if (conversationId > 0L && chatViewModel.uiState.currentConversationId != conversationId) {
                        conversationViewModel.openConversation(conversationId)
                    }
                }
                val message = chatViewModel.uiState.messages.firstOrNull { it.id == messageId }
                if (message == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    val localPreferences = container.preferences
                    val dismissImagePreview = {
                        navController.previousBackStackEntry?.savedStateHandle?.set(
                            ImageReturnScrollRequestedKey,
                            true,
                        )
                        navController.popBackStack()
                        Unit
                    }
                    ImagePreviewScreen(
                        message = message,
                        localPrefs = localPreferences,
                        strings = strings,
                        serverUrl = chatViewModel.uiState.serverUrl,
                        downloadProgress = chatViewModel.uiState.downloadProgress?.takeIf {
                            it.messageId == message.id && it.conversationId == message.conversationId
                        },
                        prepareMessageDecryption = { chatViewModel.prepareMessageDecryption(it) },
                        onDismiss = dismissImagePreview,
                        onDownload = {
                            navigationScope.launch {
                                downloadAndOpenAttachment(context, chatViewModel, message)
                            }
                        },
                    )
                }
            }
            composable(FamilyChatDestinations.SETTINGS) {
                SettingsMenuScreen(
                    state = settingsViewModel.uiState,
                    onBack = { navController.popBackStack() },
                    onOpenGeneral = { navController.navigate(FamilyChatDestinations.SETTINGS_GENERAL) },
                    onOpenAbout = { navController.navigate(FamilyChatDestinations.SETTINGS_ABOUT) },
                    onOpenSecurity = { navController.navigate(FamilyChatDestinations.SETTINGS_SECURITY) }
                )
            }
            composable(FamilyChatDestinations.SETTINGS_GENERAL) {
                SettingsGeneralScreen(
                    viewModel = settingsViewModel,
                    state = settingsViewModel.uiState,
                    onBack = { navController.popBackStack() }
                )
            }
            composable(FamilyChatDestinations.SETTINGS_ABOUT) {
                SettingsAboutScreen(
                    viewModel = settingsViewModel,
                    state = settingsViewModel.uiState,
                    onBack = { navController.popBackStack() },
                    onOpenPrerelease = { navController.navigate(FamilyChatDestinations.PRERELEASE) }
                )
            }
            composable(FamilyChatDestinations.SETTINGS_SECURITY) {
                SettingsSecurityScreen(
                    viewModel = settingsViewModel,
                    state = settingsViewModel.uiState,
                    onBack = { navController.popBackStack() }
                )
            }
            composable(FamilyChatDestinations.ACCOUNT) {
                val managementViewModel = managementViewModelHolder.value
                AccountManageScreen(
                    viewModel = managementViewModel,
                    state = managementViewModel.uiState,
                    onBack = { navController.popBackStack() }
                )
            }
            composable(
                route = FamilyChatDestinations.CONTACT_PATTERN,
                arguments = listOf(navArgument("userCode") { type = NavType.StringType }),
                deepLinks = listOf(navDeepLink { uriPattern = "familychat://open/contact/{userCode}" }),
            ) { entry ->
                val managementViewModel = managementViewModelHolder.value
                val selectedUserCode = entry.arguments?.getString("userCode").orEmpty()
                PeerManageScreen(
                    viewModel = managementViewModel,
                    state = managementViewModel.uiState,
                    selectedUserCode = selectedUserCode.takeIf(String::isNotBlank),
                    onBack = { navController.popBackStack() },
                    onOpenDirect = {
                        managementViewModel.openConversation(it)
                        navController.navigate(FamilyChatDestinations.chat(it)) {
                            launchSingleTop = true
                            popUpTo(FamilyChatDestinations.HOME)
                        }
                    }
                )
            }
            composable(
                route = FamilyChatDestinations.GROUP_PATTERN,
                arguments = listOf(navArgument("conversationId") { type = NavType.LongType }),
                deepLinks = listOf(navDeepLink { uriPattern = "familychat://open/group/{conversationId}" }),
            ) { entry ->
                val managementViewModel = managementViewModelHolder.value
                val conversationId = entry.arguments?.getLong("conversationId") ?: 0L
                LaunchedEffect(conversationId) {
                    if (conversationId > 0L && managementViewModel.uiState.currentConversationId != conversationId) {
                        managementViewModel.openConversation(conversationId)
                    }
                    managementViewModel.refreshCurrentConversationManage()
                }
                GroupManageScreen(
                    viewModel = managementViewModel,
                    state = managementViewModel.uiState,
                    onBack = { navController.popBackStack() },
                    onOpenMember = { userCode ->
                        navController.navigate(FamilyChatDestinations.contact(userCode)) { launchSingleTop = true }
                    }
                )
            }
            composable(FamilyChatDestinations.PRERELEASE) {
                PrereleaseScreen(
                    viewModel = settingsViewModel,
                    state = settingsViewModel.uiState,
                    onBack = { navController.popBackStack() }
                )
            }
            composable(
                route = FamilyChatDestinations.CALL_PATTERN,
                arguments = listOf(navArgument("conversationId") { type = NavType.LongType }),
                deepLinks = listOf(navDeepLink { uriPattern = "familychat://open/call/{conversationId}" }),
            ) {
                VoiceCallScreen(
                    callState = callState,
                    strings = strings,
                    onAccept = ::acceptVoiceCallWithPermission,
                    onDecline = callViewModel::declineIncomingCall,
                    onEnd = callViewModel::endVoiceCall,
                    onToggleMute = callViewModel::toggleVoiceCallMute,
                    onSelectAudioRoute = callViewModel::setVoiceCallAudioRoute,
                    onDismissTerminal = callViewModel::dismissVoiceCallStatus,
                    onMinimize = {
                        minimizeVoiceCall()
                        navController.popBackStack()
                    },
                )
            }
        }

        AnimatedVisibility(
            visible = sectionBarVisible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(tween(MotionMedium, easing = FastOutSlowInEasing)) { it } +
                fadeIn(tween(MotionShort)),
            exit = slideOutVertically(tween(MotionShort, easing = FastOutSlowInEasing)) { it } +
                fadeOut(tween(MotionShort)),
        ) {
            FamilySectionBar(
                blogSelected = currentRoute == FamilyChatDestinations.BLOG,
                strings = strings,
                onChats = onOpenChats,
                onBlog = onOpenBlogSection,
            )
        }

        callState.takeIf { it.phase != VoiceCallPhase.IDLE }?.let { activeCall ->
            if (voiceCallMinimized && activeCall.phase != VoiceCallPhase.ENDED && activeCall.phase != VoiceCallPhase.FAILED) {
                VoiceCallMiniBubbleLayer(
                    callState = activeCall,
                    strings = strings,
                    modifier = Modifier.fillMaxSize(),
                    onExpand = { VoiceCallRuntime.setMinimized(context, false) },
                    onEnd = callViewModel::endVoiceCall
                )
            }
        }
    }
}
