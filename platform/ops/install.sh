#!/usr/bin/env bash
# Collect platform installer (run as root on the Linux host)
# Usage: sudo ./ops/install.sh /path/to/platform
set -euo pipefail

ROOT_DIR="${1:-$(cd "$(dirname "$0")/.." && pwd)}"
APP_DIR=/opt/collect
CONF_DIR=/etc/collect
DATA_DIR=/var/lib/collect
LOCK=/var/lock/collect-worker.lock

if [ "$(id -u)" != "0" ]; then
  echo "must run as root" >&2
  exit 1
fi

id collect >/dev/null 2>&1 || useradd -r -s /usr/sbin/nologin collect

mkdir -p "$APP_DIR" "$APP_DIR/ops" "$CONF_DIR" "$DATA_DIR/out"
cp -f "$ROOT_DIR/worker/target/worker.jar" "$APP_DIR/worker.jar"
cp -rf "$ROOT_DIR/ops/." "$APP_DIR/ops/"
chmod +x "$APP_DIR/ops/backup/backup.sh"

[ -f "$CONF_DIR/worker.env" ] || cp "$APP_DIR/ops/systemd/worker.env.example" "$CONF_DIR/worker.env"
[ -f "$CONF_DIR/backup.env" ] || cp "$APP_DIR/ops/backup/backup.env.example" "$CONF_DIR/backup.env"
chown root:collect "$CONF_DIR/worker.env" "$CONF_DIR/backup.env"
chmod 640 "$CONF_DIR/worker.env" "$CONF_DIR/backup.env"
chown -R collect:collect "$APP_DIR" "$DATA_DIR"
touch "$LOCK"
chown collect:collect "$LOCK"

cp -f "$APP_DIR/ops/systemd/"*.service "$APP_DIR/ops/systemd/"*.timer /etc/systemd/system/
cp -f "$APP_DIR/ops/backup/"*.service "$APP_DIR/ops/backup/"*.timer /etc/systemd/system/
chmod 644 /etc/systemd/system/collect-*.service /etc/systemd/system/collect-*.timer

systemctl daemon-reload
systemctl enable collect-reminder.timer collect-escalate.timer collect-close.timer \
  collect-retry.timer collect-alert.timer collect-backup.timer >/dev/null
systemctl start collect-reminder.timer collect-escalate.timer collect-close.timer \
  collect-retry.timer collect-alert.timer collect-backup.timer

echo "installed. verify env files then:"
echo "  systemctl list-timers --all | grep collect-"
echo "  systemctl start collect-worker.service && journalctl -u collect-worker -n 20"
