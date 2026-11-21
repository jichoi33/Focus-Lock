package com.focuslock.app

import android.animation.ValueAnimator
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

internal data class CompletedFocus(val mode: Int, val name: String, val millis: Long) {
    val timeLabel: String?
        get() = if (millis <= 0) null
            else if (millis < Rule.MINUTE) "Less than 1 min focused"
            else "${durationLabel((millis / Rule.MINUTE).toInt())} focused"
}

@Composable
internal fun CompletionScreen(modes: Int, results: List<CompletedFocus>, onDone: () -> Unit) {
    val accent = rainbowAccent()
    val modeLabel = when (modes) {
        SessionCompletion.HARD -> "Hard Lock complete"
        SessionCompletion.SOFT -> "Soft Lock complete"
        else -> "Hard Lock and Soft Lock complete"
    }
    Box(
        Modifier.fillMaxSize().clipToBounds().focusRainbow().safeDrawingPadding(),
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
                    Text(modeLabel, style = MaterialTheme.typography.labelLarge, color = accent,
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
                            Text(mode, style = MaterialTheme.typography.labelMedium, color = accent)
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
            PrimaryButton("Done", onDone, colors = rainbowButtonColors())
        }
    }
}

@Composable
private fun CompletionMark() {
    val motion = !LocalInspectionMode.current && ValueAnimator.areAnimatorsEnabled()
    val rotation = if (motion) rememberInfiniteTransition(label = "Completion ribbons").animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(14000, easing = LinearEasing)),
        label = "Orbit"
    ) else null
    Box(Modifier.size(188.dp).drawWithCache {
        val r = size.minDimension * 0.46f
        val cx = size.width / 2
        val cy = size.height / 2
        // Two curves meet at each tip so each ribbon tapers as it sweeps around the seal.
        val ribbon = Path().apply {
            moveTo(cx - r * 0.95f, cy + r * 0.16f)
            cubicTo(cx - r * 1.08f, cy - r * 0.62f, cx - r * 0.24f, cy - r * 1.12f, cx + r * 0.40f, cy - r * 0.82f)
            cubicTo(cx - r * 0.32f, cy - r * 0.94f, cx - r * 0.85f, cy - r * 0.46f, cx - r * 0.95f, cy + r * 0.16f)
            close()
        }
        val ribbons = listOf(Color(0xFFE7AB87), Color(0xFFA388E3), Color(0xFFEB8EAC)).map { hue ->
            Brush.linearGradient(listOf(hue.copy(alpha = 0.36f), hue),
                Offset(cx - r, cy), Offset(cx + r * 0.4f, cy - r))
        }
        val enamel = Brush.linearGradient(listOf(Color(0xFF965C80), Color(0xFF56354D)),
            Offset(cx, cy - r * 0.48f), Offset(cx, cy + r * 0.48f))
        val check = Path().apply {
            moveTo(cx - r * 0.18f, cy)
            lineTo(cx - r * 0.045f, cy + r * 0.13f)
            lineTo(cx + r * 0.20f, cy - r * 0.14f)
        }
        val stroke = Stroke(4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        onDrawBehind {
            val angle = rotation?.value ?: 0f
            ribbons.forEachIndexed { index, brush ->
                rotate(angle + index * 120f) { drawPath(ribbon, brush) }
            }
            drawCircle(enamel, radius = r * 0.52f)
            drawPath(check, Color.White, style = stroke)
        }
    })
}

@Preview(showBackground = true, widthDp = 390, heightDp = 740)
@Composable
private fun CompletionPreview() {
    FocusTheme { CompletionScreen(SessionCompletion.HARD, listOf(CompletedFocus(SessionCompletion.HARD, "Evening focus", 45 * Rule.MINUTE))) {} }
}
