#!/usr/bin/env bash
# Exports work/in30.mp4 at 0.5x on a connected device (debug build installed) and scores it.
#   usage: run.sh <off|blend|flow> [label]
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"; W="$HERE/work"
ADB="${ADB:-adb}"; MODE="$1"; LABEL="${2:-$1}"; PKG=com.vits.app; F=/data/user/0/$PKG/files
[[ -f "$W/in30.mp4" ]] || { echo "run make-ground-truth.sh first" >&2; exit 1; }
"$ADB" push "$W/in30.mp4" /data/local/tmp/vits_in30.mp4 >/dev/null
"$ADB" shell run-as $PKG cp /data/local/tmp/vits_in30.mp4 files/in30.mp4
"$ADB" shell run-as $PKG rm -f files/out.mp4
"$ADB" shell am force-stop $PKG; "$ADB" logcat -c
"$ADB" shell am start -n $PKG/.BenchActivity --es in $F/in30.mp4 --es out $F/out.mp4 --ef speed 0.5 --es mode "$MODE" >/dev/null
RESULT=""
for _ in $(seq 1 900); do
  sleep 1; RESULT=$("$ADB" logcat -d -s VitsBench | grep -E "done|failed" || true); [[ -n "$RESULT" ]] && break
done
echo "${RESULT##*VitsBench: }"
[[ "$RESULT" == *done* ]] || exit 1
"$ADB" exec-out run-as $PKG cat files/out.mp4 > "$W/out_$LABEL.mp4"
"$HERE/score.sh" "$LABEL"
