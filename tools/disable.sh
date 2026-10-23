#!/usr/bin/env bash
# Disable packages on the TV, one `pm` call per package, refusing anything
# that can stop the set booting or make it unusable.
#
#   tools/disable.sh com.example.telemetry com.example.ads
#
# Never uninstalls. Undo with tools/restore.sh (generated at the end of the
# job) or: pm enable <pkg>
set -uo pipefail
cd "$(dirname "$0")/.."

TV="python3 tools/tv.py"

# Packages that must never be disabled. This is the list from the hard-won
# failure in #884 where an original Mi TV Stick never booted again.
PROTECTED_EXACT=(
  "android"
  "com.android.systemui"
  "com.android.shell"
  "com.android.phone"
  "com.android.settings"
  "com.android.tv.settings"
  "com.android.permissioncontroller"
  "com.google.android.permissioncontroller"
  "com.android.packageinstaller"
  "com.google.android.packageinstaller"
  "com.android.ext.services"
  "com.android.ext.shared"
  "com.google.android.modulemetadata"
  "com.google.android.gms"
  "com.google.android.gsf"
  "com.android.vending"
)

PROTECTED_REGEX=(
  '^com\.google\.android\.overlay\.modules'
  '^com\.android\.providers\.'
  '^com\.google\.android\.overlay\.'
)

# Providers we are allowed to touch (calendar, contacts, user dictionary,
# blocked numbers) even though com.android.providers.* is otherwise sacred.
PROVIDER_ALLOW=(
  '^com\.android\.providers\.calendar'
  '^com\.android\.providers\.contacts'
  '^com\.android\.providers\.userdictionary'
  '^com\.android\.providers\.blockednumber'
)

refuse() {
  local pkg=$1 why=$2
  printf 'REFUSED %-45s %s\n' "$pkg" "$why"
  return 0
}

is_protected() {
  local pkg=$1 p
  for p in "${PROTECTED_EXACT[@]}"; do
    [ "$pkg" = "$p" ] && { echo "core package"; return 0; }
  done
  for r in "${PROVIDER_ALLOW[@]}"; do
    [[ "$pkg" =~ $r ]] && return 1
  done
  for r in "${PROTECTED_REGEX[@]}"; do
    [[ "$pkg" =~ $r ]] && { echo "matches $r"; return 0; }
  done
  if [[ "$pkg" =~ ^com\.android\.providers\. ]]; then
    echo "core provider"; return 0
  fi
  return 1
}

for pkg in "$@"; do
  if reason=$(is_protected "$pkg"); then
    refuse "$pkg" "$reason"
    continue
  fi

  before=$(echo $($TV "pm list packages -d" | grep -c "^package:$pkg$"))
  out=$($TV "pm disable-user --user 0 $pkg" 2>&1 | tr -d '\r')
  printf '%-45s %s\n' "$pkg" "$(echo "$out" | head -1)"

  # Exit codes lie on some firmware; trust only the list.
  after=$($TV "pm list packages -d" | grep -c "^package:$pkg$")
  if [ "$after" -eq 0 ]; then
    echo "  !! still enabled after disable (before=$before)"
  fi
done

echo
echo "currently disabled:"
$TV "pm list packages -d"
