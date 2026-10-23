#!/usr/bin/env bash
# Verify APK acceptance with the existing debug APK, then build and install
# the current source. This intentionally does not change the default HOME app.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TV_HOST="${TV_HOST:-127.0.0.1}"
TV_PORT="${TV_PORT:-15555}"
APK="$ROOT/launcher/app/build/outputs/apk/debug/app-debug.apk"
REMOTE="/data/local/tmp/tv-debloat-launcher.apk"
if [[ -n "${GRADLE:-}" ]]; then
  GRADLE="$GRADLE"
elif command -v gradle >/dev/null 2>&1; then
  GRADLE="$(command -v gradle)"
elif [[ -x /private/tmp/gradle-8.9/bin/gradle ]]; then
  GRADLE="/private/tmp/gradle-8.9/bin/gradle"
else
  echo "Gradle 8.9 or a compatible installed Gradle is required." >&2
  exit 2
fi

tv() {
  TV_HOST="$TV_HOST" TV_PORT="$TV_PORT" python3 "$ROOT/tools/tv.py" "$@"
}

install_apk() {
  local file="$1" output
  tv --push "$file" "$REMOTE"
  output="$(tv "pm install -r $REMOTE" 2>&1)" || {
    printf '%s\n' "$output" >&2
    return 1
  }
  printf '%s\n' "$output"
  if ! printf '%s\n' "$output" | grep -qx 'Success'; then
    echo "Install did not report Success; stopping." >&2
    return 1
  fi
}

if [[ ! -f "$APK" ]]; then
  echo "No existing debug APK is available for the required install-acceptance check: $APK" >&2
  echo "Do not build yet. Put a known launcher APK at this path, then rerun." >&2
  exit 2
fi

sdk="$(tv 'getprop ro.build.version.sdk' | tr -d '\r')"
min_sdk="$(awk '$1 == "minSdk" { print $2; exit }' "$ROOT/launcher/app/build.gradle")"
if [[ ! "$sdk" =~ ^[0-9]+$ || ! "$min_sdk" =~ ^[0-9]+$ ]]; then
  echo "Could not read SDK level or launcher minSdk (device=$sdk, minSdk=$min_sdk)." >&2
  exit 2
fi
if (( sdk < min_sdk )); then
  echo "Device SDK $sdk is below launcher minSdk $min_sdk; stopping before build." >&2
  exit 1
fi

echo "Checking APK installation acceptance before building (device SDK $sdk)."
install_apk "$APK"

echo "Building launcher source."
"$GRADLE" -p "$ROOT/launcher" assembleDebug

echo "Installing the rebuilt launcher."
install_apk "$APK"
echo "Install complete. Set HOME and disable the stock launcher only after the separate device checks."
