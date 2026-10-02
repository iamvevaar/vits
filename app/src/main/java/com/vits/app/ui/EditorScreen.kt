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
import com.vits.app.export.ExportStatus
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.vits.app.R
import com.vits.app.UiError
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

@Composable
fun EditorScreen(vm: EditorViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.load(uri)
    }
    val pickVideo = {
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
    }
    val clip = state.clip
    val context = LocalContext.current
    // Ask once for notifications so a background export can show progress; export runs either way.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        vm.export()
    }
    val export = {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            vm.export()
        }
    }

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
            Text(stringResource(R.string.app_name), color = VitsColors.Text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            if (clip != null) {
                TextButton(onClick = pickVideo) { Text(stringResource(R.string.action_replace), color = VitsColors.TextDim) }
                Button(onClick = export, enabled = state.export !is ExportStatus.Running) { Text(stringResource(R.string.action_export)) }
            }
        }

        Box(
            Modifier.fillMaxWidth().weight(1f).background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            if (clip != null) {
                VideoPreview(vm.engine, Modifier.fillMaxSize())
            } else if (state.loading) {
                CircularProgressIndicator()
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Button(onClick = pickVideo) { Text(stringResource(R.string.action_import_video)) }
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.works_offline), color = VitsColors.TextDim, fontSize = 12.sp)
                }
            }
        }

        if (clip != null) {
            Transport(
                playing = state.playing,
                positionUs = state.positionUs,
                durationUs = state.timelineDurationUs,
                onToggle = vm::togglePlay,
                onSeek = vm::seekTo,
                onSeekFinished = vm::endGesture,
            )
            Box(Modifier.verticalScroll(rememberScrollState()).weight(1.15f)) {
                SpeedPanel(state, vm, clip)
            }
        }
    }

    ExportDialog(state.export, vm)
    state.error?.let { err ->
        val msg = stringResource(
            if (err.kind == UiError.Kind.OPEN_FAILED) R.string.error_open_video else R.string.error_playback,
            err.detail.orEmpty(),
        )
        AlertDialog(
            onDismissRequest = vm::dismissError,
            confirmButton = { TextButton(onClick = vm::dismissError) { Text(stringResource(R.string.action_ok)) } },
            title = { Text(stringResource(R.string.error_title)) },
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
    onSeekFinished: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onToggle) {
            if (playing) PauseGlyph() else Icon(Icons.Filled.PlayArrow, stringResource(R.string.action_play), tint = VitsColors.Text)
        }
        Text(
            stringResource(R.string.time_position, formatTime(positionUs), formatTime(durationUs)),
            color = VitsColors.TextDim,
            fontSize = 12.sp,
        )
        Spacer(Modifier.width(8.dp))
        Slider(
            value = if (durationUs > 0) positionUs.toFloat() / durationUs else 0f,
            onValueChange = { onSeek((it * durationUs).toLong()) },
            onValueChangeFinished = onSeekFinished,
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
    val label = stringResource(R.string.action_pause)
    androidx.compose.foundation.Canvas(
        Modifier.width(18.dp).height(18.dp).semantics { contentDescription = label },
    ) {
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
            title = { Text(stringResource(R.string.export_running)) },
            text = {
                Column {
                    LinearProgressIndicator(progress = { status.progress }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.export_percent, (status.progress * 100).toInt()), color = VitsColors.TextDim)
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = vm::cancelExport) { Text(stringResource(R.string.action_cancel)) } },
        )
        is ExportStatus.Done -> AlertDialog(
            onDismissRequest = vm::dismissExport,
            title = { Text(stringResource(R.string.export_saved)) },
            text = { Text(stringResource(R.string.export_saved_where)) },
            confirmButton = {
                if (status.uri.scheme == "content") {
                    TextButton(onClick = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW).setDataAndType(status.uri, "video/mp4")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                        )
                        vm.dismissExport()
                    }) { Text(stringResource(R.string.action_play)) }
                }
            },
            dismissButton = { TextButton(onClick = vm::dismissExport) { Text(stringResource(R.string.action_close)) } },
        )
        is ExportStatus.Failed -> AlertDialog(
            onDismissRequest = vm::dismissExport,
            title = { Text(stringResource(R.string.export_failed)) },
            text = { Text(status.message) },
            confirmButton = { TextButton(onClick = vm::dismissExport) { Text(stringResource(R.string.action_ok)) } },
        )
    }
}

private fun formatTime(us: Long): String {
    val tenths = us / 100_000
    return "%d:%02d.%d".format(tenths / 600, (tenths / 10) % 60, tenths % 10)
}

