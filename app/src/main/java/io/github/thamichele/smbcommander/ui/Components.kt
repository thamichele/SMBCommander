package io.github.thamichele.smbcommander.ui

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.thamichele.smbcommander.core.RemoteEntry
import io.github.thamichele.smbcommander.data.ThumbnailCache
import io.github.thamichele.smbcommander.ui.theme.Arancio
import io.github.thamichele.smbcommander.ui.theme.ArancioColors
import io.github.thamichele.smbcommander.ui.theme.ArancioShape
import io.github.thamichele.smbcommander.ui.theme.ArancioType
import kotlin.math.roundToInt

/** Room between the back button, the title and the actions of a [PageHeader]. */
private val HEADER_GAP = 4.dp

/**
 * PageHeader() - the header of a page: grey context line over a big title, actions on the right
 * @param context the small grey line over the title (a path, "Album"); empty for none
 * @param title the big title in JetBrains Mono
 * @param navigation the back or close button on the left, or null
 * @param collapse when not null, the title shrinks and fades with the scroll and only the buttons stay
 * @param actions the buttons of the page
 *
 * No text is ever cut, and the header stays short: the title wraps beside the
 * buttons. Only when even its longest word does not fit there (big display
 * size, big font, narrow phone) the title takes the whole width and the
 * buttons move to a line of their own under it, on the right.
 *
 * Context: composable.
 * Return: nothing.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PageHeader(
    context: String,
    title: String,
    navigation: (@Composable () -> Unit)? = null,
    collapse: CollapsingHeaderState? = null,
    actions: @Composable () -> Unit = {},
) {
    val part = rememberCollapsingPart(collapse)
    Layout(
        contents = listOf(
            { navigation?.invoke() },
            {
                Box(Modifier.graphicsLayer { alpha = 1f - (collapse?.fraction ?: 0f) }) { HeaderTitle(context, title) }
            },
            {
                FlowRow(horizontalArrangement = Arrangement.End, itemVerticalAlignment = Alignment.CenterVertically) {
                    actions()
                }
            },
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clipToBounds()
            .padding(start = if (navigation == null) 16.dp else 4.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
    ) { (navMeasurables, titleMeasurables, actionMeasurables), constraints ->
        val width = constraints.maxWidth
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val nav = navMeasurables.firstOrNull()?.measure(loose)
        val navWidth = nav?.let { it.width + HEADER_GAP.roundToPx() } ?: 0
        val actions = actionMeasurables.first().measure(loose)
        val titleMeasurable = titleMeasurables.first()
        val titleRoom = (width - navWidth).coerceAtLeast(0)
        // one line while even the longest word of the title fits beside the buttons: the title wraps there
        val isOneLine = titleMeasurable.minIntrinsicWidth(Constraints.Infinity) + actions.width <= titleRoom
        val titleWidth = if (isOneLine) titleRoom - actions.width else titleRoom
        val titlePlaced = titleMeasurable.measure(loose.copy(maxWidth = titleWidth))
        val navHeight = nav?.height ?: 0
        val fullTop = maxOf(navHeight, titlePlaced.height, if (isOneLine) actions.height else 0)
        // shrunk by the scroll, the title goes and the row of the buttons stays
        val shrunkTop = maxOf(navHeight, if (isOneLine) actions.height else 0)
        collapse?.report(part, fullTop - shrunkTop)
        val fraction = collapse?.fraction ?: 0f
        val topHeight = fullTop - ((fullTop - shrunkTop) * fraction).roundToInt()
        val height = if (isOneLine) topHeight else topHeight + actions.height
        layout(width, height) {
            nav?.placeRelative(0, (topHeight - nav.height) / 2)
            val titleY = if (isOneLine) (topHeight - titlePlaced.height) / 2 else topHeight - titlePlaced.height
            titlePlaced.placeRelative(navWidth, titleY)
            val actionsY = if (isOneLine) (topHeight - actions.height) / 2 else topHeight
            actions.placeRelative(width - actions.width, actionsY)
        }
    }
}

/* Private: the two texts of a PageHeader, both wrapping instead of being cut. */
@Composable
private fun HeaderTitle(context: String, title: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (context.isNotEmpty()) {
            Text(context, style = MaterialTheme.typography.bodySmall, color = Arancio.colors.textSecondary)
        }
        Text(title, style = MaterialTheme.typography.headlineMedium)
    }
}

/**
 * CollapsingHeaderState - how much a header is shrunk by the scroll
 *
 * Give [connection] to Modifier.nestedScroll() on a parent of both the header
 * and the list: scrolling down shrinks the header a pixel at a time down to
 * the row of its buttons, scrolling up brings it back at once, wherever the
 * list is. Each part that can shrink (the title of a [PageHeader], a
 * [CollapsibleBox]) tells the state how many pixels it can give.
 */
@Stable
class CollapsingHeaderState {
    /** 0 = whole header shown, -[range] = shrunk to the buttons. */
    private var offset by mutableFloatStateOf(0f)
    private val parts = mutableStateMapOf<Any, Int>()

    private val range: Int get() = parts.values.sum()

    /** 0 = whole header, 1 = only the buttons left. */
    val fraction: Float get() = if (range == 0) 0f else (-offset / range).coerceIn(0f, 1f)

    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            val before = offset
            offset = (offset + available.y).coerceIn(-range.toFloat(), 0f)
            return Offset(0f, offset - before)
        }
    }

    /**
     * show() - brings the whole header back
     *
     * Return: nothing.
     */
    fun show() {
        offset = 0f
    }

    internal fun report(part: Any, pixels: Int) {
        if (parts[part] != pixels) parts[part] = pixels
    }

    internal fun forget(part: Any) {
        parts.remove(part)
    }
}

/**
 * rememberCollapsingHeaderState() - a [CollapsingHeaderState] kept across recompositions
 *
 * Return: the state.
 */
@Composable
fun rememberCollapsingHeaderState(): CollapsingHeaderState = remember { CollapsingHeaderState() }

/* Private: registers one shrinking part with the state for as long as it is on screen. */
@Composable
private fun rememberCollapsingPart(state: CollapsingHeaderState?): Any {
    val part = remember { Any() }
    if (state != null) {
        DisposableEffect(state, part) { onDispose { state.forget(part) } }
    }
    return part
}

/**
 * CollapsibleBox() - something under a header that disappears with the scroll (the tabs)
 * @param state the state shared with the header and the list
 * @param content what disappears
 *
 * It is only made shorter, clipped and faded: never squeezed.
 *
 * Context: composable.
 * Return: nothing.
 */
@Composable
fun CollapsibleBox(state: CollapsingHeaderState, content: @Composable () -> Unit) {
    val part = rememberCollapsingPart(state)
    Layout(
        content = { Box(Modifier.graphicsLayer { alpha = 1f - state.fraction }) { content() } },
        modifier = Modifier.fillMaxWidth().clipToBounds(),
    ) { measurables, constraints ->
        val placed = measurables.first().measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
        state.report(part, placed.height)
        val shown = (placed.height * (1f - state.fraction)).roundToInt()
        layout(placed.width, shown) { placed.placeRelative(0, shown - placed.height) }
    }
}

/** Size of every button with an icon only, the same as the bar of the video player: easy to hit at arm's length. */
val TOOL_BUTTON_SIZE = 56.dp
val TOOL_ICON_SIZE = 28.dp

/** Room around the buttons of a [ButtonBar], and the bar's whole height. */
private val BUTTON_BAR_PADDING = 4.dp
val BUTTON_BAR_HEIGHT = TOOL_BUTTON_SIZE + BUTTON_BAR_PADDING * 2

/** Inside a text field the button fits the field's own slot instead. */
private val FIELD_BUTTON_SIZE = 48.dp
private val FIELD_ICON_SIZE = 24.dp

/** A button that does nothing right now stays visible, faded, as the kit says. */
private const val TOOL_DISABLED_ALPHA = 0.5f

/** Pressed: a little smaller for a moment, as every button of the kit. */
private const val TOOL_PRESSED_SCALE = 0.97f
private const val TOOL_PRESS_MS = 80

/**
 * ToolButton() - the icon button of the whole app, in the style of the video player's bar
 * @param icon what the button shows
 * @param label what the button does, read aloud by TalkBack
 * @param onClick the action
 * @param tint colour of the icon: the main colour chosen in the settings, red for deleting
 * @param enabled false fades the button and ignores touches
 * @param isPrimary dark tinted background with a border in the main colour: the one action that matters most (Play)
 * @param caption a short word under the icon ("10 s"), or null
 *
 * 56dp with a 28dp icon and corners of 4 everywhere: in the headers, in the
 * viewer of photos and videos, in the rows of the lists and in the mini player.
 * The background stays dark; only the icon takes the main colour.
 *
 * Context: composable.
 * Return: nothing.
 */
@Composable
fun ToolButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    tint: Color = Arancio.colors.accentText,
    enabled: Boolean = true,
    isPrimary: Boolean = false,
    caption: String? = null,
) {
    val colors = Arancio.colors
    val interaction = remember { MutableInteractionSource() }
    val isPressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) TOOL_PRESSED_SCALE else 1f, tween(TOOL_PRESS_MS), label = "press")
    val shape = if (isPrimary) ArancioShape.fab else ArancioShape.control
    // dark background always: the tinted one of the kit for the primary, the raised surface while pressed
    val background = when {
        isPrimary -> colors.accentDeep
        isPressed -> colors.surfaceRaised
        else -> Color.Transparent
    }
    Column(
        Modifier
            .size(TOOL_BUTTON_SIZE)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .background(background)
            .then(if (isPrimary) Modifier.border(1.dp, colors.accentBorder, shape) else Modifier)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClickLabel = label,
                onClick = onClick,
            )
            .alpha(if (enabled) 1f else TOOL_DISABLED_ALPHA),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(TOOL_ICON_SIZE),
        )
        if (caption != null) {
            Text(caption, style = ArancioType.data, color = colors.textSecondary, maxLines = 1)
        }
    }
}

/**
 * FieldButton() - the icon button inside a text field (clear, show password)
 * @param icon what the button shows
 * @param label what the button does, read aloud by TalkBack
 * @param onClick the action
 *
 * Same look as [ToolButton] but sized to the field's own slot, so the field
 * keeps the height of the others.
 *
 * Context: composable.
 * Return: nothing.
 */
@Composable
fun FieldButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(FIELD_BUTTON_SIZE)
            .clip(ArancioShape.control)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = Arancio.colors.accentText, modifier = Modifier.size(FIELD_ICON_SIZE))
    }
}

/**
 * ButtonBar() - a bar of [ToolButton]s on the colour of the bars, like the one under a video
 * @param modifier where the bar goes
 * @param content the buttons, spread evenly over the width
 *
 * Context: composable.
 * Return: nothing.
 */
@Composable
fun ButtonBar(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier
            .fillMaxWidth()
            .background(Arancio.colors.nav)
            .padding(horizontal = 8.dp, vertical = BUTTON_BAR_PADDING),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** Filter chip of the kit: filled light when selected, outlined panel otherwise, corners of 4. */
@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit) {
    val c = Arancio.colors
    val shape = ArancioShape.control
    Box(
        Modifier
            .heightIn(min = 36.dp)
            .clip(shape)
            .background(if (selected) c.text else c.surface)
            .then(if (selected) Modifier else Modifier.border(1.dp, c.border, shape))
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium),
            color = if (selected) c.background else c.text,
        )
    }
}

/** Small label as a pill: accent at 16% with the accent label colour, or [tint] at 16%. */
@Composable
fun Tag(text: String, tint: Color? = null, modifier: Modifier = Modifier) {
    val c = Arancio.colors
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = tint ?: c.accentLabel,
        modifier = modifier
            .background((tint ?: c.accent).copy(alpha = 0.16f), ArancioShape.pill)
            .padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

/** Uppercase heading over a group of cards or settings. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = ArancioType.section,
        color = Arancio.colors.textSecondary,
        modifier = modifier.semantics { heading() },
    )
}

/** Card or panel at level 0: no shadow, 1dp border, corners of 12. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    raised: Boolean = false,
    padding: PaddingValues = PaddingValues(16.dp),
    spacing: Dp = 12.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Arancio.colors
    Column(
        modifier
            .fillMaxWidth()
            .background(if (raised) c.surfaceRaised else c.surface, ArancioShape.card)
            .border(1.dp, c.border, ArancioShape.card)
            .padding(padding),
        verticalArrangement = Arrangement.spacedBy(spacing),
        content = content,
    )
}

/** Dialog panel at level 2: raised ground, strong border, deeper shadow. */
fun Modifier.dialogPanel(colors: ArancioColors): Modifier = this
    .shadow(16.dp, ArancioShape.card, ambientColor = colors.shadow, spotColor = colors.shadow)
    .background(colors.surfaceRaised, ArancioShape.card)
    .border(1.dp, colors.borderStrong, ArancioShape.card)

/** Floating card at level 1 (toasts, transfer progress): raised ground, strong border, small shadow. */
fun Modifier.floating(colors: ArancioColors): Modifier = this
    .shadow(6.dp, ArancioShape.card, ambientColor = colors.shadow, spotColor = colors.shadow)
    .background(colors.surfaceRaised, ArancioShape.card)
    .border(1.dp, colors.borderStrong, ArancioShape.card)

enum class ButtonKind { Primary, Secondary, Ghost, Danger, Tinted }

/**
 * Button of the kit: corners of 4, 40dp high (48dp touch target), pressed at scale 0.97 for 80ms,
 * disabled at half opacity. Primary is filled with the main colour, Secondary is the raised grey with a border,
 * Ghost has no ground, Danger is the soft red with a red border, Tinted the soft main colour ("Ask AI").
 */
@Composable
fun ArancioButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Primary,
    enabled: Boolean = true,
    contentPadding: PaddingValues? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val c = Arancio.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, tween(80), label = "press")
    val (container, content2, border) = when (kind) {
        ButtonKind.Primary -> Triple(if (pressed) c.accentHover else c.accentButton, c.onAccent, null)
        ButtonKind.Secondary -> Triple(c.surfaceRaised, c.text, c.borderStrong)
        ButtonKind.Ghost -> Triple(Color.Transparent, c.text, null)
        ButtonKind.Danger -> Triple(c.dangerSoft, c.danger, c.dangerBorder)
        ButtonKind.Tinted -> Triple(c.accentDeep, c.accentText, c.accentBorder)
    }
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = ArancioShape.control,
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = content2,
            disabledContainerColor = container,
            disabledContentColor = content2,
        ),
        border = border?.let { BorderStroke(1.dp, it) },
        contentPadding = contentPadding ?: PaddingValues(horizontal = if (kind == ButtonKind.Ghost) 12.dp else 16.dp),
        interactionSource = interaction,
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .alpha(if (enabled) 1f else 0.5f),
    ) {
        ProvideTextStyle(MaterialTheme.typography.labelLarge.copy(fontWeight = if (kind == ButtonKind.Primary) FontWeight.SemiBold else FontWeight.Medium)) {
            content()
        }
    }
}

@Composable
fun PrimaryButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) =
    ArancioButton(onClick, modifier, ButtonKind.Primary, enabled, content = content)

@Composable
fun SecondaryButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) =
    ArancioButton(onClick, modifier, ButtonKind.Secondary, enabled, content = content)

@Composable
fun GhostButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) =
    ArancioButton(onClick, modifier, ButtonKind.Ghost, enabled, content = content)

@Composable
fun DangerButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) =
    ArancioButton(onClick, modifier, ButtonKind.Danger, enabled, content = content)

/** Floating button of the kit: 56dp, main colour, corners of 6. */
@Composable
fun AccentFab(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Arancio.colors
    FloatingActionButton(
        onClick = onClick,
        containerColor = c.accentButton,
        contentColor = c.onAccent,
        shape = ArancioShape.fab,
        elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 6.dp, pressedElevation = 8.dp),
        modifier = modifier,
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(24.dp))
    }
}

/** Dropdown menu at level 1: raised ground, strong border, corners of 8, small shadow. */
@Composable
fun ArancioMenu(expanded: Boolean, onDismissRequest: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val c = Arancio.colors
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        shape = ArancioShape.menu,
        containerColor = c.surfaceRaised,
        tonalElevation = 0.dp,
        shadowElevation = 6.dp,
        border = BorderStroke(1.dp, c.borderStrong),
        content = content,
    )
}

/** Menu entry: the active one on the main colour at 10% with accent text, destructive ones in red. */
@Composable
fun ArancioMenuItem(icon: ImageVector?, label: String, danger: Boolean = false, active: Boolean = false, onClick: () -> Unit) {
    val c = Arancio.colors
    val color = when {
        danger -> c.danger
        active -> c.accentText
        else -> c.text
    }
    DropdownMenuItem(
        text = { Text(label, style = MaterialTheme.typography.bodyLarge, color = color) },
        leadingIcon = if (icon != null) {
            { Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp)) }
        } else null,
        trailingIcon = if (active) {
            { Icon(AppIcons.Check, contentDescription = "Attivo", tint = color, modifier = Modifier.size(18.dp)) }
        } else null,
        onClick = onClick,
        modifier = Modifier
            .padding(horizontal = 6.dp)
            .clip(ArancioShape.control)
            .background(if (active) c.accentTint else Color.Transparent)
            .heightIn(min = 44.dp),
    )
}

/** Toasts of the kit: raised card from the bottom, strong border, corners of 8. */
@Composable
fun ArancioSnackbarHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    val c = Arancio.colors
    SnackbarHost(state, modifier) { data ->
        Snackbar(
            data,
            shape = ArancioShape.menu,
            containerColor = c.surfaceRaised,
            contentColor = c.text,
            actionColor = c.accentText,
            actionContentColor = c.accentText,
            dismissActionContentColor = c.textSecondary,
            modifier = Modifier
                .padding(horizontal = 4.dp)
                .border(1.dp, c.borderStrong, ArancioShape.menu),
        )
    }
}

/** Progress bar of the kit: 6dp, round ends, on the border grey. Without [fraction] it runs indeterminate. */
@Composable
fun ArancioProgress(fraction: Float?, modifier: Modifier = Modifier, color: Color = Arancio.colors.accent) {
    val c = Arancio.colors
    val m = modifier.fillMaxWidth().height(6.dp)
    if (fraction != null) {
        LinearProgressIndicator(
            progress = { fraction },
            modifier = m,
            color = color,
            trackColor = c.border,
            strokeCap = StrokeCap.Round,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
    } else {
        LinearProgressIndicator(modifier = m, color = color, trackColor = c.border, strokeCap = StrokeCap.Round, gapSize = 0.dp)
    }
}

@Composable
fun arancioSwitchColors() = SwitchDefaults.colors(
    checkedTrackColor = Arancio.colors.accent,
    checkedThumbColor = Arancio.colors.onAccent,
    checkedBorderColor = Arancio.colors.accent,
    uncheckedTrackColor = Arancio.colors.surfaceRaised,
    uncheckedThumbColor = Arancio.colors.textSecondary,
    uncheckedBorderColor = Arancio.colors.borderStrong,
)

/** Loading placeholder: the slow 1.2s sheen of the kit, the only looping animation it allows. */
@Composable
fun rememberShimmer(): Brush {
    val c = Arancio.colors
    val transition = rememberInfiniteTransition(label = "shimmer")
    val x by transition.animateFloat(
        initialValue = -400f,
        targetValue = 400f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)),
        label = "shimmer-x",
    )
    return Brush.linearGradient(
        colors = listOf(c.surfaceRaised, c.border, c.surfaceRaised),
        start = Offset(x, 0f),
        end = Offset(x + 800f, 0f),
    )
}

/** Rows of a list that is still loading: tile, name and details as grey bars. */
@Composable
fun SkeletonList(modifier: Modifier = Modifier, rows: Int = 8) {
    val brush = rememberShimmer()
    Column(modifier.fillMaxSize().padding(top = 4.dp)) {
        repeat(rows) { i ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(44.dp).background(brush, RoundedCornerShape(10.dp)))
                Spacer(Modifier.width(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.fillMaxWidth(if (i % 3 == 0) 0.62f else if (i % 3 == 1) 0.48f else 0.7f).height(14.dp).background(brush, ArancioShape.control))
                    Box(Modifier.fillMaxWidth(0.32f).height(10.dp).background(brush, ArancioShape.control))
                }
            }
        }
    }
}

/** Squares of a photo grid that is still loading. */
@Composable
fun SkeletonGrid(modifier: Modifier = Modifier, columns: Int = 4, rows: Int = 6) {
    val brush = rememberShimmer()
    Column(modifier.fillMaxSize().padding(horizontal = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Box(Modifier.padding(horizontal = 14.dp, vertical = 12.dp).fillMaxWidth(0.3f).height(14.dp).background(brush, ArancioShape.control))
        repeat(rows) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                repeat(columns) { Box(Modifier.weight(1f).aspectRatio(1f).background(brush)) }
            }
        }
    }
}

/** Empty screen or list: icon on a bordered tile, a title, a line of help and maybe one action. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    text: String? = null,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    val c = Arancio.colors
    Column(
        modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(64.dp).background(c.surface, ArancioShape.card).border(1.dp, c.border, ArancioShape.card),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = c.textSecondary, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = c.text, textAlign = TextAlign.Center)
        if (text != null) {
            Spacer(Modifier.height(6.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary, textAlign = TextAlign.Center)
        }
        if (action != null) {
            Spacer(Modifier.height(20.dp))
            action()
        }
    }
}

enum class NoticeKind { Info, Success, Warning, Error }

private class NoticeStyle(val icon: ImageVector, val tint: Color, val ground: Color, val edge: Color)

/** Notice of the kit: icon, bold line and explanation on a tinted ground with a tinted border, corners of 10. */
@Composable
fun Notice(
    kind: NoticeKind,
    title: String,
    text: String? = null,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    val c = Arancio.colors
    val style = when (kind) {
        NoticeKind.Info -> NoticeStyle(AppIcons.Info, c.accentText, c.accentDeep, c.accentBorder)
        NoticeKind.Success -> NoticeStyle(AppIcons.Check, c.success, c.successSoft, c.successBorder)
        NoticeKind.Warning -> NoticeStyle(AppIcons.Alert, c.warning, c.surfaceRaised, c.borderStrong)
        NoticeKind.Error -> NoticeStyle(AppIcons.Alert, c.danger, c.dangerSoft, c.dangerBorder)
    }
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier
            .fillMaxWidth()
            .background(style.ground, shape)
            .border(1.dp, style.edge, shape)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(style.icon, contentDescription = null, tint = style.tint, modifier = Modifier.padding(top = 1.dp).size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), color = c.text)
                if (text != null) Text(text, style = MaterialTheme.typography.bodySmall, color = c.textSecondary)
            }
        }
        action?.invoke()
    }
}

/** Square tile with the icon of the entry's kind, tinted by category. */
@Composable
fun KindTile(entry: RemoteEntry, size: Dp, iconSize: Dp = 22.dp) {
    val (icon, tint) = kindOf(entry)
    Box(
        Modifier.size(size).background(tint.copy(alpha = 0.16f), RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(iconSize))
    }
}

@Composable
fun kindOf(entry: RemoteEntry): Pair<ImageVector, Color> {
    val c = Arancio.colors
    return when {
        entry.isDirectory -> AppIcons.Folder to c.folder
        entry.extension in ThumbnailCache.IMAGE_EXTENSIONS -> AppIcons.Image to c.image
        entry.extension in VIDEO -> AppIcons.Video to c.media
        entry.extension in AUDIO -> AppIcons.Music to c.media
        entry.extension in ARCHIVE -> AppIcons.Archive to c.textSecondary
        entry.extension in DOCUMENT -> AppIcons.Document to c.document
        else -> AppIcons.File to c.textSecondary
    }
}

// the formats the viewer plays, plus a few the icon alone covers
private val VIDEO = ThumbnailCache.VIDEO_EXTENSIONS + setOf("avi", "wmv", "ts")
private val AUDIO = setOf("mp3", "flac", "wav", "m4a", "aac", "ogg", "opus", "wma")
private val ARCHIVE = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "iso")
private val DOCUMENT = setOf(
    "pdf", "txt", "md", "doc", "docx", "odt", "rtf", "xls", "xlsx", "ods", "csv", "ppt", "pptx", "odp",
    "json", "xml", "html", "log", "ini", "conf", "yml", "yaml",
)

@Composable
fun entryDetails(entry: RemoteEntry): String {
    val context = LocalContext.current
    val date = if (entry.modified > 0) {
        DateUtils.formatDateTime(
            context,
            entry.modified,
            if (DateUtils.isToday(entry.modified)) DateUtils.FORMAT_SHOW_TIME
            else DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_YEAR,
        ).let { if (DateUtils.isToday(entry.modified)) "oggi $it" else it }
    } else ""
    val kind = if (entry.isDirectory) "Cartella" else Formatter.formatShortFileSize(context, entry.size)
    return listOf(kind, date).filter { it.isNotEmpty() }.joinToString(" · ")
}

/** Card for an error with a way out. */
@Composable
fun ErrorCard(text: String, action: String, onAction: () -> Unit) {
    Notice(NoticeKind.Error, text, modifier = Modifier.padding(16.dp)) {
        SecondaryButton(onClick = onAction) { Text(action) }
    }
}

/** Fields of the kit: ground colour inside, strong grey border, main colour when focused, corners of 4. */
@Composable
fun arancioFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Arancio.colors.accent,
    unfocusedBorderColor = Arancio.colors.borderStrong,
    disabledBorderColor = Arancio.colors.border,
    errorBorderColor = Arancio.colors.danger,
    focusedLabelColor = Arancio.colors.accentText,
    unfocusedLabelColor = Arancio.colors.textSecondary,
    focusedLeadingIconColor = Arancio.colors.textSecondary,
    unfocusedLeadingIconColor = Arancio.colors.textSecondary,
    focusedTrailingIconColor = Arancio.colors.textSecondary,
    unfocusedTrailingIconColor = Arancio.colors.textSecondary,
    focusedPlaceholderColor = Arancio.colors.textSecondary,
    unfocusedPlaceholderColor = Arancio.colors.textSecondary,
    cursorColor = Arancio.colors.accent,
    focusedContainerColor = Arancio.colors.background,
    unfocusedContainerColor = Arancio.colors.background,
    selectionColors = TextSelectionColors(handleColor = Arancio.colors.accent, backgroundColor = Arancio.colors.accent.copy(alpha = 0.30f)),
)

/** Dialog asking for a file or folder name; the part before the extension is preselected. */
@Composable
fun NameDialog(title: String, initial: String, confirm: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val stem = initial.lastIndexOf('.').takeIf { it > 0 } ?: initial.length
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, stem))) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Arancio.colors.surfaceRaised,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                shape = ArancioShape.control,
                colors = arancioFieldColors(),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = {
            PrimaryButton(
                onClick = { onConfirm(value.text) },
                enabled = value.text.isNotBlank(),
            ) { Text(confirm) }
        },
        dismissButton = { GhostButton(onClick = onDismiss) { Text("Annulla") } },
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirm: String,
    danger: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Arancio.colors.surfaceRaised,
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = { Text(text, style = MaterialTheme.typography.bodyLarge) },
        confirmButton = {
            ArancioButton(onClick = onConfirm, kind = if (danger) ButtonKind.Danger else ButtonKind.Primary) { Text(confirm) }
        },
        dismissButton = { GhostButton(onClick = onDismiss) { Text("Annulla") } },
    )
}

/** Tick in the corner of a selected entry. */
@Composable
fun SelectedMark(modifier: Modifier, size: Dp = 22.dp) {
    val c = Arancio.colors
    Box(modifier.size(size).background(c.accent, CircleShape).border(1.5.dp, c.background, CircleShape), contentAlignment = Alignment.Center) {
        Icon(AppIcons.Check, contentDescription = null, tint = c.onAccent, modifier = Modifier.size(size * 0.64f))
    }
}

/** Small play mark on the thumbnail of a video. */
@Composable
fun VideoBadge(modifier: Modifier = Modifier, size: Dp = 22.dp) {
    Box(modifier.size(size).background(Color.Black.copy(alpha = 0.55f), CircleShape), contentAlignment = Alignment.Center) {
        Icon(AppIcons.Play, contentDescription = "Video", tint = Color.White, modifier = Modifier.size(size * 0.6f))
    }
}
