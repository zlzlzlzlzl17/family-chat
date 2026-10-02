package com.example.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Comment
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.chat.ui.theme.MotionMedium
import com.example.chat.ui.theme.MotionShort
import com.example.chat.ui.theme.Spacing
import com.example.chat.ui.theme.arrivalSpring
import com.example.chat.ui.theme.placementSpring
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Blog: the app's second top-level section.
 *
 * The section bar and the accent header are the same ones Chats uses - both are
 * shell chrome, not screen chrome - so switching sections moves only the
 * content. Everything below is built from the component kit for the same
 * reason: a feed that invented its own card, dialog and button set was the
 * thing that made the blog read as a different product bolted on the side.
 */

private const val MaxMediaItems = 9

// Media geometry. These are the post's numbers, not the file's: the point is
// that two posts with the same number of attachments occupy the same space
// whatever shape the originals happen to be.
//
// The clamp is 4:5 to 1.91:1 - the portrait and landscape limits used by most
// feeds, and wide enough that almost nothing is cropped hard. A 9:16 phone
// photo is the case that matters: unclamped it is 1.78x as tall as the card is
// wide, which is more than a screen.
private const val BlogPortraitRatio = 0.8f
private const val BlogLandscapeRatio = 1.91f
private val BlogSingleMaxHeight = 340.dp
private val BlogPairHeight = 220.dp
private val BlogGridHeight = 300.dp
private val BlogMediaGap = 3.dp

private sealed class BlogMediaLoadState<out T> {
    data object Loading : BlogMediaLoadState<Nothing>()
    data class Ready<T>(val value: T) : BlogMediaLoadState<T>()
    data class Failed(val reason: String) : BlogMediaLoadState<Nothing>()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BlogFeedScreen(
    viewModel: BlogViewModel,
    state: BlogUiState,
    strings: AppStrings,
    language: AppLanguage,
    onCompose: () -> Unit,
    onOpenPost: (Long) -> Unit,
    onOpenMedia: (Long, Int) -> Unit,
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppTopBar(
                title = strings.blog.title,
                filled = true,
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(Icons.Default.Refresh, contentDescription = strings.refresh)
                    }
                    IconButton(onClick = onCompose) {
                        Icon(Icons.Default.Add, contentDescription = strings.blog.newPost)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(padding)
                // The section bar is drawn over the NavHost, not laid out above
                // it, so this screen reserves the bar's height itself.
                .padding(bottom = FamilySectionBarHeight)
        ) {
            AnimatedVisibility(
                visible = state.isRefreshing,
                enter = fadeIn(tween(MotionShort)),
                exit = fadeOut(tween(MotionShort)),
            ) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            BlogErrorBanner(state, viewModel, language)
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = Spacing.md, vertical = Spacing.sm),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                if (state.posts.isEmpty() && !state.isRefreshing) {
                    item {
                        BlogEmptyState(strings, onCompose, Modifier.fillParentMaxHeight())
                    }
                }
                items(state.posts, key = { it.id }) { post ->
                    BlogPostCard(
                        post = post,
                        state = state,
                        viewModel = viewModel,
                        strings = strings,
                        language = language,
                        onOpenPost = onOpenPost,
                        onOpenMedia = { index -> onOpenMedia(post.id, index) },
                        modifier = Modifier.animateItem(
                            fadeInSpec = arrivalSpring(),
                            placementSpec = placementSpring(),
                            fadeOutSpec = arrivalSpring(),
                        ),
                    )
                }
                if (state.nextCursor.isNotBlank()) {
                    item {
                        FcTextButton(
                            text = strings.blog.loadOlder,
                            onClick = viewModel::loadOlder,
                            enabled = !state.isLoadingOlder,
                            modifier = Modifier.fillMaxWidth(),
                            leading = if (!state.isLoadingOlder) null else ({
                                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            }),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Errors are dismissible here. The banner previously had no way out at all:
 * once a refresh failed it sat over the feed for the rest of the session, even
 * though the very next refresh had succeeded.
 */
@Composable
private fun BlogErrorBanner(
    state: BlogUiState,
    viewModel: BlogViewModel,
    language: AppLanguage,
) {
    AnimatedVisibility(
        visible = state.error != null,
        enter = fadeIn(tween(MotionMedium, easing = FastOutSlowInEasing)),
        exit = fadeOut(tween(MotionShort)),
    ) {
        val message = remember(state.error, language) {
            state.error?.let { friendlyErrorMessage(it, language) }.orEmpty()
        }
        FcMessageBanner(
            text = message,
            tone = FcMessageTone.PROBLEM,
            onDismiss = viewModel::clearError,
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xs),
        )
    }
}

@Composable
private fun BlogEmptyState(
    strings: AppStrings,
    onCompose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        FcIconTile { Icon(Icons.Default.Article, contentDescription = null) }
        Spacer(Modifier.height(Spacing.md))
        Text(strings.blog.emptyTitle, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(Spacing.xs))
        Text(
            strings.blog.emptyBody,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.lg))
        FcPrimaryButton(text = strings.blog.newPost, onClick = onCompose)
    }
}

@Composable
private fun BlogPostCard(
    post: BlogPost,
    state: BlogUiState,
    viewModel: BlogViewModel,
    strings: AppStrings,
    language: AppLanguage,
    onOpenPost: (Long) -> Unit,
    onOpenMedia: (Int) -> Unit,
    modifier: Modifier = Modifier,
    cardClickable: Boolean = true,
    onDeleted: () -> Unit = {},
) {
    var confirmDelete by remember(post.id) { mutableStateOf(false) }
    FcCard(
        modifier = modifier,
        onClick = if (cardClickable) ({ onOpenPost(post.id) }) else null,
    ) {
        Column(
            Modifier.padding(vertical = Spacing.md),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AvatarBubble(post.author.avatarUrl, post.author.username, post.author.color, 40.dp, state.serverUrl)
                Spacer(Modifier.width(Spacing.md))
                Column(Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        Text(post.author.username, style = MaterialTheme.typography.titleSmall)
                        // A post from the app itself should not be mistaken for
                        // one from a family member - the name alone does not
                        // carry that, since a name is just a name.
                        if (post.isAnnouncement) {
                            FcStatusPill(text = strings.blog.announcement, emphasis = FcPillEmphasis.PRIMARY)
                        }
                    }
                    Text(
                        formatTimelineLabel(post.createdAt, language, strings),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (post.author.userCode == state.currentUserCode) {
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Default.Delete, contentDescription = strings.blog.deletePost)
                    }
                }
            }
            if (post.body.isNotBlank()) {
                Text(
                    post.body,
                    modifier = Modifier.padding(horizontal = Spacing.md),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            BlogMediaBlock(post.media, viewModel, strings, onOpenMedia)
            Row(
                Modifier.padding(horizontal = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                BlogLikeButton(post, viewModel, strings)
                FcTextButton(
                    text = post.commentCount.toString(),
                    onClick = { onOpenPost(post.id) },
                    leading = { Icon(Icons.Default.Comment, contentDescription = strings.blog.comments) },
                )
            }
        }
    }
    if (confirmDelete) {
        AnimatedActionDialog(
            title = strings.blog.deletePost,
            body = { FcDialogText(strings.blog.deletePostConfirm) },
            confirmText = strings.delete,
            dismissText = strings.cancel,
            destructive = true,
            onConfirm = {
                confirmDelete = false
                viewModel.deletePost(post.id, onDeleted)
            },
            onDismissRequest = { confirmDelete = false },
        )
    }
}

/**
 * The like has to acknowledge the tap. Swapping the glyph with no transition
 * read as a redraw rather than as a response, which is most of why the feed
 * felt inert next to the rest of the app.
 */
@Composable
private fun BlogLikeButton(
    post: BlogPost,
    viewModel: BlogViewModel,
    strings: AppStrings,
) {
    val scale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    val tint by animateColorAsState(
        targetValue = if (post.likedByMe) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = tween(MotionShort, easing = FastOutSlowInEasing),
        label = "blogLikeTint",
    )
    FcTextButton(
        text = post.likeCount.toString(),
        onClick = {
            viewModel.toggleLike(post.id)
            scope.launch {
                scale.animateTo(1.25f, tween(MotionShort / 2, easing = FastOutSlowInEasing))
                scale.animateTo(1f, tween(MotionShort, easing = FastOutSlowInEasing))
            }
        },
        leading = {
            Icon(
                imageVector = if (post.likedByMe) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                contentDescription = if (post.likedByMe) strings.blog.unlike else strings.blog.like,
                tint = tint,
                modifier = Modifier.scale(scale.value),
            )
        },
    )
}

/**
 * The media block.
 *
 * Its height is decided by the *post*, never by the source file. Previously the
 * block took the media's own aspect ratio, so a portrait photo produced a frame
 * one and a half times as tall as the card was wide - a single 9:16 phone
 * snapshot pushed everything else off the screen and the feed lost any rhythm.
 *
 * A blog feed is scanned, so posts have to be comparable in size. One item gets
 * its own shape within a portrait/landscape clamp and a hard height ceiling;
 * two or more are laid into a fixed-height mosaic, which also shows at a glance
 * how many there are - the horizontal scroller this replaces did not.
 */
@Composable
private fun BlogMediaBlock(
    media: List<BlogMedia>,
    viewModel: BlogViewModel,
    strings: AppStrings,
    onOpenMedia: (Int) -> Unit,
) {
    if (media.isEmpty()) return
    // Only the outer block is rounded. Rounding each tile as well would leave
    // gaps of card colour inside the mosaic and read as scattered chips.
    val block = Modifier
        .fillMaxWidth()
        .padding(horizontal = Spacing.md)
        .clip(MaterialTheme.shapes.medium)
    val gap = Arrangement.spacedBy(BlogMediaGap)
    when (media.size) {
        1 -> {
            val item = media.first()
            val ratio = if (item.width > 0 && item.height > 0) {
                (item.width.toFloat() / item.height).coerceIn(BlogPortraitRatio, BlogLandscapeRatio)
            } else 1f
            BoxWithConstraints(block) {
                val height = (maxWidth / ratio).coerceAtMost(BlogSingleMaxHeight)
                BlogMediaTile(item, viewModel, strings, Modifier.fillMaxWidth().height(height)) {
                    onOpenMedia(0)
                }
            }
        }
        2 -> Row(block.height(BlogPairHeight), horizontalArrangement = gap) {
            media.forEachIndexed { index, item ->
                BlogMediaTile(item, viewModel, strings, Modifier.weight(1f).fillMaxHeight()) {
                    onOpenMedia(index)
                }
            }
        }
        3 -> Row(block.height(BlogPairHeight), horizontalArrangement = gap) {
            BlogMediaTile(media[0], viewModel, strings, Modifier.weight(2f).fillMaxHeight()) {
                onOpenMedia(0)
            }
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = gap) {
                BlogMediaTile(media[1], viewModel, strings, Modifier.fillMaxWidth().weight(1f)) {
                    onOpenMedia(1)
                }
                BlogMediaTile(media[2], viewModel, strings, Modifier.fillMaxWidth().weight(1f)) {
                    onOpenMedia(2)
                }
            }
        }
        else -> Column(block.height(BlogGridHeight), verticalArrangement = gap) {
            for (row in 0 until 2) {
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = gap) {
                    for (column in 0 until 2) {
                        val index = row * 2 + column
                        val isLastTile = index == 3
                        val remaining = media.size - 4
                        BlogMediaTile(
                            media = media[index],
                            viewModel = viewModel,
                            strings = strings,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            overflowCount = if (isLastTile && remaining > 0) remaining else 0,
                            onClick = { onOpenMedia(index) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BlogMediaTile(
    media: BlogMedia,
    viewModel: BlogViewModel,
    strings: AppStrings,
    modifier: Modifier,
    overflowCount: Int = 0,
    onClick: () -> Unit,
) {
    var retryNonce by remember(media.id, media.fileSize) { mutableIntStateOf(0) }
    val frame = modifier
        .background(MaterialTheme.colorScheme.surfaceVariant)
        .clickable(onClick = onClick)
    Box(frame, contentAlignment = Alignment.Center) {
        // Videos show their first frame rather than a grey rectangle. In a
        // four-up mosaic an untextured tile reads as a tile that failed to load,
        // not as a video.
        //
        // Decoding ran synchronously inside composition, at full resolution, on
        // every recomposition. A feed of phone photos janked hard and could
        // exhaust memory outright. It is now sampled to roughly the size it is
        // drawn at, off the main thread.
        val loadState by produceState<BlogMediaLoadState<Bitmap?>>(BlogMediaLoadState.Loading, media.id, media.fileSize, retryNonce) {
            value = runCatching { loadBlogBitmap(viewModel, media, maxEdge = 1280) }
                .fold(
                    onSuccess = { BlogMediaLoadState.Ready(it) },
                    onFailure = { BlogMediaLoadState.Failed(it.message.orEmpty()) },
                )
        }
        when (val result = loadState) {
            is BlogMediaLoadState.Ready -> result.value?.let { shown -> Image(
                bitmap = shown.asImageBitmap(),
                contentDescription = media.originalName,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            ) }
            BlogMediaLoadState.Loading -> if (media.kind != "video") {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
            is BlogMediaLoadState.Failed -> BlogMediaRetry(strings, onRetry = { retryNonce += 1 })
        }
        if (media.kind == "video") {
            BlogMediaBadge(Icons.Default.PlayArrow, strings.blog.video)
        }
        if (overflowCount > 0) {
            Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "+$overflowCount",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                )
            }
        }
    }
}

@Composable
private fun BlogMediaBadge(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f)) {
        Icon(
            icon,
            contentDescription = description,
            tint = MaterialTheme.colorScheme.inverseOnSurface,
            modifier = Modifier.padding(Spacing.md).size(28.dp),
        )
    }
}

@Composable
private fun BlogMediaRetry(
    strings: AppStrings,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onRetry: () -> Unit,
) {
    IconButton(onClick = onRetry) {
        Icon(Icons.Default.Refresh, contentDescription = strings.refresh, tint = tint)
    }
}

private suspend fun loadBlogBitmap(
    viewModel: BlogViewModel,
    media: BlogMedia,
    maxEdge: Int,
): Bitmap? = withContext(Dispatchers.IO) {
    var file = viewModel.cachedMedia(media)
    var bitmap = if (media.kind == "video") videoPoster(file) else decodeSampled(file, maxEdge)
    if (media.kind != "video" && bitmap == null) {
        file = viewModel.cachedMedia(media, forceRefresh = true)
        bitmap = decodeSampled(file, maxEdge)
        check(bitmap != null) { "blog_media_decode_failed" }
    }
    bitmap
}

/** First readable frame of a video, used as its poster in the feed. */
private fun videoPoster(file: File): Bitmap? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(file.absolutePath)
        retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
    } catch (error: Exception) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}

/** Decode scaled down to roughly [maxEdge], so a 12MP photo is not held whole. */
private fun decodeSampled(file: File, maxEdge: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    var sample = 1
    while (longest / sample > maxEdge) sample *= 2
    return BitmapFactory.decodeFile(
        file.absolutePath,
        BitmapFactory.Options().apply { inSampleSize = sample },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BlogComposeScreen(
    viewModel: BlogViewModel,
    state: BlogUiState,
    strings: AppStrings,
    language: AppLanguage,
    onBack: () -> Unit,
    onPublished: (Long) -> Unit,
) {
    val context = LocalContext.current
    var body by remember { mutableStateOf("") }
    var media by remember { mutableStateOf<List<BlogPendingMedia>>(emptyList()) }
    // The picker used to drop the whole selection silently when it contained a
    // second video, and silently truncate past nine. Both now keep what is
    // usable and say what was left behind.
    var pickerNotice by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MaxMediaItems)
    ) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val truncated = uris.size > MaxMediaItems
        val picked = uris.take(MaxMediaItems).map { BlogRepository.pendingMedia(context, it) }
        var seenVideo = false
        var droppedVideo = false
        val kept = picked.filter { item ->
            if (!item.mimeType.startsWith("video/")) return@filter true
            if (seenVideo) {
                droppedVideo = true
                false
            } else {
                seenVideo = true
                true
            }
        }
        media = kept
        pickerNotice = when {
            droppedVideo -> strings.blog.mediaOneVideoOnly
            truncated -> strings.blog.mediaTruncated
            else -> null
        }
    }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            AppTopBar(
                title = strings.blog.newPost,
                onBack = onBack,
                actions = {
                    FcTextButton(
                        text = if (state.isPublishing) strings.blog.publishing else strings.blog.publish,
                        onClick = { viewModel.publish(body, media, onPublished) },
                        enabled = !state.isPublishing && (body.isNotBlank() || media.isNotEmpty()),
                    )
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            // Publishing failures were invisible: the progress bar stopped and
            // the draft stayed put with nothing said. This is the screen the
            // failure belongs to, so it is reported here.
            state.error?.let { error ->
                FcMessageBanner(
                    text = friendlyErrorMessage(error, language),
                    tone = FcMessageTone.PROBLEM,
                    onDismiss = viewModel::clearError,
                )
            }
            pickerNotice?.let { notice ->
                FcMessageBanner(
                    text = notice,
                    tone = FcMessageTone.NOTICE,
                    onDismiss = { pickerNotice = null },
                )
            }
            OutlinedTextField(
                value = body,
                onValueChange = { if (it.length <= 5000) body = it },
                placeholder = { Text(strings.blog.composeHint) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 150.dp),
                shape = MaterialTheme.shapes.medium,
                minLines = 5,
            )
            FcTonalButton(
                text = strings.blog.addMedia,
                onClick = {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                },
                leading = { Icon(Icons.Default.Image, contentDescription = null) },
            )
            Text(
                strings.blog.mediaLimit,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (media.isNotEmpty()) {
                FcListGroup {
                    media.forEachIndexed { index, item ->
                        if (index > 0) FcRowDivider()
                        FcListRow(
                            title = item.displayName,
                            leading = {
                                Icon(
                                    if (item.mimeType.startsWith("video/")) Icons.Default.PlayArrow else Icons.Default.Image,
                                    contentDescription = null,
                                )
                            },
                            trailing = {
                                IconButton(onClick = { media = media.toMutableList().also { it.removeAt(index) } }) {
                                    Icon(Icons.Default.Delete, contentDescription = strings.delete)
                                }
                            },
                        )
                    }
                }
            }
            AnimatedVisibility(
                visible = state.isPublishing,
                enter = fadeIn(tween(MotionShort)),
                exit = fadeOut(tween(MotionShort)),
            ) {
                LinearProgressIndicator({ state.uploadProgress }, Modifier.fillMaxWidth())
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BlogPostScreen(
    viewModel: BlogViewModel,
    state: BlogUiState,
    strings: AppStrings,
    language: AppLanguage,
    onBack: () -> Unit,
    onOpenMedia: (Long, Int) -> Unit,
) {
    val post = state.selectedPost
    var comment by remember { mutableStateOf("") }
    var pendingCommentDelete by remember { mutableStateOf<Long?>(null) }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AppTopBar(title = strings.blog.title, onBack = onBack) },
        bottomBar = {
            if (post != null) {
                Surface(
                    modifier = Modifier.navigationBarsPadding().imePadding(),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        OutlinedTextField(
                            value = comment,
                            onValueChange = { if (it.length <= 1000) comment = it },
                            placeholder = { Text(strings.blog.writeComment) },
                            modifier = Modifier.weight(1f),
                            shape = MaterialTheme.shapes.large,
                            maxLines = 3,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = {
                                if (comment.isNotBlank()) {
                                    viewModel.addComment(post.id, comment)
                                    comment = ""
                                }
                            }),
                        )
                        FilledIconButton(onClick = {
                            if (comment.isNotBlank()) {
                                viewModel.addComment(post.id, comment)
                                comment = ""
                            }
                        }) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = strings.blog.sendComment)
                        }
                    }
                }
            }
        },
    ) { padding ->
        if (post == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(padding)
        ) {
            // A comment that failed to send used to vanish with no explanation.
            state.error?.let { error ->
                FcMessageBanner(
                    text = friendlyErrorMessage(error, language),
                    tone = FcMessageTone.PROBLEM,
                    onDismiss = viewModel::clearError,
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xs),
                )
            }
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(Spacing.md),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                item {
                    BlogPostCard(
                        post = post,
                        state = state,
                        viewModel = viewModel,
                        strings = strings,
                        language = language,
                        onOpenPost = {},
                        onOpenMedia = { index -> onOpenMedia(post.id, index) },
                        cardClickable = false,
                        onDeleted = onBack,
                    )
                }
                item { FcSectionLabel(strings.blog.comments) }
                items(state.comments, key = { it.id }) { item ->
                    FcCard(
                        modifier = Modifier.animateItem(
                            fadeInSpec = arrivalSpring(),
                            placementSpec = placementSpring(),
                            fadeOutSpec = arrivalSpring(),
                        )
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(Spacing.md),
                            verticalAlignment = Alignment.Top,
                        ) {
                            AvatarBubble(item.author.avatarUrl, item.author.username, item.author.color, 32.dp, state.serverUrl)
                            Spacer(Modifier.width(Spacing.sm))
                            Column(Modifier.weight(1f)) {
                                Text(item.author.username, style = MaterialTheme.typography.labelLarge)
                                Text(item.body, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    formatTimelineLabel(item.createdAt, language, strings),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (item.author.userCode == state.currentUserCode) {
                                IconButton(onClick = { pendingCommentDelete = item.id }) {
                                    Icon(Icons.Default.Delete, contentDescription = strings.blog.deleteComment)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    // Deleting a comment used to happen on the first tap with no confirmation,
    // while deleting a post asked. Both are irreversible; both now ask.
    pendingCommentDelete?.let { commentId ->
        AnimatedActionDialog(
            title = strings.blog.deleteComment,
            body = { FcDialogText(strings.blog.deleteCommentConfirm) },
            confirmText = strings.delete,
            dismissText = strings.cancel,
            destructive = true,
            onConfirm = {
                pendingCommentDelete = null
                viewModel.deleteComment(commentId)
            },
            onDismissRequest = { pendingCommentDelete = null },
        )
    }
}

/**
 * Full-screen media, opened by tapping a tile.
 *
 * Tapping a photo or a video in a post did nothing at all - the media was
 * visible but not openable, so a portrait photo could only ever be seen through
 * the crop the feed gave it. Videos were worse: the feed drew a play badge that
 * was not a control.
 *
 * Deliberately built to match the chat image viewer rather than to be clever
 * about it: black field, pinch to zoom, tap to dismiss, a gradient rather than a
 * hard scrim behind the chrome. Opening a photo should feel the same wherever
 * the photo came from. Swiping moves through the post's other attachments,
 * which is the part the chat viewer has no need for.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BlogMediaViewerScreen(
    viewModel: BlogViewModel,
    post: BlogPost?,
    startIndex: Int,
    strings: AppStrings,
    language: AppLanguage,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    if (post == null || post.media.isEmpty()) {
        Box(
            Modifier.fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center,
        ) { CircularProgressIndicator(color = Color.White) }
        return
    }
    val pagerState = rememberPagerState(
        initialPage = startIndex.coerceIn(0, post.media.lastIndex),
        pageCount = { post.media.size },
    )
    // Once a photo is zoomed, dragging has to pan it rather than turn the page -
    // otherwise the gesture that inspects a detail is the same gesture that
    // throws the photo away.
    var zoomed by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            userScrollEnabled = !zoomed,
        ) { page ->
            val item = post.media[page]
            if (item.kind == "video") {
                BlogVideoPlayer(viewModel, item, strings, Modifier.fillMaxSize())
            } else {
                BlogZoomableImage(
                    media = item,
                    viewModel = viewModel,
                    strings = strings,
                    onZoomChanged = { zoomed = it },
                    onDismiss = onBack,
                )
            }
        }
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.62f), Color.Transparent))
                )
                .statusBarsPadding()
                .padding(horizontal = Spacing.sm, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = strings.cancel,
                    tint = Color.White,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    post.author.username,
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White,
                )
                Text(
                    formatTimelineLabel(post.createdAt, language, strings),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.75f),
                )
            }
            if (post.media.size > 1) {
                Text(
                    "${pagerState.currentPage + 1} / ${post.media.size}",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                    modifier = Modifier.padding(end = Spacing.sm),
                )
            }
        }
    }
}

@Composable
private fun BlogZoomableImage(
    media: BlogMedia,
    viewModel: BlogViewModel,
    strings: AppStrings,
    onZoomChanged: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    // Decoded larger than a feed tile - this is the screen where detail is the
    // point - but still sampled, because the originals are phone-camera sized.
    var retryNonce by remember(media.id, media.fileSize) { mutableIntStateOf(0) }
    val loadState by produceState<BlogMediaLoadState<Bitmap?>>(BlogMediaLoadState.Loading, media.id, media.fileSize, retryNonce) {
        value = runCatching { loadBlogBitmap(viewModel, media, maxEdge = 2560) }
            .fold(
                onSuccess = { BlogMediaLoadState.Ready(it) },
                onFailure = { BlogMediaLoadState.Failed(it.message.orEmpty()) },
            )
    }
    var scale by remember(media.id) { mutableFloatStateOf(1f) }
    var offset by remember(media.id) { mutableStateOf(Offset.Zero) }
    val shown = when (val result = loadState) {
        BlogMediaLoadState.Loading -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White)
            }
            return
        }
        is BlogMediaLoadState.Failed -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                BlogMediaRetry(strings, tint = Color.White, onRetry = { retryNonce += 1 })
            }
            return
        }
        is BlogMediaLoadState.Ready -> result.value ?: return
    }
    Image(
        bitmap = shown.asImageBitmap(),
        contentDescription = media.originalName.ifBlank { strings.image },
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            }
            .pointerInput(media.id) {
                detectTapGestures(onTap = {
                    // Zoomed in, a tap resets rather than leaves - otherwise the
                    // only way out of a zoom is to guess at the back gesture.
                    if (scale > 1.02f) {
                        scale = 1f
                        offset = Offset.Zero
                        onZoomChanged(false)
                    } else {
                        onDismiss()
                    }
                })
            }
            .pointerInput(media.id) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val next = (scale * zoom).coerceIn(1f, 4f)
                    scale = next
                    offset = if (next <= 1f) Offset.Zero else offset + pan * 1.75f
                    onZoomChanged(next > 1.02f)
                }
            },
    )
}

/**
 * Playback with the app's own transport rather than the platform's.
 *
 * The stock [MediaController] draws a grey system scrubber that takes no part
 * in the theme - in a rounded, accented card it looked like a different app had
 * been embedded. Tapping the frame plays and pauses; the badge matches the one
 * on unplayed thumbnails, so the same control means the same thing in the feed
 * and on the post.
 */
@Composable
internal fun BlogVideoPlayer(
    viewModel: BlogViewModel,
    media: BlogMedia,
    strings: AppStrings,
    modifier: Modifier = Modifier,
) {
    var retryNonce by remember(media.id, media.fileSize) { mutableIntStateOf(0) }
    val loadState by produceState<BlogMediaLoadState<File>>(BlogMediaLoadState.Loading, media.id, media.fileSize, retryNonce) {
        value = runCatching { viewModel.cachedMedia(media) }
            .fold(
                onSuccess = { BlogMediaLoadState.Ready(it) },
                onFailure = { BlogMediaLoadState.Failed(it.message.orEmpty()) },
            )
    }
    val loaded = when (val result = loadState) {
        BlogMediaLoadState.Loading -> {
            Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return
        }
        is BlogMediaLoadState.Failed -> {
            Box(modifier, contentAlignment = Alignment.Center) {
                BlogMediaRetry(strings, onRetry = { retryNonce += 1 })
            }
            return
        }
        is BlogMediaLoadState.Ready -> result.value
    }
    var player by remember(loaded.absolutePath) { mutableStateOf<VideoView?>(null) }
    var playing by remember(loaded.absolutePath) { mutableStateOf(false) }
    Box(modifier, contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { viewContext ->
                VideoView(viewContext).apply {
                    setVideoURI(Uri.fromFile(loaded))
                    setOnCompletionListener { playing = false }
                    player = this
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .clickable {
                    if (playing) player?.pause() else player?.start()
                    playing = !playing
                },
        )
        AnimatedVisibility(
            visible = !playing,
            enter = fadeIn(tween(MotionShort)),
            exit = fadeOut(tween(MotionShort)),
        ) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f)) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = strings.blog.video,
                    tint = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier.padding(Spacing.md).size(32.dp),
                )
            }
        }
    }
}
