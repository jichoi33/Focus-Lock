package com.focuslock.app

import android.animation.ValueAnimator
import androidx.compose.animation.core.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalInspectionMode
import android.media.AudioAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.SystemClock
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.text.TextStyle

private val light = lightColorScheme(
    primary = Color(0xFF1659CD), onPrimary = Color.White,
    primaryContainer = Color(0xFFE9F1FF), onPrimaryContainer = Color(0xFF173F87),
    background = Color(0xFFF3F4F8), onBackground = Color(0xFF171B25),
    surface = Color.White, onSurface = Color(0xFF171B25),
    surfaceVariant = Color(0xFFF1F3F8), onSurfaceVariant = Color(0xFF555E70),
    surfaceContainer = Color(0xFFF6F7FA), surfaceContainerHighest = Color(0xFFE9EDF4),
    outline = Color(0xFF767F90), outlineVariant = Color(0xFFE1E5EE)
)
private val dark = darkColorScheme(
    primary = Color(0xFFA9C7FF), onPrimary = Color(0xFF102E62),
    primaryContainer = Color(0xFF243A5F), onPrimaryContainer = Color(0xFFD5E4FF),
    background = Color(0xFF11141B), onBackground = Color(0xFFF1F4FB),
    surface = Color(0xFF1B202A), onSurface = Color(0xFFF1F4FB),
    surfaceVariant = Color(0xFF242B37), onSurfaceVariant = Color(0xFFBBC4D5),
    surfaceContainer = Color(0xFF202632), surfaceContainerHighest = Color(0xFF2D3644),
    outline = Color(0xFF969EAC), outlineVariant = Color(0xFF343D4F)
)
private val focusType = Typography(
    headlineLarge = TextStyle(fontSize = 34.sp, lineHeight = 42.sp, letterSpacing = (-0.9).sp, fontWeight = FontWeight.Bold),
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, letterSpacing = (-0.5).sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 27.sp, letterSpacing = (-0.3).sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium)
)

@Composable
internal fun FocusTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) dark else light
    MaterialTheme(colorScheme = colors, typography = focusType) {
        CompositionLocalProvider(LocalContentColor provides colors.onBackground, content = content)
    }
}

private enum class GlyphPath(val path: String) {
    Lock("M7 10V7a5 5 0 0 1 10 0v3 M6 10h12a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2v-8a2 2 0 0 1 2-2 Z M12 15v2"),
    Sliders("M5 3v3 M5 10v11 M12 3v9 M12 16v5 M19 3v2 M19 9v12 M3 8h4 M10 14h4 M17 7h4"),
    Chev("m9 6 6 6-6 6"),
    Plus("M12 5v14 M5 12h14"),
    Check("m5 12 4 4L19 6"),
    Clock("M12 7v5l3 2 M22 12a10 10 0 1 0-20 0 10 10 0 1 0 20 0"),
    Chart("M4 20h16 M7 16v-4 M12 16V5 M17 16V8"),
    Grid("M4 4h6v6H4z M14 4h6v6h-6z M4 14h6v6H4z M14 14h6v6h-6z"),
    Close("m6 6 12 12 M18 6 6 18")
}

@Composable
internal fun Symbol(name: String, description: String? = null, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current) {
    val glyph = GlyphPath.valueOf(name)
    val path = remember(glyph) { PathParser().parsePathString(glyph.path).toPath() }
    val label = if (description == null) Modifier else Modifier.semantics { contentDescription = description }
    Canvas(modifier.size(20.dp).then(label)) {
        scale(size.width / 24f, size.height / 24f, Offset.Zero) {
            drawPath(path, tint, style = Stroke(1.7f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

@Composable
internal fun GroupCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(23.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.outlineVariant)
    ) { Column(content = content) }
}

@Composable
internal fun Modifier.focusRainbow(): Modifier {
    val dark = isSystemInDarkTheme()
    val stops = remember(dark) {
        if (dark) listOf(Color(0xFF3B1D42), Color(0xFF452D25), Color(0xFF36361B), Color(0xFF153F39),
            Color(0xFF192E50), Color(0xFF302447))
        else listOf(Color(0xFFFFD5E5), Color(0xFFFFE0C9), Color(0xFFFFEEB8), Color(0xFFC7F1DC),
            Color(0xFFCBE8FF), Color(0xFFE3D7FF))
    }
    val motion = !LocalInspectionMode.current && ValueAnimator.areAnimatorsEnabled()
    val drift = if (motion) rememberInfiniteTransition(label = "Rainbow background").animateFloat(
        initialValue = -1f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(9500, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "Color drift"
    ) else null
    return drawWithCache {
        val dx = size.width * 0.22f
        val dy = size.height * 0.16f
        val brush = Brush.linearGradient(stops, Offset(dx, dy), Offset(dx + size.width, dy + size.height))
        val extent = Size(size.width + 2 * dx, size.height + 2 * dy)
        onDrawBehind {
            val shift = drift?.value ?: 0f
            translate(dx * (shift - 1f), dy * (shift - 1f)) { drawRect(brush, size = extent) }
        }
    }
}

@Composable
internal fun TimerCard(modifier: Modifier = Modifier, rainbow: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(28.dp)
    Column(
        modifier.fillMaxWidth()
            .then(if (rainbow) Modifier.clip(shape).focusRainbow() else Modifier.background(colors.primaryContainer, shape))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(15.dp),
        content = content
    )
}

@Composable
internal fun DurationFields(value: DurationInput, onChange: (DurationInput) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val total = value.total ?: 30
    val hours = total / 60
    val minutes = total % 60
    val maximumHours = Rule.DAY / 60
    val minimumMinutes = if (hours == 0) 1 else 0
    val maximumMinutes = if (hours == maximumHours) 0 else 59
    val rowHeight = with(LocalDensity.current) { (36.sp.toDp() + 12.dp).coerceAtLeast(48.dp) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.widthIn(max = 320.dp).fillMaxWidth()) {
            for (label in listOf("Hours", "Minutes")) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(label, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                }
            }
        }
        Box(Modifier.widthIn(max = 320.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Spacer(Modifier.fillMaxWidth().height(rowHeight).background(colors.primaryContainer, MaterialTheme.shapes.large))
            Row(Modifier.fillMaxWidth()) {
                DurationWheel("Hours", hours, 0..maximumHours, rowHeight, Modifier.weight(1f)) { next ->
                    onChange(DurationInput.of((next * 60 + minutes).coerceIn(1, Rule.DAY)))
                }
                key(minimumMinutes, maximumMinutes) {
                    DurationWheel("Minutes", minutes, minimumMinutes..maximumMinutes, rowHeight,
                        Modifier.weight(1f), twoDigits = true) { next ->
                        onChange(DurationInput.of(hours * 60 + next))
                    }
                }
            }
        }
    }
}

@Composable
private fun DurationWheel(label: String, value: Int, range: IntRange, rowHeight: Dp, modifier: Modifier,
                          twoDigits: Boolean = false, onValueChange: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pager = rememberPagerState(initialPage = value - range.first) { range.last - range.first + 1 }
    val listener by rememberUpdatedState(onValueChange)
    val latestValue by rememberUpdatedState(value)
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    LaunchedEffect(pager, view) {
        val context = view.context
        val motor = context.getSystemService(Vibrator::class.java)
        val pulse = VibrationEffect.createOneShot(22, 220)
        val touch = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        var lastPulse = 0L
        snapshotFlow { pager.currentPage + range.first }.collect { selected ->
            if (selected != latestValue) {
                val now = SystemClock.uptimeMillis()
                if (now - lastPulse >= 38 && view.isHapticFeedbackEnabled &&
                    Settings.System.getInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0) {
                    if (motor?.hasVibrator() == true) motor.vibrate(pulse, touch)
                    else view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_PRESS)
                    lastPulse = now
                }
                listener(selected)
            }
        }
    }
    VerticalPager(
        state = pager,
        modifier = modifier.height(rowHeight * 3).clearAndSetSemantics {
            contentDescription = label
            stateDescription = value.toString()
            if (range.first != range.last) {
                progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat(), range.first.toFloat()..range.last.toFloat(),
                    steps = range.last - range.first - 1)
                setProgress { requested ->
                    val index = requested.roundToInt().coerceIn(range) - range.first
                    scope.launch { pager.scrollToPage(index) }
                    true
                }
            }
        },
        contentPadding = PaddingValues(vertical = rowHeight),
        flingBehavior = PagerDefaults.flingBehavior(pager, pagerSnapDistance = PagerSnapDistance.atMost(12)),
        horizontalAlignment = Alignment.CenterHorizontally
    ) { index ->
        val selected = index == pager.currentPage
        val number = index + range.first
        Box(Modifier.fillMaxSize().clickable { scope.launch { pager.animateScrollToPage(index) } },
            contentAlignment = Alignment.Center) {
            Text(if (twoDigits) number.toString().padStart(2, '0') else number.toString(),
                modifier = Modifier.graphicsLayer {
                    val distance = abs(index - pager.currentPage - pager.currentPageOffsetFraction).coerceAtMost(1f)
                    scaleX = 1f - 0.22f * distance
                    scaleY = scaleX
                },
                style = MaterialTheme.typography.headlineLarge.copy(fontFeatureSettings = "'tnum'"),
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant)
        }
    }
}

@Composable
internal fun Caption(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)

@Composable
internal fun rainbowAccent(): Color =
    if (isSystemInDarkTheme()) Color(0xFFF0BFD9) else Color(0xFF693C5B)

@Composable
internal fun rainbowButtonColors() = ButtonDefaults.buttonColors(
    containerColor = rainbowAccent(),
    contentColor = if (isSystemInDarkTheme()) Color(0xFF371F31) else Color.White
)

@Composable
internal fun PrimaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier,
                          colors: ButtonColors = ButtonDefaults.buttonColors()) {
    Button(onClick, modifier.fillMaxWidth().heightIn(min = 50.dp), shape = RoundedCornerShape(15.dp), colors = colors) {
        Text(label)
    }
}
