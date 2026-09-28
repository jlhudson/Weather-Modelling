#!/bin/sh
# Nightly pg_dump of one database, or of every database a shared instance holds, off the box, with a
# monthly restore test.
#
# Environment (from compose): PGHOST PGUSER PGPASSWORD PGDATABASE, BACKUP_DIR (default /backups),
# BACKUP_AT (HH:MM UTC, default 03:00), BACKUP_KEEP_DAYS (default 14), BACKUP_RCLONE_REMOTE
# (an rclone remote:path such as r2:backups/hub; empty means local only; the rclone config comes in
# through RCLONE_CONFIG_* variables), BACKUP_RUN_NOW=1 to run once at start, BACKUP_NAME (the file
# prefix, default the database name), and BACKUP_DATABASES: a space-separated list of databases to
# dump one after another (the shared PostGIS stack sets it; empty means PGDATABASE alone). With a
# list, each database's files carry its own name, its copies go under $BACKUP_RCLONE_REMOTE/<database>,
# and its marker is $BACKUP_DIR/last.<database>.json.
#
# Every run writes a marker ($BACKUP_DIR/last.json for a single database), which an application can
# show on its diagnostics page:
#   {"at": "...", "file": "...", "bytes": 123, "remote": "...", "restoreTestAt": "...", "restoreTest": "..."}
set -eu
DIR="${BACKUP_DIR:-/backups}"
AT="${BACKUP_AT:-03:00}"
KEEP="${BACKUP_KEEP_DAYS:-14}"
REMOTE_BASE="${BACKUP_RCLONE_REMOTE:-}"
DATABASES="${BACKUP_DATABASES:-}"
mkdir -p "$DIR"

json_field() {
  # json_field key file -> the string value of a top-level key, or empty
  [ -f "$2" ] && sed -n "s/.*\"$1\": *\"\([^\"]*\)\".*/\1/p" "$2" | head -1 || true
}

# run_backup [database]: one database, PGDATABASE when none is named. Sets the file names, the remote
# folder and the marker for it, then dumps, prunes, copies and tests.
run_backup() {
  DB="${1:-$PGDATABASE}"
  if [ -n "$DATABASES" ]; then
    NAME="$DB"
    MARKER="$DIR/last.$DB.json"
    REMOTE="${REMOTE_BASE:+$REMOTE_BASE/$DB}"
  else
    NAME="${BACKUP_NAME:-$DB}"
    MARKER="$DIR/last.json"
    REMOTE="$REMOTE_BASE"
  fi
  STAMP=$(date -u +%Y%m%dT%H%M%SZ)
  FILE="$DIR/$NAME-$STAMP.dump"
  echo "backup: dumping $DB to $FILE"
  pg_dump --no-owner --no-acl -Fc -f "$FILE" "$DB"
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
  TEST_DB="${DB}_restore_test"
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

# run_all: every database in BACKUP_DATABASES, or the one PGDATABASE names.
run_all() {
  if [ -n "$DATABASES" ]; then
    for db in $DATABASES; do
      run_backup "$db" || echo "backup: $db FAILED"
    done
  else
    run_backup
  fi
}

until pg_isready -q; do echo "backup: waiting for postgres"; sleep 5; done
if [ "${BACKUP_RUN_NOW:-0}" = "1" ]; then run_all; fi
while true; do
  WAIT=$(seconds_until)
  echo "backup: next run in $WAIT s (at $AT UTC)"
  sleep "$WAIT"
  run_all || echo "backup: run FAILED"
  sleep 60
done
