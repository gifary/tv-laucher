#!/usr/bin/env bash
# Re-enable only packages recorded in the disable ledger.
# Usage: bash tools/restore.sh [package-list]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LIST="${1:-$ROOT/disabled-by-us.txt}"
TV_HOST="${TV_HOST:-127.0.0.1}"
TV_PORT="${TV_PORT:-15555}"

if [[ ! -f "$LIST" ]]; then
  echo "Package list not found: $LIST" >&2
  exit 2
fi

while IFS= read -r pkg || [[ -n "$pkg" ]]; do
  pkg="${pkg%%#*}"
  pkg="${pkg//[[:space:]]/}"
  [[ -z "$pkg" ]] && continue
  if [[ ! "$pkg" =~ ^[A-Za-z0-9_.]+$ ]]; then
    echo "Invalid package name in $LIST: $pkg" >&2
    exit 2
  fi

  echo "Re-enabling $pkg"
  TV_HOST="$TV_HOST" TV_PORT="$TV_PORT" \
    python3 "$ROOT/tools/tv.py" "pm enable --user 0 $pkg"
  disabled="$(TV_HOST="$TV_HOST" TV_PORT="$TV_PORT" \
    python3 "$ROOT/tools/tv.py" "pm list packages -d")"
  if printf '%s\n' "$disabled" | grep -Fqx "package:$pkg"; then
    echo "Still disabled: $pkg" >&2
    exit 1
  fi
done < "$LIST"
