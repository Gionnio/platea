#!/usr/bin/env bash
# Publishes the release build as the GitHub release v<version>, with the APKs and the latest.json that every
# Platea release build reads for its automatic updates (update/PlateaUpdater).
#   scripts/publish-update.sh [--no-build] ["release notes"]
# Needs a new version name (plateaVersion in app/build.gradle.kts); refuses a version code that is not newer
# than the published one, or a different signing certificate.
set -euo pipefail
cd "$(dirname "$0")/.."

REPO=Gionnio/platea
APK_DIR=app/build/outputs/apk/release
BUILD_TOOLS=$(ls -d /opt/homebrew/share/android-commandlinetools/build-tools/* | sort -V | tail -1)

BUILD=1; NOTES=""
for arg in "$@"; do
    case "$arg" in
        --no-build) BUILD=0 ;;
        --*) echo "Unknown option $arg" >&2; exit 2 ;;
        *) NOTES="$arg" ;;
    esac
done

if [[ $BUILD -eq 1 ]]; then
    export JAVA_HOME=$(/usr/libexec/java_home -v 17)
    export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
    ./gradlew -q assembleRelease -x lintVitalAnalyzeRelease -x lintVitalReportRelease -x lintVitalRelease
fi

STAGE=$(mktemp -d); trap 'rm -rf "$STAGE"' EXIT
cert() { "$BUILD_TOOLS/apksigner" verify --print-certs "$1" 2>/dev/null | sed -n 's/.*SHA-256 digest: //p' | head -1; }

# latest.json of this build, in the staging folder
python3 - "$APK_DIR" "$STAGE" "$(cert "$(ls "$APK_DIR"/*.apk | head -1)")" <<'EOF'
import hashlib, json, os, sys
apk_dir, stage, cert = sys.argv[1:]
meta = json.load(open(os.path.join(apk_dir, "output-metadata.json")))
code = {e["versionCode"] for e in meta["elements"]}
assert len(code) == 1, f"mixed version codes {code}"
apks = {}
for e in meta["elements"]:
    abi = next((f["value"] for f in e.get("filters", []) if f["filterType"] == "ABI"), "universal")
    data = open(os.path.join(apk_dir, e["outputFile"]), "rb").read()
    apks[abi] = {"file": e["outputFile"], "sha256": hashlib.sha256(data).hexdigest(), "size": len(data)}
manifest = {"versionCode": code.pop(), "versionName": meta["elements"][0]["versionName"], "cert": cert, "apks": apks}
json.dump(manifest, open(os.path.join(stage, "latest.json"), "w"), indent=2)
EOF

# Refuses a manifest that does not follow the published one ($1, may be missing)
check_newer() {
    python3 - "$STAGE/latest.json" "$1" <<'EOF'
import json, os, sys
new = json.load(open(sys.argv[1]))
if not os.path.exists(sys.argv[2]) or os.path.getsize(sys.argv[2]) == 0:
    sys.exit(0)
old = json.load(open(sys.argv[2]))
if old["versionCode"] >= new["versionCode"]:
    sys.exit(f"{new['versionName']} ({new['versionCode']}) is not newer than the published {old['versionName']} ({old['versionCode']})")
if old.get("cert") and old["cert"] != new["cert"]:
    sys.exit(f"signing certificate changed: {old['cert']} -> {new['cert']}")
EOF
}
field() { python3 -c "import json,sys;print(json.load(open(sys.argv[1]))[sys.argv[2]])" "$STAGE/latest.json" "$1"; }
NAME=$(field versionName)
TAG="v${NAME%%-*}"
gh release view "$TAG" --repo "$REPO" >/dev/null 2>&1 && { echo "Release $TAG exists already: raise plateaVersion in app/build.gradle.kts" >&2; exit 1; }
gh release download --repo "$REPO" --pattern latest.json --output "$STAGE/published.json" 2>/dev/null || true
check_newer "$STAGE/published.json"

ASSETS=("$STAGE/latest.json")
while IFS= read -r f; do ASSETS+=("$APK_DIR/$f"); done < <(python3 -c "import json,sys;print('\\n'.join(a['file'] for a in json.load(open(sys.argv[1]))['apks'].values()))" "$STAGE/latest.json")
gh release create "$TAG" "${ASSETS[@]}" --repo "$REPO" --title "Platea ${NAME%%-*}" --notes "${NOTES:-Platea $NAME}" --latest
echo "Published $NAME as GitHub release $TAG"
