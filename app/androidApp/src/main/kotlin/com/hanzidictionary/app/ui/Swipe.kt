package com.hanzidictionary.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlinx.coroutines.launch

/**
 * Swipe gestures, hand-rolled on [detectHorizontalDragGestures].
 *
 * Material3's `SwipeToDismissBox` needs 1.3+ (this app resolves 1.2.1 through
 * compose-bom 2024.06.00) and models *dismissal* — a row that leaves the list. What the
 * study flows need is an *action* that fires and snaps back, plus a card that can be
 * thrown off-screen, so both live here.
 *
 * One gesture grammar across the whole app: **right = affirm** (save it, I knew it,
 * archive it), **left = negative** (didn't know it, take it off the list).
 */

/** The two ends of a swipe. Colours are tuned to read on both M3 surfaces. */
data class SwipeAction(val label: String, val icon: String, val color: Color)

val AddAction = SwipeAction("Add to list", "＋", Color(0xFF2E7D32))
val KnewAction = SwipeAction("Knew it", "✓", Color(0xFF2E7D32))
val ArchiveAction = SwipeAction("I know this", "✓", Color(0xFF2E7D32))
val MissedAction = SwipeAction("Didn't know", "✗", Color(0xFFC62828))
val RemoveAction = SwipeAction("Remove", "✕", Color(0xFFC62828))
// Skip is neither affirm nor negative — it just moves on, so it reads grey, off the
// red/green axis, and rides the vertical direction the left/right grammar leaves free.
val SkipAction = SwipeAction("Skip", "↑", Color(0xFF9E9E9E))

/**
 * A list row that fires [onSwipeRight]/[onSwipeLeft] once dragged past a threshold, then
 * springs back. Either action may be null, in which case that direction resists.
 *
 * Also exposes both as TalkBack custom actions — a swipe-only affordance is invisible to
 * a screen reader (spec §15 a11y).
 */
@Composable
fun SwipeRow(
    rightAction: SwipeAction? = null,
    leftAction: SwipeAction? = null,
    onSwipeRight: (() -> Unit)? = null,
    onSwipeLeft: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    val threshold = with(LocalDensity.current) { 88.dp.toPx() }
    val right by rememberUpdatedState(onSwipeRight)
    val left by rememberUpdatedState(onSwipeLeft)

    val actions = buildList {
        if (right != null && rightAction != null) {
            add(CustomAccessibilityAction(rightAction.label) { right?.invoke(); true })
        }
        if (left != null && leftAction != null) {
            add(CustomAccessibilityAction(leftAction.label) { left?.invoke(); true })
        }
    }

    Box(Modifier.fillMaxWidth().semantics { customActions = actions }) {
        // The action behind the row, revealed proportionally to the drag so the gesture
        // explains itself the first time rather than needing a tutorial.
        val revealed = if (offset.value > 0) rightAction else leftAction
        if (revealed != null && offset.value != 0f) {
            val progress = (abs(offset.value) / threshold).coerceIn(0f, 1f)
            Row(
                Modifier.matchParentSize()
                    .clip(RoundedCornerShape(8.dp))
                    .background(revealed.color.copy(alpha = 0.12f + 0.18f * progress))
                    .padding(horizontal = 16.dp),
                horizontalArrangement =
                    if (offset.value > 0) Arrangement.Start else Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${revealed.icon}  ${revealed.label}",
                    color = revealed.color,
                    fontSize = 13.sp,
                )
            }
        }
        Box(
            Modifier
                .graphicsLayer { translationX = offset.value }
                // Keyed on Unit, never on the callbacks: callers pass fresh lambdas every
                // recomposition, so keying on them would tear down the gesture detector
                // mid-drag. rememberUpdatedState keeps the reads below current instead.
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val fired = when {
                                offset.value >= threshold -> right
                                offset.value <= -threshold -> left
                                else -> null
                            }
                            fired?.invoke()
                            scope.launch { offset.animateTo(0f, tween(180)) }
                        },
                        onDragCancel = { scope.launch { offset.animateTo(0f, tween(180)) } },
                    ) { change, dragAmount ->
                        val allowed = when {
                            dragAmount > 0 && right == null -> 0f
                            dragAmount < 0 && left == null -> 0f
                            else -> dragAmount
                        }
                        if (allowed != 0f) {
                            change.consume()
                            scope.launch { offset.snapTo(offset.value + allowed) }
                        }
                    }
                },
        ) { content() }
    }
}

/**
 * The practice card: drag it, and past the threshold it flies off in that direction and
 * the next card takes its place. [key] identifies the card on screen — when it changes the
 * new card is reset to centre.
 *
 * Left/right are the graded verbs (knew it / didn't). An optional **up** swipe skips the
 * card — moves on without touching its memory — so the direction the horizontal grammar
 * leaves free carries the one action that isn't a grade. The dominant axis of the drag
 * decides, so a mostly-sideways throw never registers as a skip and vice-versa.
 *
 * Omitting [onSwipeUp] also narrows the card to **horizontal drags only**, which is what lets
 * it live inside a scrolling list: claiming vertical drags there would eat the scroll (and
 * every attempt to scroll past the card would register as a skip).
 */
@Composable
fun SwipeCard(
    key: Any?,
    rightAction: SwipeAction,
    leftAction: SwipeAction,
    onSwipeRight: () -> Unit,
    onSwipeLeft: () -> Unit,
    upAction: SwipeAction? = null,
    onSwipeUp: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val threshold = with(LocalDensity.current) { 120.dp.toPx() }
    val flyAway = with(LocalDensity.current) { 1200.dp.toPx() }
    val right by rememberUpdatedState(onSwipeRight)
    val left by rememberUpdatedState(onSwipeLeft)
    val up by rememberUpdatedState(onSwipeUp)

    LaunchedEffect(key) { offset.snapTo(Offset.Zero) }

    Box(
        Modifier.fillMaxWidth().semantics {
            customActions = buildList {
                add(CustomAccessibilityAction(rightAction.label) { right(); true })
                add(CustomAccessibilityAction(leftAction.label) { left(); true })
                if (up != null && upAction != null) {
                    add(CustomAccessibilityAction(upAction.label) { up?.invoke(); true })
                }
            }
        },
    ) {
        val o = offset.value
        // Skip only when up-drag dominates; otherwise the throw reads as left or right.
        val vertical = up != null && upAction != null && -o.y > abs(o.x)
        val hint = when {
            vertical -> upAction
            o.x > 0 -> rightAction
            else -> leftAction
        }
        val travel = if (vertical) -o.y else abs(o.x)
        val progress = (travel / threshold).coerceIn(0f, 1f)
        if (hint != null && (o.x != 0f || o.y != 0f)) {
            Box(
                Modifier.matchParentSize()
                    .clip(RoundedCornerShape(12.dp))
                    .background(hint.color.copy(alpha = 0.10f * progress)),
                contentAlignment = when {
                    vertical -> Alignment.TopCenter
                    o.x > 0 -> Alignment.CenterEnd
                    else -> Alignment.CenterStart
                },
            ) {
                Text(
                    hint.icon,
                    color = hint.color.copy(alpha = progress),
                    fontSize = 44.sp,
                    modifier = Modifier.padding(24.dp),
                )
            }
        }
        Box(
            Modifier
                .graphicsLayer {
                    translationX = offset.value.x
                    translationY = offset.value.y
                    // A few degrees of tilt makes the throw feel physical rather than
                    // like a sliding panel.
                    rotationZ = (offset.value.x / 40f).coerceIn(-8f, 8f)
                }
                .pointerInput(key, onSwipeUp != null) {
                    // Settling is shared; only which drags the card claims differs.
                    val settle = {
                        val v = offset.value
                        when {
                            up != null && -v.y >= threshold && -v.y > abs(v.x) ->
                                scope.launch {
                                    offset.animateTo(Offset(v.x, -flyAway), tween(160))
                                    up?.invoke()
                                }
                            v.x >= threshold -> scope.launch {
                                offset.animateTo(Offset(flyAway, v.y), tween(160))
                                right()
                            }
                            v.x <= -threshold -> scope.launch {
                                offset.animateTo(Offset(-flyAway, v.y), tween(160))
                                left()
                            }
                            else -> scope.launch { offset.animateTo(Offset.Zero, tween(200)) }
                        }
                        Unit
                    }
                    val cancel = { scope.launch { offset.animateTo(Offset.Zero, tween(200)) }; Unit }
                    if (up != null) {
                        detectDragGestures(onDragEnd = settle, onDragCancel = cancel) { change, drag ->
                            change.consume()
                            scope.launch { offset.snapTo(offset.value + drag) }
                        }
                    } else {
                        // Horizontal only: vertical drags are left unconsumed so an enclosing
                        // list can scroll through the card.
                        detectHorizontalDragGestures(onDragEnd = settle, onDragCancel = cancel) { change, dx ->
                            change.consume()
                            scope.launch { offset.snapTo(offset.value.copy(x = offset.value.x + dx)) }
                        }
                    }
                },
        ) { content() }
    }
}

/** Fraction of the screen's width a grid tile must travel before its action will fire. See
 * [SwipeTile] for why it's this far. */
private const val TILE_COMMIT_FRACTION = 0.42f

/**
 * A grid tile that fires [onSwipeRight]/[onSwipeLeft] only once dragged nearly halfway
 * across the screen — a deliberately expensive gesture.
 *
 * [SwipeRow]'s 88dp threshold is right for a full-width row you're reading one at a time,
 * but wrong for a dense grid: the tiles are small, they sit shoulder to shoulder, and one of
 * the two actions files something into a study list while the other takes it out of the
 * feed. A flick that costs 88dp is a flick you make by accident while scrolling.
 *
 * The three guards, which are the standard ones for a destructive-ish swipe:
 *   - **distance** — the tile has to cross most of the screen, which no scroll gesture does;
 *   - **haptics** — a tick the moment the action arms, so you feel the commit point *before*
 *     lifting your finger and can still back out by dragging back;
 *   - **visible state** — the tile tints toward the action's colour as it travels and only
 *     shows the action's icon once armed, so "will this fire?" is never a guess.
 *
 * Undo (the caller's snackbar) is the fourth. Vertical scrolling is untouched: this reads
 * horizontal drags only.
 */
@Composable
fun SwipeTile(
    key: Any?,
    rightAction: SwipeAction? = null,
    leftAction: SwipeAction? = null,
    onSwipeRight: (() -> Unit)? = null,
    onSwipeLeft: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    val haptics = LocalHapticFeedback.current
    val threshold = with(LocalDensity.current) {
        (LocalConfiguration.current.screenWidthDp * TILE_COMMIT_FRACTION).dp.toPx()
    }
    val right by rememberUpdatedState(onSwipeRight)
    val left by rememberUpdatedState(onSwipeLeft)

    // A new tile in this slot starts centred — otherwise a dismissed find leaves the next
    // one already half-dragged.
    LaunchedEffect(key) { offset.snapTo(0f) }

    val travelled = abs(offset.value)
    val armed = travelled >= threshold
    // Fires on the false -> true edge only, which is exactly the moment worth announcing.
    LaunchedEffect(armed) {
        if (armed) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    val actions = buildList {
        if (right != null && rightAction != null) {
            add(CustomAccessibilityAction(rightAction.label) { right?.invoke(); true })
        }
        if (left != null && leftAction != null) {
            add(CustomAccessibilityAction(leftAction.label) { left?.invoke(); true })
        }
    }

    Box(modifier.semantics { customActions = actions }) {
        val hint = if (offset.value > 0) rightAction else leftAction
        Box(
            Modifier
                .graphicsLayer {
                    translationX = offset.value
                    // Lifting the tile as it travels separates it from the grid it's
                    // leaving, so a long drag looks intentional rather than like a glitch.
                    val lift = 1f + 0.08f * (travelled / threshold).coerceIn(0f, 1f)
                    scaleX = lift
                    scaleY = lift
                    rotationZ = (offset.value / 60f).coerceIn(-10f, 10f)
                }
                .pointerInput(key) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val fired = when {
                                offset.value >= threshold -> right
                                offset.value <= -threshold -> left
                                else -> null
                            }
                            fired?.invoke()
                            scope.launch { offset.animateTo(0f, tween(220)) }
                        },
                        onDragCancel = { scope.launch { offset.animateTo(0f, tween(220)) } },
                    ) { change, dragAmount ->
                        val allowed = when {
                            dragAmount > 0 && right == null -> 0f
                            dragAmount < 0 && left == null -> 0f
                            else -> dragAmount
                        }
                        if (allowed != 0f) {
                            change.consume()
                            scope.launch { offset.snapTo(offset.value + allowed) }
                        }
                    }
                },
        ) {
            content()
            if (hint != null && travelled > 0f) {
                val progress = (travelled / threshold).coerceIn(0f, 1f)
                Box(
                    Modifier.matchParentSize()
                        .clip(RoundedCornerShape(12.dp))
                        .background(hint.color.copy(alpha = 0.12f + 0.55f * progress)),
                    contentAlignment = Alignment.Center,
                ) {
                    // The icon appearing *is* the "you've gone far enough" signal, so it
                    // shows only once armed rather than fading in the whole way.
                    if (armed) Text(hint.icon, color = Color.White, fontSize = 26.sp)
                }
            }
        }
    }
}

/** Full-bleed swipe hint under the practice card, so the gesture is discoverable. An
 * optional [up] action (skip) rides the middle. */
@Composable
fun SwipeLegend(left: SwipeAction, right: SwipeAction, up: SwipeAction? = null) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("←  ${left.label}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (up != null) {
            Text("↑ ${up.label}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("${right.label}  →", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
