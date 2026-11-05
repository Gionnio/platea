#!/usr/bin/env bash
# Playback-related logs from a TV: app, MediaCodec, ExoPlayer, Dolby Vision, HDR and display mode.
# Usage: scripts/logcat.sh tcl|firetv [file]   (with file: also saved there)
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/devices.env

case "${1:-}" in
    tcl) serial="$TCL_IP:5555" ;;
    firetv) serial="$FIRETV_IP:5555" ;;
    *) echo "Usage: $0 tcl|firetv [file]"; exit 1 ;;
esac
adb connect "$serial" >/dev/null
adb -s "$serial" logcat -c

pid=$(adb -s "$serial" shell pidof com.github.gionnio.platea.debug | tr -d '\r' || true)
pattern=' Platea|MediaCodec|CCodec|Codec2|ACodec|OMX|ExoPlayer|MediaCodecVideoRenderer|MediaCodecAudioRenderer|AudioTrack|AudioSink|DolbyVision|dolby|dovi|DOVI|HDR|Hdr|DisplayManager|DisplayModeDirector|SurfaceFlinger.*mode|refresh|HdmiCec|hwcomposer|Playback decision|TranscodeReason|PlaybackViewModel|RefreshRateService|PlayerFactory'

filter() {
    if [[ -n "$pid" ]]; then
        grep --line-buffered -E "( $pid )|$pattern"
    else
        grep --line-buffered -E "$pattern"
    fi
}

if [[ -n "${2:-}" ]]; then
    adb -s "$serial" logcat -v threadtime | filter | tee "$2"
else
    adb -s "$serial" logcat -v threadtime | filter
fi
