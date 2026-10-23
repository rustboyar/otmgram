#!/usr/bin/env bash
# Backs up / restores the logged-in TDLib session of the debug app on the connected
# device, so a lost session doesn't need a new login (which needs a human for the code).
# Usage: scripts/session.sh backup|restore [name]
#
# Backups contain the account's auth keys: they are kept outside the repo, readable
# only by you. Media files are not included; TDLib downloads them again.
set -euo pipefail

PACKAGE=dev.otmgram
DIR=${XDG_DATA_HOME:-$HOME/.local/share}/otmgram/sessions
ADB=${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb

action=${1:-}
file=$DIR/${2:-default}.tar

# Stop the app so the database files are consistent.
"$ADB" shell am force-stop "$PACKAGE"

case $action in
  backup)
    mkdir -p "$DIR" && chmod 700 "$DIR"
    "$ADB" exec-out run-as "$PACKAGE" sh -c 'cd files/tdlib && tar -cf - td.binlog db.sqlite*' > "$file.part"
    mv "$file.part" "$file" && chmod 600 "$file"
    echo "Saved $(du -h "$file" | cut -f1) to $file"
    ;;
  restore)
    [ -f "$file" ] || { echo "error: no backup at $file" >&2; exit 1; }
    "$ADB" shell run-as "$PACKAGE" sh -c "'rm -rf files/tdlib && mkdir -m 700 files/tdlib'"
    "$ADB" exec-in run-as "$PACKAGE" sh -c 'cd files/tdlib && tar -xf -' < "$file"
    echo "Restored $file"
    ;;
  *)
    echo "usage: $0 backup|restore [name]" >&2
    exit 1
    ;;
esac
