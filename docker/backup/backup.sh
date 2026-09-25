#!/bin/sh
# Nightly pg_dump of the stack's database, off the box, with a monthly restore test (copied from
# Operations' docker/backup, the one change being BACKUP_NAME and a table count for the restore test).
#
# Environment (from compose): PGHOST PGUSER PGPASSWORD PGDATABASE, BACKUP_DIR (default /backups),
# BACKUP_AT (HH:MM UTC, default 03:00), BACKUP_KEEP_DAYS (default 14), BACKUP_RCLONE_REMOTE
# (an rclone remote:path such as r2:operations-backups; empty means local only; the rclone config
# comes in through RCLONE_CONFIG_* variables), BACKUP_RUN_NOW=1 to run once at start, BACKUP_NAME
# (the file prefix, default the database name).
#
# Every run writes $BACKUP_DIR/last.json, which the application shows on its diagnostics page:
#   {"at": "...", "file": "...", "bytes": 123, "remote": "...", "restoreTestAt": "...", "restoreTest": "..."}
set -eu
DIR="${BACKUP_DIR:-/backups}"
AT="${BACKUP_AT:-03:00}"
KEEP="${BACKUP_KEEP_DAYS:-14}"
REMOTE="${BACKUP_RCLONE_REMOTE:-}"
NAME="${BACKUP_NAME:-$PGDATABASE}"
mkdir -p "$DIR"
MARKER="$DIR/last.json"

json_field() {
  # json_field key file -> the string value of a top-level key, or empty
  [ -f "$2" ] && sed -n "s/.*\"$1\": *\"\([^\"]*\)\".*/\1/p" "$2" | head -1 || true
}

run_backup() {
  STAMP=$(date -u +%Y%m%dT%H%M%SZ)
  FILE="$DIR/$NAME-$STAMP.dump"
  echo "backup: dumping $PGDATABASE to $FILE"
  pg_dump --no-owner --no-acl -Fc -f "$FILE" "$PGDATABASE"
  BYTES=$(stat -c %s "$FILE")
  echo "backup: $BYTES bytes"
  find "$DIR" -name "$NAME-*.dump" -mtime +"$KEEP" -print -delete || true
  REMOTE_NOTE=""
  if [ -n "$REMOTE" ]; then
    if rclone copy "$FILE" "$REMOTE" --quiet; then
      REMOTE_NOTE="$REMOTE"
      echo "backup: copied to $REMOTE"
      rclone delete "$REMOTE" --min-age "${KEEP}d" --quiet || true
    else
      REMOTE_NOTE="FAILED $REMOTE"
      echo "backup: copy to $REMOTE FAILED"
    fi
  fi
  RESTORE_AT=$(json_field restoreTestAt "$MARKER")
  RESTORE_NOTE=$(json_field restoreTest "$MARKER")
  if [ "$(date -u +%d)" = "01" ] || [ "${BACKUP_RESTORE_TEST_NOW:-0}" = "1" ]; then
    RESTORE_AT=$(date -u +%Y-%m-%dT%H:%M:%SZ)
    RESTORE_NOTE=$(restore_test "$FILE")
  fi
  cat > "$MARKER" <<JSON
{"at": "$(date -u +%Y-%m-%dT%H:%M:%SZ)", "file": "$(basename "$FILE")", "bytes": $BYTES, "remote": "$REMOTE_NOTE", "restoreTestAt": "$RESTORE_AT", "restoreTest": "$RESTORE_NOTE"}
JSON
  echo "backup: marker written"
}

restore_test() {
  # Restore into a scratch database and count the rows, then drop it. Says what it found.
  TEST_DB="${PGDATABASE}_restore_test"
  psql -d postgres -qc "drop database if exists $TEST_DB" >/dev/null 2>&1 || true
  psql -d postgres -qc "create database $TEST_DB" >/dev/null
  if pg_restore --no-owner --no-acl -d "$TEST_DB" "$1" >/dev/null 2>&1; then
    COUNTS=$(psql -d "$TEST_DB" -Atc "select count(*) || ' tables' from information_schema.tables where table_schema = 'public'" 2>/dev/null || echo "restored, counts unavailable")
    psql -d postgres -qc "drop database $TEST_DB" >/dev/null 2>&1 || true
    echo "ok: $COUNTS"
  else
    psql -d postgres -qc "drop database if exists $TEST_DB" >/dev/null 2>&1 || true
    echo "FAILED"
  fi
}

seconds_until() {
  H=$(echo "$AT" | cut -d: -f1); M=$(echo "$AT" | cut -d: -f2)
  NOW=$(date -u +%s)
  TARGET=$(date -u -d "$(date -u +%Y-%m-%d) $H:$M:00" +%s 2>/dev/null || date -u -D "%Y-%m-%d %H:%M:%S" -d "$(date -u +%Y-%m-%d) $H:$M:00" +%s)
  if [ "$TARGET" -le "$NOW" ]; then TARGET=$((TARGET + 86400)); fi
  echo $((TARGET - NOW))
}

until pg_isready -q; do echo "backup: waiting for postgres"; sleep 5; done
if [ "${BACKUP_RUN_NOW:-0}" = "1" ]; then run_backup; fi
while true; do
  WAIT=$(seconds_until)
  echo "backup: next run in $WAIT s (at $AT UTC)"
  sleep "$WAIT"
  run_backup || echo "backup: run FAILED"
  sleep 60
done
