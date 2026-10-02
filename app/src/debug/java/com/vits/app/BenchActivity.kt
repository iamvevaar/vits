package com.vits.app

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.vits.engine.AssetResolver
import com.vits.engine.MediaInput
import com.vits.engine.export.ExportRequest
import com.vits.engine.export.Exporter
import com.vits.project.Project
import com.vits.project.ProjectCodec
import com.vits.project.Smoothing
import com.vits.project.Speed
import com.vits.project.appendClip
import com.vits.project.setSmoothing
import com.vits.project.setSpeed
import java.io.File

/**
 * Headless export for quality benchmarks (debug builds only).
 *
 *   Single file at constant speed:
 *     adb shell am start -n com.vits.app/.BenchActivity --es in <path> --es out <path>
 *         --ef speed 0.5 --es mode off|blend|flow
 *   Any project (multi-clip, curves…), as saved by ProjectCodec:
 *     adb shell am start -n com.vits.app/.BenchActivity --es project <json path> --es out <path>
 *
 * Logs "VitsBench: done ms=…" or "VitsBench: failed …".
 */
class BenchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val outPath = intent.getStringExtra("out") ?: return finish()
        val resolver = AssetResolver { MediaInput(applicationContext, it) }
        val thread = HandlerThread("vits-bench").apply { start() }
        Handler(thread.looper).post {
            val start = SystemClock.elapsedRealtime()
            try {
                val project = intent.getStringExtra("project")?.let { ProjectCodec.decode(File(it).readText()) }
                    ?: singleClipProject()
                Exporter(ExportRequest(project, resolver, File(outPath), bitrate = 40_000_000)).run { }
                Log.i(TAG, "done ms=${SystemClock.elapsedRealtime() - start} clips=${project.main.clips.size} durationUs=${project.durationUs}")
            } catch (t: Throwable) {
                Log.e(TAG, "failed", t)
            }
            thread.quitSafely()
            runOnUiThread { finish() }
        }
    }

    private fun singleClipProject(): Project {
        val inPath = requireNotNull(intent.getStringExtra("in")) { "--es in or --es project required" }
        val smoothing = when (intent.getStringExtra("mode")) {
            "off" -> Smoothing.OFF
            "blend" -> Smoothing.FRAME_BLENDING
            else -> Smoothing.OPTICAL_FLOW
        }
        val asset = MediaInput(applicationContext, Uri.fromFile(File(inPath))).probe("bench")
        val p = Project(id = "bench").appendClip(asset)
        val id = p.main.clips[0].id
        return p.setSpeed(id, Speed.Constant(intent.getFloatExtra("speed", 0.5f).toDouble())).setSmoothing(id, smoothing)
    }

    private companion object {
        const val TAG = "VitsBench"
    }
}
