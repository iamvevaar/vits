package com.vits.app.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vits.app.EditorViewModel
import com.vits.app.ExportStatus

@Composable
fun EditorScreen(vm: EditorViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.load(uri)
    }
    val pickVideo = {
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
    }
    val info = state.info

    Column(
        Modifier
            .fillMaxSize()
            .background(VitsColors.Background)
            .safeDrawingPadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Vits", color = VitsColors.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            if (info != null) {
                TextButton(onClick = pickVideo) { Text("Replace", color = VitsColors.TextDim) }
                Button(onClick = vm::export, enabled = state.export !is ExportStatus.Running) { Text("Export") }
            }
        }

        Box(
            Modifier.fillMaxWidth().weight(1f).background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            if (info != null) {
                VideoPreview(vm.engine, Modifier.fillMaxSize())
            } else if (state.loading) {
                CircularProgressIndicator()
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Button(onClick = pickVideo) { Text("Import video") }
                    Spacer(Modifier.height(8.dp))
                    Text("Works fully offline", color = VitsColors.TextDim, fontSize = 12.sp)
                }
            }
        }

        if (info != null) {
            Transport(
                playing = state.playing,
                positionUs = state.positionUs,
                durationUs = vm.outputDurationUs,
                onToggle = vm::togglePlay,
                onSeek = vm::seekTo,
            )
            Box(Modifier.verticalScroll(rememberScrollState()).weight(1.15f)) {
                SpeedPanel(state, vm, info.durationUs)
            }
        }
    }

    ExportDialog(state.export, vm)
    state.error?.let { msg ->
        AlertDialog(
            onDismissRequest = vm::dismissError,
            confirmButton = { TextButton(onClick = vm::dismissError) { Text("OK") } },
            title = { Text("Something went wrong") },
            text = { Text(msg) },
        )
    }
}

@Composable
private fun Transport(
    playing: Boolean,
    positionUs: Long,
    durationUs: Long,
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onToggle) {
            if (playing) PauseGlyph() else Icon(Icons.Filled.PlayArrow, "Play", tint = VitsColors.Text)
        }
        Text(
            "${formatTime(positionUs)} / ${formatTime(durationUs)}",
            color = VitsColors.TextDim,
            fontSize = 12.sp,
        )
        Spacer(Modifier.width(8.dp))
        Slider(
            value = if (durationUs > 0) positionUs.toFloat() / durationUs else 0f,
            onValueChange = { onSeek((it * durationUs).toLong()) },
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = VitsColors.Accent,
                inactiveTrackColor = VitsColors.SurfaceHigh,
            ),
        )
    }
}

@Composable
private fun PauseGlyph() {
    androidx.compose.foundation.Canvas(Modifier.width(18.dp).height(18.dp)) {
        val w = size.width * 0.3f
        drawRect(VitsColors.Text, topLeft = androidx.compose.ui.geometry.Offset(size.width * 0.12f, 0f),
            size = androidx.compose.ui.geometry.Size(w, size.height))
        drawRect(VitsColors.Text, topLeft = androidx.compose.ui.geometry.Offset(size.width * 0.58f, 0f),
            size = androidx.compose.ui.geometry.Size(w, size.height))
    }
}

@Composable
private fun ExportDialog(status: ExportStatus, vm: EditorViewModel) {
    val context = LocalContext.current
    when (status) {
        ExportStatus.Idle -> Unit
        is ExportStatus.Running -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Exporting") },
            text = {
                Column {
                    LinearProgressIndicator(progress = { status.progress }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text("${(status.progress * 100).toInt()}%", color = VitsColors.TextDim)
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = vm::cancelExport) { Text("Cancel") } },
        )
        is ExportStatus.Done -> AlertDialog(
            onDismissRequest = vm::dismissExport,
            title = { Text("Saved") },
            text = { Text("Your video is in Movies/Vits.") },
            confirmButton = {
                if (status.uri.scheme == "content") {
                    TextButton(onClick = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW).setDataAndType(status.uri, "video/mp4")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                        )
                        vm.dismissExport()
                    }) { Text("Play") }
                }
            },
            dismissButton = { TextButton(onClick = vm::dismissExport) { Text("Close") } },
        )
        is ExportStatus.Failed -> AlertDialog(
            onDismissRequest = vm::dismissExport,
            title = { Text("Export failed") },
            text = { Text(status.message) },
            confirmButton = { TextButton(onClick = vm::dismissExport) { Text("OK") } },
        )
    }
}

private fun formatTime(us: Long): String {
    val tenths = us / 100_000
    return "%d:%02d.%d".format(tenths / 600, (tenths / 10) % 60, tenths % 10)
}

