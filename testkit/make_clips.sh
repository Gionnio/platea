#!/usr/bin/env bash
# Builds the test clips from a DV 8.1 + HDR10+ web source, without re-encoding.
# Cuts with mkvmerge (keeps the Dolby Vision RPU), derives variants with dovi_tool / hdr10plus_tool.
#
# Usage: testkit/make_clips.sh <source.mkv> [output_dir]
# Output (default testkit/clips, not committed) + reports in testkit/reports.
set -euo pipefail

src="${1:?Usage: $0 <source.mkv> [output_dir]}"
here="$(cd "$(dirname "$0")" && pwd)"
out="${2:-$here/clips}"
work="$here/work"
reports="$here/reports"
mkdir -p "$out" "$work" "$reports"

# Segments (hh:mm:ss): A has forced subtitles, B is bright and moving
SEG_A="00:28:05-00:28:35"
SEG_B="00:54:45-00:55:15"
# Kept tracks of the source: video 0, E-AC3 5.1 ita 1, E-AC3 5.1 eng 3, SRT forced ita 5, ita 6, eng 7
AUDIO="1,3"
SUBS="5,6,7"

for tool in mkvmerge mkvextract dovi_tool hdr10plus_tool ffprobe mediainfo; do
    command -v "$tool" >/dev/null || { echo "Missing $tool"; exit 1; }
done

fps=$(ffprobe -v error -select_streams v:0 -show_entries stream=r_frame_rate -of default=nw=1:nk=1 "$src")

# Cut a segment, drop release titles/tags, keep only the chosen tracks
cut() { # <segment> <dest>
    mkvmerge -q -o "$2" --split "parts:$1" --title "" --no-global-tags --no-track-tags \
        --audio-tracks "$AUDIO" --subtitle-tracks "$SUBS" \
        --track-name 0: --track-name 1:Italiano --track-name 3:English \
        --track-name 5:Forced --track-name 6:Italiano --track-name 7:English \
        "$src"
}

# Replace the video of <clip> with the elementary stream <hevc>
remux() { # <clip.mkv> <video.hevc> <dest>
    mkvmerge -q -o "$3" --title "" \
        --default-duration "0:${fps}fps" "$2" \
        -D "$1"
}

echo "== Cut"
cut "$SEG_A" "$work/A.mkv"
cut "$SEG_B" "$work/B.mkv"
for s in A B; do
    mkvextract -q tracks "$work/$s.mkv" "0:$work/$s.hevc"
    # DV removed (RPU NAL units dropped): HDR10 + HDR10+
    dovi_tool remove -i "$work/$s.hevc" -o "$work/$s.nodv.hevc"
    # HDR10+ removed (SEI dropped): DV 8.1 on HDR10 base
    hdr10plus_tool remove -i "$work/$s.hevc" -o "$work/$s.nohdr10plus.hevc"
    # Both removed: plain HDR10
    hdr10plus_tool remove -i "$work/$s.nodv.hevc" -o "$work/$s.hdr10.hevc"
done

echo "== Clips"
cp "$work/B.mkv" "$out/dv-p81-hdr10plus.mkv"
remux "$work/A.mkv" "$work/A.nohdr10plus.hevc" "$out/dv-p81.mkv"
remux "$work/A.mkv" "$work/A.nodv.hevc" "$out/hdr10plus.mkv"
remux "$work/B.mkv" "$work/B.hdr10.hevc" "$out/hdr10.mkv"
# Same video as dv-p81-hdr10plus but on segment A, to test forced subtitles on the bug file
cp "$work/A.mkv" "$out/dv-p81-hdr10plus-subs.mkv"

# Audio variants on the HDR10 video (ffmpeg encoders, experimental but valid streams)
ffmpeg -v error -y -i "$out/hdr10.mkv" -map 0:v -map 0:a:0 -c:v copy -c:a truehd -strict -2 -ac 6 -ar 48000 \
    -metadata:s:a:0 title="TrueHD 5.1" "$out/hdr10-truehd.mkv"
ffmpeg -v error -y -i "$out/hdr10.mkv" -map 0:v -map 0:a:0 -c:v copy -c:a dca -strict -2 -ac 6 -ar 48000 -b:a 1509k \
    -metadata:s:a:0 title="DTS 5.1" "$out/hdr10-dts.mkv"

# Subtitle variants on the HDR10 video: PGS (forced + full, written by make_pgs.py) and styled ASS
python3 "$here/make_pgs.py" "$work/full.sup" "1-4|Platea · PGS · riga di prova 1" "5-8|Sottotitolo completo in italiano" \
    "9-12|Le battute normali compaiono solo" "13-16|se scegli la traccia completa" "18-21|Platea · PGS · riga di prova 5" \
    "23-27|Ultima riga della traccia completa"
python3 "$here/make_pgs.py" "$work/forced.sup" "6-10|[FORZATO] Cartello in lingua straniera|forced" \
    "16-20|[FORZATO] Dialogo in un'altra lingua|forced"
mkvmerge -q -o "$out/hdr10-pgs.mkv" --title "" -S "$out/hdr10.mkv" \
    --language 0:ita --track-name 0:"Forzati (PGS)" --forced-display-flag 0:1 --default-track-flag 0:1 "$work/forced.sup" \
    --language 0:ita --track-name 0:"Italiano (PGS)" --default-track-flag 0:0 "$work/full.sup"
mkvmerge -q -o "$out/hdr10-ass.mkv" --title "" -S "$out/hdr10.mkv" \
    --language 0:ita --track-name 0:"Italiano (ASS)" --default-track-flag 0:0 "$here/subs-test.ass"

echo "== Verify"
for f in "$out"/*.mkv; do
    name=$(basename "$f" .mkv)
    {
        echo "### $name"
        mediainfo --Inform="General;Duration: %Duration/String3%  Size: %FileSize/String%\n" "$f"
        mediainfo --Inform="Video;Video: %Format% %Format_Profile% %Width%x%Height% %FrameRate% fps\nHDR: %HDR_Format/String%\n" "$f"
        mediainfo --Inform="Audio;Audio: %Format% %Channel(s)%ch %Language% default=%Default%\n" "$f"
        mediainfo --Inform="Text;Subs: %Format% %Language% forced=%Forced% default=%Default% '%Title%'\n" "$f"
        ffprobe -v error -select_streams v:0 -show_entries stream_side_data=dv_profile,dv_level,rpu_present_flag,el_present_flag,dv_bl_signal_compatibility_id -of compact=p=0 "$f" | sed 's/^/dvcC: /'
        mkvextract -q tracks "$f" "0:$work/check.hevc"
        if dovi_tool extract-rpu -i "$work/check.hevc" -o "$work/check.bin" >/dev/null 2>&1 && [[ -s "$work/check.bin" ]]; then
            dovi_tool info -i "$work/check.bin" --summary 2>/dev/null | grep -E "Frames|Profile|DM|L1|scene" | sed 's/^/dovi_tool: /'
        else
            echo "dovi_tool: no RPU"
        fi
        hdr10plus_tool extract -i "$work/check.hevc" -o "$work/check.json" >/dev/null 2>&1 \
            && echo "hdr10plus_tool: HDR10+ metadata present" || echo "hdr10plus_tool: no HDR10+ metadata"
        echo
    } > "$reports/$name.txt"
    cat "$reports/$name.txt"
done
rm -f "$work/check.hevc" "$work/check.json" "$work/check.bin"
echo "Clips in $out, reports in $reports"
