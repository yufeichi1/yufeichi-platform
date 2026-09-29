#!/usr/bin/env bash
set -euo pipefail
umask 077
test "$(id -u)" = 0
compose=(docker compose -f /opt/yufeichi/deploy/compose.yml)
backup="/var/backups/yufeichi/pre-migration-$(date -u +%Y%m%dT%H%M%SZ)"
mkdir -m 700 -p "$backup"
"${compose[@]}" exec -T mysql sh -c 'MYSQL_PWD="$(cat /run/secrets/mysql-root)" exec mysqldump -uroot --single-transaction --routines --events --triggers --no-tablespaces --set-gtid-purged=OFF --databases yufeichi' | gzip > "$backup/database.sql.gz.tmp"
gzip -t "$backup/database.sql.gz.tmp"
mv "$backup/database.sql.gz.tmp" "$backup/database.sql.gz"
tar --acls --xattrs -czpf "$backup/config-and-uploads.tar.gz" -C / etc/yufeichi var/lib/yufeichi/uploads
tar -tzf "$backup/config-and-uploads.tar.gz" >/dev/null
sha256sum "$backup/database.sql.gz" "$backup/config-and-uploads.tar.gz" > "$backup/SHA256SUMS"
printf 'Verified pre-migration backup: %s\n' "$backup"
