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
import com.example.chat.ui.theme.Spacing
import com.example.chat.ui.theme.ChatTheme
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

@Composable
internal fun SessionRestoreScreen(state: AuthUiState) {
    val strings = stringsFor(state.language)
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = Spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                strings.familyChat,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(20.dp))
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                strokeWidth = 2.5.dp,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                strings.syncing,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun LoginScreen(viewModel: AuthViewModel, state: AuthUiState) {
    val strings = stringsFor(state.language)
    var registerMode by rememberSaveable { mutableStateOf(false) }
    var loginId by rememberSaveable(state.registrationUserCode) {
        mutableStateOf(state.registrationUserCode)
    }
    var registrationUsername by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var confirmPassword by rememberSaveable { mutableStateOf("") }
    val passwordFocusRequester = remember { FocusRequester() }
    val confirmPasswordFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val errorMessage = state.error?.let { friendlyErrorMessage(it, state.language) }
    LaunchedEffect(state.error) {
        if (state.error != null) {
            delay(4_500L)
            viewModel.clearError()
        }
    }
    fun submit() {
        keyboardController?.hide()
        if (registerMode) {
            viewModel.requestRegistration(registrationUsername.trim(), password, confirmPassword)
        } else {
            viewModel.login(loginId.trim(), password)
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(padding)
                .imePadding()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            // No card. Card containers were drawn on `surface`, which is the same
            // colour as `background`, so the panel never read as a panel - it was
            // an invisible box costing 24dp of inset on every side.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.lg)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                    LanguageSelector(state.language, viewModel::updateLanguage, strings.language)
                    if (state.deviceApprovalPending) {
                        Text(
                            if (state.language == AppLanguage.ZH) "等待设备批准" else "Device approval required",
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            if (state.language == AppLanguage.ZH) {
                                "密码验证成功，但这台新设备尚未获得信任。请在另一台可信设备或管理页面批准后再进入聊天。"
                            } else {
                                "Your password was accepted, but this new device is not trusted yet. Approve it from an existing trusted device or the management page."
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "${strings.userId}: ${state.me?.userCode.orEmpty()}\n${strings.deviceId}: ${state.pendingDeviceId}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Button(
                            onClick = viewModel::checkDeviceApprovalNow,
                            enabled = !state.isLoading,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (state.language == AppLanguage.ZH) "检查批准状态" else "Check approval")
                        }
                        TextButton(
                            onClick = viewModel::cancelPendingDeviceLogin,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (state.language == AppLanguage.ZH) "取消登录" else "Cancel login")
                        }
                        return@Column
                    }
                    // Accent hero. The first screen should be recognisably this
                    // app rather than a bare form, and it is the one place the
                    // brand colour can carry real weight without competing with
                    // anything the user has to read or act on.
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(Spacing.xl),
                            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(56.dp)
                                    .clip(MaterialTheme.shapes.medium)
                                    .background(MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.16f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    strings.familyChat.take(1),
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            }
                            Text(
                                if (registerMode) strings.createAccount else strings.familyChat,
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Text(
                                strings.appSubtitle,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.82f)
                            )
                        }
                    }
                    OutlinedTextField(
                        value = if (registerMode) registrationUsername else loginId,
                        onValueChange = {
                            if (registerMode) {
                                registrationUsername = it
                            } else {
                                loginId = it.filter(Char::isDigit).take(8)
                            }
                        },
                        label = { Text(if (registerMode) strings.username else strings.userId) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = if (registerMode) KeyboardType.Text else KeyboardType.Number,
                            imeAction = ImeAction.Next
                        ),
                        keyboardActions = KeyboardActions(
                            onNext = { passwordFocusRequester.requestFocus() }
                        )
                    )
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(strings.password) },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = if (registerMode) ImeAction.Next else ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                submit()
                            },
                            onNext = {
                                confirmPasswordFocusRequester.requestFocus()
                            }
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(passwordFocusRequester),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation()
                    )
                    if (registerMode) {
                        OutlinedTextField(
                            value = confirmPassword,
                            onValueChange = { confirmPassword = it },
                            label = { Text(strings.confirmPassword) },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(onDone = { submit() }),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(confirmPasswordFocusRequester),
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation()
                        )
                    }
                    Button(onClick = { submit() }, enabled = !state.isLoading, modifier = Modifier.fillMaxWidth()) {
                        if (state.isLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Text(if (registerMode) strings.createAccount else strings.login)
                        }
                    }
                    TextButton(
                        onClick = {
                            registerMode = !registerMode
                            viewModel.clearError()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (registerMode) strings.backToLogin else strings.register)
                    }
                    state.registrationMessage?.let {
                        FcMessageBanner(
                            text = friendlyErrorMessage(it, state.language),
                            tone = FcMessageTone.CONFIRMATION,
                        )
                    }
                }
            }
            AnimatedVisibility(
                visible = errorMessage != null,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                enter = fadeIn(tween(160)) + slideInVertically(tween(220)) { it / 2 },
                exit = fadeOut(tween(140)) + slideOutVertically(tween(180)) { it / 2 },
            ) {
                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    tonalElevation = 4.dp,
                    shadowElevation = 2.dp,
                ) {
                    Text(
                        text = errorMessage.orEmpty(),
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

/**
 * Turns a raw error into something a family member can act on.
 *
 * This lived on the login screen and was applied only there, so every other
 * screen showed whatever the exception carried - including "Unable to resolve
 * host ..." and, via reportError, bare Java class names. It is not auth-specific
 * and never was.
 */
internal fun friendlyErrorMessage(rawMessage: String, language: AppLanguage): String {
    val raw = rawMessage.trim()
    val normalized = raw.lowercase()
    val zh = language == AppLanguage.ZH
    return when {
        normalized == "invalid_user_id" ->
            if (zh) "请输入正确的 8 位用户 ID" else "Enter a valid 8-digit user ID"
        normalized == "invalid_credentials" ->
            if (zh) "用户 ID 或密码错误" else "Incorrect user ID or password"
        normalized == "cooldown_active" ->
            if (zh) "尝试次数过多，请约 5 分钟后再试" else "Too many attempts. Try again in about 5 minutes"
        normalized == "account_unavailable" ->
            if (zh) "此账号目前不可用" else "This account is currently unavailable"
        normalized == "device_revoked" ->
            if (zh) "此设备的登录权限已被撤销" else "This device is no longer authorized"
        normalized == "device_id_required" || normalized == "bad_refresh_request" ->
            if (zh) "设备信息无效，请重新启动应用后再试" else "Device information is invalid. Restart the app and try again"
        normalized == "session_expired" || normalized == "unauthorized" ->
            if (zh) "登录已过期，请重新登录" else "Your session expired. Please sign in again"
        normalized == "invalid_registration_data" ->
            if (zh) "注册信息格式不正确" else "The registration details are invalid"
        normalized == "username_taken" ->
            if (zh) "这个用户名已被使用" else "This username is already in use"
        normalized == "request_already_pending" ->
            if (zh) "这个用户名已有待审批的注册申请" else "A registration request for this username is already pending"
        normalized.contains("unable to resolve host") ||
            normalized.contains("failed to connect") ||
            normalized.contains("network is unreachable") ||
            normalized.contains("timeout") ||
            normalized.contains("timed out") ->
            if (zh) "网络连接不可用，请检查网络后重试" else "No network connection. Check your connection and try again"
        normalized == "secure_token_write_failed" ->
            if (zh) "无法安全保存登录信息，请重新启动应用" else "Unable to securely save your login. Restart the app"
        // "login failed" is sign-in specific. "request_failed" is not - it is the
        // generic fallback used by conversation refresh, management actions and
        // the push self-test, so it must not tell someone their sign-in failed.
        normalized == "login failed" ->
            if (zh) "登录失败，请稍后重试" else "Sign-in failed. Please try again"
        normalized == "request_failed" ->
            if (zh) "操作失败，请稍后重试" else "Something went wrong. Please try again"
        // The blog's failures all reached the generic fallback below, which told
        // six different stories with one sentence. The distinction matters here:
        // a failed publish still has the draft on screen and is worth retrying,
        // while a failed load is about the connection.
        normalized == "blog_refresh_failed" || normalized == "blog_comments_failed" ->
            if (zh) "无法加载 Blog，请检查网络后重试" else "Couldn't load the blog. Check your connection and try again"
        normalized == "blog_publish_failed" ->
            if (zh) "帖子未能发布，草稿仍在，请重试" else "Your post wasn't published. The draft is still here - try again"
        normalized == "blog_comment_failed" ->
            if (zh) "评论未能发送，请重试" else "Your comment wasn't sent. Try again"
        normalized == "blog_delete_failed" ->
            if (zh) "无法删除这条帖子，请重试" else "Couldn't delete the post. Try again"
        normalized == "blog_comment_delete_failed" ->
            if (zh) "无法删除这条评论，请重试" else "Couldn't delete the comment. Try again"
        raw.contains('_') || normalized.contains("exception") ->
            if (zh) "操作失败，请稍后重试" else "Something went wrong. Please try again"
        else -> raw
    }
}
