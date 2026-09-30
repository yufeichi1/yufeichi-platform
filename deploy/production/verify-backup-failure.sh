#!/usr/bin/env bash
# An explicit failure drill: no production DB writes, no deletion of backups.
set -euo pipefail
test "$(id -u)" = 0
unit=/run/systemd/system/yufeichi-backup-failure-test.service
test ! -e "$unit"
before=$(find /var/backups/yufeichi -maxdepth 1 -name 'backup-*.tar.gz' | wc -l)
finish() {
    systemctl reset-failed yufeichi-backup-failure-test.service || true
    rm -f -- "$unit"
    systemctl daemon-reload
}
trap finish EXIT
cat > "$unit" <<'UNIT'
[Unit]
Description=Explicit Yufeichi backup failure acceptance drill
OnFailure=yufeichi-backup-failure@yufeichi-backup-failure-test.service.service
[Service]
Type=oneshot
Environment=YUFEICHI_BACKUP_COMPOSE=/run/yufeichi-intentionally-missing-compose.yml
ExecStart=/bin/bash /opt/yufeichi/deploy/backup.sh
UNIT
systemctl daemon-reload
if systemctl start yufeichi-backup-failure-test.service; then
    echo 'Failure drill unexpectedly succeeded' >&2
    exit 1
fi
test "$(systemctl show yufeichi-backup-failure-test.service -p Result --value)" = exit-code
recorded=false
for attempt in $(seq 1 20); do
    if grep -q 'failed_unit=yufeichi-backup-failure-test.service' /var/lib/yufeichi/backup-status/last-failure; then
        recorded=true
        break
    fi
    sleep 1
done
test "$recorded" = true
after=$(find /var/backups/yufeichi -maxdepth 1 -name 'backup-*.tar.gz' | wc -l)
test "$before" = "$after"
echo 'BACKUP_FAILURE_DRILL_PASS: failed unit, OnFailure journal/state feedback, no final backup archive'
