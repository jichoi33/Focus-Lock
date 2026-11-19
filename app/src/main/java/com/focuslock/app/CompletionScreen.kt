package com.focuslock.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@Composable
internal fun CompletionScreen(modes: Int, onDone: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val modeLabel = when (modes) {
        SessionCompletion.HARD -> "Hard Lock complete"
        SessionCompletion.SOFT -> "Soft Lock complete"
        else -> "Hard Lock and Soft Lock complete"
    }
    BoxWithConstraints(
        Modifier.fillMaxSize().background(colors.background).safeDrawingPadding(),
        contentAlignment = Alignment.Center
    ) {
        val screenHeight = maxHeight
        Column(
            Modifier.widthIn(max = 420.dp).fillMaxWidth()
                .verticalScroll(rememberScrollState()).heightIn(min = screenHeight)
                .padding(horizontal = 28.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            CompletionMark()
            Spacer(Modifier.height(12.dp))
            Text(modeLabel, style = MaterialTheme.typography.labelLarge, color = colors.primary,
                textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text("Nicely done.", Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center)
            Spacer(Modifier.height(30.dp))
            PrimaryButton("Done", onDone)
        }
    }
}

@Composable
private fun CompletionMark() {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.size(204.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val unit = size.minDimension
            drawCircle(Brush.radialGradient(listOf(colors.primaryContainer, colors.background),
                center = center, radius = unit * 0.5f), radius = unit * 0.5f)
            drawCircle(colors.primary.copy(alpha = 0.18f), radius = unit * 0.34f, style = Stroke(1.dp.toPx()))
            drawCircle(colors.primary.copy(alpha = 0.12f), radius = unit * 0.43f, style = Stroke(1.dp.toPx()))

            val sparks = listOf(Offset(0.17f, 0.29f), Offset(0.78f, 0.18f), Offset(0.84f, 0.68f))
            sparks.forEachIndexed { index, position ->
                val point = position * unit
                val arm = unit * if (index == 1) 0.037f else 0.024f
                val tint = colors.primary.copy(alpha = if (index == 1) 0.8f else 0.55f)
                drawLine(tint, point - Offset(arm, 0f), point + Offset(arm, 0f), 2.dp.toPx(), StrokeCap.Round)
                drawLine(tint, point - Offset(0f, arm), point + Offset(0f, arm), 2.dp.toPx(), StrokeCap.Round)
            }
            drawCircle(colors.primary.copy(alpha = 0.42f), radius = 2.5.dp.toPx(),
                center = Offset(unit * 0.27f, unit * 0.79f))
        }
        Box(Modifier.size(84.dp).background(colors.primary, CircleShape), contentAlignment = Alignment.Center) {
            Symbol("Check", modifier = Modifier.size(42.dp), tint = colors.onPrimary)
        }
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 740)
@Composable
private fun CompletionPreview() {
    FocusTheme { CompletionScreen(SessionCompletion.HARD) {} }
}
