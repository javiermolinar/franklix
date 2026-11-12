package com.shieldstream.tv.home.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shieldstream.tv.domain.CalendarDot
import com.shieldstream.tv.domain.MinRating
import com.shieldstream.tv.model.CalendarDate
import com.shieldstream.tv.model.MonthGrid
import com.shieldstream.tv.strings.R as StringsR
import com.shieldstream.tv.strings.localizedStringResource
import com.shieldstream.tv.ui.component.AppIcons
import com.shieldstream.tv.ui.component.LineIcons
import com.shieldstream.tv.ui.component.tvClickable
import com.shieldstream.tv.ui.theme.AppShapes
import com.shieldstream.tv.ui.theme.CalendarColors
import com.shieldstream.tv.ui.theme.InterFont

// The TV viewport is 960 dp wide (1 dp = 2 px at 1080p). Sizes are larger than the original design
// so the month and the day panel fill the screen, which has no header, logo or key hints.

internal fun calendarText(size: TextUnit, medium: Boolean = false) = TextStyle(
    fontFamily = InterFont,
    fontWeight = if (medium) FontWeight.Medium else FontWeight.Normal,
    fontSize = size,
    lineHeight = size * 1.25f,
    // Tabular figures keep day numbers, counts and ratings aligned.
    fontFeatureSettings = "tnum",
)

/**
 * A pill control in the app's chip style (as in My List): white when focused, a light fill when
 * selected, a faint fill otherwise. Rating chips are a single-choice group, so radio buttons.
 */
@Composable
internal fun CalendarChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    requester: FocusRequester,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 40.dp,
    horizontalPadding: Dp = 16.dp,
    minWidth: Dp = 0.dp,
    fontSize: TextUnit = 17.sp,
    contentDescription: String? = null,
    role: Role = Role.RadioButton,
) {
    var focused by remember { mutableStateOf(false) }
    val content = if (focused) CalendarColors.OnAccent else CalendarColors.Text
    Row(
        modifier = modifier
            .height(height)
            .defaultMinSize(minWidth = minWidth)
            .background(
                when {
                    focused -> CalendarColors.Accent
                    selected -> CalendarColors.ControlSelected
                    else -> CalendarColors.ControlIdle
                },
                AppShapes.Pill,
            )
            .focusRequester(requester)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .tvClickable(onClick = onClick)
            .semantics {
                this.selected = selected
                this.role = role
                contentDescription?.let { this.contentDescription = it }
            }
            .padding(horizontal = horizontalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(label, color = content, style = calendarText(fontSize, medium = true), maxLines = 1, softWrap = false)
    }
}

private val RatingLabels = listOf(MinRating.ANY, MinRating.SEVEN, MinRating.EIGHT, MinRating.NINE)

/** "Any · 7+ · 8+ · 9+". A star marks the group instead of a text label. */
@Composable
internal fun RatingChips(
    selected: MinRating,
    onSelect: (MinRating) -> Unit,
    requesters: List<FocusRequester>,
    onFocused: (Int) -> Unit,
) {
    val groupLabel = localizedStringResource(StringsR.string.calendar_min_rating)
    val any = localizedStringResource(StringsR.string.calendar_rating_any)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(
            AppIcons.Star,
            contentDescription = groupLabel,
            tint = CalendarColors.Release,
            modifier = Modifier.size(18.dp),
        )
        RatingLabels.forEachIndexed { index, rating ->
            val label = rating.value?.let { "${it.toInt()}+" } ?: any
            CalendarChip(
                label = label,
                selected = rating == selected,
                onClick = { onSelect(rating) },
                requester = requesters[index],
                onFocused = { onFocused(index) },
                minWidth = 60.dp,
                contentDescription = "$groupLabel $label",
            )
        }
    }
}

@Composable
internal fun MonthBar(
    title: String,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
    requesters: List<FocusRequester>,
    onFocused: (MonthControl) -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
        MonthArrow(LineIcons.ChevronLeft, localizedStringResource(StringsR.string.calendar_previous_month), onPrevious, requesters[0]) {
            onFocused(MonthControl.PREVIOUS)
        }
        Text(
            title,
            color = CalendarColors.Text,
            style = calendarText(22.sp, medium = true),
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        MonthArrow(LineIcons.ChevronRight, localizedStringResource(StringsR.string.calendar_next_month), onNext, requesters[1]) {
            onFocused(MonthControl.NEXT)
        }
        Spacer(Modifier.width(6.dp))
        CalendarChip(
            label = localizedStringResource(StringsR.string.calendar_today),
            selected = false,
            onClick = onToday,
            requester = requesters[2],
            onFocused = { onFocused(MonthControl.TODAY) },
            horizontalPadding = 14.dp,
            fontSize = 15.sp,
            role = Role.Button,
        )
    }
}

@Composable
private fun MonthArrow(icon: ImageVector, label: String, onClick: () -> Unit, requester: FocusRequester, onFocused: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier
            .size(36.dp)
            .background(if (focused) CalendarColors.Accent else CalendarColors.ControlIdle, CircleShape)
            .focusRequester(requester)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .tvClickable(onClick = onClick)
            .semantics {
                contentDescription = label
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (focused) CalendarColors.OnAccent else CalendarColors.Text, modifier = Modifier.size(20.dp))
    }
}

@Composable
internal fun WeekdayLabels(labels: List<String>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CellGap)) {
        labels.forEach { label ->
            Text(
                label,
                color = CalendarColors.TextSecondary,
                style = calendarText(15.sp, medium = true),
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

internal val CellGap = 6.dp
private val MinCellHeight = 52.dp

/**
 * Fixed grid height: six 52 dp rows. Five-row months share it with taller rows, so the legend and
 * the chips below never move and no space is left empty.
 */
internal val GridHeight = MinCellHeight * MonthGrid.MAX_ROWS + CellGap * (MonthGrid.MAX_ROWS - 1)

/**
 * The month's days. Moving the selection recomposes only the previous and new selected cells: each
 * cell derives its own selected state from [selected], and the grid itself depends only on the
 * month and its data. [labels] are prebuilt per month and data, one per cell.
 */
@Composable
internal fun DayGrid(
    grid: MonthGrid,
    today: CalendarDate,
    selected: () -> CalendarDate,
    dots: Map<CalendarDate, List<CalendarDot>>,
    labels: List<String>,
    requesters: List<FocusRequester>,
    onFocused: (CalendarDate) -> Unit,
    onClick: (CalendarDate) -> Unit,
) {
    // Read only by the selected cell, so a focus-zone change recomposes one cell.
    val gridFocused = remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .height(GridHeight)
            .onFocusChanged { gridFocused.value = it.hasFocus },
        verticalArrangement = Arrangement.spacedBy(CellGap),
    ) {
        for (row in 0 until grid.rows) {
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(CellGap)) {
                for (column in 0 until 7) {
                    val index = row * 7 + column
                    val date = grid.dates[index]
                    val inMonth = date in grid.month
                    GridCell(
                        date = date,
                        inMonth = inMonth,
                        isToday = date == today,
                        selected = selected,
                        gridFocused = gridFocused,
                        dots = if (inMonth) dots[date].orEmpty() else emptyList(),
                        label = labels[index],
                        requester = requesters[index],
                        onFocused = onFocused,
                        onClick = onClick,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** A cell's own recomposition scope: the selected-state reads stay here, not in the grid. */
@Composable
private fun GridCell(
    date: CalendarDate,
    inMonth: Boolean,
    isToday: Boolean,
    selected: () -> CalendarDate,
    gridFocused: State<Boolean>,
    dots: List<CalendarDot>,
    label: String,
    requester: FocusRequester,
    onFocused: (CalendarDate) -> Unit,
    onClick: (CalendarDate) -> Unit,
    modifier: Modifier,
) {
    val isSelected by remember(date, selected) { derivedStateOf { selected() == date } }
    DayCell(
        day = date.day,
        inMonth = inMonth,
        isToday = isToday,
        isSelected = isSelected,
        ring = when {
            !isSelected -> null
            gridFocused.value -> CalendarColors.FocusRing
            else -> CalendarColors.SelectedRing
        },
        dots = dots,
        label = label,
        requester = requester,
        onFocused = { onFocused(date) },
        onClick = { onClick(date) },
        modifier = modifier,
    )
}

/**
 * One day. Background, selection ring and dots are drawn in a single draw pass; only the number is
 * a Text. Only days with content get a background, so they stand out; empty days and adjacent-month
 * days (dimmed, no dots, still selectable) are just a number.
 */
@Composable
internal fun DayCell(
    day: Int,
    inMonth: Boolean,
    isToday: Boolean,
    isSelected: Boolean,
    ring: Color?,
    dots: List<CalendarDot>,
    label: String,
    requester: FocusRequester,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val background = when {
        isSelected -> CalendarColors.CellSelected
        dots.isNotEmpty() -> CalendarColors.Cell
        else -> Color.Transparent
    }
    Box(
        modifier = modifier
            .fillMaxHeight()
            .focusRequester(requester)
            .onFocusChanged { if (it.isFocused) onFocused() }
            .tvClickable(onClick = onClick)
            .semantics {
                contentDescription = label
                role = Role.Button
                selected = isSelected
            }
            .drawBehind {
                val radius = CornerRadius(CellRadius.toPx())
                if (background != Color.Transparent) drawRoundRect(background, cornerRadius = radius)
                if (ring != null) {
                    val width = RingWidth.toPx()
                    drawRoundRect(
                        color = ring,
                        topLeft = Offset(width / 2, width / 2),
                        size = Size(size.width - width, size.height - width),
                        cornerRadius = radius,
                        style = Stroke(width),
                    )
                }
                // The number and dots are centred together, whatever the row height.
                drawDots(dots, centerY = (size.height - CellContentHeight.toPx()) / 2 + DotCenterY.toPx())
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .padding(bottom = CellContentHeight - NumberSize)
                .size(NumberSize)
                .then(if (isToday) Modifier.background(CalendarColors.Accent, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                day.toString(),
                color = when {
                    isToday -> CalendarColors.OnAccent
                    inMonth -> CalendarColors.Text
                    else -> CalendarColors.TextOutside
                },
                style = calendarText(18.sp, medium = isToday),
                maxLines = 1,
            )
        }
    }
}

private val CellRadius = 6.dp
private val RingWidth = 2.dp
private val NumberSize = 34.dp
private val DotSize = 7.dp
private val DotGap = 4.dp

/** The number circle, a 4 dp gap and the dots. */
private val CellContentHeight = NumberSize + 4.dp + DotSize

/** Dot centre measured from the top of the cell content. */
private val DotCenterY = NumberSize + 4.dp + DotSize / 2

private fun DrawScope.drawDots(dots: List<CalendarDot>, centerY: Float) {
    if (dots.isEmpty()) return
    val size = DotSize.toPx()
    val gap = DotGap.toPx()
    val total = dots.size * size + (dots.size - 1) * gap
    var x = (this.size.width - total) / 2 + size / 2
    dots.forEach { dot ->
        drawDot(dot, Offset(x, centerY), size / 2)
        x += size + gap
    }
}

internal fun DrawScope.drawDot(dot: CalendarDot, center: Offset, radius: Float) {
    when (dot) {
        CalendarDot.EPISODE -> drawCircle(CalendarColors.EpisodeDot, radius, center)
        CalendarDot.RELEASE -> drawCircle(CalendarColors.Release, radius, center)
        CalendarDot.WATCHED -> {
            val stroke = 1.dp.toPx()
            drawCircle(CalendarColors.WatchedDot, radius - stroke / 2, center, style = Stroke(stroke))
        }
    }
}

@Composable
internal fun Legend() {
    val entries = listOf(CalendarDot.EPISODE, CalendarDot.WATCHED, CalendarDot.RELEASE)
    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
        entries.forEach { dot ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).drawBehind { drawDot(dot, center, size.minDimension / 2) })
                Spacer(Modifier.width(6.dp))
                Text(
                    localizedStringResource(
                        when (dot) {
                            CalendarDot.EPISODE -> StringsR.string.calendar_legend_episode
                            CalendarDot.WATCHED -> StringsR.string.calendar_legend_watched
                            CalendarDot.RELEASE -> StringsR.string.calendar_legend_release
                        },
                    ),
                    color = CalendarColors.TextSecondary,
                    style = calendarText(15.sp),
                )
            }
        }
    }
}
