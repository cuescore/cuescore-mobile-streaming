package app.brix.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward

private val BrixShape = RoundedCornerShape(4.dp)

/** Minimum settings-row height. 48 dp is Android's recommended touch-target size;
 *  our rows were about 38 dp, making them hard to tap while moving (audit 17.09).
 *  Padding was reduced from 9 to 6 dp so rows with subtitles do not grow: their
 *  height is determined by the content, not the minimum. */
private val ROW_MIN_HEIGHT = 48.dp
private val BrixRowShape = RoundedCornerShape(8.dp)

internal val BrixButtonShape = RoundedCornerShape(4.dp)

/* The monospaced font remains where it works well: values on the right side of a row,
 * segment labels, the category rail, reports, and codes. Since 20.09, setting names
 * use a regular font—the monospaced font is noticeably slower to read, and settings
 * contain a lot of text (the owner, 20.09). */

@Composable
fun BrixCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        shape = BrixShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) { content() }
    }
}

@Composable
private fun RowDivider() {
    HorizontalDivider(
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

@Composable
fun BrixNavRow(
    title: String,
    subtitle: String? = null,
    value: String? = null,
    divider: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ROW_MIN_HEIGHT)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (value != null) {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(4.dp))
        }
        Icon(
            Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
    }
    if (divider) RowDivider()
}

@Composable
fun BrixToggleRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    divider: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ROW_MIN_HEIGHT)
            // The whole row, not just the switch: the switch is hard to tap while moving,
            // whereas the row is a target spanning the full screen width.
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        BrixToggle(checked = checked, onCheckedChange = onCheckedChange)
    }
    if (divider) RowDivider()
}

/**
 * Settings switch—the system `Switch`, scaled down to 80%.
 *
 * Previously this was a custom 36×20 rectangle with a dot: it did not move when
 * toggled and almost blended into the background when off. Worse, it was not the
 * only one—the channel-priority screen used a real Material Switch, so one menu
 * had two different switches (the owner, 20.09: use the system one). Scale 0.8
 * because the standard 52×32 is too large in landscape; the whole row remains
 * the touch target.
 */
@Composable
fun BrixToggle(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    androidx.compose.material3.Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = Modifier.scale(0.8f),
    )
}

@Composable
fun <T> BrixSegmentRow(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    subtitle: String? = null,
    divider: Boolean = true,
    onSelected: (T) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 11.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .clip(BrixRowShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            options.forEach { (value, label) ->
                val isSelected = selected == value
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                Color.Transparent
                            },
                        )
                        .clickable { onSelected(value) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
        }
    }
    if (divider) RowDivider()
}

/**
 * Compact dialog instead of `AlertDialog`.
 *
 * The Material dialog has 24 dp of padding on all sides, plus its own spacing
 * between the title, text, and buttons. On a phone held horizontally during a
 * live stream, this consumes almost all available height: two lines of content
 * in a full-screen window (the owner, 15.09: “huge padding, unclear what it's
 * even for”). Here the padding is 16 dp, spacing is 8, and width is constrained—
 * the frame hugs the content, not the other way around.
 */
@Composable
fun BrixDialog(
    title: String,
    onDismiss: () -> Unit,
    confirmLabel: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    dismissLabel: String? = null,
    /** Destructive action—the red confirmation button. */
    destructive: Boolean = false,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = BrixShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = modifier.widthIn(max = 360.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                Text(text = title, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(6.dp))
                content()
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    // TextButton has its own 40 dp minimum height and 24 dp side padding—
                    // for two words, that takes up half the dialog. Shrink it to a size
                    // that is still easy to tap.
                    val tight = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    if (dismissLabel != null) {
                        androidx.compose.material3.TextButton(
                            shape = BrixButtonShape,
                            onClick = onDismiss,
                            contentPadding = tight,
                            modifier = Modifier.heightIn(min = 34.dp),
                        ) { Text(dismissLabel) }
                        Spacer(Modifier.width(2.dp))
                    }
                    androidx.compose.material3.TextButton(
                        shape = BrixButtonShape,
                        onClick = onConfirm,
                        contentPadding = tight,
                        modifier = Modifier.heightIn(min = 34.dp),
                    ) {
                        Text(
                            text = confirmLabel,
                            color = if (destructive) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                        )
                    }
                }
            }
        }
    }
}
