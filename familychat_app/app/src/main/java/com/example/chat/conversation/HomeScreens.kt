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
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.GroupAdd
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
import com.example.chat.ui.theme.ChatTheme
import com.example.chat.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeScreen(
    viewModel: ConversationViewModel,
    state: ConversationUiState,
    onOpenChat: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAccount: () -> Unit,
) {
    val strings = stringsFor(state.language)
    // Read per row rather than cached: mute is toggled from the chat screen, and
    // a cached set would show stale state on the way back.
    val homeContext = LocalContext.current
    val homePrefs = remember(homeContext) { ChatPreferences(homeContext) }
    val conversations = remember(state.conversations) { state.conversations.sortedByDescending { it.lastMessageTs } }
    val onRefresh = remember(viewModel) { { viewModel.refreshNow(reconnectIfNeeded = true, showIndicator = true) } }
    val listState = rememberLazyListState()
    val bottomBounceOffset = remember(state.conversations.hashCode()) { Animatable(0f) }
    val bounceConnection = rememberBottomBounceConnection(listState, bottomBounceOffset)
    val connectionStatus = effectiveConnectionStatus(state)
    // The home bar is accent-filled, so the status line needs the on-accent
    // palette rather than the light-background one.
    val statusColor = connectionStatusColorOnAccent(connectionStatus)
    var actionMenuExpanded by remember { mutableStateOf(false) }
    var showAddContactDialog by remember { mutableStateOf(false) }
    var showJoinGroupDialog by remember { mutableStateOf(false) }
    var showCreateGroupDialog by remember { mutableStateOf(false) }

    if (showAddContactDialog) {
        AddContactDialog(
            state = state,
            strings = strings,
            onSearch = viewModel::lookupUserByCode,
            onRequest = viewModel::requestContact,
            onApprove = { viewModel.reviewContactRequest(it, approve = true) },
            onDismiss = {
                showAddContactDialog = false
                viewModel.clearRelationshipMessage()
            }
        )
    }
    if (showJoinGroupDialog) {
        JoinGroupDialog(
            state = state,
            strings = strings,
            onSearch = viewModel::lookupGroupByCode,
            onRequest = viewModel::requestJoinGroup,
            onDismiss = {
                showJoinGroupDialog = false
                viewModel.clearRelationshipMessage()
            }
        )
    }
    if (showCreateGroupDialog) {
        CreateGroupDialog(
            strings = strings,
            onCreate = viewModel::createGroup,
            onDismiss = {
                showCreateGroupDialog = false
                viewModel.clearRelationshipMessage()
            }
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppTopBar(
                title = strings.familyChat,
                subtitle = buildStatusLine(state, strings),
                subtitleColor = statusColor,
                filled = true,
                navigationAvatar = {
                    Box(
                        modifier = Modifier
                            .padding(start = 12.dp, end = 4.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onOpenAccount)
                    ) {
                        AvatarBubble(
                            state.me?.avatarUrl.orEmpty(),
                            state.me?.username.orEmpty(),
                            state.me?.color ?: "#128c7e",
                            40.dp,
                            serverUrl = state.serverUrl
                        )
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { actionMenuExpanded = true }) {
                            Icon(Icons.Default.Add, contentDescription = strings.contactsAndGroups)
                        }
                        DropdownMenu(
                            expanded = actionMenuExpanded,
                            onDismissRequest = { actionMenuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(strings.addContact) },
                                leadingIcon = { Icon(Icons.Default.PersonAdd, contentDescription = null) },
                                onClick = {
                                    actionMenuExpanded = false
                                    showAddContactDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(strings.joinGroup) },
                                leadingIcon = { Icon(Icons.Default.Group, contentDescription = null) },
                                onClick = {
                                    actionMenuExpanded = false
                                    showJoinGroupDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(strings.newGroup) },
                                // Was Icons.Default.Add, the same glyph as the
                                // menu's own trigger - one icon meaning two
                                // different things within a single control.
                                leadingIcon = { Icon(Icons.Default.GroupAdd, contentDescription = null) },
                                onClick = {
                                    actionMenuExpanded = false
                                    showCreateGroupDialog = true
                                }
                            )
                        }
                    }
                    // The standalone Refresh button is gone: this list is wrapped
                    // in PullToRefreshBox, so pulling down already refreshes it,
                    // and connection state is shown in the bar's subtitle. Three
                    // targets now, each meaning one thing - who I am, add
                    // something, configure.
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = strings.settings)
                    }
                }
            )
        },
        // No bottomBar: the section bar belongs to the navigation shell, which
        // draws it over the NavHost. It is an overlay rather than a layout
        // participant - see FamilySectionBarHeight - so this screen reserves
        // its height at the bottom instead of being inset for it.
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(padding)
                .padding(bottom = FamilySectionBarHeight)
        ) {
            ConnectionActivityIndicator(connectionStatus)
            state.uploadProgress?.let { TransferProgressCard(strings.uploading, it) }
            state.downloadProgress?.let { TransferProgressCard(strings.downloading, it) }
            state.error?.let {
                FcMessageBanner(
                    text = friendlyErrorMessage(it, state.language),
                    tone = FcMessageTone.PROBLEM,
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xs),
                )
            }
            state.relationshipMessage?.let {
                FcMessageBanner(
                    text = friendlyErrorMessage(it, state.language),
                    tone = FcMessageTone.CONFIRMATION,
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xs),
                )
            }
            PendingRelationshipRequests(
                state = state,
                strings = strings,
                serverUrl = state.serverUrl,
                onApproveContact = { viewModel.reviewContactRequest(it, approve = true) },
                onRejectContact = { viewModel.reviewContactRequest(it, approve = false) },
                onApproveGroup = { viewModel.reviewGroupJoinRequest(it, approve = true) },
                onRejectGroup = { viewModel.reviewGroupJoinRequest(it, approve = false) },
            )

            PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize()
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = Spacing.lg)
                        .nestedScroll(bounceConnection)
                        .offset { IntOffset(0, bottomBounceOffset.value.roundToInt()) },
                    // Cards need air between them; the old list used dividers and
                    // no gap.
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                    contentPadding = PaddingValues(top = Spacing.sm, bottom = Spacing.md)
                ) {
                    if (conversations.isEmpty()) {
                        item(key = "empty-conversations") {
                            EmptyConversationState(
                                strings = strings,
                                onAddContact = { showAddContactDialog = true },
                                onJoinGroup = { showJoinGroupDialog = true },
                                onNewGroup = { showCreateGroupDialog = true }
                            )
                        }
                    }
                    items(conversations, key = { it.id }) { conversation ->
                        ConversationCard(
                            conversation = conversation,
                            user = state.users.find {
                                (conversation.directUserCode.isNotBlank() && it.userCode == conversation.directUserCode) ||
                                    it.username == conversation.directUsername
                            },
                            serverUrl = state.serverUrl,
                            muted = homePrefs.isConversationMuted(conversation.id),
                            strings = strings,
                            onClick = { onOpenChat(conversation.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyConversationState(
    strings: AppStrings,
    onAddContact: () -> Unit,
    onJoinGroup: () -> Unit,
    onNewGroup: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 28.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
        ),
        shape = MaterialTheme.shapes.extraLarge
    ) {
        Column(
            modifier = Modifier.padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                strings.noChatsTitle,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                strings.noChatsBody,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(onClick = onAddContact, modifier = Modifier.weight(1f)) {
                    Text(strings.addContact)
                }
                FilledTonalButton(onClick = onJoinGroup, modifier = Modifier.weight(1f)) {
                    Text(strings.joinGroup)
                }
            }
            TextButton(onClick = onNewGroup) {
                Icon(Icons.Default.Add, contentDescription = strings.newGroup)
                Spacer(Modifier.width(6.dp))
                Text(strings.newGroup)
            }
        }
    }
}

@Composable
private fun ConversationCard(
    conversation: ConversationSummary,
    user: ChatUser?,
    serverUrl: String,
    muted: Boolean,
    strings: AppStrings,
    onClick: () -> Unit,
) {
    val avatarShape = if (conversation.kind == "group") MaterialTheme.shapes.medium else CircleShape
    // One card per conversation on the tinted page, rather than rows separated
    // by rules. With a family-sized list this trades a little density for faces
    // that are actually legible and a row that is unambiguously one tap target.
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AvatarBubble(
                conversation.avatarUrl.ifBlank { user?.avatarUrl.orEmpty() },
                conversation.title,
                user?.color ?: "#128c7e",
                56.dp,
                serverUrl = serverUrl,
                shape = avatarShape
            )
            Spacer(Modifier.width(Spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    Text(
                        conversation.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (muted) {
                        // Until now a muted conversation looked identical to a
                        // loud one, so there was no way to tell why a group had
                        // gone quiet.
                        Icon(
                            Icons.Default.VolumeOff,
                            contentDescription = strings.muteConversation,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
                Text(
                    conversation.lastMessagePreview.ifBlank { " " },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // Fixed height: the timestamp used to jump vertically the moment the
            // unread badge cleared.
            Column(
                modifier = Modifier.heightIn(min = 44.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                if (conversation.lastMessageTs > 0) {
                    Text(
                        formatMessageTime(conversation.lastMessageTs),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall
                    )
                } else {
                    Spacer(Modifier.height(1.dp))
                }
                if (conversation.unreadCount > 0) {
                    if (muted) {
                        // A muted conversation still has unread messages, but the
                        // count should not shout. A dot says "something is here"
                        // without demanding attention.
                        Box(
                            modifier = Modifier
                                .padding(bottom = Spacing.xs)
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.onSurfaceVariant)
                        )
                    } else {
                        Badge(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ) {
                            Text(conversation.unreadCount.toString())
                        }
                    }
                } else {
                    Spacer(Modifier.height(1.dp))
                }
            }
        }
    }
}

@Composable
internal fun ShareTargetDialog(
    state: ConversationUiState,
    strings: AppStrings,
    onSelect: (ConversationSummary) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.shareToChat) },
        text = {
            if (state.conversations.isEmpty()) {
                Text(strings.noChatsBody, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    items(
                        state.conversations.sortedByDescending { it.lastMessageTs },
                        key = { it.id }
                    ) { conversation ->
                        val user = state.users.find {
                            (conversation.directUserCode.isNotBlank() && it.userCode == conversation.directUserCode) ||
                                it.username == conversation.directUsername
                        }
                        ConversationCard(
                            conversation = conversation,
                            user = user,
                            serverUrl = state.serverUrl,
                            // Picking a share target has nothing to do with
                            // notification state, so the indicator stays off here.
                            muted = false,
                            strings = strings,
                            onClick = { onSelect(conversation) }
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(strings.cancel)
            }
        }
    )
}

@Composable
private fun AddContactDialog(
    state: ConversationUiState,
    strings: AppStrings,
    onSearch: (String) -> Unit,
    onRequest: (String) -> Unit,
    onApprove: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var userCode by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.addContact) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = userCode,
                    onValueChange = { userCode = it.filter { char -> char.isDigit() }.take(8) },
                    label = { Text(strings.userId) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch(userCode) }),
                    modifier = Modifier.fillMaxWidth()
                )
                state.relationshipMessage?.let {
                    FcMessageBanner(
                        text = friendlyErrorMessage(it, state.language),
                        tone = FcMessageTone.CONFIRMATION,
                    )
                }
                state.userLookupResult?.let { result ->
                    ContactLookupResultCard(
                        result = result,
                        strings = strings,
                        serverUrl = state.serverUrl,
                        onRequest = { onRequest(result.user.userCode) },
                        onApprove = { onApprove(result.incomingRequestId) }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSearch(userCode) }) {
                Icon(Icons.Default.Search, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(strings.search)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.cancel) } }
    )
}

@Composable
private fun ContactLookupResultCard(
    result: UserLookupResult,
    strings: AppStrings,
    serverUrl: String,
    onRequest: () -> Unit,
    onApprove: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AvatarBubble(result.user.avatarUrl, result.user.username, result.user.color, 40.dp, serverUrl)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(result.user.username, style = MaterialTheme.typography.titleSmall)
                    Text("${strings.userId}: ${result.user.userCode}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
            }
            when {
                result.isContact -> Text(strings.alreadyConnected, color = MaterialTheme.colorScheme.primary)
                result.incomingPending -> FilledTonalButton(onClick = onApprove) { Text(strings.approve) }
                result.outgoingPending -> Text(strings.outgoingRequests, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> FilledTonalButton(onClick = onRequest) { Text(strings.requestContact) }
            }
        }
    }
}

@Composable
private fun JoinGroupDialog(
    state: ConversationUiState,
    strings: AppStrings,
    onSearch: (String) -> Unit,
    onRequest: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var groupCode by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.joinGroup) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = groupCode,
                    onValueChange = { groupCode = it.filter { char -> char.isDigit() }.take(10) },
                    label = { Text(strings.groupId) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch(groupCode) }),
                    modifier = Modifier.fillMaxWidth()
                )
                state.relationshipMessage?.let {
                    FcMessageBanner(
                        text = friendlyErrorMessage(it, state.language),
                        tone = FcMessageTone.CONFIRMATION,
                    )
                }
                state.groupLookupResult?.let { result ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(result.title, style = MaterialTheme.typography.titleSmall)
                            Text("${strings.groupCode}: ${result.groupCode}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                            when {
                                result.isMember -> Text(strings.alreadyInGroup, color = MaterialTheme.colorScheme.primary)
                                result.pending -> Text(strings.outgoingRequests, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                else -> FilledTonalButton(onClick = { onRequest(result.groupCode) }) { Text(strings.requestJoin) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSearch(groupCode) }) {
                Icon(Icons.Default.Search, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(strings.search)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.cancel) } }
    )
}

@Composable
private fun CreateGroupDialog(
    strings: AppStrings,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by rememberSaveable { mutableStateOf("") }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.newGroup) },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it.take(64) },
                label = { Text(strings.groupName) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank(),
                onClick = {
                    val normalizedTitle = title.trim()
                    if (normalizedTitle.isNotEmpty()) {
                        focusManager.clearFocus(force = true)
                        onCreate(normalizedTitle)
                        onDismiss()
                    }
                },
            ) { Text(strings.save) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.cancel) } }
    )
}

@Composable
private fun PendingRelationshipRequests(
    state: ConversationUiState,
    strings: AppStrings,
    serverUrl: String,
    onApproveContact: (Long) -> Unit,
    onRejectContact: (Long) -> Unit,
    onApproveGroup: (Long) -> Unit,
    onRejectGroup: (Long) -> Unit,
) {
    val contactIncoming = state.contactRequests.filter { it.direction == "incoming" }
    val contactOutgoing = state.contactRequests.filter { it.direction == "outgoing" }
    val groupIncoming = state.groupJoinRequests.filter { it.direction == "incoming" }
    val groupOutgoing = state.groupJoinRequests.filter { it.direction == "outgoing" }
    if (contactIncoming.isEmpty() && contactOutgoing.isEmpty() && groupIncoming.isEmpty() && groupOutgoing.isEmpty()) return

    Column(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(strings.contactsAndGroups, style = MaterialTheme.typography.titleSmall)
        (contactIncoming + contactOutgoing).forEach { request ->
            RequestCard(
                title = request.user.username,
                subtitle = "${strings.userId}: ${request.user.userCode}",
                avatarUrl = request.user.avatarUrl,
                avatarTitle = request.user.username,
                color = request.user.color,
                serverUrl = serverUrl,
                incoming = request.direction == "incoming",
                strings = strings,
                onApprove = { onApproveContact(request.id) },
                onReject = { onRejectContact(request.id) }
            )
        }
        (groupIncoming + groupOutgoing).forEach { request ->
            RequestCard(
                title = request.title,
                subtitle = "${strings.groupCode}: ${request.groupCode}  ${request.requester.username}",
                avatarUrl = request.avatarUrl,
                avatarTitle = request.title,
                color = request.requester.color,
                serverUrl = serverUrl,
                shape = MaterialTheme.shapes.small,
                incoming = request.direction == "incoming",
                strings = strings,
                onApprove = { onApproveGroup(request.id) },
                onReject = { onRejectGroup(request.id) }
            )
        }
    }
}

@Composable
private fun RequestCard(
    title: String,
    subtitle: String,
    avatarUrl: String,
    avatarTitle: String,
    color: String,
    serverUrl: String,
    shape: Shape = CircleShape,
    incoming: Boolean,
    strings: AppStrings,
    onApprove: () -> Unit,
    onReject: () -> Unit,
) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            AvatarBubble(avatarUrl, avatarTitle, color, 36.dp, serverUrl, shape = shape)
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                if (!incoming) Text(strings.outgoingRequests, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
            if (incoming) {
                IconButton(onClick = onApprove) {
                    Icon(Icons.Default.Check, contentDescription = strings.approve)
                }
                IconButton(onClick = onReject) {
                    Icon(Icons.Default.Close, contentDescription = strings.reject)
                }
            }
        }
    }
}

@Composable
private fun PeerSafetyCodeCard(
    identities: List<DevicePublicIdentity>,
    strings: AppStrings,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(strings.peerSafetyCode, style = MaterialTheme.typography.labelLarge)
            if (identities.isEmpty()) {
                Text(strings.noPeerSafetyCode, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            } else {
                val identity = identities.first()
                Text(identity.safetyCode, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall)
                Text(identity.deviceName, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
        }
    }
}
