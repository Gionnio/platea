#!/usr/bin/env bash
# Diagnostic report of the last playback, pulled from the TV log.
# Usage: scripts/report.sh tcl|firetv [output.txt]   (default: testkit/reports/<device>-<timestamp>.txt)
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/devices.env

case "${1:-}" in
    tcl) serial="$TCL_IP:5555" ;;
    firetv) serial="$FIRETV_IP:5555" ;;
    *) echo "Usage: $0 tcl|firetv [output.txt]"; exit 1 ;;
esac
out="${2:-testkit/reports/$1-$(date +%Y%m%d-%H%M%S).txt}"
adb connect "$serial" >/dev/null

log=$(adb -s "$serial" logcat -d -v time)
section() { printf '\n## %s\n' "$1"; }

{
    echo "# Platea playback report — $(date '+%Y-%m-%d %H:%M')"
    section "Device"
    adb -s "$serial" shell 'echo "model: $(getprop ro.product.model)"; echo "android: $(getprop ro.build.version.release) (SDK $(getprop ro.build.version.sdk))"; echo "build: $(getprop ro.build.display.id)"'
    echo "app: $(adb -s "$serial" shell dumpsys package com.github.gionnio.platea.debug | grep -m1 versionName | tr -d ' ')"

    section "Capabilities and profile (tag Platea)"
    grep -E " Platea *\(" <<<"$log" | sed -E 's/^[0-9-]+ [0-9:.]+ I\/Platea *\( *[0-9]+\): //' | tail -n 12 || echo "(none: start a playback first)"

    section "Server decision"
    grep -E "Playback decision|Selected backend|Switching player backend|TranscodeReasons|transcodeInfo=" <<<"$log" | sed -E 's/(api_key|ApiKey|Token|token)=[^&" ]*/\1=…/g' | tail -n 6 || echo "(not found)"

    section "Video decoder"
    grep -oE "OMX\.[A-Za-z0-9_.]+|c2\.[A-Za-z0-9_.]+decoder[A-Za-z0-9_.]*" <<<"$(grep -E "onVideoDecoderInitialized|MediaCodec.*(Created|init)|createByCodecName|allocate|Allocating" <<<"$log")" | sort | uniq -c | tail -n 6 || true
    grep -E "omx\.video\.[a-z0-9]+\.(StreamAvgBitrateInKbps|TotalFramesDropped|StreamAvgFramerate)" <<<"$log" | tail -n 3 | sed -E 's/^.*MediaCodecLogger: //' || true
    grep -qE "PesReader" <<<"$log" && echo "container: MPEG-TS (remux/transcode via HLS)" || echo "container: original file (no TS reader seen)"

    section "Display"
    adb -s "$serial" shell dumpsys display | grep -m1 -oE "mActiveModeId=[0-9]+" || true
    adb -s "$serial" shell dumpsys display | grep -m1 -oE "HdrCapabilities\{mSupportedHdrTypes=\[[0-9, ]*\]" || true
} > "$out"

cat "$out"
echo
echo "Saved to $out"
