#!/usr/bin/env bash
# Builds a 60 fps ground-truth clip with known sub-pixel motion and an occluding, rotating object,
# plus the 30 fps input the app receives (every other frame). Output: work/gt60.mkv, work/in30.mp4
#   usage: make-ground-truth.sh [background.jpg] [foreground.jpg]
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; W="$HERE/work"; mkdir -p "$W"
BG="${1:-/System/Library/Wallpapers/.default/DefaultAerial.jpg}"   # any photo ≥ 3840×2160
FG_SRC="${2:-/System/Library/Desktop Pictures/Sonoma.heic}"
if [[ "$FG_SRC" == *.heic ]]; then sips -s format jpeg "$FG_SRC" --out "$W/fg_full.jpg" >/dev/null; FG_SRC="$W/fg_full.jpg"; fi
ffmpeg -v error -y -i "$FG_SRC" -vf "crop='min(iw,ih)':'min(iw,ih)',scale=420:420" "$W/fg.jpg"

# Rendered at 2× then area-downscaled: the background pans by fractional pixels, like a real camera.
ffmpeg -v error -y -loop 1 -framerate 60 -i "$BG" -loop 1 -framerate 60 -i "$W/fg.jpg" -filter_complex "\
[0:v]format=rgb24,crop=2560:1440:'100+n*7.5':'500+140*sin(n/22)'[bg];\
[1:v]format=rgba,rotate='n*0.035':c=none:ow=hypot(iw\,ih):oh=ow,scale=600:-1[fg];\
[bg][fg]overlay=x='240+n*22':y='340+260*sin(n/17)':format=rgb,scale=1280:720:flags=area,\
scale=out_range=tv:out_color_matrix=bt709,format=yuv420p[v]" \
  -map "[v]" -frames:v 150 -color_range tv -colorspace bt709 -c:v libx264 -qp 0 -preset ultrafast "$W/gt60.mkv"
ffmpeg -v error -y -i "$W/gt60.mkv" -vf "select='not(mod(n\,2))',setpts=N/30/TB" -r 30 \
  -c:v libx264 -crf 8 -preset slow -g 30 -bf 2 -pix_fmt yuv420p -color_range tv -colorspace bt709 "$W/in30.mp4"
echo "ground truth ready in $W"
