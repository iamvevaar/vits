package com.vits.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.vits.app.EditorViewModel.Companion.MIN_GAP
import com.vits.timeline.SpeedPoint
import com.vits.timeline.SpeedSpec
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow

/**
 * Interactive speed curve: x is position in the source clip, y is speed on a log scale
 * (0.1x–10x). Drag a point to reshape; touch empty space to scrub the playhead.
 */
@Composable
fun CurveEditor(
    curve: SpeedSpec.Curve,
    selected: Int?,
    playhead: Float,
    onCurveChange: (List<SpeedPoint>) -> Unit,
    onSelect: (Int?) -> Unit,
    onScrub: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val current by rememberUpdatedState(curve)
    val change by rememberUpdatedState(onCurveChange)
    val select by rememberUpdatedState(onSelect)
    val scrub by rememberUpdatedState(onScrub)

    Box(modifier.fillMaxWidth().height(190.dp)) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(190.dp)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val g = Geometry(size.width.toFloat(), size.height.toFloat(), density)
                        val down = awaitFirstDown()
                        val hitRadius = 28.dp.toPx()
                        val hit = current.points.withIndex()
                            .map { (i, p) -> i to (g.toOffset(p) - down.position).getDistance() }
                            .filter { it.second < hitRadius }
                            .minByOrNull { it.second }?.first
                        if (hit != null) select(hit) else scrub(g.xOf(down.position.x).toFloat())
                        down.consume()
                        while (true) {
                            val event = awaitPointerEvent()
                            val c = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) break
                            if (hit != null) {
                                change(movePoint(current.points, hit, g.xOf(c.position.x), g.speedOf(c.position.y)))
                            } else {
                                scrub(g.xOf(c.position.x).toFloat())
                            }
                            c.consume()
                        }
                    }
                },
        ) {
            val g = Geometry(size.width, size.height, density)
            drawGrid(g)
            drawCurve(g, curve)
            val px = g.left + playhead.coerceIn(0f, 1f) * g.width
            drawLine(Color.White, Offset(px, g.top - 6.dp.toPx()), Offset(px, g.bottom + 6.dp.toPx()), 2.dp.toPx())
            curve.points.forEachIndexed { i, p ->
                val o = g.toOffset(p)
                val r = 7.dp.toPx()
                drawCircle(if (i == selected) VitsColors.Accent else Color.White, r, o)
                drawCircle(Color.Black, r, o, style = Stroke(1.5.dp.toPx()))
            }
        }
    }
}

/** Moves point [i]; endpoints keep their x, interior points stay between their neighbours. */
private fun movePoint(points: List<SpeedPoint>, i: Int, x: Double, speed: Double): List<SpeedPoint> {
    val nx = when (i) {
        0 -> 0.0
        points.lastIndex -> 1.0
        else -> x.coerceIn(points[i - 1].x + MIN_GAP, points[i + 1].x - MIN_GAP)
    }
    val ns = speed.coerceIn(SpeedSpec.MIN_CURVE_SPEED, SpeedSpec.MAX_CURVE_SPEED)
    // Snap to 1x: it's the most common target and hard to hit exactly with a finger.
    val snapped = if (abs(log10(ns)) < 0.04) 1.0 else ns
    return points.toMutableList().also { it[i] = SpeedPoint(nx, snapped) }
}

private class Geometry(w: Float, h: Float, density: Float) {
    val left = 40f * density
    val right = w - 12f * density
    val top = 14f * density
    val bottom = h - 14f * density
    val width = right - left
    val height = bottom - top

    /** log10(speed) ∈ [-1, 1] mapped top (10x) to bottom (0.1x). */
    fun yOf(speed: Double) = top + ((1 - log10(speed)) / 2 * height).toFloat()
    fun speedOf(y: Float) = 10.0.pow(1 - 2 * ((y - top) / height).toDouble().coerceIn(0.0, 1.0))
    fun xOf(px: Float) = ((px - left) / width).toDouble().coerceIn(0.0, 1.0)
    fun toOffset(p: SpeedPoint) = Offset(left + p.x.toFloat() * width, yOf(p.speed))
}

private fun DrawScope.drawGrid(g: Geometry) {
    val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
    val paint = android.graphics.Paint().apply {
        color = android.graphics.Color.argb(255, 154, 154, 165)
        textSize = 11.dp.toPx()
        isAntiAlias = true
    }
    listOf(10.0 to "10x", 1.0 to "1x", 0.1 to "0.1x").forEach { (s, label) ->
        val y = g.yOf(s)
        drawLine(VitsColors.Grid, Offset(g.left, y), Offset(g.right, y), 1.dp.toPx(), pathEffect = dash)
        drawContext.canvas.nativeCanvas.drawText(label, 2.dp.toPx(), y + 4.dp.toPx(), paint)
    }
    drawLine(VitsColors.Grid, Offset(g.left, g.top), Offset(g.left, g.bottom), 1.dp.toPx())
}

private fun DrawScope.drawCurve(g: Geometry, curve: SpeedSpec.Curve) {
    val path = Path()
    val steps = 160
    for (i in 0..steps) {
        val x = i.toDouble() / steps
        val px = g.left + x.toFloat() * g.width
        val py = g.yOf(curve.speedAt(x))
        if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
    }
    drawPath(path, VitsColors.Accent, style = Stroke(2.5.dp.toPx()))
}

/** Tiny static rendering of a curve, for the preset tiles. */
fun DrawScope.drawMiniCurve(curve: SpeedSpec.Curve, color: Color) {
    val path = Path()
    val steps = 48
    for (i in 0..steps) {
        val x = i.toDouble() / steps
        val px = x.toFloat() * size.width
        val py = ((1 - log10(curve.speedAt(x))) / 2).toFloat() * size.height
        if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
    }
    drawLine(VitsColors.Grid, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1f,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)))
    drawPath(path, color, style = Stroke(2.dp.toPx()))
}
