package com.focuslock.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
internal fun TimerCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(28.dp)
    Column(
        modifier.fillMaxWidth()
            .background(colors.primaryContainer, shape)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(15.dp),
        content = content
    )
}

@Composable
internal fun DurationFields(value: DurationInput, onChange: (DurationInput) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(17.dp), color = colors.surface,
        border = BorderStroke(1.dp, colors.outlineVariant)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            DurationField("Hours", value.hours, { onChange(value.copy(hours = it)) }, Modifier.weight(1f))
            Box(Modifier.width(1.dp).height(47.dp).background(colors.outlineVariant))
            DurationField("Minutes", value.minutes, { onChange(value.copy(minutes = it)) }, Modifier.weight(1f))
        }
    }
}

@Composable
private fun DurationField(label: String, value: String, update: (String) -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(modifier.padding(vertical = 9.dp, horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        BasicTextField(
            value = value,
            onValueChange = { if (it.length <= 2 && it.all(Char::isDigit)) update(it) },
            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                .semantics { contentDescription = label },
            textStyle = TextStyle(color = colors.onSurface, fontSize = 35.sp, lineHeight = 42.sp,
                fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, fontFeatureSettings = "'tnum'"),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            cursorBrush = SolidColor(colors.primary),
            singleLine = true
        )
    }
}

@Composable
internal fun Caption(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)

@Composable
internal fun PrimaryButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(onClick, modifier.fillMaxWidth().heightIn(min = 50.dp), shape = RoundedCornerShape(15.dp)) {
        Text(label)
    }
}
