#!/usr/bin/env bash
set -euo pipefail
test "$(id -u)" = 0
source_dir=${1:?Usage: install-ops.sh uploaded-template-directory}
source_dir=$(realpath "$source_dir")
exec 9>/run/lock/yufeichi-ops.lock
flock -n 9 || exit 75
install -d -m 700 /var/lib/yufeichi/backup-status /var/backups/yufeichi /var/lib/yufeichi-ops
for script in backup.sh backup-failure.sh verify-backup-failure.sh verify-nginx.py ops-probe.py restore-verify.py rollback-rehearsal.py recreate-verify.py; do
    if [ -f "$source_dir/$script" ]; then install -m 644 "$source_dir/$script" "/opt/yufeichi/deploy/$script"; fi
done
for unit in yufeichi-backup.service yufeichi-backup.timer yufeichi-backup-failure@.service; do
    install -m 644 "$source_dir/$unit" "/etc/systemd/system/$unit"
done
systemd-analyze verify /etc/systemd/system/yufeichi-backup.service /etc/systemd/system/yufeichi-backup.timer '/etc/systemd/system/yufeichi-backup-failure@.service'
backup="/var/backups/yufeichi/nginx-before-ops-$(date -u +%Y%m%dT%H%M%SZ).conf"
cp -a /etc/nginx/sites-available/yufeichi "$backup"
install -m 644 "$source_dir/nginx.conf" /etc/nginx/sites-available/yufeichi
if ! nginx -t; then
    cp -a "$backup" /etc/nginx/sites-available/yufeichi
    exit 1
fi
install -m 644 "$source_dir/nginx.conf" /opt/yufeichi/deploy/nginx.conf
systemctl reload nginx
systemctl daemon-reload
echo 'OPS_TEMPLATES_INSTALLED; run acceptance before enabling backup.timer'
