#!/usr/bin/env bash
# Collect platform backup: daily dump + binlog archive + optional off-host copy.
# Env: DB_HOST DB_PORT DB_NAME DB_USER DB_PASSWORD, BACKUP_DIR, BINLOG_DIR, BACKUP_REMOTE
set -euo pipefail

BACKUP_DIR="${BACKUP_DIR:-/var/backups/collect}"
DB_NAME="${DB_NAME:-collect_platform}"
DB_HOST="${DB_HOST:-127.0.0.1}"
DB_PORT="${DB_PORT:-3306}"
DB_USER="${DB_USER:-collect}"
RETAIN_DAILY_DAYS="${RETAIN_DAILY_DAYS:-30}"
RETAIN_BINLOG_DAYS="${RETAIN_BINLOG_DAYS:-7}"
BINLOG_DIR="${BINLOG_DIR:-}"
BACKUP_REMOTE="${BACKUP_REMOTE:-}"

umask 077
mkdir -p "$BACKUP_DIR/daily" "$BACKUP_DIR/weekly" "$BACKUP_DIR/binlog" "$BACKUP_DIR/log"
LOG="$BACKUP_DIR/log/backup-$(date +%F).log"

log() { echo "$(date -Is) $*" | tee -a "$LOG" >&2; }

DAY="$(date +%F)"
FILE="$BACKUP_DIR/daily/${DAY}.sql.gz"

log "dump start db=$DB_NAME host=$DB_HOST:$DB_PORT"
DUMP_ARGS=(--single-transaction --quick --routines --triggers --events --default-character-set=utf8mb4)
if [ -n "$BINLOG_DIR" ]; then
  # PITR 起点：把 binlog 文件与位点写进 dump 头
  DUMP_ARGS+=(--source-data=2)
fi
MYSQL_PWD="${DB_PASSWORD:?DB_PASSWORD required}" mysqldump \
  -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USER" \
  "${DUMP_ARGS[@]}" \
  "$DB_NAME" | gzip -9 > "$FILE"

if ! gzip -t "$FILE" 2>/dev/null; then
  log "ERROR dump corrupted: $FILE"
  exit 1
fi
log "dump ok size=$(stat -c %s "$FILE") bytes"

if [ -n "$BINLOG_DIR" ]; then
  zcat "$FILE" | grep -m1 -E 'CHANGE (MASTER|REPLICATION SOURCE) TO (MASTER|SOURCE)_LOG_FILE' \
    > "$BACKUP_DIR/daily/${DAY}.position" || true
  log "pitr position -> $BACKUP_DIR/daily/${DAY}.position"
fi

# weekly copy on Sunday
if [ "$(date +%u)" = "7" ]; then
  cp -f "$FILE" "$BACKUP_DIR/weekly/${DAY}.sql.gz"
  log "weekly copy ok"
fi

# binlog archive (raw copy for PITR)
if [ -n "$BINLOG_DIR" ] && [ -d "$BINLOG_DIR" ]; then
  BINLOG_FILE="$(MYSQL_PWD="$DB_PASSWORD" mysql -h "$DB_HOST" -P "$DB_PORT" -u "$DB_USER" \
    -N -e 'SHOW MASTER STATUS' 2>/dev/null | awk 'NR==1{print $1}')"
  if [ -n "$BINLOG_FILE" ]; then
    # 前缀可能是 mysql-bin 或 binlog，按实际文件名推导
    BINLOG_PREFIX="${BINLOG_FILE%.*}"
    mkdir -p "$BACKUP_DIR/binlog/current"
    rsync -a --include="${BINLOG_PREFIX}.[0-9]*" --include="${BINLOG_PREFIX}.index" \
          --exclude='*' "$BINLOG_DIR/" "$BACKUP_DIR/binlog/current/"
    log "binlog archive ok prefix=$BINLOG_PREFIX"
  else
    log "binlog archive skipped (SHOW MASTER STATUS empty)"
  fi
else
  log "binlog archive skipped (BINLOG_DIR empty)"
fi

# off-host copy
if [ -n "$BACKUP_REMOTE" ]; then
  rsync -a "$BACKUP_DIR/" "$BACKUP_REMOTE/"
  log "off-host copy ok -> $BACKUP_REMOTE"
else
  log "off-host copy skipped (BACKUP_REMOTE empty)"
fi

# retention
find "$BACKUP_DIR/daily" -name '*.sql.gz' -mtime "+${RETAIN_DAILY_DAYS}" -delete
find "$BACKUP_DIR/binlog" -type f -mtime "+${RETAIN_BINLOG_DAYS}" -delete
find "$BACKUP_DIR/log" -name '*.log' -mtime "+${RETAIN_DAILY_DAYS}" -delete
log "retention applied daily>${RETAIN_DAILY_DAYS}d binlog>${RETAIN_BINLOG_DAYS}d"

log "DONE"
