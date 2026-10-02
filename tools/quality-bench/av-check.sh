#!/usr/bin/env bash
# Multi-clip audio/video correctness check: a landscape clip, a rotated 44.1 kHz clip at 0.5x and a
# speed-curved clip are exported as one project, then verified: duration, per-clip pitch (retiming
# and resampling), clicks at cuts, and where each cut lands in the audio. Exits non-zero on failure.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; W="$HERE/work"; mkdir -p "$W"
ADB="${ADB:-adb}"; PKG=com.vits.app; F=/data/user/0/$PKG/files

ffmpeg -v error -y -f lavfi -i "testsrc2=size=1280x720:rate=30:duration=4" -f lavfi -i "sine=frequency=440:duration=4:sample_rate=48000" \
  -c:v libx264 -pix_fmt yuv420p -c:a aac -shortest "$W/av_land.mp4"
ffmpeg -v error -y -f lavfi -i "testsrc2=size=1280x720:rate=30:duration=3" -f lavfi -i "sine=frequency=330:duration=3:sample_rate=44100" \
  -c:v libx264 -pix_fmt yuv420p -c:a aac -shortest "$W/av_rot_flat.mp4"
ffmpeg -v error -y -display_rotation -90 -i "$W/av_rot_flat.mp4" -c copy "$W/av_rot.mp4"

cat > "$W/av_project.json" <<JSON
{"schemaVersion":1,"id":"avcheck",
 "assets":[
  {"id":"land","uri":"file://$F/av_land.mp4","durationUs":4000000,"width":1280,"height":720,"rotation":0,"frameRate":30.0,"videoTrack":0,"audioTrack":1},
  {"id":"rot","uri":"file://$F/av_rot.mp4","durationUs":3000000,"width":1280,"height":720,"rotation":90,"frameRate":30.0,"videoTrack":0,"audioTrack":1}],
 "main":{"clips":[
  {"id":"c1","assetId":"land","sourceStartUs":0,"sourceEndUs":2000000},
  {"id":"c2","assetId":"rot","sourceStartUs":500000,"sourceEndUs":2500000,"speed":{"type":"constant","speed":0.5}},
  {"id":"c3","assetId":"land","sourceStartUs":2000000,"sourceEndUs":4000000,"smoothing":"OPTICAL_FLOW",
   "speed":{"type":"curve","preset":"BULLET","points":[{"x":0.0,"speed":5.0},{"x":0.3,"speed":5.0},{"x":0.45,"speed":0.2},{"x":0.55,"speed":0.2},{"x":0.7,"speed":5.0},{"x":1.0,"speed":5.0}]}}]}}
JSON
for f in av_land.mp4 av_rot.mp4 av_project.json; do
  "$ADB" push "$W/$f" /data/local/tmp/$f >/dev/null; "$ADB" shell run-as $PKG cp /data/local/tmp/$f files/$f
done
"$ADB" shell am force-stop $PKG; "$ADB" logcat -c
"$ADB" shell am start -n $PKG/.BenchActivity --es project $F/av_project.json --es out $F/av_out.mp4 >/dev/null
RESULT=""
for _ in $(seq 1 600); do sleep 1; RESULT=$("$ADB" logcat -d -s VitsBench | grep -E "done|failed" || true); [[ -n "$RESULT" ]] && break; done
echo "${RESULT##*VitsBench: }"; [[ "$RESULT" == *done* ]] || exit 1
"$ADB" exec-out run-as $PKG cat files/av_out.mp4 > "$W/av_out.mp4"
ffmpeg -v error -y -i "$W/av_out.mp4" -f s16le -ac 1 -ar 48000 "$W/av_out.raw"
VDUR=$(ffprobe -v error -select_streams v -show_entries stream=duration -of csv=p=0 "$W/av_out.mp4")

python3 - "$W/av_out.raw" "$VDUR" <<'PY'
import struct, sys
d = open(sys.argv[1], "rb").read(); s = struct.unpack("<%dh" % (len(d) // 2), d); R = 48000
expected = 8.282  # 2 s + 2 s at 0.5x + 2 s of Bullet curve
ok = True
def check(name, cond, detail):
    global ok
    ok &= cond
    print(f"  {'PASS' if cond else 'FAIL'}  {name}: {detail}")
def pitch(a, b):
    seg = s[int(a * R):int(b * R)]
    return sum((seg[i - 1] < 0) != (seg[i] < 0) for i in range(1, len(seg))) / 2 / (b - a)
def step(a, b): return max(abs(s[i] - s[i - 1]) for i in range(int(a * R) + 1, int(b * R)))
def dip(c):  # centre of the crossfade dip near a cut, in seconds
    env = [(max(abs(x) for x in s[k:k + 48]), k) for k in range(int((c - .06) * R), int((c + .06) * R), 48)]
    return min(env)[1] / R
v = float(sys.argv[2])
check("video duration", abs(v - expected) < 0.05, f"{v:.3f}s (expected ≈{expected})")
for (a, b, hz) in [(0.2, 1.8, 440), (2.2, 5.8, 330), (6.4, 8.1, 440)]:
    p = pitch(a, b); check(f"pitch {a}-{b}s", abs(p - hz) < 2, f"{p:.1f} Hz (expected {hz})")
normal = max(step(0.5, 1.5), step(3.0, 4.0))
for c in (2.0, 6.0):
    j = step(c - .05, c + .05); check(f"no click at {c}s", j < normal * 1.5, f"max step {j} vs normal {normal}")
    off = (dip(c) - c) * 1000; check(f"cut {c}s lands in audio", abs(off) < 30, f"{off:+.0f} ms (FFmpeg decoder; Android decoders land ≈21 ms later, i.e. on the cut)")
sys.exit(0 if ok else 1)
PY
