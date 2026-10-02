package com.vits.app.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import com.vits.engine.AssetResolver
import com.vits.engine.MediaInput
import com.vits.engine.export.ExportCancelledException
import com.vits.engine.export.ExportRequest
import com.vits.engine.export.Exporter
import com.vits.project.Project
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface ExportStatus {
    data object Idle : ExportStatus
    data class Running(val progress: Float) : ExportStatus
    data class Done(val uri: Uri) : ExportStatus
    data class Failed(val message: String) : ExportStatus
}

/**
 * Process-wide owner of the (single) running export. The work itself happens in [ExportService],
 * a foreground service, so it survives the user leaving the app; screens just observe [status].
 */
object ExportManager {
    private val _status = MutableStateFlow<ExportStatus>(ExportStatus.Idle)
    val status: StateFlow<ExportStatus> = _status.asStateFlow()

    @Volatile private var pending: Project? = null
    @Volatile private var exporter: Exporter? = null

    fun start(context: Context, project: Project) {
        check(_status.value !is ExportStatus.Running) { "an export is already running" }
        pending = project
        _status.value = ExportStatus.Running(0f)
        ContextCompat.startForegroundService(context, Intent(context, ExportService::class.java))
    }

    fun cancel() {
        exporter?.cancel()
    }

    fun dismiss() {
        if (_status.value !is ExportStatus.Running) _status.value = ExportStatus.Idle
    }

    /** Runs the pending export on the calling (Looper) thread; called by [ExportService]. */
    internal fun runPending(context: Context, onProgress: (Float) -> Unit) {
        val project = pending ?: return
        pending = null
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = "vits_$stamp.mp4"
        val file = File(context.cacheDir, "exports/$name").apply { parentFile?.mkdirs() }
        val resolver = AssetResolver { MediaInput(context, it) }
        val job = Exporter(ExportRequest(project, resolver, file))
        exporter = job
        _status.value = try {
            var last = 0f
            job.run { p ->
                if (p - last >= 0.005f || p >= 1f) {
                    last = p
                    _status.value = ExportStatus.Running(p)
                    onProgress(p)
                }
            }
            ExportStatus.Done(Gallery.save(context, file, name))
        } catch (e: ExportCancelledException) {
            ExportStatus.Idle
        } catch (e: Throwable) {
            ExportStatus.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            exporter = null
            file.delete()
        }
    }
}
