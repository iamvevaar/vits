#!/usr/bin/env bash
# PSNR-Y of every output frame against ground truth. At 0.5x, odd output frames are synthesized.
#   usage: score.sh <label>      (reads work/out_<label>.mp4)
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; W="$HERE/work"; LABEL="$1"
# settb+setpts=N pairs frames by index; matching by timestamp mispairs the ms-rounded mkv frames.
ffmpeg -v error -i "$W/out_$LABEL.mp4" -i "$W/gt60.mkv" -lavfi \
  "[0:v]format=yuv420p,settb=1/30,setpts=N[a];[1:v]format=yuv420p,settb=1/30,setpts=N[b];[a][b]psnr=stats_file=$W/psnr_$LABEL.txt" -f null -
python3 - "$W/psnr_$LABEL.txt" "$LABEL" <<'PY'
import re, sys
real, syn = [], []
for line in open(sys.argv[1]):
    n = int(re.search(r"n:(\d+)", line).group(1)) - 1
    y = re.search(r"psnr_y:(\S+)", line).group(1)
    if n >= 146:  # last frames sit past the final source frame
        continue
    (syn if n % 2 else real).append(99.0 if y == "inf" else float(y))
syn.sort()
mean = lambda v: sum(v) / len(v)
print(f"{sys.argv[2]:>14}: synthesized PSNR-Y mean {mean(syn):.2f} dB  worst {syn[0]:.2f}  "
      f"p10 {syn[len(syn) // 10]:.2f} | real frames {mean(real):.2f} dB (n={len(syn)})")
PY
