# Vits

An offline Android video editor. Built so far: a **project/timeline model** (clips, trim, split,
reorder, per-clip speed, undo/redo, autosave), **speed control** (constant 0.1x–100x and speed
curves) with **smooth slow-motion** (frame blending or optical flow), real-time preview, and MP4
export in a foreground service.

## Build & run

```sh
./gradlew :app:installDebug                      # needs a device / emulator with GLES 3.0
./gradlew test                                   # pure-JVM unit tests (core modules)
tools/quality-bench/run.sh flow                  # interpolation quality vs ground truth
tools/quality-bench/av-check.sh                  # multi-clip A/V correctness
```

## Architecture

```
app/            Compose UI, EditorViewModel (History<Project>, autosave), export service
engine/         Android media + GPU engine; renders a Project
  decode/         VideoFrameSource: MediaCodec → SurfaceTexture (zero-copy, hardware decode)
  render/         CompositionRenderer (timeline → clip → canvas), AssetRenderer (per clip),
                  FrameCache (2 bracketing frames resident on GPU)
  interp/         OpticalFlow: coarse-to-fine Lucas–Kanade + variational refinement in shaders
  gl/             EGL, programs, render targets, all GLSL in Shaders.kt
  preview/        PreviewEngine: render thread paced by Choreographer
  export/         Exporter (H.264), AudioMixdown (per-clip retime → 48 kHz stereo AAC), AacDelay
core/project/   Project, Clip, MediaAsset, edits, History, ProjectCodec (versioned JSON). Pure Kotlin.
core/timeline/  SpeedSpec (incl. Windowed), presets, TimeMap (source ↔ output time). Pure Kotlin.
core/audio/     WsolaTimeStretcher (pitch-preserving), Resampler (windowed sinc). Pure Kotlin.
```

### Project model

Immutable data; every edit is a pure function (`splitAt`, `trim`, `moveClip`, `setSpeed`, …) and
`History` gives undo/redo with gesture coalescing. A clip's speed is defined over a *speed domain*
in source time that trims and splits never change, so splitting a speed-ramped clip moves no
frame. Saved projects carry `schemaVersion`; old files migrate forward, newer files are refused.

### Frame path (preview and export share it)

```
MediaExtractor → MediaCodec (HW) → SurfaceTexture/OES ─copy→ RGBA8 slot (ring of 3)
                                                           │
             TimeMap: output t → source t, local speed     ▼
                         speed ≥ 1x: nearest frame   ─┐
                         < 1x, Off: hold previous    ─┼→ display Surface  (preview)
                         < 1x, Blend: mix(a, b, α)   ─┤   encoder Surface (export) → MediaMuxer
                         < 1x, Flow: warp a,b by flow ┘
```

Pixels never leave the GPU.

### Optical flow pipeline (`interp/OpticalFlow.kt`, shaders in `gl/Shaders.kt`)

Per source-frame pair, both directions, coarse to fine over an area/binomial-filtered pyramid:
1. **Lucas–Kanade** with a Gaussian window and a per-window brightness offset (exposure drift
   cancels); correspondences that leave the frame get zero weight.
2. **3×3 median** of the flow (outlier removal).
3. **Variational refinement**: Charbonnier data term + edge-aware smoothness, exact 2×2 solve per
   pixel per Jacobi sweep; where the match left the frame, smoothness extrapolates the motion.
4. **Scene-cut check** (one 4-byte readback): failed pairs are cut cleanly, never morphed.

Per output frame: two-sided **z-buffered forward splat** of the flow to time t (the pixel visible in
both frames wins collisions), hole fill, then a composite that trusts each frame only where its own
flow agrees with the in-between flow, uses Catmull–Rom sampling, and falls back to a cross-fade
only where nothing can be trusted. `FlowQuality.PREVIEW` keeps playback real-time;
`FlowQuality.EXPORT` runs at up to 1280 px with more iterations.

### Measuring quality

The debug build has a headless `BenchActivity` (see its KDoc). The method: render a 60 fps ground-truth
clip with known motion, feed every other frame, export at 0.5x, and PSNR the synthesized frames
against the held-out ones. Results on a sub-pixel pan plus a rotating, occluding object
(encoder ceiling ≈ 36.2 dB):

| Mode | Mean | Worst frame |
|---|---|---|
| Frame repeat | 23.6 dB | 22.3 dB |
| Frame blending | 26.0 dB | 24.2 dB |
| Optical flow | **34.1 dB** | **31.0 dB** |

### Time mapping

Curves interpolate speed in log space with a smoothstep (flat at control points, no overshoot).
Output time is `D · ∫ 1/s(x) dx`, tabulated once (4096 Simpson cells), so each lookup is O(log n).
Video frames and the audio stretcher use the same map, so A/V stay in sync by construction.

## Engine rules

- Each pipeline owns its GL context on one thread. `Exporter.run` needs a **Looper** thread: on some
  Codec2 stacks the decoder never emits frames to a SurfaceTexture owned by a bare Thread.
- Frames stay in coded orientation; rotation is applied at display time (preview) or as the muxer
  orientation hint (export).
- Timestamps are snapped within 1 ms to real frames (µs rounding otherwise shows the previous frame).
- A wedged decoder is recreated once, resuming after the last delivered frame.
- Each clip on screen gets its own decoder and the next clip is pre-rolled before the cut.
- Audio: clip edges get 4 ms raised-cosine fades; the AAC encoder+decoder latency is measured on
  the device (`AacDelay`) and cancelled in packet timestamps. MediaMuxer can't write an edit list,
  so FFmpeg-based players hear audio ≈21 ms early (Android players: on time).
- Frame rate comes from the median frame interval, snapped to standard rates. `KEY_FRAME_RATE` is
  wrong when an edit list pads the track.

## Next layers

Timeline UI (clip strip, split/delete/reorder, undo buttons) · audio in preview · HEVC / 60 fps
export option · own MP4 muxer with edit lists · flow tuning on real footage · optional ML
interpolation (RIFE via NNAPI/GPU delegate).
