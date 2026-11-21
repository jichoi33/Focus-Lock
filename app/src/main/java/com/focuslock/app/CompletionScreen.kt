package com.focuslock.app

import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.cos

internal data class CompletedFocus(val mode: Int, val name: String, val millis: Long) {
    val timeLabel: String?
        get() = if (millis <= 0) null
            else if (millis < Rule.MINUTE) "Less than 1 min focused"
            else "${durationLabel((millis / Rule.MINUTE).toInt())} focused"
}

@Composable
internal fun CompletionScreen(modes: Int, results: List<CompletedFocus>, onDone: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val modeLabel = when (modes) {
        SessionCompletion.HARD -> "Hard Lock complete"
        SessionCompletion.SOFT -> "Soft Lock complete"
        else -> "Hard Lock and Soft Lock complete"
    }
    val dark = isSystemInDarkTheme()
    val backdrop = remember(dark) {
        if (dark) listOf(Color(0xFF3B1D42), Color(0xFF452D25), Color(0xFF36361B), Color(0xFF153F39),
            Color(0xFF192E50), Color(0xFF302447))
        else listOf(Color(0xFFFFD5E5), Color(0xFFFFE0C9), Color(0xFFFFEEB8), Color(0xFFC7F1DC),
            Color(0xFFCBE8FF), Color(0xFFE3D7FF))
    }
    val confetti = remember(dark) {
        if (dark) listOf(Color(0xFFFFA8C8), Color(0xFFFFCA84), Color(0xFF83E3BA), Color(0xFF92C9FF), Color(0xFFC6AAFF))
        else listOf(Color(0xFFCC4276), Color(0xFFC57812), Color(0xFF21856E), Color(0xFF3374CB), Color(0xFF8654C1))
    }
    val motion = !LocalInspectionMode.current && ValueAnimator.areAnimatorsEnabled()
    val drift = if (motion) rememberInfiniteTransition(label = "Confetti").animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(9500, easing = LinearEasing)),
        label = "Rising ribbons"
    ) else null
    Box(
        Modifier.fillMaxSize().drawWithCache {
            val wash = Brush.linearGradient(backdrop, Offset.Zero, Offset(size.width, size.height))
            val stroke = 2.5.dp.toPx()
            onDrawBehind {
                drawRect(wash)
                val t = drift?.value ?: 0.36f
                repeat(24) { i ->
                    // Stagger the loops and keep the middle clear for session details.
                    val rise = (t + i * 0.618034f) % 1f
                    val angle = (2 * PI * (rise + i * 0.31)).toFloat()
                    val inset = 0.035f + (i % 6) * 0.022f + sin(angle) * 0.023f
                    val x = if (i % 2 == 0) inset else 1f - inset
                    val center = Offset(size.width * x, size.height * (1f - rise))
                    val tint = confetti[i % confetti.size]
                    val opacity = (sin(PI * rise).toFloat() * 0.8f).coerceIn(0f, 1f)
                    if (i % 3 == 0) {
                        drawCircle(tint, stroke, center, alpha = opacity)
                    } else {
                        val arm = Offset(cos(angle), sin(angle)) * stroke * 1.8f
                        drawLine(tint, center - arm, center + arm, stroke, StrokeCap.Round, alpha = opacity)
                    }
                }
            }
        }.safeDrawingPadding(),
        contentAlignment = Alignment.Center
    ) {
        Column(Modifier.widthIn(max = 420.dp).fillMaxSize().padding(horizontal = 28.dp, vertical = 20.dp)) {
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                val availableHeight = maxHeight
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                        .heightIn(min = availableHeight).padding(vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    CompletionMark()
                    Spacer(Modifier.height(12.dp))
                    val singleResult = results.singleOrNull()
                    Text(singleResult?.timeLabel ?: "Nicely done.", Modifier.semantics { heading() },
                        style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(6.dp))
                    Text(modeLabel, style = MaterialTheme.typography.labelLarge, color = colors.primary,
                        textAlign = TextAlign.Center)
                    results.forEach { result ->
                        val name = result.name.takeUnless { it.isBlank() || (result.mode == SessionCompletion.HARD && it == "Hard Lock") }
                        if (singleResult != null) {
                            if (name != null) {
                                Spacer(Modifier.height(12.dp))
                                Text(name, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                            }
                        } else if (result.timeLabel != null || name != null) {
                            val mode = if (result.mode == SessionCompletion.HARD) "Hard Lock" else "Soft Lock"
                            Spacer(Modifier.height(16.dp))
                            Text(mode, style = MaterialTheme.typography.labelMedium, color = colors.primary)
                            result.timeLabel?.let {
                                Text(it, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                            }
                            name?.let {
                                Text(it, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            PrimaryButton("Done", onDone)
        }
    }
}

@Composable
private fun CompletionMark() {
    val colors = MaterialTheme.colorScheme
    val preview = LocalInspectionMode.current
    var hasPlayed by rememberSaveable { mutableStateOf(false) }
    val animateOnEntry = remember { !preview && !hasPlayed && ValueAnimator.areAnimatorsEnabled() }
    val motionEnabled = !preview && ValueAnimator.areAnimatorsEnabled()
    val pulse = if (motionEnabled) rememberInfiniteTransition(label = "Celebration").animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "Rings and sparks"
    ) else null
    val progress = remember { Animatable(if (animateOnEntry) 0f else 1f) }

    LaunchedEffect(Unit) {
        hasPlayed = true
        if (animateOnEntry) {
            progress.animateTo(1f, tween(durationMillis = 850, easing = LinearEasing))
        }
    }

    Box(Modifier.size(204.dp).drawWithCache {
        val unit = size.minDimension
        val origin = Offset(size.width / 2f, size.height / 2f)
        val glow = Brush.radialGradient(listOf(colors.primaryContainer, colors.primaryContainer.copy(alpha = 0f)),
            center = origin, radius = unit * 0.5f)
        val ringStroke = Stroke(1.dp.toPx())
        val sparkStroke = 2.dp.toPx()
        val checkStroke = Stroke(3.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val check = Path().apply {
            moveTo(origin.x - 12.dp.toPx(), origin.y)
            lineTo(origin.x - 4.dp.toPx(), origin.y + 8.dp.toPx())
            lineTo(origin.x + 13.dp.toPx(), origin.y - 9.dp.toPx())
        }
        val checkLength = PathMeasure().apply { setPath(check, forceClosed = false) }
        val visibleCheck = Path()
        val sparks = listOf(Offset(0.17f, 0.29f), Offset(0.78f, 0.18f), Offset(0.84f, 0.68f))
        val dot = Offset(unit * 0.27f, unit * 0.79f)

        onDrawBehind {
            val time = progress.value
            val breath = pulse?.value ?: 0f
            val arrival = celebrationPhase(time, 0f, 0.48f)
            val ripple = celebrationPhase(time, 0.20f, 0.88f)
            drawCircle(glow, radius = unit * 0.5f, alpha = arrival)
            drawCircle(colors.primary.copy(alpha = 0.18f * ripple),
                radius = unit * lerp(0.23f, 0.34f + 0.025f * breath, ripple), style = ringStroke)
            drawCircle(colors.primary.copy(alpha = 0.12f * ripple),
                radius = unit * lerp(0.27f, 0.43f + 0.035f * breath, ripple), style = ringStroke)

            sparks.forEachIndexed { index, position ->
                val spread = celebrationPhase(time, 0.34f + index * 0.06f, 0.86f + index * 0.06f)
                val point = origin + (position * unit - origin) * lerp(0.56f, 1f + 0.055f * breath, spread)
                val arm = unit * (if (index == 1) 0.037f else 0.024f) * spread * (1f + 0.25f * breath)
                val tint = colors.primary.copy(alpha = (if (index == 1) 0.8f else 0.55f) * spread)
                drawLine(tint, point - Offset(arm, 0f), point + Offset(arm, 0f), sparkStroke, StrokeCap.Round)
                drawLine(tint, point - Offset(0f, arm), point + Offset(0f, arm), sparkStroke, StrokeCap.Round)
            }
            drawCircle(colors.primary.copy(alpha = 0.42f * ripple), radius = 2.5.dp.toPx() * ripple,
                center = origin + (dot - origin) * lerp(0.56f, 1f, ripple))

            drawCircle(colors.primary, radius = 42.dp.toPx() * lerp(0.84f, 1f, arrival))
            val trace = celebrationPhase(time, 0.16f, 0.67f)
            visibleCheck.reset()
            if (checkLength.getSegment(0f, checkLength.length * trace, visibleCheck, startWithMoveTo = true)) {
                drawPath(visibleCheck, colors.onPrimary, style = checkStroke)
            }
        }
    })
}

private fun celebrationPhase(time: Float, start: Float, end: Float): Float =
    FastOutSlowInEasing.transform(((time - start) / (end - start)).coerceIn(0f, 1f))

@Preview(showBackground = true, widthDp = 390, heightDp = 740)
@Composable
private fun CompletionPreview() {
    FocusTheme { CompletionScreen(SessionCompletion.HARD, listOf(CompletedFocus(SessionCompletion.HARD, "Evening focus", 45 * Rule.MINUTE))) {} }
}
