#!/usr/bin/env bash
# Summary of the Platea playback journal: how many titles went in direct play, what the server converted and why,
# Dolby Vision files decoded on the base layer, fallbacks.
# Usage: scripts/journal.sh tcl|firetv [--raw]
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/devices.env

case "${1:-}" in
    tcl) serial="$TCL_IP:5555" ;;
    firetv) serial="$FIRETV_IP:5555" ;;
    *) echo "Usage: $0 tcl|firetv [--raw]"; exit 1 ;;
esac
adb connect "$serial" >/dev/null
PKG=com.github.gionnio.platea.debug
# Private copy first (debug builds, every device); the external one for optimized builds, where adb can read it
journal=$(adb -s "$serial" shell "run-as $PKG cat files/platea/playback-journal.jsonl 2>/dev/null" | tr -d '\r' | grep '^{' || true)
[[ -n "$journal" ]] || journal=$(adb -s "$serial" shell "cat /sdcard/Android/data/$PKG/files/platea/playback-journal.jsonl 2>/dev/null" | tr -d '\r' | grep '^{' || true)
[[ -n "$journal" ]] || { echo "Journal empty or not moved yet: play something first"; exit 0; }
if [[ "${2:-}" == "--raw" ]]; then echo "$journal"; exit 0; fi

python3 - "$journal" <<'PY'
import json, sys
from collections import Counter
events = [json.loads(l) for l in sys.argv[1].splitlines() if l.strip()]
decisions = [e for e in events if e["event"] == "decision"]
print(f"Period: {events[0]['time'][:16]} → {events[-1]['time'][:16]}   events: {len(events)}")
print(f"\nPlayback decisions: {len(decisions)}")
for method, n in Counter(e["method"] for e in decisions).most_common():
    print(f"  {method:<14} {n}")
other = [e for e in decisions if e["method"] != "DIRECT_PLAY"]
if other:
    print("\nNot in direct play:")
    for e in other:
        print(f"  {e['time'][:16]}  {e.get('item','?')}  [{e['method']}] {e.get('reasons','')}  video={e.get('video','')} audio={e.get('audio','')}")
dv = [e for e in events if e["event"] == "decoder" and e.get("note")]
if dv:
    print("\nDolby Vision on the base layer decoder:")
    for e in dv:
        print(f"  {e['time'][:16]}  {e['name']}  {e.get('range','')}")
fb = [e for e in events if e["event"] == "fallback"]
if fb:
    print("\nFallbacks after errors:")
    for e in fb:
        print(f"  {e['time'][:16]}  {e['action']}: {e['reason']}")
if not other and not dv and not fb:
    print("\nAll in direct play, no fallbacks. ✅")
PY
