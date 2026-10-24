#!/usr/bin/env bash
# Re-enable the launcher's accessibility service, which is what routes the
# remote's shortcut buttons (Netflix, YouTube, VIDIO, Prime Video -> SmartTube,
# WeTV -> BitTV, Settings). Installing a new APK, or force-stopping the app,
# can drop the grant on some TVs: this puts it back without touching any other
# enabled accessibility service.
set -euo pipefail

cd "$(dirname "$0")/.."

SERVICE="dev.gtv.launcher/dev.gtv.launcher.RemoteKeyService"
current=$(python3 tools/tv.py "settings get secure enabled_accessibility_services")

if [[ "$current" == *"$SERVICE"* ]]; then
    echo "Remote shortcut keys already enabled."
else
    case "$current" in
        null|""|" ") updated="$SERVICE" ;;
        *) updated="$current:$SERVICE" ;;
    esac
    python3 tools/tv.py "settings put secure enabled_accessibility_services '$updated'"
    python3 tools/tv.py "settings put secure accessibility_enabled 1"
    echo "Remote shortcut keys enabled."
fi

sleep 2
python3 tools/tv.py "dumpsys accessibility | grep -m1 'Bound services'" \
    || echo "Not bound yet: open Accessibility > Remote shortcut keys on the TV."
