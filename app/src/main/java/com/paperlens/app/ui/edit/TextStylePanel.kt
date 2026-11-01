package com.paperlens.app.ui.edit

import com.paperlens.app.S
import com.paperlens.app.R

import android.graphics.Paint
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.automirrored.filled.FormatAlignRight
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.paperlens.app.imaging.TextLook

private val swatches = listOf(
    Color.Black, Color.White, Color(0xFFC62828), Color(0xFF0D47A1), Color(0xFF2E7D32), Color(0xFFFDD835), Color(0xFFEF6C00),
)

/** Add Text controls: the words, bold / italic / alignment, text colour and opacity, background and size. */
@Composable
fun TextStylePanel(
    hasText: Boolean,
    onEditText: () -> Unit,
    size: Float,
    onSize: (Float) -> Unit,
    look: TextLook,
    onLook: (TextLook) -> Unit,
) {
    Column(
        Modifier
            .heightIn(max = 300.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = onEditText) { Text(if (hasText) S.str(R.string.edit_text_2) else S.str(R.string.type_text)) }
            FilledTonalIconToggleButton(checked = look.bold, onCheckedChange = { onLook(look.copy(bold = it)) }) {
                Icon(Icons.Filled.FormatBold, S.str(R.string.bold))
            }
            FilledTonalIconToggleButton(checked = look.italic, onCheckedChange = { onLook(look.copy(italic = it)) }) {
                Icon(Icons.Filled.FormatItalic, S.str(R.string.italic))
            }
            val (icon, label, next) = when (look.align) {
                Paint.Align.LEFT -> Triple(Icons.AutoMirrored.Filled.FormatAlignLeft, S.str(R.string.aligned_left), Paint.Align.CENTER)
                Paint.Align.CENTER -> Triple(Icons.Filled.FormatAlignCenter, S.str(R.string.centred), Paint.Align.RIGHT)
                else -> Triple(Icons.AutoMirrored.Filled.FormatAlignRight, S.str(R.string.aligned_right), Paint.Align.LEFT)
            }
            FilledTonalIconToggleButton(checked = false, onCheckedChange = { onLook(look.copy(align = next)) }) { Icon(icon, label) }
        }

        Label(S.str(R.string.text_colour))
        Swatches(selected = look.color, showNone = false) { c -> onLook(look.copy(color = c ?: look.color)) }
        LabeledSlider(S.str(R.string.text_opacity), look.opacity, 0.1f..1f) { onLook(look.copy(opacity = it)) }

        Label(S.str(R.string.background))
        Swatches(selected = look.background, showNone = true) { c -> onLook(look.copy(background = c)) }
        if (look.background != null) {
            LabeledSlider(S.str(R.string.background_opacity), look.backgroundOpacity, 0.1f..1f) { onLook(look.copy(backgroundOpacity = it)) }
        }

        LabeledSlider(S.str(R.string.size), size, 0.015f..0.12f, onSize)
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 10.dp, bottom = 6.dp),
    )
}

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(140.dp))
        Slider(value = value, onValueChange = onChange, valueRange = range, modifier = Modifier.weight(1f))
    }
}

/** Colour dots; with [showNone] the first dot means "no background". */
@Composable
private fun Swatches(selected: Int?, showNone: Boolean, onPick: (Int?) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (showNone) {
            val on = selected == null
            Box(
                Modifier
                    .size(32.dp)
                    .border(if (on) 3.dp else 1.dp, if (on) MaterialTheme.colorScheme.primary else Color.LightGray, CircleShape)
                    .clickable { onPick(null) },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Block, S.str(R.string.no_background), Modifier.size(20.dp), tint = Color.Gray) }
        }
        swatches.forEach { c ->
            val argb = c.toArgb()
            val on = selected == argb
            Box(
                Modifier
                    .size(32.dp)
                    .background(c, CircleShape)
                    .border(if (on) 3.dp else 1.dp, if (on) MaterialTheme.colorScheme.primary else Color.LightGray, CircleShape)
                    .clickable { onPick(argb) },
            )
        }
    }
}
