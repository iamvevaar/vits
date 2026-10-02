package com.vits.app.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.vits.engine.preview.PreviewEngine

/** A SurfaceView whose surface is handed straight to the GPU render thread. */
@Composable
fun VideoPreview(engine: PreviewEngine, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            SurfaceView(context).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) = Unit
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) =
                        engine.setSurface(holder.surface, width, height)

                    override fun surfaceDestroyed(holder: SurfaceHolder) = engine.setSurface(null)
                })
            }
        },
    )
}
