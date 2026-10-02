package com.vits.app

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vits.app.export.ExportManager
import com.vits.app.export.ExportStatus
import com.vits.engine.AssetResolver
import com.vits.engine.MediaInput
import com.vits.engine.preview.PreviewEngine
import com.vits.project.Clip
import com.vits.project.History
import com.vits.project.Project
import com.vits.project.ProjectCodec
import com.vits.project.Smoothing
import com.vits.project.Speed
import com.vits.project.appendClip
import com.vits.project.placementAt
import com.vits.project.setSmoothing
import com.vits.project.setSpeed
import com.vits.project.startOf
import com.vits.project.timelineTimeOf
import com.vits.timeline.SpeedPoint
import com.vits.timeline.SpeedPreset
import com.vits.timeline.SpeedSpec
import com.vits.timeline.hasSlowMotion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.math.abs

enum class SpeedTab { NORMAL, CURVE }

/** What the curve grid has selected: nothing, a hand-made curve, or a preset. */
sealed interface CurveChoice {
    data object None : CurveChoice
    data object Custom : CurveChoice
    data class Preset(val preset: SpeedPreset) : CurveChoice
}

/** A failure to show the user; the UI owns the wording, [detail] is the technical cause. */
data class UiError(val kind: Kind, val detail: String?) {
    enum class Kind { OPEN_FAILED, PLAYBACK }
}

data class EditorUiState(
    val project: Project? = null,
    /** The clip the speed panel edits. */
    val clipId: String? = null,
    val loading: Boolean = false,
    val tab: SpeedTab = SpeedTab.NORMAL,
    /** Last constant speed, remembered while a curve is active so switching tabs restores it. */
    val constantSpeed: Double = 1.0,
    val selectedPoint: Int? = null,
    /** Smoothing to use when the toggle is switched back on. */
    val smoothMode: Smoothing = Smoothing.FRAME_BLENDING,
    val positionUs: Long = 0,
    val playing: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val export: ExportStatus = ExportStatus.Idle,
    val error: UiError? = null,
) {
    val clip: Clip? get() = clipId?.let { id -> project?.main?.clips?.firstOrNull { it.id == id } }

    /** The curve shown in the editor, over the clip's own 0..1; null when speed is constant. */
    val curve: SpeedSpec.Curve? get() = clip?.let(::editableCurve)

    val curveChoice: CurveChoice
        get() = when (val s = clip?.speed) {
            is Speed.Curve -> s.preset?.let { name -> SpeedPreset.entries.firstOrNull { it.name == name } }
                ?.let { CurveChoice.Preset(it) } ?: CurveChoice.Custom
            else -> CurveChoice.None
        }

    val smoothEnabled: Boolean get() = (clip?.smoothing ?: Smoothing.FRAME_BLENDING) != Smoothing.OFF
    val hasSlowMotion: Boolean get() = clip?.speedSpec?.hasSlowMotion() == true
    val timelineDurationUs: Long get() = project?.durationUs ?: 0
}

class EditorViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    private var history: History<Project>? = null
    private val autosaveFile = File(app.filesDir, "projects/autosave.json")
    private var autosaveJob: Job? = null

    private val resolver = AssetResolver { MediaInput(getApplication(), it) }

    val engine = PreviewEngine(resolver, object : PreviewEngine.Listener {
        override fun onPosition(timelineUs: Long, durationUs: Long) =
            _state.update { it.copy(positionUs = timelineUs) }

        override fun onPlayingChanged(playing: Boolean) = _state.update { it.copy(playing = playing) }

        override fun onError(error: Throwable) =
            _state.update { it.copy(error = UiError(UiError.Kind.PLAYBACK, error.message), playing = false) }
    })

    init {
        observeExports()
        restoreAutosave()
    }

    /** Normalized position (0..1) of the playhead inside the edited clip, for the curve editor. */
    fun sourceFraction(timelineUs: Long): Float {
        val s = _state.value
        val p = s.project?.placementAt(timelineUs) ?: return 0f
        val clip = s.clip ?: return 0f
        if (p.clip.id != clip.id) return if (timelineUs < (s.project.startOfOrNull(clip.id) ?: 0)) 0f else 1f
        return ((p.sourceUs - clip.sourceStartUs).toDouble() / clip.sourceDurationUs).toFloat()
    }

    fun load(uri: Uri) {
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                keepAccess(uri)
                val asset = withContext(Dispatchers.IO) {
                    MediaInput(getApplication(), uri).probe(UUID.randomUUID().toString())
                }
                startProject(Project(id = UUID.randomUUID().toString()).appendClip(asset), positionUs = 0)
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = UiError(UiError.Kind.OPEN_FAILED, e.message)) }
            }
        }
    }

    // ---------------------------------------------------------------- speed edits

    fun setTab(tab: SpeedTab) {
        val s = _state.value
        val clip = s.clip ?: return
        _state.update { it.copy(tab = tab) }
        // Normal always means constant speed; Curve keeps whatever curve (or none) is chosen.
        if (tab == SpeedTab.NORMAL && clip.speed is Speed.Curve) applySpeed(Speed.Constant(s.constantSpeed))
    }

    fun setConstantSpeed(speed: Double) {
        val v = speed.coerceIn(SpeedSpec.MIN_SPEED, SpeedSpec.MAX_SPEED)
        _state.update { it.copy(constantSpeed = v) }
        applySpeed(Speed.Constant(v), gesture = "speed")
    }

    fun selectCurve(choice: CurveChoice) {
        _state.update { it.copy(selectedPoint = null) }
        when (choice) {
            CurveChoice.None -> applySpeed(Speed.Constant(_state.value.constantSpeed))
            CurveChoice.Custom -> applySpeed(SpeedPreset.custom().toModel(preset = null))
            is CurveChoice.Preset -> applySpeed(choice.preset.curve().toModel(preset = choice.preset.name))
        }
    }

    fun updateCurve(points: List<SpeedPoint>) {
        val preset = (_state.value.clip?.speed as? Speed.Curve)?.preset
        applySpeed(Speed.Curve(points.map { Speed.Point(it.x, it.speed) }, preset), gesture = "curve")
    }

    fun selectPoint(index: Int?) = _state.update { it.copy(selectedPoint = index) }

    /** Adds a beat at the playhead (or in the widest gap if a point is already there). */
    fun addPoint() {
        val curve = _state.value.curve ?: return
        val pts = curve.points
        val head = sourceFraction(_state.value.positionUs).toDouble()
        val x = if (pts.all { abs(it.x - head) > MIN_GAP * 2 } && head in MIN_GAP..1 - MIN_GAP) {
            head
        } else {
            val i = (1 until pts.size).maxBy { pts[it].x - pts[it - 1].x }
            (pts[i].x + pts[i - 1].x) / 2
        }
        val added = (pts + SpeedPoint(x, curve.speedAt(x))).sortedBy { it.x }
        updateCurve(added)
        endGesture()
        _state.update { it.copy(selectedPoint = added.indexOfFirst { p -> p.x == x }) }
    }

    fun deleteSelectedPoint() {
        val curve = _state.value.curve ?: return
        val i = _state.value.selectedPoint ?: return
        if (i == 0 || i == curve.points.lastIndex || curve.points.size <= 2) return
        updateCurve(curve.points.filterIndexed { j, _ -> j != i })
        endGesture()
        _state.update { it.copy(selectedPoint = null) }
    }

    fun resetCurve() = selectCurve(_state.value.curveChoice)

    fun setSmoothEnabled(enabled: Boolean) =
        applySmoothing(if (enabled) _state.value.smoothMode else Smoothing.OFF)

    fun setSmoothMode(mode: Smoothing) {
        _state.update { it.copy(smoothMode = mode) }
        applySmoothing(mode)
    }

    /** Ends a continuous gesture (slider drag, curve drag): what follows is a new undo step. */
    fun endGesture() {
        history = history?.seal()
    }

    fun undo() = moveInHistory { it.undo() }

    fun redo() = moveInHistory { it.redo() }

    // ---------------------------------------------------------------- playback & export

    fun togglePlay() = if (_state.value.playing) engine.pause() else engine.play()

    fun pause() = engine.pause()

    fun seekTo(timelineUs: Long) {
        _state.update { it.copy(positionUs = timelineUs) }
        engine.seekTo(timelineUs)
    }

    fun seekToSourceFraction(x: Float) {
        val s = _state.value
        val p = s.project ?: return
        val clip = s.clip ?: return
        seekTo(p.timelineTimeOf(clip.id, clip.sourceStartUs + (x.coerceIn(0f, 1f) * clip.sourceDurationUs).toLong()))
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun export() {
        val project = _state.value.project ?: return
        if (ExportManager.status.value is ExportStatus.Running) return
        engine.pause()
        engine.setProject(null)  // free the preview decoders for the export pipeline
        ExportManager.start(getApplication(), project)
    }

    fun cancelExport() = ExportManager.cancel()

    fun dismissExport() = ExportManager.dismiss()

    // ---------------------------------------------------------------- internals

    private fun applySpeed(speed: Speed, gesture: String? = null) {
        val clip = _state.value.clip ?: return
        commit(gesture?.let { "$it:${clip.id}" }) { it.setSpeed(clip.id, speed) }
    }

    private fun applySmoothing(smoothing: Smoothing) {
        val clip = _state.value.clip ?: return
        commit(null) { it.setSmoothing(clip.id, smoothing) }
    }

    /**
     * Applies an edit, records it for undo, and keeps the same source frame on screen: changing a
     * clip's speed re-flows the timeline, but the picture must not jump.
     */
    private fun commit(gesture: String?, edit: (Project) -> Project) {
        val h = history ?: return
        val before = h.present
        val after = edit(before)
        if (after === before) return
        history = if (gesture == null) h.seal().record(after).seal() else h.record(after, gesture)
        show(after, remapPosition(before, after))
    }

    private fun moveInHistory(step: (History<Project>) -> History<Project>) {
        val h = history?.seal() ?: return
        val next = step(h)
        if (next === h) return
        history = next
        show(next.present, remapPosition(h.present, next.present))
    }

    private fun show(project: Project, positionUs: Long) {
        val clipId = _state.value.clipId?.takeIf { id -> project.main.clips.any { it.id == id } }
            ?: project.main.clips.firstOrNull()?.id
        _state.update {
            it.copy(
                project = project,
                clipId = clipId,
                positionUs = positionUs,
                canUndo = history?.canUndo == true,
                canRedo = history?.canRedo == true,
                tab = if (project.main.clips.firstOrNull { c -> c.id == clipId }?.speed is Speed.Curve) SpeedTab.CURVE else it.tab,
            )
        }
        engine.setProject(project, positionUs)
        scheduleAutosave(project)
    }

    private fun startProject(project: Project, positionUs: Long) {
        history = History(project)
        val clip = project.main.clips.firstOrNull()
        _state.update {
            it.copy(
                loading = false,
                clipId = clip?.id,
                tab = if (clip?.speed is Speed.Curve) SpeedTab.CURVE else SpeedTab.NORMAL,
                constantSpeed = (clip?.speed as? Speed.Constant)?.speed ?: 1.0,
                smoothMode = clip?.smoothing?.takeIf { s -> s != Smoothing.OFF } ?: Smoothing.FRAME_BLENDING,
                selectedPoint = null,
                playing = false,
            )
        }
        show(project, positionUs)
    }

    private fun remapPosition(before: Project, after: Project): Long {
        val pos = _state.value.positionUs
        val p = before.placementAt(pos) ?: return 0
        return if (after.main.clips.any { it.id == p.clip.id }) {
            after.timelineTimeOf(p.clip.id, p.sourceUs)
        } else {
            pos.coerceIn(0, after.durationUs)
        }
    }

    private fun observeExports() {
        viewModelScope.launch {
            var wasRunning = false
            ExportManager.status.collect { status ->
                _state.update { it.copy(export = status) }
                val running = status is ExportStatus.Running
                if (wasRunning && !running) {
                    _state.value.project?.let { engine.setProject(it, _state.value.positionUs) }
                }
                wasRunning = running
            }
        }
    }

    /** Keeps the picked file readable after restarts, so autosaved projects can reopen. */
    private fun keepAccess(uri: Uri) {
        runCatching {
            getApplication<Application>().contentResolver
                .takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun scheduleAutosave(project: Project) {
        autosaveJob?.cancel()
        autosaveJob = viewModelScope.launch(Dispatchers.IO) {
            delay(AUTOSAVE_DELAY_MS)
            autosaveFile.parentFile?.mkdirs()
            val tmp = File(autosaveFile.path + ".tmp")
            tmp.writeText(ProjectCodec.encode(project))
            tmp.renameTo(autosaveFile)  // atomic: a crash mid-write never corrupts the last save
        }
    }

    private fun restoreAutosave() {
        viewModelScope.launch {
            val project = withContext(Dispatchers.IO) {
                runCatching {
                    val p = ProjectCodec.decode(autosaveFile.readText())
                    // Only reopen if every file is still there and readable.
                    p.takeIf { it.assets.all { a -> MediaInput(getApplication(), a).isReadable() } }
                }.getOrNull()
            } ?: return@launch
            if (_state.value.project == null && project.main.clips.isNotEmpty()) startProject(project, positionUs = 0)
        }
    }

    override fun onCleared() {
        engine.release()
    }

    companion object {
        const val MIN_GAP = 0.02
        private const val AUTOSAVE_DELAY_MS = 500L
    }
}

private fun Project.startOfOrNull(clipId: String): Long? = runCatching { startOf(clipId) }.getOrNull()

private fun SpeedSpec.Curve.toModel(preset: String?) =
    Speed.Curve(points.map { Speed.Point(it.x, it.speed) }, preset)

/**
 * The clip's curve over its own 0..1. When a split or trim left the clip viewing only part of its
 * speed domain, the visible part is rebased: interior points are remapped and the window edges
 * become end points, so the editor always shows exactly what plays.
 */
private fun editableCurve(clip: Clip): SpeedSpec.Curve? {
    val speed = clip.speed as? Speed.Curve ?: return null
    val base = speed.toSpec()
    val domain = (clip.speedDomainEndUs - clip.speedDomainStartUs).toDouble()
    val x0 = (clip.sourceStartUs - clip.speedDomainStartUs) / domain
    val x1 = (clip.sourceEndUs - clip.speedDomainStartUs) / domain
    if (x0 == 0.0 && x1 == 1.0) return base
    val inner = base.points.filter { it.x > x0 + 1e-6 && it.x < x1 - 1e-6 }
        .map { SpeedPoint((it.x - x0) / (x1 - x0), it.speed) }
    return SpeedSpec.Curve(listOf(SpeedPoint(0.0, base.speedAt(x0))) + inner + SpeedPoint(1.0, base.speedAt(x1)))
}
