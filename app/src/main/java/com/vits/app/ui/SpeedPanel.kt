package com.vits.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vits.app.CurveChoice
import com.vits.app.EditorUiState
import com.vits.app.EditorViewModel
import com.vits.app.SpeedTab
import com.vits.engine.SmoothMode
import com.vits.timeline.SpeedPreset
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

@Composable
fun SpeedPanel(state: EditorUiState, vm: EditorViewModel, sourceDurationUs: Long) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
            .background(VitsColors.Surface)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text("Speed", color = VitsColors.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        SegmentedTabs(
            options = listOf("Normal", "Curve"),
            selected = state.tab.ordinal,
            onSelect = { vm.setTab(SpeedTab.entries[it]) },
        )
        Spacer(Modifier.height(14.dp))
        when (state.tab) {
            SpeedTab.NORMAL -> NormalSpeed(state.constantSpeed, vm::setConstantSpeed)
            SpeedTab.CURVE -> CurveSpeed(state, vm)
        }
        Spacer(Modifier.height(10.dp))
        DurationLine(sourceDurationUs, vm.outputDurationUs)
        Spacer(Modifier.height(10.dp))
        SmoothSlowMo(state, vm)
    }
}

@Composable
private fun SegmentedTabs(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(VitsColors.SurfaceHigh)
            .padding(3.dp),
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (on) VitsColors.Background else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    color = if (on) VitsColors.Text else VitsColors.TextDim,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    fontSize = 14.sp,
                )
            }
        }
    }
}

/** Logarithmic 0.1x–100x slider: equal finger travel for 0.5x→1x and 1x→2x. */
@Composable
private fun NormalSpeed(speed: Double, onChange: (Double) -> Unit) {
    Column {
        Text(
            formatSpeed(speed),
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
            color = VitsColors.Accent,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
        )
        Slider(
            value = log10(speed).toFloat(),
            onValueChange = { v ->
                val raw = 10.0.pow(v.toDouble())
                onChange(if (kotlin.math.abs(v) < 0.025f) 1.0 else roundSpeed(raw))
            },
            valueRange = -1f..2f,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = VitsColors.Accent,
                inactiveTrackColor = VitsColors.SurfaceHigh,
            ),
        )
        // Tick labels centred under their slider positions (the track is inset by the thumb radius).
        BoxWithConstraints(Modifier.fillMaxWidth().height(16.dp).padding(horizontal = 10.dp)) {
            listOf(0.1, 1.0, 2.0, 5.0, 10.0, 100.0).forEach { s ->
                val frac = ((log10(s) + 1) / 3).toFloat()
                Text(
                    formatSpeed(s),
                    color = VitsColors.TextDim,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(36.dp).offset(x = maxWidth * frac - 18.dp),
                )
            }
        }
    }
}

@Composable
private fun CurveSpeed(state: EditorUiState, vm: EditorViewModel) {
    val choices: List<Pair<CurveChoice, String>> =
        listOf(CurveChoice.None to "None", CurveChoice.Custom to "Custom") +
            SpeedPreset.entries.map { CurveChoice.Preset(it) to it.label }
    Column {
        choices.chunked(4).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (choice, label) ->
                    PresetTile(
                        label = label,
                        choice = choice,
                        selected = state.curveChoice == choice,
                        onClick = { vm.selectCurve(choice) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(10.dp))
        }
        val curve = state.curve
        if (curve != null) {
            CurveEditor(
                curve = curve,
                selected = state.selectedPoint,
                playhead = vm.sourceFraction(state.positionUs),
                onCurveChange = vm::updateCurve,
                onSelect = vm::selectPoint,
                onScrub = vm::seekToSourceFraction,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = vm::addPoint) { Text("+ Beat") }
                TextButton(onClick = vm::deleteSelectedPoint, enabled = state.selectedPoint != null) { Text("Delete") }
                TextButton(onClick = vm::resetCurve) { Text("Reset") }
            }
        }
    }
}

@Composable
private fun PresetTile(
    label: String,
    choice: CurveChoice,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(10.dp))
                .background(VitsColors.SurfaceHigh)
                .border(
                    width = if (selected) 2.dp else 0.dp,
                    color = if (selected) VitsColors.Accent else Color.Transparent,
                    shape = RoundedCornerShape(10.dp),
                )
                .padding(12.dp),
            contentAlignment = Alignment.Center,
        ) {
            val tint = if (selected) VitsColors.Accent else VitsColors.Text
            Canvas(Modifier.fillMaxWidth().aspectRatio(1.3f)) {
                when (choice) {
                    CurveChoice.None -> {
                        val r = size.minDimension / 2.6f
                        drawCircle(tint, r, center, style = Stroke(2.dp.toPx()))
                        drawLine(tint, center + Offset(-r * 0.7f, r * 0.7f), center + Offset(r * 0.7f, -r * 0.7f), 2.dp.toPx())
                    }
                    CurveChoice.Custom -> {
                        listOf(0.3f, 0.7f).forEachIndexed { i, y ->
                            val yy = size.height * y
                            drawLine(tint, Offset(0f, yy), Offset(size.width, yy), 2.dp.toPx())
                            drawCircle(tint, 3.5.dp.toPx(), Offset(size.width * (if (i == 0) 0.65f else 0.35f), yy))
                        }
                    }
                    is CurveChoice.Preset -> drawMiniCurve(choice.preset.curve(), tint)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(label, color = if (selected) VitsColors.Accent else VitsColors.TextDim, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun DurationLine(sourceUs: Long, outputUs: Long) {
    Text(
        "Duration: ${"%.1f".format(sourceUs / 1e6)}s → ${"%.1f".format(outputUs / 1e6)}s",
        color = VitsColors.TextDim,
        fontSize = 13.sp,
    )
}

@Composable
private fun SmoothSlowMo(state: EditorUiState, vm: EditorViewModel) {
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Smooth slow-mo", color = VitsColors.Text, fontSize = 15.sp)
                Text(
                    if (state.hasSlowMotion) "Creates in-between frames where speed < 1x"
                    else "Takes effect when speed is below 1x",
                    color = VitsColors.TextDim,
                    fontSize = 12.sp,
                )
            }
            Switch(
                checked = state.smoothEnabled,
                onCheckedChange = vm::setSmoothEnabled,
                colors = SwitchDefaults.colors(checkedTrackColor = VitsColors.Accent),
            )
        }
        if (state.smoothEnabled) {
            Spacer(Modifier.height(8.dp))
            SegmentedTabs(
                options = listOf("Frame blending", "Optical flow"),
                selected = if (state.smoothMode == SmoothMode.OPTICAL_FLOW) 1 else 0,
                onSelect = { vm.setSmoothMode(if (it == 1) SmoothMode.OPTICAL_FLOW else SmoothMode.FRAME_BLENDING) },
            )
        }
    }
}

private fun roundSpeed(s: Double): Double = when {
    s < 1 -> (s * 20).roundToInt() / 20.0
    s < 10 -> (s * 10).roundToInt() / 10.0
    else -> s.roundToInt().toDouble()
}

fun formatSpeed(s: Double): String = when {
    s >= 10 -> "${s.roundToInt()}x"
    s == s.roundToInt().toDouble() -> "${s.roundToInt()}x"
    s < 1 -> "${"%.2f".format(s).trimEnd('0')}x"
    else -> "${"%.1f".format(s)}x"
}
