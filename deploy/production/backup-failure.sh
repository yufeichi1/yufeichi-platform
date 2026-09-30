#!/usr/bin/env bash
set -euo pipefail
umask 077
test "$(id -u)" = 0
unit=${1:?Expected failed backup unit}
[[ "$unit" =~ ^yufeichi-backup(-failure-test)?\.service$ ]] || exit 2
install -d -m 700 /var/lib/yufeichi/backup-status
printf '%s failed_unit=%s\n' "$(date -u +%FT%TZ)" "$unit" > /var/lib/yufeichi/backup-status/last-failure
logger -p daemon.err -t yufeichi-backup "Backup unit failed: $unit; inspect journalctl -u $unit"
echo "BACKUP_FAILURE_RECORDED $unit" >&2
