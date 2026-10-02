# Quality bench

Objective slow-motion quality: synthesize frames we hold back, then compare against them.

```sh
tools/quality-bench/make-ground-truth.sh            # once (macOS defaults; pass your own photos elsewhere)
./gradlew :app:installDebug                         # BenchActivity exists only in debug builds
tools/quality-bench/run.sh flow                     # or: off | blend
```

Output is PSNR-Y of synthesized frames (higher is better; +3 dB ≈ half the error) next to the
real-frame score, which is the ceiling set by video compression. Run it before and after any
change to decoding, interpolation or encoding; a drop in mean or worst frame is a regression.

Reference (emulator, `c2.android.avc.encoder`): blend 25.8 · flow 34.0 (worst 31.1) dB, ceiling ≈ 36.3 dB.

`av-check.sh` is the multi-clip correctness check: a landscape clip, a rotated 44.1 kHz clip at
0.5x and a curved clip exported as one project, verified for duration, per-clip pitch, clicks at
cuts and audio placement. It prints PASS/FAIL per check and exits non-zero on any failure.

Pitfalls already hit: ground truth must be TV-range and cropped in RGB, frames must be paired by
index (not timestamp), and the crop window must stay inside the source photo for all 150 frames.
