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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.chat.ui.theme.Spacing
import com.example.chat.ui.theme.ChatTheme
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

@Composable
internal fun SettingsMenuScreen(
    state: SettingsUiState,
    onBack: () -> Unit,
    onOpenGeneral: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenSecurity: () -> Unit,
) {
    val strings = stringsFor(state.language)
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { CenteredAppTopBar(title = strings.settings, onBack = onBack) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            // Anchor the screen to the person using it rather than opening
            // straight into a list of destinations.
            state.me?.let { me ->
                FcProfileBlock(
                    name = me.username,
                    detail = "${strings.userId} ${me.userCode}",
                    avatar = {
                        AvatarBubble(
                            me.avatarUrl,
                            me.username,
                            me.color,
                            52.dp,
                            state.serverUrl,
                        )
                    },
                )
            }
            FcListGroup {
                FcListRow(
                    title = strings.general,
                    subtitle = strings.displayMode,
                    leading = {
                        FcIconTile { Icon(Icons.Default.Settings, contentDescription = null) }
                    },
                    onClick = onOpenGeneral
                )
                FcRowDivider(inset = false)
                FcListRow(
                    title = strings.security,
                    subtitle = strings.safetyCode,
                    leading = {
                        FcIconTile { Icon(Icons.Default.Lock, contentDescription = null) }
                    },
                    onClick = onOpenSecurity
                )
                FcRowDivider(inset = false)
                FcListRow(
                    title = strings.about,
                    subtitle = strings.currentVersion,
                    leading = {
                        FcIconTile { Icon(Icons.Default.Info, contentDescription = null) }
                    },
                    onClick = onOpenAbout
                )
            }
        }
    }
}

@Composable
private fun SettingsEntryCard(title: String, subtitle: String, onClick: () -> Unit) {
    ElevatedCard(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ManagementSection(
    title: String? = null,
    content: @Composable () -> Unit,
) {
    // Matches FcListGroup's surface and shape so management screens sit on the
    // same material as everything else. Keeps its own child inset, since these
    // sections hold arbitrary content and not only rows.
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md)) {
            title?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(Spacing.sm))
            }
            content()
        }
    }
}

/**
 * Delegates to [FcListRow] so management screens and the rest of the app share
 * one row spec. Horizontal padding is 0 because [ManagementSection] already
 * insets its children; without that the two would stack to 32dp.
 *
 * Call sites migrate to FcListRow directly as each screen is rebuilt.
 */
@Composable
private fun ManagementRow(
    title: String,
    subtitle: String = "",
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    destructive: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    FcListRow(
        title = title,
        subtitle = subtitle.takeIf { it.isNotBlank() },
        leading = leading,
        trailing = trailing,
        destructive = destructive,
        horizontalPadding = 0.dp,
        onClick = onClick,
    )
}

@Composable
private fun ExpandableHeader(
    title: String,
    subtitle: String,
    expanded: Boolean,
    strings: AppStrings,
    onClick: () -> Unit,
) {
    ManagementRow(
        title = title,
        subtitle = subtitle,
        trailing = {
            Icon(
                if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                // The chevron is the only indication of whether the section is
                // open, so it has to be announced rather than treated as decoration.
                contentDescription = if (expanded) strings.expandedLabel else strings.collapsedLabel,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        onClick = onClick
    )
}

@Composable
internal fun SettingsGeneralScreen(
    viewModel: SettingsViewModel,
    state: SettingsUiState,
    onBack: () -> Unit,
) {
    val strings = stringsFor(state.language)
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { CenteredAppTopBar(title = strings.general, onBack = onBack) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            FcSectionLabel(strings.language)
            FcListGroup {
                FcChoiceRow(
                    title = "\u4e2d\u6587",
                    selected = state.language == AppLanguage.ZH,
                    onClick = { viewModel.updateLanguage(AppLanguage.ZH) },
                )
                FcRowDivider(inset = false)
                FcChoiceRow(
                    title = "English",
                    selected = state.language == AppLanguage.EN,
                    onClick = { viewModel.updateLanguage(AppLanguage.EN) },
                )
            }

            FcSectionLabel(strings.displayMode)
            FcListGroup {
                FcChoiceRow(
                    title = strings.followSystem,
                    selected = state.displayMode == AppDisplayMode.SYSTEM,
                    onClick = { viewModel.updateDisplayMode(AppDisplayMode.SYSTEM) },
                )
                FcRowDivider(inset = false)
                FcChoiceRow(
                    title = strings.lightMode,
                    selected = state.displayMode == AppDisplayMode.LIGHT,
                    onClick = { viewModel.updateDisplayMode(AppDisplayMode.LIGHT) },
                )
                FcRowDivider(inset = false)
                FcChoiceRow(
                    title = strings.darkMode,
                    selected = state.displayMode == AppDisplayMode.DARK,
                    onClick = { viewModel.updateDisplayMode(AppDisplayMode.DARK) },
                )
            }

            FcSectionLabel(strings.textSize)
            FcListGroup {
                FcChoiceRow(
                    title = strings.textSizeSmall,
                    selected = state.textSize == AppTextSize.SMALL,
                    onClick = { viewModel.updateTextSize(AppTextSize.SMALL) },
                )
                FcRowDivider(inset = false)
                FcChoiceRow(
                    title = strings.textSizeMedium,
                    selected = state.textSize == AppTextSize.MEDIUM,
                    onClick = { viewModel.updateTextSize(AppTextSize.MEDIUM) },
                )
                FcRowDivider(inset = false)
                FcChoiceRow(
                    title = strings.textSizeLarge,
                    selected = state.textSize == AppTextSize.LARGE,
                    onClick = { viewModel.updateTextSize(AppTextSize.LARGE) },
                )
            }

            FcSectionLabel(strings.systemColors)
            FcListGroup {
                FcSwitchRow(
                    title = strings.systemColors,
                    subtitle = strings.systemColorsHint,
                    checked = state.dynamicColorsEnabled,
                    onCheckedChange = viewModel::updateDynamicColorsEnabled,
                )
            }

            FcSectionLabel(strings.blog.title)
            FcListGroup {
                FcSwitchRow(
                    title = strings.blog.notifications,
                    subtitle = strings.blog.notificationsHint,
                    checked = state.blogNotificationsEnabled,
                    onCheckedChange = viewModel::updateBlogNotificationsEnabled,
                )
            }
        }
    }
}

@Composable
internal fun SettingsAboutScreen(
    viewModel: SettingsViewModel,
    state: SettingsUiState,
    onBack: () -> Unit,
    onOpenPrerelease: () -> Unit,
) {
    val strings = stringsFor(state.language)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentVersion = remember(context) { currentAppVersion(context) }
    val release = state.latestAppRelease
    val updateAvailable = release != null && compareVersionNames(comparableReleaseVersion(release), currentVersion) > 0
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { CenteredAppTopBar(title = strings.about, onBack = onBack) }
    ) { padding ->
        // Previously one card holding six identically styled tonal buttons, with
        // version facts, a navigation destination and two diagnostic tools all
        // presented as though they were the same kind of thing. Now: facts are
        // rows, navigation is a row with a chevron, and the screen has exactly
        // one primary action - whichever step of the update flow you are on.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            FcSectionLabel(strings.updatesSection)
            FcListGroup {
                FcListRow(
                    title = strings.appVersion,
                    trailing = {
                        Text(
                            currentVersion,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
                FcRowDivider(inset = false)
                FcListRow(
                    title = strings.latestVersion,
                    subtitle = state.updateStatus ?: when {
                        release == null -> strings.noReleaseUploaded
                        updateAvailable -> strings.updateAvailable
                        else -> strings.latestVersionInstalled
                    },
                    trailing = {
                        Text(
                            releaseDisplayVersion(release),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (updateAvailable) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    },
                )
            }

            state.downloadProgress?.let { progress ->
                LinearProgressIndicator(
                    progress = { progress.progress },
                    modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.sm),
                )
            }

            // One primary action, reflecting the stage of the update flow rather
            // than offering every stage at once.
            val downloaded = state.downloadedUpdate
            when {
                downloaded != null -> FcPrimaryButton(
                    text = strings.installUpdateAction,
                    onClick = { openInstaller(context, viewModel, downloaded) },
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
                )
                updateAvailable -> FcPrimaryButton(
                    text = strings.downloadUpdate,
                    onClick = { scope.launch { downloadLatestUpdate(context, viewModel) } },
                    enabled = state.downloadProgress == null,
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
                )
                else -> FcTonalButton(
                    text = if (state.isCheckingUpdate) strings.checkingUpdate else strings.checkUpdate,
                    onClick = viewModel::checkForUpdates,
                    enabled = !state.isCheckingUpdate,
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
                )
            }

            FcSectionLabel(strings.prereleaseCenter)
            FcListGroup {
                // Navigation, not an action: this opens a screen, so it reads as
                // a destination rather than as a button that does something.
                FcListRow(
                    title = strings.prereleaseBuilds,
                    subtitle = strings.prereleaseCenter,
                    leading = {
                        FcIconTile { Icon(Icons.Default.Download, contentDescription = null) }
                    },
                    onClick = onOpenPrerelease,
                )
            }

            FcSectionLabel(strings.troubleshootingSection)
            FcListGroup {
                FcListRow(
                    title = if (state.isRunningPushHealthCheck) {
                        strings.pushSelfTestRunning
                    } else {
                        strings.pushSelfTest
                    },
                    // The self-test reports its own outcome here, success or
                    // failure, so a failing run reads in the row the user tapped
                    // rather than as a banner elsewhere on the screen.
                    subtitle = state.pushHealthStatus
                        ?.let { friendlyErrorMessage(it, state.language) }
                        ?: strings.pushSelfTestHint,
                    enabled = !state.isRunningPushHealthCheck,
                    leading = {
                        FcIconTile { Icon(Icons.Default.Refresh, contentDescription = null) }
                    },
                    onClick = viewModel::runPushHealthCheck,
                )
                FcRowDivider(inset = false)
                FcListRow(
                    title = strings.exportDiagnostics,
                    subtitle = strings.exportDiagnosticsHint,
                    leading = {
                        FcIconTile { Icon(Icons.Default.Share, contentDescription = null) }
                    },
                    onClick = { exportDiagnostics(context, state.language) },
                )
            }
        }
    }
}

@Composable
internal fun SettingsSecurityScreen(
    viewModel: SettingsViewModel,
    state: SettingsUiState,
    onBack: () -> Unit,
) {
    val strings = stringsFor(state.language)
    var pendingRemoveDeviceId by rememberSaveable { mutableStateOf("") }
    val pendingRemoveDevice = state.myDevices.find { it.deviceId == pendingRemoveDeviceId }
    LaunchedEffect(state.isLoggedIn) {
        if (state.isLoggedIn) viewModel.refreshMyDevices()
    }
    if (pendingRemoveDeviceId.isNotBlank()) {
        val label = pendingRemoveDevice?.deviceName?.takeIf { it.isNotBlank() } ?: pendingRemoveDeviceId.take(8)
        AnimatedActionDialog(
            title = strings.removeDevice,
            destructive = true,
            body = { Text(strings.removeDeviceConfirm.format(label)) },
            confirmText = strings.removeDevice,
            onConfirm = {
                viewModel.removeMyDevice(pendingRemoveDeviceId)
                pendingRemoveDeviceId = ""
            },
            dismissText = strings.cancel,
            onDismissRequest = { pendingRemoveDeviceId = "" }
        )
    }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { CenteredAppTopBar(title = strings.security, onBack = onBack) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            FcSectionLabel(strings.thisDeviceSection)
            FcListGroup {
                val identity = state.deviceIdentity
                if (identity == null) {
                    FcListRow(title = strings.noDeviceIdentity)
                } else {
                    FcListRow(
                        title = strings.deviceName,
                        trailing = {
                            Text(
                                identity.deviceName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                    )
                    FcRowDivider(inset = false)
                    // The safety code is the thing you read aloud to verify a
                    // contact, so it gets its own row and monospaced-ish
                    // prominence rather than being one line among four.
                    FcListRow(
                        title = strings.safetyCode,
                        subtitle = strings.safetyCodeHint,
                    )
                    FcListRow(
                        title = identity.safetyCode,
                        horizontalPadding = Spacing.lg,
                    )
                }
            }

            FcSectionLabel(strings.e2ee)
            FcListGroup {
                FcSwitchRow(
                    title = strings.e2ee,
                    checked = state.e2eeEnabled,
                    onCheckedChange = viewModel::updateE2eeEnabled,
                )
            }

            FcSectionLabel(strings.devices)
            if (state.myDevices.isEmpty()) {
                FcListGroup {
                    FcListRow(title = strings.noDeviceIdentity)
                }
            } else {
                FcListGroup {
                    state.myDevices.forEachIndexed { index, device ->
                        val isCurrent = device.deviceId == state.deviceIdentity?.deviceId
                        val deviceName = device.deviceName.ifBlank { device.deviceId.take(8) }
                        val pending = device.status == "pending"
                        if (index > 0) FcRowDivider(inset = false)
                        FcListRow(
                            title = deviceName,
                            // Was a single string with embedded newlines holding
                            // device id, status and last seen. Status is state,
                            // not prose, so it is a pill; the id is noise for a
                            // family user and drops to the subtitle's tail.
                            subtitle = device.lastSeenAt.takeIf { it > 0L }
                                ?.let { "${strings.lastSeen} ${formatTimelineLabel(it, state.language, strings)}" }
                                ?: device.deviceId.take(12),
                            leading = {
                                FcIconTile {
                                    Icon(
                                        if (isCurrent) Icons.Default.Done else Icons.Default.Lock,
                                        contentDescription = null,
                                    )
                                }
                            },
                            trailing = {
                                if (isCurrent) {
                                    FcStatusPill(strings.currentDevice, emphasis = FcPillEmphasis.PRIMARY)
                                } else {
                                    FcRowActions {
                                        FcStatusPill(
                                            if (pending) strings.deviceStatusPending else strings.deviceStatusTrusted,
                                            emphasis = if (pending) FcPillEmphasis.INFO else FcPillEmphasis.NEUTRAL,
                                        )
                                        if (pending) {
                                            // Approve is constructive and Reject
                                            // is not; they were previously two
                                            // identical TextButtons side by side.
                                            // These also used inline ZH/EN
                                            // literals while strings.approve and
                                            // strings.reject already existed.
                                            FcTonalButton(
                                                text = strings.approve,
                                                onClick = { viewModel.approveMyDevice(device.deviceId) },
                                            )
                                        }
                                        FcTextButton(
                                            text = if (pending) strings.reject else strings.removeDevice,
                                            onClick = { pendingRemoveDeviceId = device.deviceId },
                                        )
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun AccountManageScreen(
    viewModel: ManagementViewModel,
    state: ManagementUiState,
    onBack: () -> Unit,
) {
    val strings = stringsFor(state.language)
    val context = LocalContext.current
    var showPasswordDialog by remember { mutableStateOf(false) }
    var showUsernameDialog by remember { mutableStateOf(false) }
    var showDeletionDialog by remember { mutableStateOf(false) }
    var pendingAvatarCrop by remember { mutableStateOf<PendingAvatarCrop?>(null) }
    var passwordDraft by rememberSaveable { mutableStateOf("") }
    var usernameDraft by rememberSaveable(state.me?.username) { mutableStateOf(state.me?.username.orEmpty()) }
    val avatarPicker = rememberLauncherForActivityResult(GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@rememberLauncherForActivityResult
        val name = queryName(context, uri) ?: "avatar.jpg"
        pendingAvatarCrop = PendingAvatarCrop(
            fileName = name,
            mime = context.contentResolver.getType(uri) ?: "image/jpeg",
            bytes = bytes
        )
    }

    if (showPasswordDialog) {
        SimpleTextInputDialog(
            title = strings.changePassword,
            label = strings.newPassword,
            initial = "",
            password = true,
            strings = strings,
            onDismiss = { showPasswordDialog = false },
            onSave = {
                passwordDraft = it
                viewModel.changePassword(passwordDraft)
                passwordDraft = ""
                showPasswordDialog = false
            }
        )
    }
    if (showUsernameDialog) {
        SimpleTextInputDialog(
            title = strings.changeUsername,
            label = strings.newUsername,
            initial = usernameDraft,
            password = false,
            strings = strings,
            onDismiss = { showUsernameDialog = false },
            onSave = {
                usernameDraft = it
                viewModel.changeUsername(usernameDraft)
                showUsernameDialog = false
            }
        )
    }
    if (showDeletionDialog) {
        AnimatedActionDialog(
            title = strings.requestAccountDeletion,
            destructive = true,
            body = { Text(strings.requestAccountDeletionConfirm) },
            confirmText = strings.requestAccountDeletion,
            onConfirm = {
                showDeletionDialog = false
                viewModel.requestAccountDeletion()
            },
            dismissText = strings.cancel,
            onDismissRequest = { showDeletionDialog = false }
        )
    }
    pendingAvatarCrop?.let { crop ->
        AvatarCropDialog(
            title = strings.changeAvatar,
            source = crop,
            strings = strings,
            onDismiss = { pendingAvatarCrop = null },
            onConfirm = { croppedBytes ->
                viewModel.uploadAvatar("avatar.jpg", "image/jpeg", croppedBytes)
                pendingAvatarCrop = null
            }
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { CenteredAppTopBar(title = strings.accountManage, onBack = onBack) }
    ) { padding ->
        // Reorganised around consequence. Signing out and deleting the account
        // previously sat adjacent in one card, as though they were comparable:
        // one keeps your history and is reversible, the other needs admin
        // approval and cannot be undone. They are now separated, and the
        // destructive one sits alone under its own heading.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            // The avatar is right here, so tapping it is the obvious way to
            // change it - more direct than a "Change photo" row further down.
            FcProfileBlock(
                name = state.me?.username.orEmpty(),
                detail = state.me?.userCode?.takeIf { it.isNotBlank() }
                    ?.let { "${strings.userId} $it" }
                    ?: strings.changeAvatarHint,
                onClick = { avatarPicker.launch("image/*") },
                modifier = Modifier.padding(bottom = Spacing.sm),
                avatar = {
                    AvatarBubble(
                        state.me?.avatarUrl.orEmpty(),
                        state.me?.username.orEmpty(),
                        state.me?.color ?: "#128c7e",
                        64.dp,
                        state.serverUrl,
                    )
                },
            )

            FcSectionLabel(strings.profileSection)
            FcListGroup {
                FcListRow(
                    title = strings.changeUsername,
                    subtitle = state.me?.username.orEmpty(),
                    leading = {
                        FcIconTile { Icon(Icons.Default.Person, contentDescription = null) }
                    },
                    onClick = {
                        usernameDraft = state.me?.username.orEmpty()
                        showUsernameDialog = true
                    },
                )
                FcRowDivider(inset = false)
                FcListRow(
                    title = strings.changeAvatar,
                    subtitle = strings.changeAvatarHint,
                    leading = {
                        FcIconTile { Icon(Icons.Default.Image, contentDescription = null) }
                    },
                    onClick = { avatarPicker.launch("image/*") },
                )
                FcRowDivider(inset = false)
                FcListRow(
                    // Was subtitled "New password", a form field label rather
                    // than anything describing what the row does.
                    title = strings.changePassword,
                    subtitle = strings.changePasswordHint,
                    leading = {
                        FcIconTile { Icon(Icons.Default.Lock, contentDescription = null) }
                    },
                    onClick = { showPasswordDialog = true },
                )
            }

            FcListGroup(modifier = Modifier.padding(top = Spacing.sm)) {
                FcListRow(
                    title = strings.logout,
                    subtitle = strings.signOutHint,
                    leading = {
                        FcIconTile { Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null) }
                    },
                    onClick = { viewModel.logout(clearSavedLogin = true) },
                )
            }

            FcSectionLabel(strings.dangerZoneSection)
            FcListGroup {
                FcListRow(
                    // Was subtitled with the confirmation dialog's body text.
                    title = strings.requestAccountDeletion,
                    subtitle = strings.deleteAccountHint,
                    destructive = true,
                    leading = {
                        FcIconTile(destructive = true) {
                            Icon(Icons.Default.Delete, contentDescription = null)
                        }
                    },
                    onClick = { showDeletionDialog = true },
                )
            }

            state.relationshipMessage?.let {
                FcMessageBanner(
                    text = friendlyErrorMessage(it, state.language),
                    tone = FcMessageTone.CONFIRMATION,
                    modifier = Modifier.padding(top = Spacing.sm),
                )
            }
        }
    }
}

@Composable
private fun SimpleTextInputDialog(
    title: String,
    label: String,
    initial: String,
    password: Boolean,
    strings: AppStrings,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var value by rememberSaveable(initial) { mutableStateOf(initial) }
    AnimatedDialogContainer(onDismissRequest = onDismiss) { dismiss ->
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
        ) {
            Column(modifier = Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(label) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = dismiss) { Text(strings.cancel) }
                    TextButton(onClick = { onSave(value) }) { Text(strings.save) }
                }
            }
        }
    }
}

@Composable
private fun AvatarCropDialog(
    title: String,
    source: PendingAvatarCrop,
    strings: AppStrings,
    onDismiss: () -> Unit,
    onConfirm: (ByteArray) -> Unit,
) {
    val bitmap = remember(source.bytes) {
        BitmapFactory.decodeByteArray(source.bytes, 0, source.bytes.size)
    }
    if (bitmap == null) {
        LaunchedEffect(source.fileName) { onDismiss() }
        return
    }

    val cropSize = 280.dp
    val density = LocalDensity.current
    val cropSizePx = remember(density) { with(density) { cropSize.roundToPx().toFloat() } }
    var scale by remember(source.bytes) { mutableStateOf(1f) }
    var offset by remember(source.bytes) { mutableStateOf(Offset.Zero) }

    AnimatedDialogContainer(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
        scrimAlpha = 0.72f,
        enterScale = 0.985f,
        exitScale = 1.015f
    ) { dismiss ->
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                Text(
                    strings.avatarCropHint,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.84f)
                            .aspectRatio(1f)
                            .clip(CircleShape)
                            .border(2.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                            .pointerInput(source.bytes) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    val nextScale = (scale * zoom).coerceIn(1f, 4.5f)
                                    scale = nextScale
                                    offset = clampAvatarCropOffset(bitmap, cropSizePx, nextScale, offset + pan)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = strings.image,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                    translationX = offset.x
                                    translationY = offset.y
                                }
                        )
                    }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = dismiss) { Text(strings.cancel) }
                    TextButton(
                        onClick = {
                            cropAvatarBitmap(bitmap, scale, offset, cropSizePx)?.let(onConfirm)
                        }
                    ) { Text(strings.save) }
                }
            }
        }
    }
}

@Composable
internal fun PeerManageScreen(
    viewModel: ManagementViewModel,
    state: ManagementUiState,
    selectedUserCode: String?,
    onBack: () -> Unit,
    onOpenDirect: (Long) -> Unit,
) {
    val strings = stringsFor(state.language)
    val conversation = state.conversations.find { it.id == state.currentConversationId }
    val peer = selectedUserCode
        ?.takeIf { it.isNotBlank() }
        ?.let { requestedCode -> state.users.firstOrNull { it.userCode == requestedCode } }
        ?: state.users.firstOrNull { user ->
            conversation != null &&
                ((conversation.directUserCode.isNotBlank() && user.userCode == conversation.directUserCode) ||
                    user.username == conversation.directUsername)
        }
    val directConversation = peer?.let { peerUser ->
        state.conversations.firstOrNull { item ->
            item.kind == "direct" &&
                ((peerUser.userCode.isNotBlank() && item.directUserCode == peerUser.userCode) ||
                    item.directUsername == peerUser.username)
        }
    }
    val isCurrentDirectConversation = conversation?.kind == "direct" && directConversation?.id == conversation.id
    val peerCodes = state.deviceIdentities.filter { identity ->
        peer != null && ((peer.userCode.isNotBlank() && identity.userCode == peer.userCode) || identity.username == peer.username)
    }

    // Both of these were single taps with no confirmation. Clearing history is
    // irreversible and deleting a contact removes the conversation as well, so
    // both now ask - matching the chat screen, which has always confirmed.
    var showClearPeerHistoryDialog by rememberSaveable(conversation?.id) { mutableStateOf(false) }
    var showDeleteContactDialog by rememberSaveable(conversation?.id) { mutableStateOf(false) }

    if (showClearPeerHistoryDialog) {
        AnimatedActionDialog(
            title = strings.clearChatHistory,
            destructive = true,
            body = { Text(strings.deleteHistoryConfirm) },
            confirmText = strings.delete,
            onConfirm = {
                showClearPeerHistoryDialog = false
                viewModel.clearCurrentConversationHistory()
                onBack()
            },
            dismissText = strings.cancel,
            onDismissRequest = { showClearPeerHistoryDialog = false }
        )
    }
    if (showDeleteContactDialog) {
        AnimatedActionDialog(
            title = strings.deleteContact,
            destructive = true,
            body = { Text(strings.deleteContactConfirm) },
            confirmText = strings.delete,
            onConfirm = {
                showDeleteContactDialog = false
                viewModel.deleteCurrentDirectConversation()
                onBack()
            },
            dismissText = strings.cancel,
            onDismissRequest = { showDeleteContactDialog = false }
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { CenteredAppTopBar(title = strings.friendManage, onBack = onBack) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            FcProfileBlock(
                name = peer?.username.orEmpty(),
                detail = peer?.userCode?.takeIf { it.isNotBlank() }
                    ?.let { "${strings.userId} $it" }
                    .orEmpty(),
                modifier = Modifier.padding(bottom = Spacing.sm),
                avatar = {
                    AvatarBubble(
                        peer?.avatarUrl.orEmpty(),
                        peer?.username.orEmpty(),
                        peer?.color ?: "#128c7e",
                        64.dp,
                        state.serverUrl,
                    )
                },
            )

            if (peerCodes.isNotEmpty()) {
                FcSectionLabel(strings.peerSafetyCode)
                FcListGroup {
                    peerCodes.forEachIndexed { index, identity ->
                        if (index > 0) FcRowDivider(inset = false)
                        // Was one subtitle string joining the code and the device
                        // name with a newline. The code is what you compare in
                        // person, so it is the row's title.
                        FcListRow(
                            title = identity.safetyCode,
                            subtitle = "${strings.deviceName} ${identity.deviceName}",
                        )
                    }
                }
                Text(
                    strings.safetyCodeHint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xs),
                )
            }

            if (isCurrentDirectConversation) {
                FcSectionLabel(strings.dangerZoneSection)
                FcListGroup {
                    FcListRow(
                        title = strings.clearChatHistory,
                        subtitle = strings.deleteHistoryConfirm,
                        leading = {
                            FcIconTile { Icon(Icons.Default.Delete, contentDescription = null) }
                        },
                        onClick = { showClearPeerHistoryDialog = true },
                    )
                    FcRowDivider(inset = false)
                    FcListRow(
                        title = strings.deleteContact,
                        subtitle = strings.deleteContactConfirm,
                        destructive = true,
                        leading = {
                            FcIconTile(destructive = true) {
                                Icon(Icons.Default.Delete, contentDescription = null)
                            }
                        },
                        onClick = { showDeleteContactDialog = true },
                    )
                }
            } else if (peer != null) {
                FcListGroup {
                    FcListRow(
                        title = if (directConversation != null) strings.privateMessage else strings.requestContact,
                        subtitle = peer.username,
                        leading = {
                            FcIconTile { Icon(Icons.Default.PersonAdd, contentDescription = null) }
                        },
                        onClick = {
                            directConversation?.let { onOpenDirect(it.id) }
                                ?: viewModel.requestContact(peer.userCode)
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun GroupManageScreen(
    viewModel: ManagementViewModel,
    state: ManagementUiState,
    onBack: () -> Unit,
    onOpenMember: (String) -> Unit,
) {
    val strings = stringsFor(state.language)
    val context = LocalContext.current
    val manage = state.currentConversationManage
    val isOwner = manage?.ownRole == "owner"
    var titleDraft by rememberSaveable(manage?.id, manage?.title) { mutableStateOf(manage?.title.orEmpty()) }
    var addMemberExpanded by rememberSaveable(manage?.id) { mutableStateOf(false) }
    var adminExpanded by rememberSaveable(manage?.id) { mutableStateOf(false) }
    var pendingRemoveMemberCode by rememberSaveable(manage?.id) { mutableStateOf("") }
    var pendingRemoveMemberName by rememberSaveable(manage?.id) { mutableStateOf("") }
    var pendingRemoveAdminCode by rememberSaveable(manage?.id) { mutableStateOf("") }
    var pendingRemoveAdminName by rememberSaveable(manage?.id) { mutableStateOf("") }
    var pendingTransferOwnerCode by rememberSaveable(manage?.id) { mutableStateOf("") }
    var pendingTransferOwnerName by rememberSaveable(manage?.id) { mutableStateOf("") }
    var showLeaveGroupDialog by rememberSaveable(manage?.id) { mutableStateOf(false) }
    var showDeleteGroupDialog by rememberSaveable(manage?.id) { mutableStateOf(false) }
    var showClearGroupHistoryDialog by rememberSaveable(manage?.id) { mutableStateOf(false) }
    var pendingAvatarCrop by remember { mutableStateOf<PendingAvatarCrop?>(null) }
    val avatarPicker = rememberLauncherForActivityResult(GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@rememberLauncherForActivityResult
        val name = queryName(context, uri) ?: "group_avatar.jpg"
        pendingAvatarCrop = PendingAvatarCrop(
            fileName = name,
            mime = context.contentResolver.getType(uri) ?: "image/jpeg",
            bytes = bytes
        )
    }
    LaunchedEffect(state.currentConversationId) {
        viewModel.refreshCurrentConversationManage()
    }
    pendingAvatarCrop?.let { crop ->
        AvatarCropDialog(
            title = strings.changeGroupIcon,
            source = crop,
            strings = strings,
            onDismiss = { pendingAvatarCrop = null },
            onConfirm = { croppedBytes ->
                viewModel.uploadCurrentGroupAvatar("group_avatar.jpg", "image/jpeg", croppedBytes)
                pendingAvatarCrop = null
            }
        )
    }
    if (pendingRemoveMemberCode.isNotBlank()) {
        AnimatedActionDialog(
            title = strings.removeMember,
            destructive = true,
            body = { Text(strings.removeMemberConfirm.format(pendingRemoveMemberName)) },
            confirmText = strings.removeMember,
            onConfirm = {
                viewModel.removeCurrentGroupMember(pendingRemoveMemberCode)
                pendingRemoveMemberCode = ""
                pendingRemoveMemberName = ""
            },
            dismissText = strings.cancel,
            onDismissRequest = {
                pendingRemoveMemberCode = ""
                pendingRemoveMemberName = ""
            }
        )
    }
    if (pendingRemoveAdminCode.isNotBlank()) {
        AnimatedActionDialog(
            title = strings.removeAdmin,
            destructive = true,
            body = { Text(strings.removeAdminConfirm.format(pendingRemoveAdminName)) },
            confirmText = strings.removeAdmin,
            onConfirm = {
                viewModel.removeCurrentGroupAdmin(pendingRemoveAdminCode)
                pendingRemoveAdminCode = ""
                pendingRemoveAdminName = ""
            },
            dismissText = strings.cancel,
            onDismissRequest = {
                pendingRemoveAdminCode = ""
                pendingRemoveAdminName = ""
            }
        )
    }
    if (pendingTransferOwnerCode.isNotBlank()) {
        AnimatedActionDialog(
            title = strings.transferOwner,
            destructive = true,
            body = { Text(strings.transferOwnerConfirm.format(pendingTransferOwnerName)) },
            confirmText = strings.transferOwner,
            onConfirm = {
                viewModel.transferCurrentGroupOwner(pendingTransferOwnerCode)
                pendingTransferOwnerCode = ""
                pendingTransferOwnerName = ""
            },
            dismissText = strings.cancel,
            onDismissRequest = {
                pendingTransferOwnerCode = ""
                pendingTransferOwnerName = ""
            }
        )
    }
    if (showLeaveGroupDialog) {
        AnimatedActionDialog(
            title = strings.leaveGroup,
            destructive = true,
            body = { Text(strings.leaveGroupConfirm) },
            confirmText = strings.leaveGroup,
            onConfirm = {
                showLeaveGroupDialog = false
                viewModel.leaveCurrentGroupConversation()
                onBack()
            },
            dismissText = strings.cancel,
            onDismissRequest = { showLeaveGroupDialog = false }
        )
    }
    if (showDeleteGroupDialog) {
        AnimatedActionDialog(
            title = strings.deleteGroup,
            destructive = true,
            body = { Text(strings.deleteGroupConfirm) },
            confirmText = strings.deleteGroup,
            onConfirm = {
                showDeleteGroupDialog = false
                viewModel.deleteCurrentGroupConversation()
                onBack()
            },
            dismissText = strings.cancel,
            onDismissRequest = { showDeleteGroupDialog = false }
        )
    }
    // Clearing group history was previously a single tap with no confirmation,
    // while the identical action in the chat screen and in peer management both
    // ask first. It is irreversible, so it now asks here too.
    if (showClearGroupHistoryDialog) {
        AnimatedActionDialog(
            title = strings.clearChatHistory,
            destructive = true,
            body = { Text(strings.deleteHistoryConfirm) },
            confirmText = strings.delete,
            onConfirm = {
                showClearGroupHistoryDialog = false
                viewModel.clearCurrentConversationHistory()
            },
            dismissText = strings.cancel,
            onDismissRequest = { showClearGroupHistoryDialog = false }
        )
    }
    val memberCodes = remember(manage?.members) {
        manage?.members?.mapNotNull { it.user.userCode.takeIf(String::isNotBlank) }?.toSet().orEmpty()
    }
    val directContactKeys = remember(state.conversations) {
        state.conversations
            .filter { it.kind == "direct" }
            .flatMap { listOf(it.directUserCode, it.directUsername) }
            .filter { it.isNotBlank() }
            .toSet()
    }
    val addableContacts = remember(state.users, directContactKeys, memberCodes, state.me?.userCode) {
        state.users
            .filter { user ->
                user.userCode.isNotBlank() &&
                    user.userCode != state.me?.userCode &&
                    user.userCode !in memberCodes &&
                    (user.userCode in directContactKeys || user.username in directContactKeys)
            }
            .distinctBy { it.userCode }
            .sortedBy { it.username.lowercase() }
    }
    val adminCandidates = remember(manage?.members, state.me?.userCode) {
        manage?.members
            ?.filter { member ->
                member.role == "member" &&
                    member.user.userCode.isNotBlank() &&
                    member.user.userCode != state.me?.userCode
            }
            ?.sortedBy { it.user.username.lowercase() }
            .orEmpty()
    }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { CenteredAppTopBar(title = strings.groupManage, onBack = onBack) }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (manage == null) {
                Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                // Header was five lines of engineering telemetry stacked beside
                // the avatar - group code, admin limit, key epoch, key device
                // readiness - as if all of it were equally the subject of the
                // screen. The identity goes up top; the technical state gets its
                // own labelled section further down where it belongs.
                FcProfileBlock(
                    name = manage.title,
                    detail = "${strings.groupId} ${manage.groupCode}",
                    onClick = if (manage.canManage) {
                        { avatarPicker.launch("image/*") }
                    } else {
                        null
                    },
                    modifier = Modifier.padding(bottom = Spacing.sm),
                    avatar = {
                        AvatarBubble(
                            manage.avatarUrl,
                            manage.title,
                            "#128c7e",
                            64.dp,
                            state.serverUrl,
                            shape = MaterialTheme.shapes.medium,
                        )
                    },
                )

                if (manage.canManage) {
                    FcSectionLabel(strings.groupName)
                    FcListGroup {
                        Column(Modifier.padding(Spacing.lg)) {
                            OutlinedTextField(
                                value = titleDraft,
                                onValueChange = { titleDraft = it },
                                label = { Text(strings.groupName) },
                                singleLine = true,
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(Spacing.md))
                            FcPrimaryButton(
                                text = strings.save,
                                onClick = { viewModel.changeCurrentGroupTitle(titleDraft) },
                                enabled = titleDraft.isNotBlank() && titleDraft != manage.title,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        FcRowDivider(inset = false)
                        FcListRow(
                            title = strings.changeGroupIcon,
                            subtitle = strings.avatarHint,
                            leading = {
                                FcIconTile { Icon(Icons.Default.Image, contentDescription = null) }
                            },
                            onClick = { avatarPicker.launch("image/*") }
                        )
                    }

                    // Was a horizontally scrolling FilterChip row, the same
                    // pattern replaced in General: some options sat off screen
                    // and the active one was signalled only by a fill.
                    FcSectionLabel(strings.messageExpiration)
                    FcListGroup {
                        listOf(
                            0L to strings.noExpiration,
                            8L * 60L * 60L * 1000L to strings.hours8,
                            24L * 60L * 60L * 1000L to strings.hours24,
                            72L * 60L * 60L * 1000L to strings.hours72,
                        ).forEachIndexed { index, (ttl, label) ->
                            if (index > 0) FcRowDivider(inset = false)
                            FcChoiceRow(
                                title = label,
                                selected = manage.messageTtlMs == ttl,
                                onClick = { viewModel.setCurrentGroupExpiration(ttl) },
                            )
                        }
                    }

                    FcSectionLabel(strings.groupKeyStatus)
                    FcListGroup {
                        FcListRow(
                            title = strings.groupKeyEpoch,
                            trailing = {
                                Text(
                                    manage.keyEpoch.toString(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                        )
                        FcRowDivider(inset = false)
                        FcListRow(
                            title = strings.groupKeyDevicesReady,
                            trailing = {
                                Text(
                                    "${manage.keyReadyDeviceCount}/${manage.keyDeviceCount}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (manage.keyReadyDeviceCount >= manage.keyDeviceCount) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    },
                                )
                            },
                        )
                        FcRowDivider(inset = false)
                        FcListRow(
                            title = strings.adminLimit,
                            trailing = {
                                Text(
                                    "${manage.adminCount}/${manage.adminLimit}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                        )
                    }

                    // Separated from the settings above: this one cannot be
                    // undone, and it now asks before doing it.
                    FcSectionLabel(strings.dangerZoneSection)
                    FcListGroup {
                        FcListRow(
                            title = strings.clearChatHistory,
                            subtitle = strings.deleteHistoryConfirm,
                            destructive = true,
                            leading = {
                                FcIconTile(destructive = true) {
                                    Icon(Icons.Default.Delete, contentDescription = null)
                                }
                            },
                            onClick = { showClearGroupHistoryDialog = true }
                        )
                    }

                    ManagementSection {
                        ExpandableHeader(
                            title = strings.addGroupMember,
                            subtitle = strings.selectContact,
                            expanded = addMemberExpanded,
                            strings = strings,
                            onClick = { addMemberExpanded = !addMemberExpanded }
                        )
                        AnimatedVisibility(visible = addMemberExpanded) {
                            Column {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                                if (addableContacts.isEmpty()) {
                                    Text(strings.noAvailableContacts, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
                                } else {
                                    addableContacts.forEachIndexed { index, user ->
                                        ManagementRow(
                                            title = user.username,
                                            subtitle = "${strings.userId}: ${user.userCode}",
                                            leading = { AvatarBubble(user.avatarUrl, user.username, user.color, 36.dp, state.serverUrl) },
                                            trailing = {
                                                TextButton(onClick = { viewModel.addCurrentGroupMember(user.userCode) }) {
                                                    Text(strings.addGroupMember)
                                                }
                                            }
                                        )
                                        if (index != addableContacts.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                                    }
                                }
                            }
                        }
                    }

                    ManagementSection {
                        ExpandableHeader(
                            title = if (manage.canManageOwner) strings.addAdmin else strings.requestAdmin,
                            subtitle = strings.selectMember,
                            expanded = adminExpanded,
                            strings = strings,
                            onClick = { adminExpanded = !adminExpanded }
                        )
                        AnimatedVisibility(visible = adminExpanded) {
                            Column {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
                                if (adminCandidates.isEmpty()) {
                                    Text(strings.noAvailableMembers, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
                                } else {
                                    adminCandidates.forEachIndexed { index, member ->
                                        ManagementRow(
                                            title = member.user.username,
                                            subtitle = "${strings.userId}: ${member.user.userCode}",
                                            leading = { AvatarBubble(member.user.avatarUrl, member.user.username, member.user.color, 36.dp, state.serverUrl) },
                                            trailing = {
                                                TextButton(onClick = { viewModel.requestCurrentGroupAdmin(member.user.userCode) }) {
                                                    Text(if (manage.canManageOwner) strings.addAdmin else strings.requestAdmin)
                                                }
                                            }
                                        )
                                        if (index != adminCandidates.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                                    }
                                }
                            }
                        }
                    }

                    if (manage.pendingJoinRequests.isNotEmpty() || manage.pendingAdminRequests.isNotEmpty()) {
                        ManagementSection(title = strings.incomingRequests) {
                            manage.pendingJoinRequests.forEachIndexed { index, request ->
                                ManagementRow(
                                    title = request.requester.username,
                                    subtitle = "${strings.groupCode}: ${request.groupCode}",
                                    leading = { AvatarBubble(request.requester.avatarUrl, request.requester.username, request.requester.color, 36.dp, state.serverUrl) },
                                    trailing = {
                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            TextButton(onClick = { viewModel.reviewGroupJoinRequest(request.id, true) }) { Text(strings.approve) }
                                            TextButton(onClick = { viewModel.reviewGroupJoinRequest(request.id, false) }) { Text(strings.reject) }
                                        }
                                    }
                                )
                                if (index != manage.pendingJoinRequests.lastIndex || manage.pendingAdminRequests.isNotEmpty()) {
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                                }
                            }
                            manage.pendingAdminRequests.forEachIndexed { index, request ->
                                ManagementRow(
                                    title = "${request.requesterUsername} -> ${request.targetUsername}",
                                    subtitle = strings.addAdmin,
                                    trailing = {
                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            TextButton(onClick = { viewModel.reviewGroupAdminRequest(request.id, true) }) { Text(strings.approve) }
                                            TextButton(onClick = { viewModel.reviewGroupAdminRequest(request.id, false) }) { Text(strings.reject) }
                                        }
                                    }
                                )
                                if (index != manage.pendingAdminRequests.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                            }
                        }
                    }
                }

                ManagementSection(title = strings.member) {
                    manage.members.forEachIndexed { index, member ->
                        val roleLabel = when (member.role) {
                            "owner" -> strings.owner
                            "admin" -> strings.admin
                            else -> strings.member
                        }
                        val canRemoveMember = manage.canManage &&
                            member.role != "owner" &&
                            member.user.userCode != state.me?.userCode &&
                            (isOwner || member.role != "admin")
                        val canRemoveAdmin = isOwner &&
                            member.role == "admin" &&
                            member.user.userCode != state.me?.userCode
                        val canTransferOwner = isOwner && member.user.userCode != state.me?.userCode
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = member.user.userCode.isNotBlank()) {
                                        onOpenMember(member.user.userCode)
                                    },
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                AvatarBubble(
                                    member.user.avatarUrl,
                                    member.user.username,
                                    member.user.color,
                                    38.dp,
                                    state.serverUrl
                                )
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(member.user.username, style = MaterialTheme.typography.bodyLarge)
                                    Text(roleLabel, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                            if (canRemoveMember || canRemoveAdmin || canTransferOwner) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 50.dp)
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    if (canRemoveAdmin) {
                                        TextButton(onClick = {
                                            pendingRemoveAdminCode = member.user.userCode
                                            pendingRemoveAdminName = member.user.username
                                        }) {
                                            Text(strings.removeAdmin)
                                        }
                                    }
                                    if (canRemoveMember) {
                                        TextButton(onClick = {
                                            pendingRemoveMemberCode = member.user.userCode
                                            pendingRemoveMemberName = member.user.username
                                        }) {
                                            Text(strings.removeMember)
                                        }
                                    }
                                    if (canTransferOwner) {
                                        TextButton(onClick = {
                                            pendingTransferOwnerCode = member.user.userCode
                                            pendingTransferOwnerName = member.user.username
                                        }) {
                                            Text(strings.transferOwner)
                                        }
                                    }
                                }
                            }
                        }
                        if (index != manage.members.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f))
                    }
                }

                ManagementSection {
                    if (isOwner) {
                        ManagementRow(
                            title = strings.deleteGroup,
                            subtitle = strings.deleteGroupConfirm,
                            destructive = true,
                            leading = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { showDeleteGroupDialog = true }
                        )
                    } else {
                        ManagementRow(
                            title = strings.leaveGroup,
                            subtitle = strings.leaveGroupConfirm,
                            destructive = true,
                            leading = { Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { showLeaveGroupDialog = true }
                        )
                    }
                }

                state.relationshipMessage?.let {
                    FcMessageBanner(
                        text = friendlyErrorMessage(it, state.language),
                        tone = FcMessageTone.CONFIRMATION,
                        modifier = Modifier.padding(top = Spacing.sm),
                    )
                }
            }
        }
    }
}

@Composable
internal fun PrereleaseScreen(
    viewModel: SettingsViewModel,
    state: SettingsUiState,
    onBack: () -> Unit,
) {
    val strings = stringsFor(state.language)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentVersion = remember(context) { currentAppVersion(context) }
    val release = state.latestPrerelease
    val updateAvailable = release != null && compareVersionNames(comparableReleaseVersion(release), currentVersion) > 0
    val updateProgress = state.downloadProgress
    val downloadedUpdate = state.downloadedPrerelease
    val releaseFileName = releaseDownloadFileName(release)
    val updateAlreadyDownloaded = downloadedUpdate != null && downloadedUpdate.name == releaseFileName

    LaunchedEffect(Unit) {
        viewModel.checkForPrerelease()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { CenteredAppTopBar(title = strings.prereleaseCenter, onBack = onBack) }
    ) { padding ->
        // Same treatment as About: version facts are rows, and there is one
        // action reflecting the stage of the flow rather than three tonal
        // buttons offering every stage at once.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.xs)
        ) {
            FcSectionLabel(strings.prereleaseBuilds)
            FcListGroup {
                FcListRow(
                    title = strings.appVersion,
                    trailing = {
                        Text(
                            currentVersion,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
                FcRowDivider(inset = false)
                FcListRow(
                    title = strings.latestVersion,
                    subtitle = state.prereleaseStatus ?: when {
                        release == null -> strings.noPrereleaseUploaded
                        updateAvailable -> strings.prereleaseAvailable
                        else -> strings.latestVersionInstalled
                    },
                    trailing = {
                        Text(
                            releaseDisplayVersion(release),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (updateAvailable) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    },
                )
                if (downloadedUpdate != null) {
                    FcRowDivider(inset = false)
                    FcListRow(
                        title = strings.updateDownloaded,
                        subtitle = downloadedUpdate.name,
                    )
                }
            }

            updateProgress?.let { progress ->
                Column(
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    Text(
                        "${strings.downloading} ${progress.label}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LinearProgressIndicator(
                        progress = { progress.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "${formatSize(progress.bytesDone)} / ${formatSize(progress.totalBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            when {
                downloadedUpdate != null -> FcPrimaryButton(
                    text = strings.installUpdateAction,
                    onClick = { openInstaller(context, viewModel, downloadedUpdate) },
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
                )
                updateAvailable -> FcPrimaryButton(
                    text = strings.downloadUpdate,
                    onClick = { scope.launch { downloadLatestPrerelease(context, viewModel) } },
                    enabled = !updateAlreadyDownloaded && !state.isCheckingPrerelease && updateProgress == null,
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
                )
                else -> FcTonalButton(
                    text = if (state.isCheckingPrerelease) strings.checkingUpdate else strings.checkUpdate,
                    onClick = viewModel::checkForPrerelease,
                    enabled = !state.isCheckingPrerelease,
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
                )
            }

            state.error?.let {
                FcMessageBanner(
                    text = friendlyErrorMessage(it, state.language),
                    tone = FcMessageTone.PROBLEM,
                    modifier = Modifier.padding(top = Spacing.sm),
                )
            }
        }
    }
}
