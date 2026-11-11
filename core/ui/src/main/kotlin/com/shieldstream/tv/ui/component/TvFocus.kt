package com.shieldstream.tv.ui.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.KeyInputModifierNode
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

@OptIn(ExperimentalFoundationApi::class)
private class PivotBringIntoViewSpec(private val leadingPx: Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val childSize = abs(size)
        val target = if (childSize <= containerSize && containerSize - leadingPx < childSize) {
            containerSize - childSize
        } else {
            leadingPx
        }
        return offset - target
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProvidePivotScroll(leading: Dp, content: @Composable () -> Unit) {
    val px = with(LocalDensity.current) { leading.toPx() }
    val spec = remember(px) { PivotBringIntoViewSpec(px) }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}

@OptIn(ExperimentalFoundationApi::class)
private class MinimalBringIntoViewSpec(private val marginPx: Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, rawSize: Float, containerSize: Float): Float {
        val offset = offset - marginPx
        val size = rawSize + 2 * marginPx
        val trailing = offset + size
        return when {
            offset >= 0f && trailing <= containerSize -> 0f
            offset < 0f && trailing > containerSize -> 0f
            abs(offset) < abs(trailing - containerSize) -> offset
            else -> trailing - containerSize
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProvideMinimalScroll(margin: Dp = 0.dp, content: @Composable () -> Unit) {
    val px = with(LocalDensity.current) { margin.toPx() }
    val spec = remember(px) { MinimalBringIntoViewSpec(px) }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}

fun Modifier.onBackKey(enabled: Boolean, onBack: () -> Unit): Modifier = onKeyEvent { event ->
    if (!enabled || (event.key != Key.Back && event.key != Key.Escape)) return@onKeyEvent false
    if (event.type == KeyEventType.KeyUp) onBack()
    true
}

fun Modifier.tvClickable(enabled: Boolean = true, onClick: () -> Unit): Modifier =
    this
        .then(TvClickKeyElement(enabled = enabled, onLongClick = null, onClick = onClick))
        .clickable(interactionSource = null, indication = null, enabled = enabled, onClick = onClick)

fun Modifier.tvClickable(onLongClick: (() -> Unit)?, onClick: () -> Unit): Modifier =
    if (onLongClick == null) {
        tvClickable(onClick = onClick)
    } else {
        this
            .then(TvClickKeyElement(enabled = true, onLongClick = onLongClick, onClick = onClick))
            .combinedClickable(interactionSource = null, indication = null, onLongClick = onLongClick, onClick = onClick)
    }

private fun Key.isTvConfirmKey(): Boolean =
    this == Key.DirectionCenter || this == Key.Enter || this == Key.NumPadEnter || this == Key.Spacebar

private data class TvClickKeyElement(
    val enabled: Boolean,
    val onLongClick: (() -> Unit)?,
    val onClick: () -> Unit,
) : ModifierNodeElement<TvClickKeyNode>() {
    override fun create(): TvClickKeyNode = TvClickKeyNode(
        enabled = enabled,
        onLongClick = onLongClick,
        onClick = onClick,
    )

    override fun update(node: TvClickKeyNode) {
        node.update(
            enabled = enabled,
            onLongClick = onLongClick,
            onClick = onClick,
        )
    }
}

private class TvClickKeyNode(
    private var enabled: Boolean,
    private var onLongClick: (() -> Unit)?,
    private var onClick: () -> Unit,
) : Modifier.Node(), KeyInputModifierNode, FocusEventModifierNode, CompositionLocalConsumerModifierNode {
    private var pressedKey: Key? = null
    private var longPressTriggered = false
    private var longPressJob: Job? = null

    fun update(
        enabled: Boolean,
        onLongClick: (() -> Unit)?,
        onClick: () -> Unit,
    ) {
        if (!enabled) resetPress()
        this.enabled = enabled
        this.onLongClick = onLongClick
        this.onClick = onClick
    }

    override fun onFocusEvent(focusState: FocusState) {
        if (!focusState.isFocused) {
            resetPress()
        }
    }

    override fun onDetach() {
        resetPress()
    }

    override fun onPreviewKeyEvent(event: KeyEvent): Boolean {
        if (!enabled || !event.key.isTvConfirmKey()) return false
        return when (event.type) {
            KeyEventType.KeyDown -> {
                if (event.nativeKeyEvent.repeatCount == 0) {
                    pressedKey = event.key
                    longPressTriggered = false
                    longPressJob?.cancel()
                    if (onLongClick != null) {
                        val timeoutMs = currentValueOf(LocalViewConfiguration).longPressTimeoutMillis
                        longPressJob = coroutineScope.launch {
                            delay(timeoutMs)
                            if (pressedKey == event.key && !longPressTriggered) {
                                triggerLongPress()
                            }
                        }
                    }
                } else if (pressedKey == event.key && !longPressTriggered && onLongClick != null && event.nativeKeyEvent.isLongPress) {
                    triggerLongPress()
                }
                true
            }
            KeyEventType.KeyUp -> {
                val shouldClick = pressedKey == event.key && !longPressTriggered
                resetPress()
                if (shouldClick) {
                    onClick()
                }
                true
            }
            else -> false
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean = false

    private fun triggerLongPress() {
        longPressTriggered = true
        longPressJob?.cancel()
        longPressJob = null
        onLongClick?.invoke()
    }

    private fun resetPress() {
        pressedKey = null
        longPressTriggered = false
        longPressJob?.cancel()
        longPressJob = null
    }
}
