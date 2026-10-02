package com.vits.app

import android.app.Application
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vits.engine.MediaInfo
import com.vits.engine.MediaInput
import com.vits.engine.SmoothMode
import com.vits.engine.export.ExportRequest
import com.vits.engine.export.Exporter
import com.vits.engine.preview.PreviewEngine
import com.vits.timeline.SpeedPoint
import com.vits.timeline.SpeedPreset
import com.vits.timeline.SpeedSpec
import com.vits.timeline.TimeMap
import com.vits.timeline.hasSlowMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class SpeedTab { NORMAL, CURVE }

/** What the curve grid has selected: nothing, a hand-made curve, or a preset. */
sealed interface CurveChoice {
    data object None : CurveChoice
    data object Custom : CurveChoice
    data class Preset(val preset: SpeedPreset) : CurveChoice
}

sealed interface ExportStatus {
    data object Idle : ExportStatus
    data class Running(val progress: Float) : ExportStatus
    data class Done(val uri: Uri) : ExportStatus
    data class Failed(val message: String) : ExportStatus
}

data class EditorUiState(
    val info: MediaInfo? = null,
    val loading: Boolean = false,
    val tab: SpeedTab = SpeedTab.NORMAL,
    val constantSpeed: Double = 1.0,
    val curveChoice: CurveChoice = CurveChoice.None,
    val curve: SpeedSpec.Curve? = null,
    val selectedPoint: Int? = null,
    val smoothEnabled: Boolean = true,
    val smoothMode: SmoothMode = SmoothMode.FRAME_BLENDING,
    val positionUs: Long = 0,
    val playing: Boolean = false,
    val export: ExportStatus = ExportStatus.Idle,
    val error: String? = null,
) {
    val spec: SpeedSpec
        get() = if (tab == SpeedTab.CURVE && curve != null) curve else SpeedSpec.Constant(constantSpeed)

    val engineMode: SmoothMode get() = if (smoothEnabled) smoothMode else SmoothMode.OFF
    val hasSlowMotion: Boolean get() = spec.hasSlowMotion()
}

class EditorViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    private var input: MediaInput? = null
    private var timeMap: TimeMap? = null
    private var exporter: Exporter? = null

    val engine = PreviewEngine(object : PreviewEngine.Listener {
        override fun onPosition(outputUs: Long, durationUs: Long) =
            _state.update { it.copy(positionUs = outputUs) }

        override fun onPlayingChanged(playing: Boolean) = _state.update { it.copy(playing = playing) }

        override fun onError(error: Throwable) =
            _state.update { it.copy(error = error.message ?: error.javaClass.simpleName, playing = false) }
    })

    init {
        engine.setSmoothMode(_state.value.engineMode)
    }

    val outputDurationUs: Long get() = timeMap?.outputDurationUs ?: 0

    /** Normalized source position (0..1) of the current frame, for the curve playhead. */
    fun sourceFraction(outputUs: Long): Float {
        val map = timeMap ?: return 0f
        return (map.sourceTimeAt(outputUs).toDouble() / map.sourceDurationUs).toFloat()
    }

    fun load(uri: Uri) {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val media = MediaInput(getApplication(), uri)
                val info = withContext(Dispatchers.IO) { media.probe() }
                input = media
                _state.update { it.copy(info = info, loading = false, positionUs = 0, playing = false) }
                rebuildTimeMap()
                engine.open(media, info)
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = "Couldn't open video: ${e.message}") }
            }
        }
    }

    fun setTab(tab: SpeedTab) = applySpeed { it.copy(tab = tab) }

    fun setConstantSpeed(speed: Double) =
        applySpeed { it.copy(constantSpeed = speed.coerceIn(SpeedSpec.MIN_SPEED, SpeedSpec.MAX_SPEED)) }

    fun selectCurve(choice: CurveChoice) = applySpeed {
        val curve = when (choice) {
            CurveChoice.None -> null
            CurveChoice.Custom -> SpeedPreset.custom()
            is CurveChoice.Preset -> choice.preset.curve()
        }
        it.copy(curveChoice = choice, curve = curve, selectedPoint = null)
    }

    fun updateCurve(points: List<SpeedPoint>) = applySpeed { it.copy(curve = SpeedSpec.Curve(points)) }

    fun selectPoint(index: Int?) = _state.update { it.copy(selectedPoint = index) }

    /** Adds a beat at the playhead (or in the widest gap if a point is already there). */
    fun addPoint() {
        val s = _state.value
        val curve = s.curve ?: return
        val pts = curve.points
        val head = sourceFraction(s.positionUs).toDouble()
        val x = if (pts.all { kotlin.math.abs(it.x - head) > MIN_GAP * 2 } && head in MIN_GAP..1 - MIN_GAP) {
            head
        } else {
            val i = (1 until pts.size).maxBy { pts[it].x - pts[it - 1].x }
            (pts[i].x + pts[i - 1].x) / 2
        }
        val added = (pts + SpeedPoint(x, curve.speedAt(x))).sortedBy { it.x }
        applySpeed { it.copy(curve = SpeedSpec.Curve(added), selectedPoint = added.indexOfFirst { p -> p.x == x }) }
    }

    fun deleteSelectedPoint() {
        val s = _state.value
        val curve = s.curve ?: return
        val i = s.selectedPoint ?: return
        if (i == 0 || i == curve.points.lastIndex || curve.points.size <= 2) return
        applySpeed { it.copy(curve = SpeedSpec.Curve(curve.points.filterIndexed { j, _ -> j != i }), selectedPoint = null) }
    }

    fun resetCurve() = selectCurve(_state.value.curveChoice)

    fun setSmoothEnabled(enabled: Boolean) = applyMode { it.copy(smoothEnabled = enabled) }

    fun setSmoothMode(mode: SmoothMode) = applyMode { it.copy(smoothMode = mode, smoothEnabled = true) }

    fun togglePlay() = if (_state.value.playing) engine.pause() else engine.play()

    fun pause() = engine.pause()

    fun seekTo(outputUs: Long) {
        _state.update { it.copy(positionUs = outputUs) }
        engine.seekTo(outputUs)
    }

    fun seekToSourceFraction(x: Float) {
        val map = timeMap ?: return
        seekTo(map.outputTimeAt((x.coerceIn(0f, 1f) * map.sourceDurationUs).toLong()))
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun export() {
        val s = _state.value
        val media = input ?: return
        val info = s.info ?: return
        if (s.export is ExportStatus.Running) return
        val resume = s.positionUs
        engine.pause()
        engine.open(null, null)  // free the preview decoder for the export pipeline
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(getApplication<Application>().cacheDir, "exports/vits_$stamp.mp4")
        file.parentFile?.mkdirs()
        val job = Exporter(ExportRequest(media, info, s.spec, s.engineMode, file))
        exporter = job
        _state.update { it.copy(export = ExportStatus.Running(0f)) }
        // A Looper thread: some codec/SurfaceTexture stacks deliver events through the caller's looper.
        val thread = HandlerThread("vits-export").apply { start() }
        Handler(thread.looper).post {
            val result = try {
                var last = 0f
                job.run { p ->
                    if (p - last >= 0.005f || p >= 1f) {
                        last = p
                        _state.update { it.copy(export = ExportStatus.Running(p)) }
                    }
                }
                ExportStatus.Done(Gallery.save(getApplication(), file, "vits_$stamp.mp4"))
            } catch (e: Throwable) {
                ExportStatus.Failed(if (e.message == "Export cancelled") "Cancelled" else e.message ?: "Export failed")
            } finally {
                file.delete()
            }
            _state.update { it.copy(export = result) }
            engine.open(media, info, resume)
            thread.quitSafely()
        }
    }

    fun cancelExport() = exporter?.cancel()

    fun dismissExport() = _state.update { it.copy(export = ExportStatus.Idle) }

    private fun applySpeed(change: (EditorUiState) -> EditorUiState) {
        _state.update(change)
        rebuildTimeMap()
        engine.setSpeed(_state.value.spec)
    }

    private fun applyMode(change: (EditorUiState) -> EditorUiState) {
        _state.update(change)
        engine.setSmoothMode(_state.value.engineMode)
    }

    private fun rebuildTimeMap() {
        val s = _state.value
        val info = s.info ?: return
        val old = timeMap
        val source = old?.sourceTimeAt(s.positionUs) ?: 0
        val map = TimeMap(s.spec, info.durationUs)
        timeMap = map
        _state.update { it.copy(positionUs = map.outputTimeAt(source)) }
    }

    override fun onCleared() {
        exporter?.cancel()
        engine.release()
    }

    companion object {
        const val MIN_GAP = 0.02
    }
}
