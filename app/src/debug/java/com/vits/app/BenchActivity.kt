package com.vits.app

import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.vits.engine.MediaInput
import com.vits.engine.SmoothMode
import com.vits.engine.export.ExportRequest
import com.vits.engine.export.Exporter
import com.vits.timeline.SpeedSpec
import java.io.File

/**
 * adb shell am start -n com.vits.app/.BenchActivity --es in <path> --es out <path>
 *     --ef speed 0.5 --es mode off|blend|flow
 * Logs "VitsBench: done ms=…" or "VitsBench: failed …".
 */
class BenchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val inPath = intent.getStringExtra("in") ?: return finish()
        val outPath = intent.getStringExtra("out") ?: return finish()
        val speed = intent.getFloatExtra("speed", 0.5f).toDouble()
        val mode = when (intent.getStringExtra("mode")) {
            "off" -> SmoothMode.OFF
            "blend" -> SmoothMode.FRAME_BLENDING
            else -> SmoothMode.OPTICAL_FLOW
        }
        val thread = HandlerThread("vits-bench").apply { start() }
        Handler(thread.looper).post {
            val start = SystemClock.elapsedRealtime()
            try {
                val input = MediaInput(applicationContext, Uri.fromFile(File(inPath)))
                val info = input.probe()
                Exporter(ExportRequest(input, info, SpeedSpec.Constant(speed), mode, File(outPath), bitrate = 40_000_000))
                    .run { }
                Log.i(TAG, "done ms=${SystemClock.elapsedRealtime() - start} mode=$mode")
            } catch (t: Throwable) {
                Log.e(TAG, "failed", t)
            }
            thread.quitSafely()
            runOnUiThread { finish() }
        }
    }

    private companion object {
        const val TAG = "VitsBench"
    }
}
