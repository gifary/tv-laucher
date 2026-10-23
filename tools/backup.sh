#!/usr/bin/env bash
# Snapshot everything we might need to undo. Run this BEFORE any change.
#
#   tools/backup.sh
#
# Writes backup/<timestamp>/ and points backup/latest at it.
set -uo pipefail
cd "$(dirname "$0")/.."

STAMP=$(date +%Y%m%d-%H%M%S)
OUT="backup/$STAMP"
mkdir -p "$OUT"
TV="python3 tools/tv.py"

grab() { # name, command
  printf '== %s\n' "$1"
  $TV "$2" > "$OUT/$1.txt" 2>&1
}

grab packages_all        "pm list packages"
grab packages_disabled   "pm list packages -d"
grab packages_enabled    "pm list packages -e"
grab packages_system     "pm list packages -s"
grab packages_thirdparty "pm list packages -3"
grab getprop             "getprop"
grab settings_global     "settings list global"
grab settings_secure     "settings list secure"
grab settings_system     "settings list system"
grab home_activities     "cmd package query-activities -a android.intent.action.MAIN -c android.intent.category.HOME"
grab top_activity        "dumpsys activity activities"
grab wm_size             "wm size"
grab wm_density          "wm density"
grab display_forced      "settings get secure display_density_forced"
grab wifi_cnames         "dumpsys wifi"

# Per-package detail is slow but is the only way to tell a telemetry service
# from the input switcher later.
mkdir -p "$OUT/dumpsys"
while read -r line; do
  pkg=${line#package:}
  [ -z "$pkg" ] && continue
  $TV "dumpsys package $pkg" > "$OUT/dumpsys/$pkg.txt" 2>&1 < /dev/null
done < "$OUT/packages_all.txt"

ln -sfn "$STAMP" backup/latest
echo "backup written to $OUT"
echo "disabled at backup time: $(wc -l < "$OUT/packages_disabled.txt" | tr -d ' ')"
