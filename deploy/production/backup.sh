#!/usr/bin/env bash
# Snapshot DB first, then copy immutable uploads. Never delete production data or old backups.
set -euo pipefail
umask 077
test "$(id -u)" = 0
exec 9>/run/lock/yufeichi-ops.lock
flock -n 9 || { echo 'Another deployment/backup operation is running' >&2; exit 75; }
base=/var/backups/yufeichi
install -d -m 700 "$base" /var/lib/yufeichi/backup-status
id="backup-$(date -u +%Y%m%dT%H%M%SZ)-$$"
partial="$base/.$id.partial"
archive="$base/.$id.tar.gz.partial"
mkdir -m 700 "$partial"
completed=false
finish() {
    if [ "$completed" != true ]; then
        logger -p daemon.err -t yufeichi-backup "FAILED: $id; partial files retained for diagnosis"
        printf '%s backup_failed=%s\n' "$(date -u +%FT%TZ)" "$id" > /var/lib/yufeichi/backup-status/last-failure
    fi
}
trap finish EXIT
compose=(docker compose -f "${YUFEICHI_BACKUP_COMPOSE:-/opt/yufeichi/deploy/compose.yml}")
backend=$(readlink -f /opt/yufeichi/backend/current)
frontend=$(readlink -f /var/www/yufeichi-app/current)
"${compose[@]}" exec -T mysql sh -c 'MYSQL_PWD="$(cat /run/secrets/mysql-root)" exec mysqldump -uroot --single-transaction --routines --events --triggers --no-tablespaces --set-gtid-purged=OFF --databases yufeichi' | gzip > "$partial/database.sql.gz"
gzip -t "$partial/database.sql.gz"
tar --acls --xattrs -czpf "$partial/uploads.tar.gz" -C /var/lib/yufeichi uploads
tar -tzf "$partial/uploads.tar.gz" >/dev/null
# This archive contains secrets: root-only on host and private ACL on offsite copies.
tar --acls --xattrs --exclude=etc/yufeichi/acceptance-admin.json --exclude=etc/yufeichi/initial-admin.json -czpf "$partial/config.tar.gz" -C / etc/yufeichi etc/nginx/sites-available/yufeichi etc/systemd/system/yufeichi.service
tar -tzf "$partial/config.tar.gz" >/dev/null
"${compose[@]}" exec -T mysql sh -c 'MYSQL_PWD="$(cat /run/secrets/mysql-root)" exec mysql -uroot -Nse "SELECT version,success,checksum FROM yufeichi.flyway_schema_history ORDER BY installed_rank"' > "$partial/flyway.txt"
printf 'timestamp_utc=%s\nbackend=%s\nfrontend=%s\nmysql=mysql:8.4.11\nredis=redis:7.4.10\n' "$(date -u +%FT%TZ)" "$backend" "$frontend" > "$partial/manifest.txt"
test "$backend" = "$(readlink -f /opt/yufeichi/backend/current)"
test "$frontend" = "$(readlink -f /var/www/yufeichi-app/current)"
(cd "$partial" && sha256sum database.sql.gz uploads.tar.gz config.tar.gz flyway.txt manifest.txt > SHA256SUMS && sha256sum -c SHA256SUMS)
mv "$partial" "$base/$id"
tar -czf "$archive" -C "$base" "$id"
tar -tzf "$archive" >/dev/null
mv "$archive" "$base/$id.tar.gz"
sha256sum "$base/$id.tar.gz" > "$base/$id.tar.gz.sha256"
printf '%s archive=%s\n' "$(date -u +%FT%TZ)" "$base/$id.tar.gz" > /var/lib/yufeichi/backup-status/last-success
completed=true
logger -t yufeichi-backup "SUCCESS: $id"
printf 'BACKUP_PASS %s\n' "$base/$id.tar.gz"
