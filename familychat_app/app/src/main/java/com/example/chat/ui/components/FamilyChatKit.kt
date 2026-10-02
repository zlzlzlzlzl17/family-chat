package com.example.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.chat.ui.theme.FullShape
import com.example.chat.ui.theme.Spacing

/**
 * The Family Chat component kit.
 *
 * The app's screens felt chaotic because the same job was solved several
 * different ways: two row components in settings and a third in the conversation
 * list, three dialog chassis across 32 dialogs, and no rule at all separating
 * Button (79 uses) from FilledTonalButton (24). Nothing lined up with anything
 * else because nothing was the same thing twice.
 *
 * These are the primitives every screen is rebuilt on. If a screen needs
 * something that is not here, that is a conversation about adding it here -
 * not a reason to hand-roll one more variant.
 */

// ---------------------------------------------------------------------------
// Buttons
//
// The intent rule, which previously did not exist:
//   Primary   the one action this screen exists for. At most one per screen.
//   Tonal     secondary but common - additive, not committal.
//   Text      dismissive or navigational. Never the confirming action.
//   Danger    destructive and irreversible.
//
// All are 48dp tall and pill-shaped, so a row of mixed intents still aligns.
// ---------------------------------------------------------------------------

private val ButtonHeight = 48.dp
private val ButtonPadding = androidx.compose.foundation.layout.PaddingValues(
    horizontal = Spacing.xl,
    vertical = 0.dp,
)

@Composable
fun FcPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = FullShape,
        contentPadding = ButtonPadding,
        modifier = modifier.defaultMinSize(minHeight = ButtonHeight),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

/**
 * The optional [leading] slot is a slot rather than an ImageVector so callers
 * can animate or tint the glyph themselves - the blog's like button pulses its
 * heart - without the kit growing a parameter per visual property.
 */
@Composable
fun FcTonalButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leading: @Composable (() -> Unit)? = null,
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        shape = FullShape,
        contentPadding = ButtonPadding,
        modifier = modifier.defaultMinSize(minHeight = ButtonHeight),
    ) { FcButtonContent(text, leading) }
}

@Composable
fun FcTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leading: @Composable (() -> Unit)? = null,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        shape = FullShape,
        modifier = modifier.defaultMinSize(minHeight = ButtonHeight),
    ) { FcButtonContent(text, leading) }
}

@Composable
private fun FcButtonContent(text: String, leading: (@Composable () -> Unit)?) {
    if (leading == null) {
        Text(text, style = MaterialTheme.typography.labelLarge)
        return
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        leading()
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun FcDangerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = FullShape,
        contentPadding = ButtonPadding,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
        ),
        modifier = modifier.defaultMinSize(minHeight = ButtonHeight),
    ) { Text(text, style = MaterialTheme.typography.labelLarge) }
}

// ---------------------------------------------------------------------------
// List rows
// ---------------------------------------------------------------------------

/**
 * A tinted tile holding a row's leading icon.
 *
 * A bare 24dp glyph floating at the start of a row reads as decoration; the same
 * glyph on a soft container reads as the row's subject. It also gives the
 * destructive rows somewhere to carry their colour without shouting.
 */
@Composable
fun FcIconTile(
    destructive: Boolean = false,
    icon: @Composable () -> Unit,
) {
    val container = if (destructive) {
        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
    } else {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
    }
    val content = if (destructive) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primary
    }
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(MaterialTheme.shapes.small)
            .background(container),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides content) { icon() }
    }
}

/**
 * The account block at the top of Settings: who you are signed in as.
 *
 * Settings previously opened straight into a list of destinations, which is fine
 * for a utility but odd for an app whose whole subject is people. This anchors
 * the screen to the person using it.
 */
@Composable
fun FcProfileBlock(
    name: String,
    detail: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    avatar: @Composable () -> Unit,
) {
    val clickable = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        modifier = modifier.fillMaxWidth().then(clickable),
    ) {
        Row(
            modifier = Modifier.padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            avatar()
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * A section heading above an [FcListGroup].
 *
 * Sentence-cased and coloured with the accent rather than uppercased and grey,
 * so it reads as a label for what follows rather than as system chrome.
 */
@Composable
fun FcSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = Spacing.lg, end = Spacing.lg, top = Spacing.md, bottom = Spacing.xs),
    )
}

/**
 * The card that rows sit inside. Rows supplied to [content] are separated
 * automatically, so callers never place their own dividers and cannot disagree
 * about the inset.
 */
@Composable
fun FcListGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(content = content)
    }
}

/**
 * Divider between rows inside an [FcListGroup]. Inset so the rule aligns with
 * the text column instead of cutting under the leading icon.
 */
@Composable
fun FcRowDivider(inset: Boolean = true) {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        modifier = Modifier.padding(start = if (inset) 52.dp else 0.dp),
    )
}

/**
 * The one list row.
 *
 * Replaces SettingsEntryCard, ManagementRow and the ad-hoc rows scattered
 * through the management screens. Minimum height is 56dp so every row is a
 * comfortable touch target even without a subtitle.
 */
@Composable
fun FcListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    destructive: Boolean = false,
    enabled: Boolean = true,
    // Rows normally supply their own edge inset. Containers that already pad
    // their children pass 0 here so the two do not stack to 32dp.
    horizontalPadding: androidx.compose.ui.unit.Dp = Spacing.lg,
    onClick: (() -> Unit)? = null,
) {
    val titleColor = when {
        destructive -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    val clickable = if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier

    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(clickable)
            .heightIn(min = 56.dp)
            .padding(horizontal = horizontalPadding, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        if (leading != null) {
            // Deliberately unconstrained: the slot holds either a bare 24dp icon
            // or a 38dp FcIconTile, and a fixed box would clip the latter.
            Box(contentAlignment = Alignment.Center) { leading() }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.invoke()
    }
}

/**
 * A row that represents one choice in a group of mutually exclusive options.
 *
 * Replaces the horizontally scrolling chip rows the settings screens used for
 * language and theme. Chips put the options in a scroller where some are off
 * screen; rows show every choice at once, give each a full-width touch target,
 * and mark the active one with a checkmark rather than a fill the user has to
 * decode.
 */
@Composable
fun FcChoiceRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    FcListRow(
        title = title,
        subtitle = subtitle,
        modifier = modifier,
        onClick = onClick,
        trailing = {
            if (selected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        },
    )
}

/** A row whose trailing control is a switch. The whole row toggles it. */
@Composable
fun FcSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    FcListRow(
        title = title,
        subtitle = subtitle,
        modifier = modifier,
        onClick = { onCheckedChange(!checked) },
        trailing = {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        },
    )
}

/**
 * A small status pill for the trailing slot - "Owner", "Admin", "This device".
 * Uses the tonal container roles so it never competes with a primary action.
 */
@Composable
fun FcStatusPill(
    text: String,
    modifier: Modifier = Modifier,
    emphasis: FcPillEmphasis = FcPillEmphasis.NEUTRAL,
) {
    val container = when (emphasis) {
        FcPillEmphasis.PRIMARY -> MaterialTheme.colorScheme.primaryContainer
        FcPillEmphasis.INFO -> MaterialTheme.colorScheme.tertiaryContainer
        FcPillEmphasis.NEUTRAL -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val content = when (emphasis) {
        FcPillEmphasis.PRIMARY -> MaterialTheme.colorScheme.onPrimaryContainer
        FcPillEmphasis.INFO -> MaterialTheme.colorScheme.onTertiaryContainer
        FcPillEmphasis.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = content,
        modifier = modifier
            .clip(FullShape)
            .background(container)
            .padding(horizontal = Spacing.md, vertical = Spacing.xs),
    )
}

enum class FcPillEmphasis { PRIMARY, INFO, NEUTRAL }

// ---------------------------------------------------------------------------
// Dialogs
// ---------------------------------------------------------------------------

/**
 * The one dialog chassis.
 *
 * The app had three - AlertDialog, AnimatedActionDialog and raw Dialog - across
 * 32 dialogs, which meant three sets of padding, corner radius, button
 * placement and animation. This is the promoted version of AnimatedActionDialog,
 * which was already the closest to right.
 *
 * [destructive] styles the confirming action as danger, so "Delete group" stops
 * looking identical to "Save".
 */
@Composable
fun FcDialog(
    title: String,
    confirmText: String,
    onConfirm: () -> Unit,
    dismissText: String,
    onDismissRequest: () -> Unit,
    destructive: Boolean = false,
    confirmEnabled: Boolean = true,
    body: @Composable ColumnScope.() -> Unit,
) {
    AnimatedDialogContainer(onDismissRequest = onDismissRequest) { dismiss ->
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.xl),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 6.dp,
        ) {
            Column(
                Modifier.padding(Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.lg),
            ) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                body()
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.End),
                ) {
                    FcTextButton(dismissText, onClick = dismiss)
                    if (destructive) {
                        FcDangerButton(
                            confirmText,
                            onClick = { onConfirm(); dismiss() },
                            enabled = confirmEnabled,
                        )
                    } else {
                        FcPrimaryButton(
                            confirmText,
                            onClick = { onConfirm(); dismiss() },
                            enabled = confirmEnabled,
                        )
                    }
                }
            }
        }
    }
}

/** What a message is telling the user, which decides how it is presented. */
/**
 * PROBLEM      something went wrong and the user may need to act.
 * CONFIRMATION something the user asked for succeeded.
 * NOTICE       standing context that is neither - a scope note, a limit, a
 *              "here is what this screen will do". Carrying these in the
 *              confirmation colour claimed success for a message that was not
 *              reporting an outcome at all.
 */
enum class FcMessageTone { PROBLEM, CONFIRMATION, NOTICE }

/**
 * A message about the whole screen.
 *
 * Errors and confirmations were previously bare `Text` in the accent or error
 * colour, dropped inline into a scrolling column. With no container they read as
 * stray content rather than as the app speaking, and a red sentence floating
 * between two cards looks like a rendering fault.
 *
 * This is the login screen's error banner promoted into the kit: it was the only
 * place in the app that presented a message properly, and there was no reason
 * for that to be true of only one screen.
 *
 * Use for messages that concern the screen. A message that belongs to one row
 * belongs in that row's subtitle instead - see the update status rows in About.
 */
@Composable
fun FcMessageBanner(
    text: String,
    modifier: Modifier = Modifier,
    tone: FcMessageTone = FcMessageTone.PROBLEM,
    onDismiss: (() -> Unit)? = null,
) {
    val container = when (tone) {
        FcMessageTone.PROBLEM -> MaterialTheme.colorScheme.errorContainer
        FcMessageTone.CONFIRMATION -> MaterialTheme.colorScheme.primaryContainer
        FcMessageTone.NOTICE -> MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val content = when (tone) {
        FcMessageTone.PROBLEM -> MaterialTheme.colorScheme.onErrorContainer
        FcMessageTone.CONFIRMATION -> MaterialTheme.colorScheme.onPrimaryContainer
        FcMessageTone.NOTICE -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = container,
        contentColor = content,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            Icon(
                imageVector = when (tone) {
                    FcMessageTone.PROBLEM -> Icons.Default.Info
                    FcMessageTone.CONFIRMATION -> Icons.Default.Check
                    FcMessageTone.NOTICE -> Icons.Default.Info
                },
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            if (onDismiss != null) {
                Text(
                    "✕",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier
                        .clip(FullShape)
                        .clickable(onClick = onDismiss)
                        .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                )
            }
        }
    }
}

/** Supporting text inside an [FcDialog] body. */
@Composable
fun FcDialogText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Row of actions for the trailing slot of a list row. */
@Composable
fun FcRowActions(content: @Composable RowScope.() -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

// ---------------------------------------------------------------------------
// Cards
// ---------------------------------------------------------------------------

/**
 * A block of free-form content on the page background.
 *
 * [FcListGroup] is for rows the kit lays out; this is for content the caller
 * composes itself - a blog post, a media block, anything with its own internal
 * shape. Same surface and same radius as the list group, so the two read as one
 * family rather than as two card systems.
 *
 * Content is *not* padded automatically: cards routinely need edge-to-edge
 * children (a photo bleeding to the corners) beside inset ones, so callers pad
 * the parts that need it.
 */
@Composable
fun FcCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Column(content = content)
    }
}
