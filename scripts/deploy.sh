#!/usr/bin/env bash
# Build the Platea debug APK and install it over Wi-Fi ADB.
# Usage: scripts/deploy.sh tcl|firetv|sony|all [--no-build]
set -euo pipefail
cd "$(dirname "$0")/.."

target="${1:-}"
[[ "$target" =~ ^(tcl|firetv|sony|all)$ ]] || { echo "Usage: $0 tcl|firetv|sony|all [--no-build]"; exit 1; }
[[ -f scripts/devices.env ]] || { echo "Missing scripts/devices.env (see devices.env.example)"; exit 1; }
source scripts/devices.env

export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 17)}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
PKG=com.github.gionnio.platea.debug

if [[ "${2:-}" != "--no-build" ]]; then
    ./gradlew assembleDebug --console=plain -q
fi
apk_dir=app/build/outputs/apk/debug

install_on() {
    local name="$1" ip="$2"
    local serial="$ip:5555"
    echo "== $name ($serial)"
    adb connect "$serial" >/dev/null
    adb -s "$serial" wait-for-device
    echo "   model: $(adb -s "$serial" shell getprop ro.product.model | tr -d '\r')"
    # Pick the APK for the device's primary ABI (Fire TV 4K Max runs a 32-bit userspace)
    local abi apk
    abi=$(adb -s "$serial" shell getprop ro.product.cpu.abi | tr -d '\r')
    # Newest build first: older APKs can still sit in the output folder
    apk=$(ls -t "$apk_dir"/Platea-debug-*-"$abi".apk 2>/dev/null | head -1)
    [[ -n "$apk" ]] || apk=$(ls -t "$apk_dir"/Platea-debug-*.apk | grep -vE 'arm64|armeabi|x86' | head -1)
    echo "   abi: $abi, APK: $(basename "$apk")"
    # No -g: granting the microphone permission up front made Fire OS revoke it hours later, which kills the app
    adb -s "$serial" install -r "$apk"
    adb -s "$serial" shell am start -n "$PKG/com.github.gionnio.platea.MainActivity" >/dev/null
}

case "$target" in
    tcl) install_on TCL "$TCL_IP" ;;
    sony) install_on Sony "$SONY_IP" ;;
    firetv) install_on "Fire TV" "$FIRETV_IP" ;;
    all) install_on TCL "$TCL_IP"; install_on "Fire TV" "$FIRETV_IP" ;;
esac
