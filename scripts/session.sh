#!/usr/bin/env bash
# Backs up / restores the logged-in TDLib session of the debug app on the connected
# device, so a lost session doesn't need a new login (which needs a human for the code).
# Usage: scripts/session.sh backup|restore [name]
#
# Backups contain the account's auth keys: they are kept outside the repo, readable
# only by you. Media files are not included; TDLib downloads them again.
#
# The database is encrypted with a key that is itself encrypted by the Android Keystore
# (no_backup/database.key, included here). An encrypted backup therefore only restores
# into the same installation: after an uninstall the keystore key is gone, and the app
# starts over with a new login. Unencrypted backups (from before encryption) restore
# anywhere; the app encrypts them on the next start.
set -euo pipefail

PACKAGE=dev.otmgram
DIR=${XDG_DATA_HOME:-$HOME/.local/share}/otmgram/sessions
ADB=${ANDROID_HOME:-$HOME/Android/Sdk}/platform-tools/adb

action=${1:-}
file=$DIR/${2:-default}.tar

backup() {
  mkdir -p "$DIR" && chmod 700 "$DIR"
  # run-as starts in the app's data folder: TDLib's files and the database key.
  "$ADB" exec-out run-as "$PACKAGE" sh -c 'tar -cf - files/tdlib/td.binlog files/tdlib/db.sqlite* $(ls no_backup/database.key 2>/dev/null)' > "$1.part"
  mv "$1.part" "$1" && chmod 600 "$1"
  echo "Saved $(du -h "$1" | cut -f1) to $1"
}

case $action in
  backup|restore) ;;
  *)
    echo "usage: $0 backup|restore [name]" >&2
    exit 1
    ;;
esac
[ "$action" = restore ] && [ ! -f "$file" ] && { echo "error: no backup at $file" >&2; exit 1; }

# Stop the app so the database files are consistent.
"$ADB" shell am force-stop "$PACKAGE"

case $action in
  backup)
    backup "$file"
    ;;
  restore)
    # The session being replaced, in case the backup doesn't work here (an encrypted one
    # only restores into the installation it came from).
    if "$ADB" shell run-as "$PACKAGE" ls files/tdlib/td.binlog >/dev/null 2>&1; then
      backup "$DIR/before-restore.tar"
    fi
    "$ADB" shell run-as "$PACKAGE" sh -c "'rm -rf files/tdlib no_backup/database.key && mkdir -m 700 files/tdlib'"
    if tar -tf "$file" | grep -q '^files/'; then
      "$ADB" exec-in run-as "$PACKAGE" sh -c 'tar -xf -' < "$file"
    else
      # Older backups: TDLib's files only, at the top.
      "$ADB" exec-in run-as "$PACKAGE" sh -c 'cd files/tdlib && tar -xf -' < "$file"
    fi
    echo "Restored $file (the previous session is in $DIR/before-restore.tar)"
    ;;
esac
